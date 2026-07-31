package com.dwolla.tracing.mtl
package syntax

import com.dwolla.tagless.mtl.OnRaise
import natchez.{Trace, TraceableValue}

/** Resolves the [[com.dwolla.tagless.mtl.OnRaise]] hook that the `RaiseAspect`-based tracing strategy in
  * `RaiseTraceWeaveOps` sequences via `RaiseAspect.observing`: a user-supplied
  * `OnRaise[F, Err]` if one is in scope, or else a default that records the typed
  * error as span fields via the ambient `Trace[F]`.
  *
  * Same sealed-typeclass low-priority-implicit mechanism as
  * `com.dwolla.tagless.mtl.WeaveInterpreter` — see that type class's docs for why
  * a single sealed type class, rather than two separate conversions, is what lets
  * one instance take priority over the other: there's no supertype relationship
  * between the two candidates to make one win otherwise.
  */
sealed trait RaiseRecorder[F[_], Err[_]] {
  def onRaise: OnRaise[F, Err]
}

object RaiseRecorder extends LowPriorityRaiseRecorder {
  val ErrorTypeKey: String = "raise.error.type"
  val ErrorValueKey: String = "raise.error.value"

  /** Higher priority: a user-supplied `OnRaise[F, Err]` wins. */
  implicit def fromOnRaise[F[_], Err[_]](implicit or: OnRaise[F, Err]): RaiseRecorder[F, Err] =
    new RaiseRecorder[F, Err] {
      def onRaise: OnRaise[F, Err] = or
    }

  /** Witnesses that `Err` ''is'' `TraceableValue` — its only instance is the
    * identity at `Err = TraceableValue`. Exists purely so
    * [[LowPriorityRaiseRecorder#fromTrace]] can be given the same type-parameter
    * shape as [[fromOnRaise]] (both `[F[_], Err[_]]`, both returning
    * `RaiseRecorder[F, Err]`): on Scala 2.13 specifically (not 2.12, not 3), the two
    * low/high-priority instances are reported as ambiguous rather than resolved by
    * the usual object-extends-trait priority when one of them fixes `Err` directly
    * (`RaiseRecorder[F, TraceableValue]`) instead of leaving it as a type parameter
    * shared with the other candidate — this witness restores that shared shape
    * without weakening `fromTrace`'s actual behavior, which still only ever fires
    * for `Err = TraceableValue`.
    */
  sealed abstract class IsTraceableValue[Err[_]] {
    def widen[A](ev: Err[A]): TraceableValue[A]
  }

  object IsTraceableValue {
    implicit val reflexive: IsTraceableValue[TraceableValue] =
      new IsTraceableValue[TraceableValue] {
        def widen[A](ev: TraceableValue[A]): TraceableValue[A] = ev
      }
  }
}

trait LowPriorityRaiseRecorder {

  /** Lower priority: used only when no `OnRaise[F, TraceableValue]` is available.
    * Records the typed error's runtime class name and its `TraceableValue`
    * rendering as span fields, under a `raise.*` key prefix deliberately
    * distinct from the `exception.*` fields a backend derives from
    * `attachError` — those still receive cats-mtl's opaque `Submarine` wrapper
    * on an unhandled raise.
    *
    * Pinned at `Err = TraceableValue`: it is the only evidence type class this
    * default can render through — `RaiseRecorder.IsTraceableValue` has exactly
    * one instance, at `Err = TraceableValue`, so this method only ever actually
    * fires there despite the `Err` type parameter (see that witness's scaladoc
    * for why it's generic in `Err` at all rather than fixed, as the milestone's
    * design intends).
    */
  implicit def fromTrace[F[_], Err[_]](implicit
      T: Trace[F],
      isTraceableValue: RaiseRecorder.IsTraceableValue[Err]
  ): RaiseRecorder[F, Err] =
    new RaiseRecorder[F, Err] {
      def onRaise: OnRaise[F, Err] = new OnRaise[F, Err] {
        def apply[E](e: E)(implicit ev: Err[E]): F[Unit] =
          T.put(
            RaiseRecorder.ErrorTypeKey -> e.getClass.getName,
            RaiseRecorder.ErrorValueKey -> isTraceableValue.widen(ev).toTraceValue(e)
          )
      }
    }
}
