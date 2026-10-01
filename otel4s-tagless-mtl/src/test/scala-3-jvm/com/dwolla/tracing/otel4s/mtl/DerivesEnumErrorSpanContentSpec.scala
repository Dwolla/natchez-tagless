package com.dwolla.tracing.otel4s.mtl

import cats.Applicative
import cats.effect.IO
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import com.dwolla.tracing.otel4s.ToAnyValue
import com.dwolla.tracing.otel4s.mtl.syntax.*
import io.opentelemetry.sdk.trace.data.SpanData
import munit.CatsEffectSuite
import org.typelevel.otel4s.AnyValue
import org.typelevel.otel4s.oteljava.testkit.trace.TracesTestkit
import org.typelevel.otel4s.trace.Tracer

import scala.annotation.experimental

enum ColorError derives CanEqual:
  case Red, Blue

object ColorError:
  given ToAnyValue[ColorError] = ToAnyValue.instance(e => AnyValue.string(e.toString))

trait ColorAlgebra[F[_]] derives AnyValueRaiseAspect:
  def color(raiseBlue: Boolean)(using R: Raise[F, ColorError]): F[String]

@experimental
object ColorAlgebra:
  def apply[F[_]: Applicative]: ColorAlgebra[F] = new ColorAlgebra[F]:
    def color(raiseBlue: Boolean)(using R: Raise[F, ColorError]): F[String] =
      R.raise(if raiseBlue then ColorError.Blue else ColorError.Red)

/** Scala 3 enum cases compile to anonymous subclasses of the enum, so their
  * runtime class names are indistinguishable (`...ColorError$$anon$1`); the
  * raise-time error type must name the case instead.
  */
@experimental
class DerivesEnumErrorSpanContentSpec extends CatsEffectSuite {
  private def raiseErrorType(raiseBlue: Boolean): IO[Option[String]] =
    TracesTestkit.inMemory[IO]().use { testkit =>
      testkit.tracerProvider.get("otel4s-tagless-mtl-test").flatMap { implicit tracer =>
        Handle.allowF[IO, ColorError] { implicit h =>
          ColorAlgebra[IO].traceWithInputs.color(raiseBlue)
        }.rescue(_ => "rescued".pure[IO])
      } *> testkit.finishedSpans.map { (spans: List[SpanData]) =>
        spans.headOption.flatMap(s => Option(s.getAttributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("com.dwolla.raise.error.type"))))
      }
    }

  test("raise-time error type names each Scala 3 enum case distinctly") {
    (raiseErrorType(raiseBlue = false), raiseErrorType(raiseBlue = true)).mapN { (red, blue) =>
      assertEquals(red, Some("com.dwolla.tracing.otel4s.mtl.ColorError.Red"))
      assertEquals(blue, Some("com.dwolla.tracing.otel4s.mtl.ColorError.Blue"))
      assertNotEquals(red, blue)
    }
  }
}
