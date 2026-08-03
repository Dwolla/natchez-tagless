package com.dwolla.tracing.otel4s.mtl

import munit.FunSuite

/** Pins what `RaiseTracerWeaveOps`'s methods actually demand of a caller.
  *
  * Every other test in this module runs at `F = IO`, where `FlatMap[F]` is
  * always in scope, so none of them can tell "requires `FlatMap`" apart from
  * "requires nothing". That is why the milestone twice shipped a wrong claim
  * about this: first that `traceWithInputs` needed an `Apply[F]` (it does
  * not), then that its signature therefore costs a caller exactly what the
  * non-mtl `com.dwolla.tracing.otel4s.syntax.TracerWeaveOps#traceWithInputs`
  * costs (it does not — see D9 in
  * `docs/plans/raise-aspect/32-milestone-M17-otel4s-tagless-mtl.md`). The
  * claim belongs in a test rather than in prose.
  *
  * Each probe is a `def` at an '''abstract''' `F[_]` and `Alg[_[_]]`, both
  * bound inside the probed snippet. That is what makes these negative
  * assertions structurally immune to the `compileErrors` leak that bit this
  * milestone earlier: `compileErrors` typechecks its argument in the
  * enclosing lexical context, but nothing in any enclosing scope can supply a
  * `FlatMap` for a type parameter introduced by the snippet itself. This file
  * still declares no file-level syntax import and no file-level implicits, to
  * keep that property obvious rather than merely true.
  *
  * The assertions are non-emptiness and emptiness, never compiler wording,
  * which differs between 2.13 and 3.
  */
class RaiseTracerConstraintSpec extends FunSuite {

  test("traceWithInputs on the default recorder does NOT compile without a FlatMap[F]") {
    // The whole point of the finding: the non-mtl traceWithInputs declares no
    // effect constraint at all, so this exact capability set compiles under
    // `com.dwolla.tracing.otel4s.syntax`. Here it must not.
    val errors: String = compileErrors(
      """import cats.tagless.aop.Aspect
import com.dwolla.tracing.otel4s.ToAnyValue
import com.dwolla.tracing.otel4s.mtl.syntax._
import org.typelevel.otel4s.trace.Tracer
def probe[Alg[_[_]], F[_]](alg: Alg[F])(implicit
                                        T: Tracer[F],
                                        A: Aspect[Alg, ToAnyValue, ToAnyValue]): Alg[F] =
  alg.traceWithInputs[ToAnyValue]
()"""
    )

    assert(errors.nonEmpty, "traceWithInputs compiled without a FlatMap[F]; the default RaiseRecorder no longer demands one")
  }

  test("adding FlatMap[F] — and nothing else — is what makes it compile") {
    // Differs from the previous probe by exactly one implicit parameter, so
    // the two together identify FlatMap as the missing constraint rather than
    // merely establishing that something was missing.
    val errors: String = compileErrors(
      """import cats.FlatMap
import cats.tagless.aop.Aspect
import com.dwolla.tracing.otel4s.ToAnyValue
import com.dwolla.tracing.otel4s.mtl.syntax._
import org.typelevel.otel4s.trace.Tracer
def probe[Alg[_[_]], F[_]](alg: Alg[F])(implicit
                                        F: FlatMap[F],
                                        T: Tracer[F],
                                        A: Aspect[Alg, ToAnyValue, ToAnyValue]): Alg[F] =
  alg.traceWithInputs[ToAnyValue]
()"""
    )

    assertNoDiff(errors, "")
  }

  test("the FlatMap[F] is the default recorder's, not the signature's: a user OnRaise removes it") {
    // Conditionality. This hook declares no effect constraint whatsoever, and
    // RaiseRecorder.fromOnRaise outranks fromDefault, so Otel4sDefaultOnRaise
    // is never summoned and its FlatMap is never demanded.
    val errors: String = compileErrors(
      """import cats.tagless.aop.Aspect
import com.dwolla.tagless.mtl.OnRaise
import com.dwolla.tracing.otel4s.ToAnyValue
import com.dwolla.tracing.otel4s.mtl.syntax._
import org.typelevel.otel4s.trace.Tracer
def probe[Alg[_[_]], F[_]](alg: Alg[F])(implicit
                                        T: Tracer[F],
                                        OR: OnRaise[F, ToAnyValue],
                                        A: Aspect[Alg, ToAnyValue, ToAnyValue]): Alg[F] =
  alg.traceWithInputs[ToAnyValue]
()"""
    )

    assertNoDiff(errors, "")
  }

  test("traceWithInputsAndOutputs really is a signature match: FlatMap[F] + Tracer[F] + Aspect suffice") {
    // The non-mtl traceWithInputsAndOutputs declares FlatMap[F] itself, so the
    // default recorder asks for nothing the caller was not already supplying.
    // This is the half of the "switching an import is free" claim that is true.
    val errors: String = compileErrors(
      """import cats.FlatMap
import cats.tagless.aop.Aspect
import com.dwolla.tracing.otel4s.ToAnyValue
import com.dwolla.tracing.otel4s.mtl.syntax._
import org.typelevel.otel4s.trace.Tracer
def probe[Alg[_[_]], F[_]](alg: Alg[F])(implicit
                                        F: FlatMap[F],
                                        T: Tracer[F],
                                        A: Aspect[Alg, ToAnyValue, ToAnyValue]): Alg[F] =
  alg.traceWithInputsAndOutputs
()"""
    )

    assertNoDiff(errors, "")
  }
}
