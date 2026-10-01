package com.dwolla.metrics.otel4s

import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.mtl.Handle
import cats.syntax.all._
import cats.tagless.aop.Instrumentation
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import org.scalacheck.Gen
import org.scalacheck.effect.PropF
import org.typelevel.otel4s.AttributeKey
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.semconv.attributes.{CodeAttributes, ErrorAttributes}
import org.typelevel.otel4s.semconv.experimental.attributes.RpcExperimentalAttributes

import java.util.concurrent.TimeoutException
import scala.concurrent.duration._

/** Metric ''content'', asserted against a real SDK.
  *
  * Every property lives here; a subclass supplies only `histogramsFrom`, which
  * runs a function against a real `MeterProvider[IO]` and reports what it recorded.
  * It runs against otel4s-sdk (`OtelSdkMeasurementContentSpec`, every
  * platform) and oteljava (`OtelJavaMeasurementContentSpec`, JVM).
  */
abstract class MeasurementContentSuite extends CatsEffectSuite with ScalaCheckEffectSuite {

  /** Runs `f` with a `MeterProvider[IO]` backed by a real SDK and returns what `f`
    * produced plus every histogram recorded. `measured` calls this inside
    * `TestControl`, so an implementation must allocate its SDK inside the
    * returned `IO`, never eagerly.
    */
  protected def histogramsFrom[A](f: MeterProvider[IO] => IO[A]): IO[(A, List[RecordedHistogram])]

  /** `histogramsFrom` on virtual time: `IO.sleep` inside `f` advances the
    * clock `recordDuration` reads, instantly and exactly.
    */
  protected def measured[A](f: MeterProvider[IO] => IO[A]): IO[(A, List[RecordedHistogram])] =
    TestControl.executeEmbed(histogramsFrom(f))

  protected def histogramNamed(name: String, histograms: List[RecordedHistogram]): RecordedHistogram =
    histograms.filter(_.name == name) match {
      case List(histogram) => histogram
      case other => fail(s"expected exactly one histogram named $name, found ${other.size} among ${histograms.map(_.name)}")
    }

  private def attributeKeys(point: RecordedPoint): Set[String] =
    point.attributes.map(_.key.name).toSet

  protected def pointFor(key: AttributeKey[String], value: String, histogram: RecordedHistogram): RecordedPoint =
    histogram.points.filter(_.attributes.get(key).map(_.value).contains(value)) match {
      case List(point) => point
      case other => fail(s"expected exactly one point with $key=$value in ${histogram.name}, found ${other.size}: ${histogram.points}")
    }

  private val callDurations: Gen[FiniteDuration] = Gen.chooseNum(1L, 60000L).map(_.millis)
  private val callCounts: Gen[Int] = Gen.chooseNum(1, 10)

  private val functionDuration: String = "com.dwolla.code.function.duration"

  private val defaultBoundaries: List[Double] =
    List(0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0)

  private def sleepingFoo(greetFor: FiniteDuration, pingFor: FiniteDuration): Foo[IO] =
    Foo[IO](name => IO.sleep(greetFor).as(s"hello $name"), IO.sleep(pingFor))

  /** `foo` behind a freshly built in-process interpreter. */
  private def generic(foo: Foo[IO])(implicit meterProvider: MeterProvider[IO]): IO[Foo[IO]] =
    MeterInstrumentation[IO]().map(Foo.metered(foo, _))

  test("calls across methods, through separately built interpreters, aggregate into one com.dwolla.code.function.duration") {
    // Gates the design (spec D1): each interpreter creates the histogram once,
    // when it is built, and two interpreters each do so.
    // That is only correct if the SDK hands back the same instrument for an
    // identical descriptor. A second storage would export a second histogram
    // with the same name, which histogramNamed rejects.
    PropF.forAllF(callCounts, callCounts) { (greets, pings) =>
      measured { implicit meterProvider =>
        val foo = sleepingFoo(1.milli, 1.milli)
        for {
          first <- generic(foo)
          second <- generic(foo)
          _ <- first.greet("a").replicateA_(greets)
          _ <- second.greet("b").replicateA_(greets)
          _ <- first.ping().replicateA_(pings)
        } yield ()
      }.map { case (_, histograms) =>
        val fooDuration = histogramNamed(functionDuration, histograms)
        assertEquals(pointFor(CodeAttributes.CodeFunctionName, "Foo.greet", fooDuration).count, 2L * greets)
        assertEquals(pointFor(CodeAttributes.CodeFunctionName, "Foo.ping", fooDuration).count, pings.toLong)
      }
    }
  }

