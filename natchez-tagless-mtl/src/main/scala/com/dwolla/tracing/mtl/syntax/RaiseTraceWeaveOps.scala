package com.dwolla.tracing.mtl
package syntax

import cats.{Apply, FlatMap}
import com.dwolla.tagless.mtl.WeaveInterpreter
import com.dwolla.tracing.{TraceWeaveCapturingInputs, TraceWeaveCapturingInputsAndOutputs}
import natchez.{Trace, TraceableValue}

/** Mirrors `com.dwolla.tracing.syntax.ToTraceWeaveOps`/`TraceWeaveOps`, but
  * resolves either an `Aspect` or a `RaiseAspect` instance for the algebra via
  * `WeaveInterpreter` — see that type class for why a single sealed type class
  * is what makes one strategy take priority over the other.
  *
  * The signatures are deliberately identical to the non-mtl `TraceWeaveOps`'s.
  * The two syntax packages cannot be imported into the same scope (that
  * reintroduces exactly the ambiguity this module exists to avoid), so
  * switching an import between them must not break call sites.
  *
  * Import `com.dwolla.tracing.mtl.syntax._` in place of
  * `com.dwolla.tracing.syntax._` to get both capabilities under the same call
  * syntax.
  */
trait ToRaiseTraceWeaveOps {
  implicit def toRaiseTraceWeaveOps[Alg[_[_]], F[_]](alg: Alg[F]): RaiseTraceWeaveOps[Alg, F] =
    new RaiseTraceWeaveOps(alg)
}

class RaiseTraceWeaveOps[Alg[_[_]], F[_]](val alg: Alg[F]) extends AnyVal {

  /** `Err` is pinned to `TraceableValue` independently of `Cod`, so opting out
    * of return-value rendering does not silently disable typed error
    * recording.
    */
  def traceWithInputs[Cod[_]](implicit
      F: Apply[F],
      T: Trace[F],
      R: RaiseRecorder[F, TraceableValue],
      ev: WeaveInterpreter[Alg, TraceableValue, Cod, TraceableValue, F]
  ): Alg[F] =
    ev(alg)(TraceWeaveCapturingInputs[F, Cod], R.onRaise)

  def traceWithInputsAndOutputs(implicit
      F: FlatMap[F],
      T: Trace[F],
      R: RaiseRecorder[F, TraceableValue],
      ev: WeaveInterpreter[Alg, TraceableValue, TraceableValue, TraceableValue, F]
  ): Alg[F] =
    ev(alg)(TraceWeaveCapturingInputsAndOutputs[F], R.onRaise)
}
