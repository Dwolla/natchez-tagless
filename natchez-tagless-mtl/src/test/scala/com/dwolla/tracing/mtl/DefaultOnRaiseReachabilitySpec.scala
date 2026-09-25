package com.dwolla.tracing.mtl

import cats.effect.IO
import com.dwolla.tagless.mtl.RaiseRecorder
import munit.CatsEffectSuite
import natchez.{Trace, TraceableValue}

/** `RaiseRecorder` lives in `raise-aspect`, which cannot name natchez, so the
  * natchez default (`NatchezDefaultOnRaise`) is reached lexically — by importing
  * `com.dwolla.tracing.mtl.syntax`, which carries it via the package object — not
  * automatically through implicit scope.
  *
  * Declared in `com.dwolla.tracing.mtl`, one package up from `.syntax`, on purpose:
  * `RaiseRecorderPrioritySpec` lives *inside* `.syntax`, so it gets the default via
  * ordinary same-package visibility regardless of whether it imports the syntax
  * package. This spec sits where a real caller of the library actually
  * would, outside that package, so the import is doing real work rather than
  * being redundant with lexical scope that was already going to supply it.
  *
  * Deliberately no file-level `import com.dwolla.tracing.mtl.syntax._`: MUnit's
  * `compileErrors` typechecks its string using the macro's enclosing compiler
  * context (`c.typecheck(c.parse(...))` in `MacroCompatScala2`), which inherits
  * whatever is lexically in scope at the call site — including a *file-level*
  * import, even though the string itself never mentions it. A file-level import
  * of the syntax package would silently satisfy the second test's summon and
  * make it compile, defeating the point. The first test imports it locally,
  * scoped to its own test block only, so it can't leak into the second.
  */
class DefaultOnRaiseReachabilitySpec extends CatsEffectSuite {
  private implicit val trace: Trace[IO] = Trace.Implicits.noop[IO]

  test("with the syntax import in scope, the natchez default resolves and runs") {
    import com.dwolla.tracing.mtl.syntax._
    val recorder = implicitly[RaiseRecorder[IO, TraceableValue]]
    recorder.onRaise(42).assertEquals(())
  }

  test("without the syntax import, the same summon fails to compile") {
    // Only asserts the summon fails at all, not any particular diagnostic: the
    // "no implicit found" message text differs across 2.12/2.13/3.
    val errors: String = compileErrors(
      """import cats.effect.IO
import com.dwolla.tagless.mtl.RaiseRecorder
import natchez.{Trace, TraceableValue}
implicit val trace: Trace[IO] = Trace.Implicits.noop[IO]
implicitly[RaiseRecorder[IO, TraceableValue]]"""
    )
    assert(errors.nonEmpty, "expected resolving RaiseRecorder[IO, TraceableValue] without importing com.dwolla.tracing.mtl.syntax to fail, but it compiled")
  }
}
