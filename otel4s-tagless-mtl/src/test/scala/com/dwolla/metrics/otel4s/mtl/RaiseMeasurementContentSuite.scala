package com.dwolla.metrics.otel4s.mtl

import cats.effect.IO
import cats.mtl.{Handle, Raise}
import cats.syntax.all._
import cats.tagless.aop.{Aspect, Instrument, Instrumentation}
import cats.{Apply, ~>}
import com.dwolla.metrics.otel4s.{FooFailure, HistogramAssertions, RecordedPoint, RpcRole, RpcService, RpcSystem, Foo => PlainFoo}
import com.dwolla.metrics.otel4s.mtl.syntax._
import com.dwolla.tagless.mtl.{OnRaise, RaiseAspect}
import com.dwolla.tracing.otel4s.ToAnyValue
import com.dwolla.tracing.otel4s.mtl.{Foo, FooError}
import munit.CatsEffectSuite
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.semconv.attributes.{CodeAttributes, ErrorAttributes}
import org.typelevel.otel4s.semconv.experimental.attributes.RpcExperimentalAttributes
import org.typelevel.otel4s.trace.TracerProvider

import java.util.concurrent.TimeoutException
import scala.concurrent.duration._

/** Metric ''content'' recorded by `com.dwolla.metrics.otel4s.mtl.syntax`'s
  * `withMetrics`, asserted against a real SDK: a `Raise` algebra records what
  * `com.dwolla.metrics.otel4s.syntax` records for a plain one.
  *
  * Every test lives here; a subclass supplies only `histogramsFrom` by mixing
  * in one of otel4s-tagless-metrics's backends: otel4s-sdk
  * (`OtelSdkRaiseMeasurementContentSpec`, every platform) or oteljava
  * (`OtelJavaRaiseMeasurementContentSpec`, JVM).
  */
abstract class RaiseMeasurementContentSuite extends CatsEffectSuite with HistogramAssertions {

  private def attributeKeys(point: RecordedPoint): Set[String] =
    point.attributes.map(_.key.name).toSet

  private val functionDuration: String = "com.dwolla.code.function.duration"

  /** A `Foo[IO]` whose every call sleeps for `callDuration`, then ends as `behave` says. */
  private def sleepingFoo(callDuration: FiniteDuration, behave: (Int, Raise[IO, FooError]) => IO[String]): Foo[IO] =
    new Foo[IO] {
      override def foo(i: Int)(implicit R: Raise[IO, FooError]): IO[String] =
        IO.sleep(callDuration) >> behave(i, R)
    }

  /** Runs `call` with a `Raise[IO, FooError]` in scope; a raise that escapes
    * `call` comes back on the left.
    */
  private def handlingFooError[A](call: Raise[IO, FooError] => IO[A]): IO[Either[FooError, A]] =
    Handle.allowF[IO, FooError](h => call(h).map(_.asRight[FooError])).rescue(_.asLeft[A].pure[IO])

  test("a Raise algebra's successful call records its duration in seconds under code.function.name = Foo.foo, with no error.type") {
    measured { implicit meterProvider =>
      sleepingFoo(250.millis, (i, _) => s"foo:$i".pure[IO])
        .withMetrics()
        .flatMap(metered => handlingFooError(implicit h => metered.foo(1)))
    }.map { case (result, histograms) =>
      assertEquals(result, Right("foo:1"))
      val histogram = histogramNamed(functionDuration, histograms)
      val point = pointFor(CodeAttributes.CodeFunctionName, "Foo.foo", histogram)
      assertEquals(histogram.points, List(point))
      assertEquals(point.count, 1L)
      assertEqualsDouble(point.sum, 0.25, 1e-9)
      assertEquals(point.attributes.get(ErrorAttributes.ErrorType), None)
      assertEquals(attributeKeys(point), Set("code.function.name"))
    }
  }

  test("a Raise algebra's failed call returns the identical error and records its class name as error.type") {
    val failure = new FooFailure
    measured { implicit meterProvider =>
      sleepingFoo(1.milli, (_, _) => IO.raiseError(failure))
        .withMetrics()
        .flatMap(metered => handlingFooError(implicit h => metered.foo(1).attempt))
    }.map { case (result, histograms) =>
      assert(result.exists(_.left.exists(_ eq failure)), s"expected the identical FooFailure back, got $result")
      val point = pointFor(ErrorAttributes.ErrorType, classOf[FooFailure].getName, histogramNamed(functionDuration, histograms))
      assertEquals(point.attributes.get(CodeAttributes.CodeFunctionName).map(_.value), Some("Foo.foo"))
      assertEquals(point.count, 1L)
    }
  }

