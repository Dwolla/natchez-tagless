package com.dwolla.tracing.mtl

import cats.data.Kleisli
import cats.effect.MonadCancelThrow
import cats.mtl.{Handle, Local}
import cats.syntax.all.*
import com.dwolla.tracing.InMemorySuite
import com.dwolla.tracing.mtl.syntax.*
import natchez.InMemory.Lineage.Root
import natchez.InMemory.NatchezCommand.*
import natchez.InMemory.{Lineage, NatchezCommand}
import natchez.TraceValue.{NumberValue, StringValue}
import natchez.*

import scala.annotation.experimental

/** The point of M13, stated as a test: an algebra that says
  * `derives TraceableRaiseAspect` and nothing else traces exactly as one with a
  * hand-declared instance does.
  *
  * Nothing here imports `DeriveRaise`, declares an instance, or mentions
  * `RaiseAspect`. The only difference from `RaiseTraceIntegrationSuite` is how
  * the instance came to exist — and the expected histories below are that
  * suite's, with `Bar` replaced by `DerivesBar`.
  */
@experimental
class DerivesBarTracingSpec extends InMemorySuite {
  traceTest(
    "an algebra deriving TraceableRaiseAspect captures span, input, and output",
    new TraceTest {
      def program[F[_]: MonadCancelThrow](entryPoint: EntryPoint[F])(implicit L: Local[F, Span[F]]): F[Unit] = {
        import natchez.mtl.*

        val traced: DerivesBar[F] = DerivesBar[F].traceWithInputsAndOutputs

        val effect: F[Unit] =
          Handle
            .allowF[F, BarError] { implicit h => traced.bar(5) }
            .rescue(_ => "unexpected raise".pure[F])
            .void

        entryPoint.root("test").use(L.scope(effect))
      }

      override def expectedHistory: List[(Lineage, NatchezCommand)] = List(
        Root -> CreateRootSpan("test", Kernel(Map.empty), Span.Options.Defaults),
        Root("test") -> CreateSpan("DerivesBar.bar", None, Span.Options.Defaults),
        Root("test") / "DerivesBar.bar" -> Put(List("DerivesBar.bar.i" -> NumberValue(5))),
        Root("test") / "DerivesBar.bar" -> Put(
          List("DerivesBar.bar.returnValue" -> StringValue("bar:5"))
        ),
        Root("test") -> ReleaseSpan("DerivesBar.bar"),
        Root -> ReleaseRootSpan("test")
      )
    }
  )

  /** The raise path, which is where the hook and the `Err` evidence matter.
    * Asserted structurally around the `AttachError` entry for the same reason
    * `RaiseTraceIntegrationSuite` does it that way: cats-mtl's `Submarine` is
    * `private[mtl]` and carries an unpredictable identity marker.
    */
  test("a raise through a derived instance still records the typed error") {
    import natchez.mtl.*

    InMemory.EntryPoint
      .create[Kleisli[cats.effect.IO, Span[cats.effect.IO], *]]
      .flatMap { ep =>
        type F[A] = Kleisli[cats.effect.IO, Span[cats.effect.IO], A]
        val traced: DerivesBar[F] = DerivesBar[F].traceWithInputsAndOutputs
        val effect: F[Unit] =
          Handle.allowF[F, BarError] { implicit h => traced.bar(-1) }.rescue(_ => "rescued".pure[F]).void

        ep.root("test").use(localSpan[cats.effect.IO].scope(effect)) *> ep.ref.get.map(_.toList)
      }
      .run(Span.noop[cats.effect.IO])
      .map { history =>
        assertEquals(history.size, 7)
        assertEquals(
          history(3),
          Root("test") / "DerivesBar.bar" -> Put(
            List(
              RaiseRecorder.ErrorTypeKey -> StringValue(classOf[BarError.Negative].getName),
              RaiseRecorder.ErrorValueKey -> StringValue("negative:-1")
            )
          )
        )
      }
  }
}
