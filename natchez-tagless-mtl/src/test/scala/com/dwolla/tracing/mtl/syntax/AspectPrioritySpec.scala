package com.dwolla.tracing.mtl
package syntax

import cats.effect.IO
import munit.CatsEffectSuite
import natchez.{Trace, TraceableValue}

/** Task 3 (M6) / M11 — when an algebra has both an `Aspect` and a `RaiseAspect`
  * instance in scope, both `.traceWithInputsAndOutputs` and `.traceWithInputs`
  * must resolve unambiguously to the `Aspect` path, via the `WeaveInterpreter`
  * that now backs both. Each method gets the same pair of checks: a
  * compilation check (no ambiguous-implicit error) and a behavioral check
  * (the `Aspect` path actually ran).
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

  test("resolving traceWithInputs with both instances in scope does not report an ambiguous implicit") {
    // Same rationale as the traceWithInputsAndOutputs case above: the type
    // annotation on `errors` is required on Scala 3 to avoid a cyclic-reference
    // check masking the snippet's own diagnostics.
    val errors: String = compileErrors(
      """import cats.effect.IO
import natchez.{Trace, TraceableValue}
implicit val trace: Trace[IO] = Trace.Implicits.noop[IO]
com.dwolla.tracing.mtl.syntax.Foo.io.traceWithInputs[TraceableValue]"""
    )
    assertNoDiff(errors, "")
  }

  test("the Aspect path is used by traceWithInputs too, not the poison RaiseAspect instance") {
    val traced = Foo.io.traceWithInputs[TraceableValue]
    traced.foo(3).assertEquals("foo:3")
  }

  test("the syntax package no longer supplies a Synthetic instance") {
    // Belt-and-braces, not the guarantee itself: this only shows nothing named
    // `com.dwolla.tagless.mtl.Synthetic` resolves today. The real guarantee is
    // structural, per 22-milestone-M12-fused-derivation.md — the fused
    // `intercept` never puts a capability on the woven carrier, so no design
    // in this library ever needs a `Functor[Weave[...]]`, synthesized or
    // otherwise. A rename or a differently-named reintroduction of the same
    // idea would slip past this check.
    assert(
      compileErrors("com.dwolla.tagless.mtl.Synthetic").nonEmpty,
      "Synthetic must not exist: the fused derivation never places a capability on the woven carrier"
    )
  }
}