  test("a raise that escapes a Raise algebra's call records the domain error's class as error.type") {
    measured { implicit meterProvider =>
      Foo[IO].withMetrics().flatMap { metered =>
        Handle.allowF[IO, FooError](implicit h => metered.foo(-1).as(Option.empty[FooError])).rescue(e => e.some.pure[IO])
      }
    }.map { case (result, histograms) =>
      assertEquals(result, Some(FooError.Negative(-1)))
      val point = pointFor(ErrorAttributes.ErrorType, classOf[FooError.Negative].getName, histogramNamed(functionDuration, histograms))
      assertEquals(point.attributes.get(CodeAttributes.CodeFunctionName).map(_.value), Some("Foo.foo"))
      assertEquals(point.count, 1L)
    }
  }

  test("a raise rescued inside the method is a success with no error.type") {
    measured { implicit meterProvider =>
      val rescuingInside = sleepingFoo(1.milli, (i, _) =>
        Handle.allowF[IO, FooError](h => h.raise[FooError, String](FooError.Negative(i))).rescue(_ => "recovered".pure[IO])
      )
      rescuingInside.withMetrics().flatMap(metered => handlingFooError(implicit h => metered.foo(-1)))
    }.map { case (result, histograms) =>
      assertEquals(result, Right("recovered"))
      val point = pointFor(CodeAttributes.CodeFunctionName, "Foo.foo", histogramNamed(functionDuration, histograms))
      assertEquals(point.attributes.get(ErrorAttributes.ErrorType), None)
      assertEquals(point.count, 1L)
    }
  }

  test("a Raise algebra's call canceled by a timeout records error.type = canceled") {
    measured { implicit meterProvider =>
      sleepingFoo(10.seconds, (i, _) => s"foo:$i".pure[IO])
        .withMetrics()
        .flatMap(metered => handlingFooError(implicit h => metered.foo(1).timeout(1.second).attempt))
    }.map { case (result, histograms) =>
      assert(result.exists(_.left.exists(_.isInstanceOf[TimeoutException])), s"expected a TimeoutException, got $result")
      val point = pointFor(ErrorAttributes.ErrorType, "canceled", histogramNamed(functionDuration, histograms))
      assertEquals(point.attributes.get(CodeAttributes.CodeFunctionName).map(_.value), Some("Foo.foo"))
      assertEquals(point.count, 1L)
      assertEqualsDouble(point.sum, 1.0, 1e-9)
    }
  }

  test("the RPC overload on a Raise algebra records rpc.server.call.duration with rpc.method = <service>/foo and the escaped raise's error.type") {
    measured { implicit meterProvider =>
      Foo[IO]
        .withMetrics(RpcRole.Server, RpcSystem("thrift"), RpcService("com.example.FooService"))
        .flatMap(metered => handlingFooError(implicit h => metered.foo(-1)))
    }.map { case (result, histograms) =>
      assertEquals(result, Left(FooError.Negative(-1)))
      assertEquals(histograms.map(_.name), List("rpc.server.call.duration"))
      val point = pointFor(RpcExperimentalAttributes.RpcMethod, "com.example.FooService/foo", histogramNamed("rpc.server.call.duration", histograms))
      assertEquals(point.attributes.get(RpcExperimentalAttributes.RpcSystemName).map(_.value), Some("thrift"))
      assertEquals(point.attributes.get(ErrorAttributes.ErrorType).map(_.value), Some(classOf[FooError.Negative].getName))
      assertEquals(point.count, 1L)
    }
  }

  test("a plain Instrument algebra metered through the mtl syntax records exactly as the plain syntax does") {
    def greeting: PlainFoo[IO] = PlainFoo[IO](name => IO.sleep(250.millis).as(s"hello $name"), IO.unit)

    val viaPlainSyntax =
      measured { implicit meterProvider =>
        com.dwolla.metrics.otel4s.syntax.toWithMetricsOps(greeting).withMetrics().flatMap(_.greet("world"))
      }
    val viaMtlSyntax =
      measured { implicit meterProvider =>
        greeting.withMetrics().flatMap(_.greet("world"))
      }

    (viaPlainSyntax, viaMtlSyntax).mapN { case ((plainResult, plainHistograms), (mtlResult, mtlHistograms)) =>
      assertEquals(mtlResult, "hello world")
      assertEquals(mtlResult, plainResult)
      assertEquals(mtlHistograms, plainHistograms)
      val point = pointFor(CodeAttributes.CodeFunctionName, "Foo.greet", histogramNamed(functionDuration, mtlHistograms))
      assertEquals(point.count, 1L)
    }
  }

  test("plain and Raise algebras share one com.dwolla.code.function.duration metric") {
    measured { implicit meterProvider =>
      for {
        plain <- PlainFoo[IO](name => s"hello $name".pure[IO], IO.unit).withMetrics()
        raising <- Foo[IO].withMetrics()
        _ <- plain.greet("world")
        _ <- handlingFooError(implicit h => raising.foo(1))
      } yield ()
    }.map { case (_, histograms) =>
      val histogram = histogramNamed(functionDuration, histograms)
      assertEquals(histograms.map(_.name), List(functionDuration))
      assertEquals(pointFor(CodeAttributes.CodeFunctionName, "Foo.greet", histogram).count, 1L)
      assertEquals(pointFor(CodeAttributes.CodeFunctionName, "Foo.foo", histogram).count, 1L)
      assertEquals(histogram.points.size, 2)
    }
  }

