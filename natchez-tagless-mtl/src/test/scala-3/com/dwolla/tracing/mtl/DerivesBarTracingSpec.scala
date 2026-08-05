package com.dwolla.tracing.mtl

import cats.data.Kleisli
import cats.effect.{IO, IOLocal, MonadCancelThrow}
import cats.mtl.{Handle, Local}
import cats.syntax.all.*
import com.dwolla.tagless.mtl.RaiseRecorder
import com.dwolla.tracing.InMemorySuite
import com.dwolla.tracing.mtl.syntax.*
import natchez.InMemory.Lineage.Root
import natchez.InMemory.NatchezCommand.*
import natchez.InMemory.{Lineage, NatchezCommand}
import natchez.TraceValue.{NumberValue, StringValue}
import natchez.*

import scala.annotation.experimental

/** An algebra that says `derives TraceableRaiseAspect` and nothing else traces
  * exactly as one with a hand-declared instance does.
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

  /** Mirrors `RaiseTraceIntegrationSuite#raisingProgram`, `DerivesBar` in place
    * of `Bar` — the only difference the milestone permits.
    */
  private def raisingProgram[F[_]: MonadCancelThrow](entryPoint: EntryPoint[F])(implicit
      L: Local[F, Span[F]]
  ): F[Unit] = {
    import natchez.mtl.*

    val traced: DerivesBar[F] = DerivesBar[F].traceWithInputsAndOutputs

    val effect: F[Unit] =
      Handle
        .allowF[F, BarError] { implicit h => traced.bar(-1) }
        .rescue(_ => "rescued".pure[F])
        .void

    entryPoint.root("test").use(L.scope(effect))
  }

  /** The raise path's history minus its `AttachError` entry — mirrors
    * `RaiseTraceIntegrationSuite#raisingProgramHistory` exactly, `DerivesBar` in
    * place of `Bar`. [[assertRaisingHistory]] checks the `AttachError` entry
    * structurally and this list around it, for the same reason the other suite
    * does: cats-mtl's `Submarine` is `private[mtl]` and carries an
    * unpredictable identity marker.
    */
  private val raisingProgramHistory: List[(Lineage, NatchezCommand)] = List(
    Root -> CreateRootSpan("test", Kernel(Map.empty), Span.Options.Defaults),
    Root("test") -> CreateSpan("DerivesBar.bar", None, Span.Options.Defaults),
    Root("test") / "DerivesBar.bar" -> Put(List("DerivesBar.bar.i" -> NumberValue(-1))),
    Root("test") / "DerivesBar.bar" -> Put(
      List(
        RaiseRecorder.ErrorTypeKey -> StringValue(classOf[BarError.Negative].getName),
        RaiseRecorder.ErrorValueKey -> StringValue("negative:-1")
      )
    ),
    Root("test") -> ReleaseSpan("DerivesBar.bar"),
    Root -> ReleaseRootSpan("test")
  )

  /** `RaiseTraceIntegrationSuite#assertRaisingHistory` is `private` to that
    * suite and unreachable from here (this class extends `InMemorySuite`
    * directly, not that suite), so this is a faithful copy rather than a
    * shared helper — same checks, `DerivesBar` lineage: full equality on
    * entries 0-3 and 5-6, and the `AttachError` at entry 4 checked
    * structurally by lineage and wrapped-exception class.
    */
  private def assertRaisingHistory(history: List[(Lineage, NatchezCommand)]): Unit = {
    assertEquals(history.size, 7)
    assertEquals(history.take(4), raisingProgramHistory.take(4))
    assertEquals(history.drop(5), raisingProgramHistory.drop(4))

    val (lineage, attachError) = history(4)
    assertEquals(lineage, Root("test") / "DerivesBar.bar")
    attachError match {
      case AttachError(err, Nil) => assertEquals(err.getClass.getSimpleName, "Submarine")
      case other => fail(s"expected an AttachError, got $other")
    }
  }

  test("a raise through a derived instance still records the typed error, releases the span, and attaches the error - Kleisli") {
    InMemory.EntryPoint.create[Kleisli[IO, Span[IO], *]]
      .flatMap { ep =>
        raisingProgram[Kleisli[IO, Span[IO], *]](ep) *> ep.ref.get.map(_.toList)
      }
      .run(Span.noop[IO])
      .map(assertRaisingHistory)
  }

  test("a raise through a derived instance still records the typed error, releases the span, and attaches the error - IOLocal") {
    IOLocal(Span.noop[IO])
      .map(localViaIoLocal(_))
      .map(implicit L => raisingProgram[IO](_))
      .flatMap { program =>
        InMemory.EntryPoint.create[IO].flatMap { ep =>
          program(ep) *> ep.ref.get.map(_.toList)
        }
      }
      .map(assertRaisingHistory)
  }
}
