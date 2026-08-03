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
  * The signatures are deliberately identical to the non-mtl
  * `TracerWeaveOps`'s, so switching an import between the two syntax packages
  * changes nothing at a call site (the two packages cannot be imported into
  * the same scope — that reintroduces exactly the ambiguity this module
  * exists to avoid).
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
    * The signature is deliberately identical to
    * `com.dwolla.tracing.otel4s.syntax.TracerWeaveOps#traceWithInputs` — no
    * `Apply[F]` here either. `WeaveInterpreter#apply` itself demands no
    * effect constraint (see `WeaveInterpreter.scala`), and whatever
    * `RaiseAspect#intercept`'s own `Apply[F]` needs is resolved from the
    * *caller's* scope when `ev` is summoned there, not from this method's
    * parameter list — a method's own implicit parameters are not candidates
    * for resolving its own other implicit parameters. Moving a call site
    * between `otel4s.syntax` and `otel4s.mtl.syntax` therefore changes
    * nothing about what the caller must provide.
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
