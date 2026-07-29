package com.dwolla.tracing.mtl
package syntax

import cats.effect.IO
import munit.CatsEffectSuite
import natchez.Trace

/** Task 3 — when a user-supplied `OnRaise[F]` is in scope alongside an ambient
  * `Trace[F]`, `RaiseRecorder[F]` resolution must pick the user's instance over the
  * `Trace`-derived default; when no `OnRaise[F]` is in scope, the `Trace`-derived
  * default must still be reachable. Mirrors `AspectPrioritySpec`'s style: a
  * compilation check (no ambiguous-implicit error) plus behavioral checks that the
  * correct instance actually ran, using the same poison-instance technique.
  */
class RaiseRecorderPrioritySpec extends CatsEffectSuite {
  private implicit val trace: Trace[IO] = Trace.Implicits.noop[IO]

  test("resolving RaiseRecorder with both a user OnRaise and a Trace instance in scope does not report an ambiguous implicit") {
    // Shared across 2.12/2.13/3: the type annotation on `errors` is required on
    // Scala 3, where an untyped val here trips a "Recursive value errors needs
    // type" cyclic-reference check and the captured string is that error instead
    // of the snippet's own diagnostics (a lesson from M4's DerivationErrorSpec).
    val errors: String = compileErrors(
      """import cats.effect.IO
import natchez.Trace
import com.dwolla.tagless.mtl.OnRaise
import com.dwolla.tracing.mtl.syntax.RaiseRecorder
implicit val trace: Trace[IO] = Trace.Implicits.noop[IO]
implicit val userOnRaise: OnRaise[IO] = new OnRaise[IO] {
  def apply[E](e: E): IO[Unit] = IO.unit
}
implicitly[RaiseRecorder[IO]]"""
    )
    assert(!errors.toLowerCase.contains("ambiguous"), errors)
  }

  test("a user-supplied OnRaise wins over the Trace-derived default, and it is the one that runs") {
    import RaiseRecorderPriorityFixtures._

    val recorder = implicitly[RaiseRecorder[IO]]
    intercept[AssertionError] {
      recorder.onRaise(42)
    }
  }

  test("the Trace-derived default is reachable when no OnRaise is in scope") {
    val recorder = implicitly[RaiseRecorder[IO]]
    recorder.onRaise(42).assertEquals(())
  }
}