  test("a call's duration is recorded in seconds, exactly, on virtual time") {
    PropF.forAllF(callDurations) { callDuration =>
      measured { implicit meterProvider =>
        generic(sleepingFoo(callDuration, callDuration)).flatMap(_.greet("world"))
      }.map { case (result, histograms) =>
        assertEquals(result, "hello world")
        val point = pointFor(CodeAttributes.CodeFunctionName, "Foo.greet", histogramNamed(functionDuration, histograms))
        assertEquals(point.count, 1L)
        assertEqualsDouble(point.sum, callDuration.toUnit(SECONDS), 1e-9)
      }
    }
  }

  test("the in-process histogram is in seconds, described, and uses the default bucket boundaries") {
    measured { implicit meterProvider =>
      generic(sleepingFoo(1.milli, 1.milli)).flatMap(_.ping())
    }.map { case (_, histograms) =>
      val fooDuration = histogramNamed(functionDuration, histograms)
      assertEquals(fooDuration.unit, "s")
      assertEquals(fooDuration.description, "Measures the duration of calls to instrumented functions.")
      assertEquals(fooDuration.points.map(_.boundaries), List(defaultBoundaries))
    }
  }

  test("separately wrapped algebras share one in-process metric, told apart by code.function.name") {
    measured { implicit meterProvider =>
      (MeterInstrumentation[IO](), MeterInstrumentation[IO]()).flatMapN { (fooInterpreter, barInterpreter) =>
        fooInterpreter(Instrumentation(IO.unit, "Foo", "ping")) >> barInterpreter(Instrumentation(IO.unit, "Bar", "baz"))
      }
    }.map { case (_, histograms) =>
      val duration = histogramNamed(functionDuration, histograms)
      assertEquals(pointFor(CodeAttributes.CodeFunctionName, "Foo.ping", duration).count, 1L)
      assertEquals(pointFor(CodeAttributes.CodeFunctionName, "Bar.baz", duration).count, 1L)
    }
  }

  test("both flavors record under this library's own instrumentation scope, versioned") {
    measured { implicit meterProvider =>
      (generic(sleepingFoo(1.milli, 1.milli)).flatMap(_.ping()),
       rpc(sleepingFoo(1.milli, 1.milli), RpcRole.Server).flatMap(_.ping())).tupled
    }.map { case (_, histograms) =>
      assertEquals(
        histograms.map(h => (h.name, h.scopeName, h.scopeVersion)).toSet,
        Set[(String, String, Option[String])](
          (functionDuration, "com.dwolla.metrics.otel4s", Some(BuildInfo.version)),
          ("rpc.server.call.duration", "com.dwolla.metrics.otel4s", Some(BuildInfo.version)),
        )
      )
    }
  }

  test("a successful call records no error.type") {
    measured { implicit meterProvider =>
      generic(sleepingFoo(1.milli, 1.milli)).flatMap(_.greet("world"))
    }.map { case (_, histograms) =>
      val point = pointFor(CodeAttributes.CodeFunctionName, "Foo.greet", histogramNamed(functionDuration, histograms))
      assertEquals(point.attributes.get(ErrorAttributes.ErrorType), None)
      assertEquals(attributeKeys(point), Set("code.function.name"))
    }
  }

  test("a failed call returns the identical error and records its class name as error.type") {
    PropF.forAllF(callDurations) { callDuration =>
      val failure = new FooFailure
      measured { implicit meterProvider =>
        generic(Foo[IO](_ => IO.sleep(callDuration) >> IO.raiseError(failure), IO.unit))
          .flatMap(_.greet("world").attempt)
      }.map { case (result, histograms) =>
        assert(result.left.exists(_ eq failure), s"expected the identical FooFailure back, got $result")
        val point = pointFor(ErrorAttributes.ErrorType, classOf[FooFailure].getName, histogramNamed(functionDuration, histograms))
        assertEquals(point.attributes.get(CodeAttributes.CodeFunctionName).map(_.value), Some("Foo.greet"))
        assertEqualsDouble(point.sum, callDuration.toUnit(SECONDS), 1e-9)
        assertEquals(attributeKeys(point), Set("code.function.name", "error.type"))
      }
    }
  }

  test("a raise that escapes the call records the domain error's class as error.type, not cats-mtl's Submarine") {
    measured { implicit meterProvider =>
      Handle.allowF[IO, NotFound] { h =>
        generic(Foo[IO](_ => h.raise(new NotFound(42)), IO.unit)).flatMap(_.greet("world")).as(Option.empty[NotFound])
      }.rescue(e => IO.pure(e.some))
    }.map { case (result, histograms) =>
      assert(result.exists(_.id == 42), s"expected the raised NotFound(42) back, got $result")
      val point = pointFor(ErrorAttributes.ErrorType, classOf[NotFound].getName, histogramNamed(functionDuration, histograms))
      assertEquals(point.count, 1L)
    }
  }

