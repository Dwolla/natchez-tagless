package com.dwolla.tracing.mtl

import cats.Applicative
import cats.data.Kleisli
import cats.effect.{IO, MonadCancelThrow}
import cats.mtl.{Handle, Local, Raise}
import cats.syntax.all.*
import com.dwolla.tracing.InMemorySuite
import com.dwolla.tracing.mtl.syntax.*
import natchez.*
import natchez.InMemory.NatchezCommand.Put

import scala.annotation.experimental

enum ColorError derives CanEqual:
  case Red, Blue

object ColorError:
  given TraceableValue[ColorError] = TraceableValue[String].contramap(_.toString)

trait ColorAlgebra[F[_]] derives TraceableRaiseAspect:
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
class DerivesEnumErrorTracingSpec extends InMemorySuite {
  private type Traced[A] = Kleisli[IO, Span[IO], A]

  private def program[F[_]: MonadCancelThrow](raiseBlue: Boolean)(entryPoint: EntryPoint[F])(implicit
      L: Local[F, Span[F]]
  ): F[Unit] = {
    import natchez.mtl.*

    val traced: ColorAlgebra[F] = ColorAlgebra[F].traceWithInputs
    val effect: F[Unit] =
      Handle
        .allowF[F, ColorError] { implicit h => traced.color(raiseBlue) }
        .rescue(_ => "rescued".pure[F])
        .void

    entryPoint.root("test").use(L.scope(effect))
  }

  private def recordedErrorType(raiseBlue: Boolean): IO[Option[TraceValue]] =
    InMemory.EntryPoint.create[Traced]
      .flatMap { ep => program[Traced](raiseBlue)(ep) *> ep.ref.get.map(_.toList) }
      .run(Span.noop[IO])
      .map(_.collectFirst {
        case (_, Put(fields)) if fields.exists(_._1 == "com.dwolla.raise.error.type") =>
          fields.collectFirst { case ("com.dwolla.raise.error.type", v) => v }
      }.flatten)

  test("raise-time error type names each Scala 3 enum case distinctly") {
    (recordedErrorType(raiseBlue = false), recordedErrorType(raiseBlue = true)).mapN { (red, blue) =>
      assertEquals(red, Some(TraceValue.StringValue("com.dwolla.tracing.mtl.ColorError.Red")))
      assertEquals(blue, Some(TraceValue.StringValue("com.dwolla.tracing.mtl.ColorError.Blue")))
      assertNotEquals(red, blue)
    }
  }
}
