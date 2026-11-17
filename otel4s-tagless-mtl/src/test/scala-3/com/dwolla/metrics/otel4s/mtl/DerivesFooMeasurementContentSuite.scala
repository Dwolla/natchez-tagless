package com.dwolla.metrics.otel4s.mtl

import cats.effect.IO
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import com.dwolla.metrics.otel4s.{FooFailure, HistogramAssertions}
import com.dwolla.metrics.otel4s.mtl.syntax.*
import com.dwolla.tracing.otel4s.mtl.{DerivesFoo, FooError}
import munit.CatsEffectSuite
import org.typelevel.otel4s.semconv.attributes.{CodeAttributes, ErrorAttributes}

import scala.annotation.experimental
import scala.concurrent.duration.*

/** Metric ''content'' recorded by `withMetrics` for an algebra that says
  * `derives AnyValueRaiseAspect` and nothing else: the derivation's algebra
  * and method names are what reach `code.function.name`.
  *
  * Scala 3 only, because `derives` is. A subclass supplies only `histogramsFrom`
  * by mixing in one of otel4s-tagless-metrics's backends: otel4s-sdk
  * (`OtelSdkDerivesFooMeasurementContentSpec`, every platform) or oteljava
  * (`OtelJavaDerivesFooMeasurementContentSpec`, JVM).
  *
  * `@experimental` is on the class because `DerivesFoo`'s companion is, and
  * this is the narrowest definition that compiles with it in scope.
  */
@experimental
abstract class DerivesFooMeasurementContentSuite extends CatsEffectSuite with HistogramAssertions {

  private val functionDuration: String = "com.dwolla.code.function.duration"

  /** A `DerivesFoo[IO]` whose every call sleeps for `callDuration`, then ends as `behave` says. */
  private def sleepingDerivesFoo(callDuration: FiniteDuration, behave: (Int, Raise[IO, FooError]) => IO[String]): DerivesFoo[IO] =
    new DerivesFoo[IO] {
      override def foo(i: Int)(using R: Raise[IO, FooError]): IO[String] =
        IO.sleep(callDuration) >> behave(i, R)
    }

  /** Runs `call` with a `Raise[IO, FooError]` in scope; a raise that escapes
    * `call` comes back on the left.
    */
  private def handlingFooError[A](call: Raise[IO, FooError] => IO[A]): IO[Either[FooError, A]] =
    Handle.allowF[IO, FooError](h => call(h).map(_.asRight[FooError])).rescue(_.asLeft[A].pure[IO])

  test("a derived Raise algebra's successful call records its duration under code.function.name = DerivesFoo.foo") {
    measured { implicit meterProvider =>
      sleepingDerivesFoo(250.millis, (i, _) => s"foo:$i".pure[IO])
        .withMetrics()
        .flatMap(metered => handlingFooError(h => metered.foo(1)(using h)))
    }.map { case (result, histograms) =>
      assertEquals(result, Right("foo:1"))
      val histogram = histogramNamed(functionDuration, histograms)
      val point = pointFor(CodeAttributes.CodeFunctionName, "DerivesFoo.foo", histogram)
      assertEquals(histogram.points, List(point))
      assertEquals(point.count, 1L)
      assertEqualsDouble(point.sum, 0.25, 1e-9)
      assertEquals(point.attributes.get(ErrorAttributes.ErrorType), None)
    }
  }

  test("a derived Raise algebra's failed call records its class name as error.type") {
    val failure = new FooFailure
    measured { implicit meterProvider =>
      sleepingDerivesFoo(1.milli, (_, _) => IO.raiseError(failure))
        .withMetrics()
        .flatMap(metered => handlingFooError(h => metered.foo(1)(using h).attempt))
    }.map { case (result, histograms) =>
      assert(result.exists(_.left.exists(_ eq failure)), s"expected the identical FooFailure back, got $result")
      val point = pointFor(ErrorAttributes.ErrorType, classOf[FooFailure].getName, histogramNamed(functionDuration, histograms))
      assertEquals(point.attributes.get(CodeAttributes.CodeFunctionName).map(_.value), Some("DerivesFoo.foo"))
      assertEquals(point.count, 1L)
    }
  }

  test("a raise that escapes a derived Raise algebra's call records the domain error's class as error.type") {
    measured { implicit meterProvider =>
      DerivesFoo[IO].withMetrics().flatMap { metered =>
        handlingFooError(h => metered.foo(-1)(using h))
      }
    }.map { case (result, histograms) =>
      assertEquals(result, Left(FooError.Negative(-1)))
      val point = pointFor(ErrorAttributes.ErrorType, classOf[FooError.Negative].getName, histogramNamed(functionDuration, histograms))
      assertEquals(point.attributes.get(CodeAttributes.CodeFunctionName).map(_.value), Some("DerivesFoo.foo"))
      assertEquals(point.count, 1L)
    }
  }
}