  test("an escaped raise on an RPC-instrumented algebra records the domain error's class as error.type") {
    measured { implicit meterProvider =>
      Handle.allowF[IO, NotFound] { h =>
        rpc(Foo[IO](_ => h.raise(new NotFound(42)), IO.unit), RpcRole.Server).flatMap(_.greet("world")).as(Option.empty[NotFound])
      }.rescue(e => IO.pure(e.some))
    }.map { case (_, histograms) =>
      val histogram = histogramNamed("rpc.server.call.duration", histograms)
      assertEquals(pointFor(ErrorAttributes.ErrorType, classOf[NotFound].getName, histogram).count, 1L)
    }
  }

  test("a call canceled by a timeout records error.type = canceled, with the time spent before cancellation") {
    measured { implicit meterProvider =>
      generic(sleepingFoo(10.seconds, 1.milli)).flatMap(_.greet("world").timeout(1.second).attempt)
    }.map { case (result, histograms) =>
      assert(result.left.exists(_.isInstanceOf[TimeoutException]), s"expected a TimeoutException, got $result")
      val point = pointFor(ErrorAttributes.ErrorType, "canceled", histogramNamed(functionDuration, histograms))
      assertEquals(point.count, 1L)
      assertEqualsDouble(point.sum, 1.0, 1e-9)
    }
  }

  private val thrift: RpcSystem = RpcSystem("thrift")
  private val fooService: RpcService = RpcService("com.example.FooService")

  private def rpc(foo: Foo[IO], role: RpcRole)(implicit meterProvider: MeterProvider[IO]): IO[Foo[IO]] =
    RpcMeterInstrumentation[IO](role, thrift, fooService).map(Foo.metered(foo, _))

  private val rpcRoles: List[(RpcRole, String, String)] = List(
    (RpcRole.Server, "rpc.server.call.duration", "Measures the duration of an incoming Remote Procedure Call (RPC)."),
    (RpcRole.Client, "rpc.client.call.duration", "Measures the duration of an outgoing Remote Procedure Call (RPC)."),
  )

  rpcRoles.foreach { case (role, metricName, description) =>
    test(s"$role records to $metricName in seconds, with rpc.system.name and rpc.method = <service>/<method>") {
      PropF.forAllF(callDurations) { callDuration =>
        measured { implicit meterProvider =>
          rpc(sleepingFoo(callDuration, callDuration), role).flatMap(_.greet("world"))
        }.map { case (result, histograms) =>
          assertEquals(result, "hello world")
          val histogram = histogramNamed(metricName, histograms)
          assertEquals(histogram.unit, "s")
          assertEquals(histogram.description, description)
          val point = pointFor(RpcExperimentalAttributes.RpcMethod, "com.example.FooService/greet", histogram)
          assertEquals(point.attributes.get(RpcExperimentalAttributes.RpcSystemName).map(_.value), Some("thrift"))
          assertEquals(point.attributes.get(ErrorAttributes.ErrorType), None)
          assertEquals(point.count, 1L)
          assertEqualsDouble(point.sum, callDuration.toUnit(SECONDS), 1e-9)
          assertEquals(point.boundaries, defaultBoundaries)
          assertEquals(attributeKeys(point), Set("rpc.system.name", "rpc.method"))
        }
      }
    }
  }

  test("an RPC-instrumented algebra records only the RPC metric, never the in-process one") {
    measured { implicit meterProvider =>
      rpc(sleepingFoo(1.milli, 1.milli), RpcRole.Server).flatMap(foo => foo.ping() >> foo.greet("world"))
    }.map { case (_, histograms) =>
      assertEquals(histograms.map(_.name), List("rpc.server.call.duration"))
    }
  }

  test("a failed or canceled RPC call records error.type alongside the RPC attributes") {
    val failure = new FooFailure
    measured { implicit meterProvider =>
      rpc(Foo[IO](_ => IO.raiseError(failure), IO.sleep(10.seconds)), RpcRole.Client).flatMap { foo =>
        (foo.greet("world").attempt, foo.ping().timeout(1.second).attempt).tupled
      }
    }.map { case ((greeted, pinged), histograms) =>
      assert(greeted.left.exists(_ eq failure), s"expected the identical FooFailure back, got $greeted")
      assert(pinged.left.exists(_.isInstanceOf[TimeoutException]), s"expected a TimeoutException, got $pinged")
      val histogram = histogramNamed("rpc.client.call.duration", histograms)
      val greet = pointFor(RpcExperimentalAttributes.RpcMethod, "com.example.FooService/greet", histogram)
      val ping = pointFor(RpcExperimentalAttributes.RpcMethod, "com.example.FooService/ping", histogram)
      assertEquals(greet.attributes.get(ErrorAttributes.ErrorType).map(_.value), Some(classOf[FooFailure].getName))
      assertEquals(ping.attributes.get(ErrorAttributes.ErrorType).map(_.value), Some("canceled"))
      assertEquals(attributeKeys(greet), Set("rpc.system.name", "rpc.method", "error.type"))
      assertEquals(attributeKeys(ping), Set("rpc.system.name", "rpc.method", "error.type"))
    }
  }
}
