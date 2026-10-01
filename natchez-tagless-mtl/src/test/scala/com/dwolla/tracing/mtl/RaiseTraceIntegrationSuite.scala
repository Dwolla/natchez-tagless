package com.dwolla.tracing.mtl

import cats.data.Kleisli
import cats.effect.{IO, IOLocal, MonadCancelThrow}
import cats.mtl.{Handle, Local}
import cats.syntax.all._
import cats.tagless.Trivial
import com.dwolla.tagless.mtl.RaiseAspect
import com.dwolla.tracing.InMemorySuite
import com.dwolla.tracing.mtl.syntax._
import natchez.InMemory.Lineage.Root
import natchez.InMemory.NatchezCommand._
import natchez.InMemory.{Lineage, NatchezCommand}
import natchez.TraceValue.{NumberValue, StringValue}
import natchez._

/** The end-to-end integration test
  *
  * `Bar` has a method-level `Raise` capability. This suite
  * checks span naming, input/output attribute capture, and capability erasure
  * through the actual natchez `InMemory` backend; [[RaiseTraceValueSpec]] separately
  * checks the raise/`Handle.allow`/`rescue` round trip's actual *value*, which
  * `InMemory`'s command history doesn't observe.
  *
  * `barRaiseAspect` is abstract so the Scala 2 and Scala 3 concrete specs can each
  * supply `DeriveRaise.aspect[Bar, TraceableValue, TraceableValue, TraceableValue]`
  * at their own, version-appropriate call site (the Scala 3 one needs `@experimental`).
  *
  * `barRaiseAspectTrivialCod` supplies the same derivation at `Cod = Trivial`, so
  * `traceWithInputs[Trivial]` has a `RaiseAspect[Bar, TraceableValue, Trivial, TraceableValue]`
  * to resolve `WeaveInterpreter.fromRaiseAspect` against, exercising
  * that resolution at a `Cod` other than `Err`.
  *
  * '''This suite has a twin.''' `DerivesBarTracingSpec` (Scala 3 only) holds a
  * verbatim copy of [[raisingProgram]], [[raisingProgramHistory]] and
  * [[assertRaisingHistory]] with `DerivesBar` in place of `Bar`, because those
  * members are `private` here and this class is not abstracted over the algebra.
  * Nothing links the two mechanically, so a ''new'' assertion added here — as
  * `assertNoReturnValuePut` was — will not appear there and nothing will go red.
  * If you extend this suite, decide deliberately whether the copy needs it too.
  */
abstract class RaiseTraceIntegrationSuite extends InMemorySuite {
  implicit def barRaiseAspect: RaiseAspect[Bar, TraceableValue, TraceableValue, TraceableValue]
  implicit def barRaiseAspectTrivialCod: RaiseAspect[Bar, TraceableValue, Trivial, TraceableValue]

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

  /** Same raising program as [[raisingProgram]], but woven through
    * `traceWithInputs[Trivial]` instead of `traceWithInputsAndOutputs` —
    * fixing `Cod` to `Trivial` opts the woven algebra out of return-value
    * rendering, per `RaiseTraceWeaveOps#traceWithInputs`'s scaladoc.
    */
  private def raisingProgramTraceWithInputs[F[_]: MonadCancelThrow](entryPoint: EntryPoint[F])(implicit
      L: Local[F, Span[F]]
  ): F[Unit] = {
    import natchez.mtl._

    val tracedBar: Bar[F] = Bar[F].traceWithInputs[Trivial]

    val effect: F[Unit] =
      Handle
        .allowF[F, BarError] { implicit h => tracedBar.bar(-1) }
        .rescue(_ => "rescued".pure[F])
        .void

    entryPoint.root("test").use(L.scope(effect))
  }