  test("Instrument wins when an algebra has both an Instrument and a RaiseAspect") {
    measured { implicit meterProvider =>
      DoublyInstrumented.io.withMetrics().flatMap(_.call())
    }.map { case (result, histograms) =>
      assertEquals(result, "called")
      val histogram = histogramNamed(functionDuration, histograms)
      val point = pointFor(CodeAttributes.CodeFunctionName, "DoublyInstrumented.viaInstrument", histogram)
      assertEquals(histogram.points, List(point))
    }
  }

  test("metrics then traceWithInputs composes for a Raise algebra") {
    import com.dwolla.tracing.otel4s.mtl.syntax._
    implicit val tracerProvider: TracerProvider[IO] = TracerProvider.noop[IO]

    measured { implicit meterProvider =>
      Foo[IO]
        .withMetrics()
        .flatMap(_.traceWithInputs[ToAnyValue])
        .flatMap(traced => handlingFooError(implicit h => traced.foo(-1)))
    }.map { case (result, histograms) =>
      assertEquals(result, Left(FooError.Negative(-1)))
      val point = pointFor(CodeAttributes.CodeFunctionName, "Foo.foo", histogramNamed(functionDuration, histograms))
      assertEquals(point.count, 1L)
    }
  }

  test("an algebra with neither instance fails to compile with a message naming both options") {
    // The type annotation on `errors` is required on Scala 3, where an untyped
    // val here trips a cyclic-reference check that masks the snippet's own
    // diagnostics.
    val errors: String = compileErrors(
      """import cats.effect.IO
import com.dwolla.metrics.otel4s.mtl.syntax._
import org.typelevel.otel4s.metrics.MeterProvider
implicit val meterProvider: MeterProvider[IO] = MeterProvider.noop[IO]
com.dwolla.metrics.otel4s.mtl.Unmeterable.io.withMetrics()"""
    )
    // Scala 3 appends an "I found: ... Instrument[...]" hint of its own, so the
    // first line also pins that the message is Meterable's @implicitNotFound.
    assert(errors.contains("Can't meter com.dwolla.metrics.otel4s.mtl.Unmeterable's calls"), errors)
    assert(errors.contains("Instrument"), errors)
    assert(errors.contains("RaiseAspect"), errors)
  }
}

/** Has neither an `Instrument` nor a `RaiseAspect`, so it can't be metered. */
trait Unmeterable[F[_]] {
  def call(): F[String]
}

object Unmeterable {
  val io: Unmeterable[IO] = new Unmeterable[IO] {
    override def call(): IO[String] = "called".pure[IO]
  }
}

/** Raise-free, so it can have both an `Instrument` and a pinned `RaiseAspect`.
  * Each names `call` differently, so the recorded `code.function.name` shows
  * which one `Meterable` chose.
  */
trait DoublyInstrumented[F[_]] {
  def call(): F[String]
}

object DoublyInstrumented {
  val io: DoublyInstrumented[IO] = new DoublyInstrumented[IO] {
    override def call(): IO[String] = "called".pure[IO]
  }

  implicit val doublyInstrumentedInstrument: Instrument[DoublyInstrumented] =
    new Instrument[DoublyInstrumented] {
      override def instrument[F[_]](af: DoublyInstrumented[F]): DoublyInstrumented[Instrumentation[F, *]] =
        new DoublyInstrumented[Instrumentation[F, *]] {
          override def call(): Instrumentation[F, String] =
            Instrumentation(af.call(), "DoublyInstrumented", "viaInstrument")
        }

      override def mapK[F[_], G[_]](af: DoublyInstrumented[F])(fk: F ~> G): DoublyInstrumented[G] =
        new DoublyInstrumented[G] {
          override def call(): G[String] = fk(af.call())
        }
    }

  implicit val doublyInstrumentedRaiseAspect: RaiseAspect[DoublyInstrumented, ToAnyValue, ToAnyValue, ToAnyValue] =
    new RaiseAspect[DoublyInstrumented, ToAnyValue, ToAnyValue, ToAnyValue] {
      override def intercept[F[_]](af: DoublyInstrumented[F])(
          fk: Aspect.Weave[F, ToAnyValue, ToAnyValue, *] ~> F,
          onRaise: OnRaise[F, ToAnyValue]
      )(implicit F: Apply[F]): DoublyInstrumented[F] =
        new DoublyInstrumented[F] {
          override def call(): F[String] =
            fk(
              Aspect.Weave[F, ToAnyValue, ToAnyValue, String](
                "DoublyInstrumented",
                Nil,
                Aspect.Advice[F, ToAnyValue, String]("viaRaiseAspect", af.call())
              )
            )
        }
    }
}
