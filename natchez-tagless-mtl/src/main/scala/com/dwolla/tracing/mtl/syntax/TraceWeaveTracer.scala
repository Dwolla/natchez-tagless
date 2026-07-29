package com.dwolla.tracing.mtl
package syntax

import cats._
import cats.tagless.aop.Aspect
import com.dwolla.tagless.mtl.{RaiseArrow, RaiseAspect, Synthetic, WeaveArrows}
import com.dwolla.tracing.{TraceWeaveCapturingInputs, TraceWeaveCapturingInputsAndOutputs}
import natchez.{Trace, TraceableValue}

/** Resolves which of the two available strategies traces `Alg[F]`: the existing
  * `Aspect`-based one from `com.dwolla.tracing.syntax`, or the new `RaiseAspect`-based
  * one, when only the latter exists.
  *
  * `core` cannot depend on this module (this module depends on `core`), so the usual
  * `LowPriorityXxx`-trait mechanism this repo already uses for `TraceableValue`
  * resolution (see `com.dwolla.tracing.ToTraceValue`) cannot be built across the two
  * existing `ToTraceWeaveOps`/candidate conversions directly — there is no supertype
  * relationship available to make one win over the other. Instead, both strategies
  * are expressed as instances of this one sealed typeclass, entirely within this
  * module, and the usual low-priority-trait pattern disambiguates between the two
  * instances exactly as it already does for `TraceableValue`.
  */
sealed trait WithInputsAndOutputsTracer[Alg[_[_]], F[_]] {
  def apply(alg: Alg[F]): Alg[F]
}

object WithInputsAndOutputsTracer extends LowPriorityWithInputsAndOutputsTracer {

  /** Higher priority: the existing `Aspect`-based enrichment wins whenever both an
    * `Aspect` and a `RaiseAspect` instance are available for the same algebra.
    */
  implicit def fromAspect[Alg[_[_]], F[_]](implicit
      F: FlatMap[F],
      T: Trace[F],
      A: Aspect[Alg, TraceableValue, TraceableValue]
  ): WithInputsAndOutputsTracer[Alg, F] =
    new WithInputsAndOutputsTracer[Alg, F] {
      def apply(alg: Alg[F]): Alg[F] = A.mapK(A.weave(alg))(TraceWeaveCapturingInputsAndOutputs[F])
    }
}

trait LowPriorityWithInputsAndOutputsTracer {

  /** Lower priority: used only when no `Aspect` instance is available. Reuses the
    * existing `Weave ~> F` interpreter unchanged, wrapped in a `RaiseArrow` whose
    * `pull` is `WeaveArrows.raiseLift` — the shell-`Weave` side of the canonical pair
    * from `raise-aspect-core`. `Dom`/`Cod` are fixed to `TraceableValue` here (unlike
    * [[WithInputsTracer]]), so `raiseLift` resolves the package's own
    * `syntheticTraceableValue` directly; no extra constraint parameter is needed.
    */
  implicit def fromRaiseAspect[Alg[_[_]], F[_]](implicit
      F: FlatMap[F],
      T: Trace[F],
      A: RaiseAspect[Alg, TraceableValue, TraceableValue],
      R: RaiseRecorder[F]
  ): WithInputsAndOutputsTracer[Alg, F] =
    new WithInputsAndOutputsTracer[Alg, F] {
      def apply(alg: Alg[F]): Alg[F] =
        A.mapK(A.weave(alg))(
          RaiseArrow(TraceWeaveCapturingInputsAndOutputs[F], WeaveArrows.raiseLift(R.onRaise))
        )
    }
}

/** The inputs-only analogue of [[WithInputsAndOutputsTracer]], mirroring the existing
  * `traceWithInputs[Cod[_]]` variant: the codomain type class is a free parameter.
  */
sealed trait WithInputsTracer[Alg[_[_]], Cod[_], F[_]] {
  def apply(alg: Alg[F]): Alg[F]
}

object WithInputsTracer extends LowPriorityWithInputsTracer {

  implicit def fromAspect[Alg[_[_]], Cod[_], F[_]](implicit
      F: Apply[F],
      T: Trace[F],
      A: Aspect[Alg, TraceableValue, Cod]
  ): WithInputsTracer[Alg, Cod, F] =
    new WithInputsTracer[Alg, Cod, F] {
      def apply(alg: Alg[F]): Alg[F] = A.mapK(A.weave(alg))(TraceWeaveCapturingInputs[F, Cod])
    }
}

trait LowPriorityWithInputsTracer {

  /** `Synthetic[Cod]` is required here (unlike the `Aspect`-based branch, and unlike
    * [[WithInputsAndOutputsTracer]]'s fixed-`Cod` variant) because `Cod` is a free
    * type parameter and `WeaveArrows.raiseLift` needs a `Cod` instance to build the
    * shell `Weave`'s codomain advice — a structural consequence of `RaiseAspect`'s
    * design, not an arbitrary new restriction.
    */
  implicit def fromRaiseAspect[Alg[_[_]], Cod[_], F[_]](implicit
      F: Apply[F],
      T: Trace[F],
      A: RaiseAspect[Alg, TraceableValue, Cod],
      syn: Synthetic[Cod],
      R: RaiseRecorder[F]
  ): WithInputsTracer[Alg, Cod, F] =
    new WithInputsTracer[Alg, Cod, F] {
      def apply(alg: Alg[F]): Alg[F] =
        A.mapK(A.weave(alg))(RaiseArrow(TraceWeaveCapturingInputs[F, Cod], WeaveArrows.raiseLift(R.onRaise)))
    }
}
