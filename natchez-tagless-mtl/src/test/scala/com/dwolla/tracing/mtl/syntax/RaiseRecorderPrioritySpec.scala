package com.dwolla.tracing.mtl
package syntax

import cats.effect.IO
import com.dwolla.tagless.mtl.RaiseRecorder
import munit.CatsEffectSuite
import natchez.{Trace, TraceableValue}

/** When a user-supplied `OnRaise[F, Err]` is in scope alongside an ambient
  * `Trace[F]`, `RaiseRecorder[F, Err]` resolution must pick the user's instance over
  * the `Trace`-derived default; when no `OnRaise[F, Err]` is in scope, the
  * `Trace`-derived default must still be reachable. Mirrors `AspectPrioritySpec`'s
  * style: a compilation check (no ambiguous-implicit error) plus behavioral checks
  * that the correct instance actually ran, using the same poison-instance technique.
  */
class RaiseRecorderPrioritySpec extends CatsEffectSuite {
  private implicit val trace: Trace[IO] = Trace.Implicits.noop[IO]

  test("resolving RaiseRecorder with both a user OnRaise and a Trace instance in scope does not report an ambiguous implicit") {
    // Shared across 2.12/2.13/3: the type annotation on `errors` is required on
    // Scala 3, where an untyped val here trips a "Recursive value errors needs
    // type" cyclic-reference check and the captured string is that error instead
    // of the snippet's own diagnostics.
    val errors: String = compileErrors(
      """import cats.effect.IO
import natchez.{Trace, TraceableValue}
import com.dwolla.tagless.mtl.OnRaise
import com.dwolla.tagless.mtl.RaiseRecorder
implicit val trace: Trace[IO] = Trace.Implicits.noop[IO]
implicit val userOnRaise: OnRaise[IO, TraceableValue] = new OnRaise[IO, TraceableValue] {
  def apply[E](e: E)(implicit ev: TraceableValue[E]): IO[Unit] = IO.unit
}
implicitly[RaiseRecorder[IO, TraceableValue]]"""
    )
    assertNoDiff(errors, "")
  }

  test("a user-supplied OnRaise wins over the Trace-derived default, and it is the one that runs") {
    import RaiseRecorderPriorityFixtures.*

    val recorder = implicitly[RaiseRecorder[IO, TraceableValue]]
    intercept[AssertionError] {
      recorder.onRaise(42)
    }
  }

  test("the Trace-derived default is reachable when no OnRaise is in scope") {
    val recorder = implicitly[RaiseRecorder[IO, TraceableValue]]
    recorder.onRaise(42).assertEquals(())
  }

  test("an OnRaise declared only in an error ADT's companion object is never found, and the Trace-derived default runs instead") {
    // Deliberately not `import RaiseRecorderPriorityFixtures._` or
    // `RaiseRecorderPriorityFixtures.CompanionPoisonError._` — the point of this
    // test is that RaiseRecorderPriorityFixtures.CompanionPoisonError's own
    // companion object poison hook is reachable *only* by importing it
    // explicitly. Implicit search for OnRaise[IO, TraceableValue] consults the
    // companions of OnRaise, IO, and TraceableValue — never the companion of
    // whatever error type is raised — so a hook parked there (as one might
    // reasonably try, since that's exactly where a TraceableValue instance for
    // the same type would work) is silently skipped rather than found. If this
    // test ever throws AssertionError instead of completing, companion-object
    // placement started working for OnRaise and the "Overriding the default
    // recording" scaladoc in mtl/package.scala is wrong again.
    val recorder = implicitly[RaiseRecorder[IO, TraceableValue]]
    recorder
      .onRaise(RaiseRecorderPriorityFixtures.CompanionPoisonError("boom"))
      .assertEquals(())
  }
}
