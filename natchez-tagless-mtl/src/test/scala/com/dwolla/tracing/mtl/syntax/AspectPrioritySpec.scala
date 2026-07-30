package com.dwolla.tracing.mtl
package syntax

import cats.effect.IO
import munit.CatsEffectSuite
import natchez.{Trace, TraceableValue}

/** Task 3 (M6) / M11 — when an algebra has both an `Aspect` and a `RaiseAspect`
  * instance in scope, both `.traceWithInputsAndOutputs` and `.traceWithInputs`
  * must resolve unambiguously to the `Aspect` path, via the `WeaveInterpreter`
  * that now backs both. A compilation check (no ambiguous-implicit error) plus a
  * behavioral check (the `Aspect` path actually ran) cover `traceWithInputsAndOutputs`;
  * a third test pins the same behavioral property for `traceWithInputs`.
  */
class AspectPrioritySpec extends CatsEffectSuite {
  private implicit val trace: Trace[IO] = Trace.Implicits.noop[IO]

  test("resolving traceWithInputsAndOutputs with both instances in scope does not report an ambiguous implicit") {
    // Shared across 2.12/2.13/3: the type annotation on `errors` is required on
    // Scala 3, where an untyped val here trips a "Recursive value errors needs
    // type" cyclic-reference check and the captured string is that error instead
    // of the snippet's own diagnostics (a lesson from M4's DerivationErrorSpec).
    val errors: String = compileErrors(
      """import cats.effect.IO
import natchez.Trace
implicit val trace: Trace[IO] = Trace.Implicits.noop[IO]
com.dwolla.tracing.mtl.syntax.Foo.io.traceWithInputsAndOutputs"""
    )
    assertNoDiff(errors, "")
  }

  test("the Aspect path is used, not the poison RaiseAspect instance, and it produces the expected result") {
    val traced = Foo.io.traceWithInputsAndOutputs
    traced.foo(3).assertEquals("foo:3")
  }

  test("the Aspect path is used by traceWithInputs too, not the poison RaiseAspect instance") {
    val traced = Foo.io.traceWithInputs[TraceableValue]
    traced.foo(3).assertEquals("foo:3")
  }
}
