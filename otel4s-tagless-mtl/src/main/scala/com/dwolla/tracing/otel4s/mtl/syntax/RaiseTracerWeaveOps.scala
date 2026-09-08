package com.dwolla.tracing.otel4s.mtl
package syntax

import cats.FlatMap
import com.dwolla.tagless.mtl.{RaiseRecorder, WeaveInterpreter}
import com.dwolla.tracing.otel4s.{ToAnyValue, TracerWeaveCapturingInputs, TracerWeaveCapturingInputsAndOutputs}
import org.typelevel.otel4s.trace.Tracer

/** Mirrors `com.dwolla.tracing.otel4s.syntax.ToTracerWeaveOps`/`TracerWeaveOps`,
  * but resolves either a plain `Aspect` or a `RaiseAspect` instance for the
  * algebra via `WeaveInterpreter` — see that type class for why a single
  * sealed type class is what makes one strategy take priority over the other.
  *
  * `traceWithInputsAndOutputs` matches the non-mtl `TracerWeaveOps`'s
  * signature exactly: both declare `FlatMap[F]`, so switching an import
  * changes nothing about what that call site must provide.
  * `traceWithInputs` does '''not''' match — taking the default recorder
  * costs a `FlatMap[F]` the non-mtl version does not ask for. See that
  * method's own scaladoc. Either way the two packages cannot be imported
  * into the same scope — that reintroduces exactly the ambiguity this module
  * exists to avoid.
  *
  * Import `com.dwolla.tracing.otel4s.mtl.syntax._` in place of
  * `com.dwolla.tracing.otel4s.syntax._` to get both capabilities under the
  * same call syntax.
  */
trait ToRaiseTracerWeaveOps {
  implicit def toRaiseTracerWeaveOps[Alg[_[_]], F[_]](alg: Alg[F]): RaiseTracerWeaveOps[Alg, F] =
    new RaiseTracerWeaveOps(alg)
}

class RaiseTracerWeaveOps[Alg[_[_]], F[_]](val alg: Alg[F]) extends AnyVal {

  /** `Err` is pinned to `ToAnyValue` independently of `Cod`, so opting out of
    * return-value rendering does not silently disable typed error recording.
    *
    * '''This is not a signature match for
    * `com.dwolla.tracing.otel4s.syntax.TracerWeaveOps#traceWithInputs`''',
    * which declares no effect constraint at all. No effect constraint is
    * declared here either, but `R: RaiseRecorder[F, ToAnyValue]` is resolved
    * in the ''caller's'' scope, and with no user-supplied `OnRaise[F,
    * ToAnyValue]` there it resolves through `RaiseRecorder.fromDefault` to
    * `Otel4sDefaultOnRaise.otel4sDefaultOnRaise`, which is declared
    * `[F[_] : FlatMap : Tracer]`. So a caller taking the default recorder
    * must supply `FlatMap[F]` where `otel4s.syntax` asked for nothing.
    *
    * The requirement is not conditional on that resolution, unlike before
    * `RaiseAspect#intercept` was tightened to `FlatMap[F]`: even a caller
    * with its own `OnRaise[F, ToAnyValue]` in scope needs `FlatMap[F]`,
    * because `WeaveInterpreter.fromRaiseAspect`'s own constraint demands it
    * regardless of what the hook itself needs.
    * `traceWithInputsAndOutputs` is unaffected either way: it already
    * declares `FlatMap[F]` in both packages, so the default recorder adds
    * nothing there.
    *
    * There is no `FlatMap[F]` here, and none is needed:
    * `WeaveInterpreter#apply` itself declares no effect constraint (see
    * `WeaveInterpreter.scala`), and `fromRaiseAspect`'s own `FlatMap[F]` — the
    * one `RaiseAspect#intercept` requires — is resolved from the caller's
    * scope when `ev` is summoned there, not from this method's parameter
    * list; a method's own implicit parameters are not candidates for
    * resolving its other implicit parameters. (A `RaiseAspect` algebra
    * therefore does need `FlatMap[F]` at the call site, but the non-mtl syntax
    * cannot trace such an algebra at all, so there is nothing to compare.)
    */
  def traceWithInputs[Cod[_]](implicit
      T: Tracer[F],
      R: RaiseRecorder[F, ToAnyValue],
      ev: WeaveInterpreter[Alg, ToAnyValue, Cod, ToAnyValue, F]
  ): Alg[F] =
    ev(alg)(TracerWeaveCapturingInputs[F, Cod], R.onRaise)

  def traceWithInputsAndOutputs(implicit
      F: FlatMap[F],
      T: Tracer[F],
      R: RaiseRecorder[F, ToAnyValue],
      ev: WeaveInterpreter[Alg, ToAnyValue, ToAnyValue, ToAnyValue, F]
  ): Alg[F] =
    ev(alg)(TracerWeaveCapturingInputsAndOutputs[F], R.onRaise)
}
