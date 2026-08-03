package com.dwolla.tracing.otel4s.mtl
package syntax

import cats.{Apply, FlatMap}
import com.dwolla.tagless.mtl.{RaiseRecorder, WeaveInterpreter}
import com.dwolla.tracing.otel4s.{ToAnyValue, TracerWeaveCapturingInputs, TracerWeaveCapturingInputsAndOutputs}
import org.typelevel.otel4s.trace.Tracer

import scala.annotation.nowarn

/** Mirrors `com.dwolla.tracing.otel4s.syntax.ToTracerWeaveOps`/`TracerWeaveOps`,
  * but resolves either a plain `Aspect` or a `RaiseAspect` instance for the
  * algebra via `WeaveInterpreter` — see that type class for why a single
  * sealed type class is what makes one strategy take priority over the other.
  *
  * The signatures are deliberately identical to the non-mtl `TracerWeaveOps`'s,
  * with one exception `traceWithInputs`'s scaladoc calls out (D9). The two
  * syntax packages cannot be imported into the same scope (that reintroduces
  * exactly the ambiguity this module exists to avoid), so switching an import
  * between them should not otherwise change a call site.
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
    * '''Requires `Apply[F]`, which
    * `com.dwolla.tracing.otel4s.syntax.TracerWeaveOps#traceWithInputs` does
    * not (D9).''' That method needs only `Tracer[F]`, because its attributes
    * go onto the `SpanBuilder` before the span exists, so there is nothing to
    * sequence. Here, resolving via `WeaveInterpreter.fromRaiseAspect` may hand
    * the call to `RaiseAspect#intercept`, which needs `Apply[F]` to sequence
    * the `onRaise` hook's effect before the raise — and this method cannot
    * know in advance which of the two `WeaveInterpreter` instances will
    * resolve, so it must demand `Apply[F]` up front regardless. Moving a call
    * site from `otel4s.syntax` to `otel4s.mtl.syntax` therefore adds this
    * constraint; every realistic `F` has `Apply`, so this is documented rather
    * than designed around.
    *
    * `@nowarn`, bare: `F` is never referenced by name in the body — its only
    * job is to be *in scope* when `ev` (which may resolve to
    * `WeaveInterpreter.fromRaiseAspect`, itself demanding `Apply[F]`) is
    * resolved, which the "unused parameter" check can't see. The natchez
    * sibling has no equivalent warning to suppress, because its `Apply[F]` is
    * also used explicitly, to sequence `Trace[F].put(...) *> target`.
    */
  def traceWithInputs[Cod[_]](implicit
      @nowarn F: Apply[F],
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
