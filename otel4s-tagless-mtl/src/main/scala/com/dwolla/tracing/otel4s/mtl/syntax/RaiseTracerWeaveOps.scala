package com.dwolla.tracing.otel4s.mtl
package syntax

import cats.{FlatMap, Functor}
import cats.syntax.all._
import com.dwolla.tagless.mtl.{RaiseRecorder, WeaveInterpreter}
import com.dwolla.tracing.otel4s.{ToAnyValue, TracerWeaveCapturingInputs, TracerWeaveCapturingInputsAndOutputs}
import org.typelevel.otel4s.trace.TracerProvider

/** Mirrors `com.dwolla.tracing.otel4s.syntax.ToTracerWeaveOps`/`TracerWeaveOps`,
  * but resolves either a plain `Aspect` or a `RaiseAspect` instance for the
  * algebra via `WeaveInterpreter` — see that type class for why a single
  * sealed type class is what makes one strategy take priority over the other.
  *
  * Both methods take the ambient `TracerProvider[F]` and return `F[Alg[F]]`,
  * exactly like the non-mtl methods: running the `F` obtains this library's
  * tracer (instrumentation scope `com.dwolla.tracing.otel4s`, versioned) and
  * yields the traced algebra.
  *
  * `traceWithInputsAndOutputs` matches the non-mtl `TracerWeaveOps`'s
  * signature exactly: both declare `FlatMap[F]`, so switching an import
  * changes nothing about what that call site must provide.
  * `traceWithInputs` does '''not''' match — taking the default recorder
  * costs a `FlatMap[F]` where the non-mtl version asks only for `Functor[F]`.
  * See that method's own scaladoc. Either way the two packages cannot be imported
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
    * which declares only `Functor[F]`, for mapping over the obtained tracer.
    * This method declares the same `Functor[F]`, but
    * `R: RaiseRecorder[F, ToAnyValue]` is resolved in the ''caller's'' scope,
    * and with no user-supplied `OnRaise[F, ToAnyValue]` there it resolves
    * through `RaiseRecorder.fromDefault` to
    * `Otel4sDefaultOnRaise.otel4sDefaultOnRaise`, which is declared
    * `[F[_] : FlatMap : TracerProvider]`. So a caller taking the default
    * recorder must supply `FlatMap[F]` where `otel4s.syntax` asked only for
    * `Functor[F]`.
    *
    * The requirement is conditional on that resolution, not on this
    * signature: a caller with its own `OnRaise[F, ToAnyValue]` in scope
    * needs only `Functor[F]` plus whatever that hook needs.
    * `RaiseTracerConstraintSpec` pins both directions.
    * `traceWithInputsAndOutputs` is unaffected: it declares `FlatMap[F]` in
    * both packages, so the default recorder adds nothing there.
    *
    * There is no `Apply[F]` here, and none is needed:
    * `WeaveInterpreter#apply` itself declares no effect constraint (see
    * `WeaveInterpreter.scala`), and `fromRaiseAspect`'s own `Apply[F]` — the
    * one `RaiseAspect#intercept` requires — is resolved from the caller's
    * scope when `ev` is summoned there, not from this method's parameter
    * list; a method's own implicit parameters are not candidates for
    * resolving its other implicit parameters. (A `RaiseAspect` algebra
    * therefore does need `Apply[F]` at the call site, but the non-mtl syntax
    * cannot trace such an algebra at all, so there is nothing to compare.)
    */
  def traceWithInputs[Cod[_]](implicit
      F: Functor[F],
      T: TracerProvider[F],
      R: RaiseRecorder[F, ToAnyValue],
      ev: WeaveInterpreter[Alg, ToAnyValue, Cod, ToAnyValue, F]
  ): F[Alg[F]] =
    TracerWeaveCapturingInputs[F, Cod].map(interpreter => ev(alg)(interpreter, R.onRaise))

  def traceWithInputsAndOutputs(implicit
      F: FlatMap[F],
      T: TracerProvider[F],
      R: RaiseRecorder[F, ToAnyValue],
      ev: WeaveInterpreter[Alg, ToAnyValue, ToAnyValue, ToAnyValue, F]
  ): F[Alg[F]] =
    TracerWeaveCapturingInputsAndOutputs[F].map(interpreter => ev(alg)(interpreter, R.onRaise))
}