  /** The raise path's history minus its `AttachError` entry: the input `Put` runs
    * before the codomain target raises, so there is no `Put` for the return value —
    * but the `RaiseRecorder` default now records the typed error itself as a second
    * `Put`, sequenced (per `RaiseAspect.observing`) before the raise actually
    * happens. Both variants below additionally record an `AttachError` for the
    * escaping `Submarine` exception, between that `Put` and `ReleaseSpan`;
    * [[assertRaisingHistory]] checks that entry structurally and this list around it.
    */
  private val raisingProgramHistory: List[(Lineage, NatchezCommand)] = List(
    Root -> CreateRootSpan("test", Kernel(Map.empty), Span.Options.Defaults),
    Root("test") -> CreateSpan("Bar.bar", None, Span.Options.Defaults),
    Root("test") / "Bar.bar" -> Put(List("Bar.bar.i" -> NumberValue(-1))),
    Root("test") / "Bar.bar" -> Put(
      List(
        "com.dwolla.raise.error.type" -> StringValue(classOf[BarError.Negative].getName),
        "com.dwolla.raise.error.value" -> StringValue("negative:-1")
      )
    ),
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
    assertEquals(history.size, 7)
    assertEquals(history.take(4), raisingProgramHistory.take(4))
    assertEquals(history.drop(5), raisingProgramHistory.drop(4))

    val (lineage, attachError) = history(4)
    assertEquals(lineage, Root("test") / "Bar.bar")
    attachError match {
      case AttachError(err, Nil) => assertEquals(err.getClass.getSimpleName, "Submarine")
      case other => fail(s"expected an AttachError, got $other")
    }
  }

  test("RaiseAspect tracing releases the span and records the input attribute and the raised error's type/message when the method raises - Kleisli") {
    InMemory.EntryPoint.create[Kleisli[IO, Span[IO], *]]
      .flatMap { ep =>
        raisingProgram[Kleisli[IO, Span[IO], *]](ep) *> ep.ref.get.map(_.toList)
      }
      .run(Span.noop[IO])
      .map(assertRaisingHistory)
  }

  test("RaiseAspect tracing releases the span and records the input attribute and the raised error's type/message when the method raises - IOLocal") {
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

  /** `Cod = Trivial` never has a `TraceableValue` (or any other `Cod`)
    * instance for `Bar.bar`'s `String` result to render through, so a stray
    * `Bar.bar.returnValue` `Put` in the history would mean output rendering
    * leaked back in despite `Cod` being fixed to `Trivial` — the failure mode
    * this scan exists to catch, distinct from [[assertRaisingHistory]]'s
    * exact-equality check (which would also fail, but only incidentally,
    * since it isn't specifically about the return value).
    */
  private def assertNoReturnValuePut(history: List[(Lineage, NatchezCommand)]): Unit =
    assert(
      !history.exists {
        case (_, Put(fields)) => fields.exists(_._1 == "Bar.bar.returnValue")
        case _ => false
      },
      s"expected no Bar.bar.returnValue Put in $history"
    )

  test("traceWithInputs[Trivial] still records the raised error's type/message and the input attribute, but no return value - Kleisli") {
    InMemory.EntryPoint.create[Kleisli[IO, Span[IO], *]]
      .flatMap { ep =>
        raisingProgramTraceWithInputs[Kleisli[IO, Span[IO], *]](ep) *> ep.ref.get.map(_.toList)
      }
      .run(Span.noop[IO])
      .map { history =>
        assertRaisingHistory(history)
        assertNoReturnValuePut(history)
      }
  }

  test("traceWithInputs[Trivial] still records the raised error's type/message and the input attribute, but no return value - IOLocal") {
    IOLocal(Span.noop[IO])
      .map(localViaIoLocal(_))
      .map(implicit L => raisingProgramTraceWithInputs[IO](_))
      .flatMap { program =>
        InMemory.EntryPoint.create[IO].flatMap { ep =>
          program(ep) *> ep.ref.get.map(_.toList)
        }
      }
      .map { history =>
        assertRaisingHistory(history)
        assertNoReturnValuePut(history)
      }
  }

  test("the default recorder renders the error through TraceableValue, not toString") {
    assertNotEquals("negative:-1", BarError.Negative(-1).toString)
  }
}
