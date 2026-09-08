package com.dwolla.tracing.mtl
package syntax

import cats.FlatMap
import com.dwolla.tagless.mtl.{RaiseRecorder, WeaveInterpreter}
import com.dwolla.tracing.{TraceWeaveCapturingInputs, TraceWeaveCapturingInputsAndOutputs}
import natchez.{Trace, TraceableValue}

/** Mirrors `com.dwolla.tracing.syntax.ToTraceWeaveOps`/`TraceWeaveOps`, but
  * resolves either an `Aspect` or a `RaiseAspect` instance for the algebra via
  * `WeaveInterpreter` — see that type class for why a single sealed type class
  * is what makes one strategy take priority over the other.
  *
  * `traceWithInputsAndOutputs`'s signature is deliberately identical to the
  * non-mtl `TraceWeaveOps`'s, both declaring `FlatMap[F]`. The two syntax
  * packages cannot be imported into the same scope (that reintroduces exactly
  * the ambiguity this module exists to avoid), so switching an import between
  * them must not break that call site.
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
    *
    * '''Not a signature match for the non-mtl `TraceWeaveOps#traceWithInputs`''',
    * which declares only `Apply[F]`. This one needs `FlatMap[F]`:
    * `WeaveInterpreter.fromRaiseAspect` forwards to `RaiseAspect#intercept`,
    * which itself requires `FlatMap[F]` to sequence the `onRaise` hook ahead
    * of the underlying raise without an accumulating `Applicative` folding the
    * hook's own effect into the raised value (see `RaiseAspect.observing`).
    * Switching the import for `traceWithInputs` is therefore not free the way
    * it is for `traceWithInputsAndOutputs`, which already asked for
    * `FlatMap[F]` on both sides.
    */
  def traceWithInputs[Cod[_]](implicit
      F: FlatMap[F],
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
