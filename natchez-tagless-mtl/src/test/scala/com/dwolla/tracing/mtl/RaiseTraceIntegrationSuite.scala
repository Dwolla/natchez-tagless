package com.dwolla.tracing.mtl

import cats.data.Kleisli
import cats.effect.{IO, IOLocal, MonadCancelThrow}
import cats.mtl.{Handle, Local}
import cats.syntax.all._
import com.dwolla.tagless.mtl.RaiseAspect
import com.dwolla.tracing.InMemorySuite
import com.dwolla.tracing.mtl.syntax._
import natchez.InMemory.Lineage.Root
import natchez.InMemory.NatchezCommand._
import natchez.InMemory.{Lineage, NatchezCommand}
import natchez.TraceValue.{NumberValue, StringValue}
import natchez._

/** Task 4 — the end-to-end integration test.
  *
  * `Bar` has a method-level `Raise` capability, like the M1–M4 fixtures. This suite
  * checks span naming, input/output attribute capture, and capability erasure
  * through the actual natchez `InMemory` backend; [[RaiseTraceValueSpec]] separately
  * checks the raise/`Handle.allow`/`rescue` round trip's actual *value*, which
  * `InMemory`'s command history doesn't observe.
  *
  * `barRaiseAspect` is abstract so the Scala 2 and Scala 3 concrete specs can each
  * supply `DeriveRaise.aspect[Bar, TraceableValue, TraceableValue]` at their own,
  * version-appropriate call site (the Scala 3 one needs `@experimental`).
  */
abstract class RaiseTraceIntegrationSuite extends InMemorySuite {
  implicit def barRaiseAspect: RaiseAspect[Bar, TraceableValue, TraceableValue]

  traceTest(
    "RaiseAspect tracing captures span, input, and output",
    new TraceTest {
      def program[F[_]: MonadCancelThrow](entryPoint: EntryPoint[F])(implicit L: Local[F, Span[F]]): F[Unit] = {
        import natchez.mtl._

        val tracedBar: Bar[F] = Bar[F].traceWithInputsAndOutputs

        val effect: F[Unit] =
          Handle
            .allowF[F, BarError] { implicit h => tracedBar.bar(5) }
            .rescue(_ => "unexpected raise".pure[F])
            .void

        entryPoint.root("test").use(L.scope(effect))
      }

      override def expectedHistory: List[(Lineage, NatchezCommand)] = List(
        Root -> CreateRootSpan("test", Kernel(Map.empty), Span.Options.Defaults),
        Root("test") -> CreateSpan("Bar.bar", None, Span.Options.Defaults),
        Root("test") / "Bar.bar" -> Put(List("Bar.bar.i" -> NumberValue(5))),
        Root("test") / "Bar.bar" -> Put(List("Bar.bar.returnValue" -> StringValue("bar:5"))),
        Root("test") -> ReleaseSpan("Bar.bar"),
        Root -> ReleaseRootSpan("test")
      )
    }
  )

  private def raisingProgram[F[_]: MonadCancelThrow](entryPoint: EntryPoint[F])(implicit
      L: Local[F, Span[F]]
  ): F[Unit] = {
    import natchez.mtl._

    val tracedBar: Bar[F] = Bar[F].traceWithInputsAndOutputs

    val effect: F[Unit] =
      Handle
        .allowF[F, BarError] { implicit h => tracedBar.bar(-1) }
        .rescue(_ => "rescued".pure[F])
        .void

    entryPoint.root("test").use(L.scope(effect))
  }

  /** The raise path's history minus its `AttachError` entry: only the input `Put`
    * runs before the codomain target raises, so there is no second `Put` for the
    * return value. Both variants below additionally record an `AttachError` for
    * the escaping `Submarine` exception, between the input `Put` and
    * `ReleaseSpan`; [[assertRaisingHistory]] checks that entry structurally and
    * this list around it.
    *
    * `natchez.mtl.LocalTrace#span` calls `s.attachError(err)` in an `.onError`
    * handler whenever the traced body raises — confirmed by reading its source —
    * so a `Handle.rescue`d raise is still attached to the span as an error before
    * being caught, exactly as the milestone's Submarine caveat anticipates: the
    * attached error is cats-mtl's opaque `Submarine` wrapper, not `BarError`
    * directly, so span error annotations for a recovered domain error carry only
    * that the raise happened, not what it was.
    */
  private val raisingProgramHistory: List[(Lineage, NatchezCommand)] = List(
    Root -> CreateRootSpan("test", Kernel(Map.empty), Span.Options.Defaults),
    Root("test") -> CreateSpan("Bar.bar", None, Span.Options.Defaults),
    Root("test") / "Bar.bar" -> Put(List("Bar.bar.i" -> NumberValue(-1))),
    Root("test") -> ReleaseSpan("Bar.bar"),
    Root -> ReleaseRootSpan("test")
  )

  /** `Submarine` is `private[mtl]` and carries a fresh, unpredictable identity
    * marker, so it can't be reconstructed for an exact equality check the way
    * every other expected entry can — and field-level reflection to read its
    * wrapped value (`getDeclaredField`) doesn't exist on Scala.js, only on the
    * JVM. Assert the surrounding history structurally, and the attached error by
    * its class alone; `RaiseTraceValueSuite` already proves separately, on this
    * same `bar(-1)` call, that the wrapped domain error is `Negative(-1)`.
    */
  private def assertRaisingHistory(history: List[(Lineage, NatchezCommand)]): Unit = {
    assertEquals(history.size, 6)
    assertEquals(history.take(3), raisingProgramHistory.take(3))
    assertEquals(history.drop(4), raisingProgramHistory.drop(3))

    val (lineage, attachError) = history(3)
    assertEquals(lineage, Root("test") / "Bar.bar")
    attachError match {
      case AttachError(err, Nil) => assertEquals(err.getClass.getSimpleName, "Submarine")
      case other => fail(s"expected an AttachError, got $other")
    }
  }

  test("RaiseAspect tracing releases the span and records only the input attribute when the method raises - Kleisli") {
    InMemory.EntryPoint.create[Kleisli[IO, Span[IO], *]]
      .flatMap { ep =>
        raisingProgram[Kleisli[IO, Span[IO], *]](ep) *> ep.ref.get.map(_.toList)
      }
      .run(Span.noop[IO])
      .map(assertRaisingHistory)
  }

  test("RaiseAspect tracing releases the span and records only the input attribute when the method raises - IOLocal") {
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
