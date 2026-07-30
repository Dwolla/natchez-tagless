package com.dwolla.tracing.mtl
package syntax

import com.dwolla.tagless.mtl.OnRaise
import natchez.Trace

/** Resolves the [[OnRaise]] hook that the `RaiseAspect`-based tracing strategies in
  * [[TraceWeaveTracer]] sequence at a `raiseLift` interception point: a user-supplied
  * `OnRaise[F]` if one is in scope, or else a default that records the typed error as
  * span fields via the ambient `Trace[F]`.
  *
  * Same sealed-typeclass low-priority-implicit mechanism as
  * [[WithInputsAndOutputsTracer]]/[[WithInputsTracer]] — see that file's docs for why
  * this pattern, rather than a single implicit method with a default argument, is
  * used to let one instance take priority over another.
  */
sealed trait RaiseRecorder[F[_]] {
  def onRaise: OnRaise[F]
}

object RaiseRecorder extends LowPriorityRaiseRecorder {
  val ErrorTypeKey: String = "raise.error.type"
  val ErrorMessageKey: String = "raise.error.message"

  /** Higher priority: a user-supplied `OnRaise[F]` wins. */
  implicit def fromOnRaise[F[_]](implicit or: OnRaise[F]): RaiseRecorder[F] =
    new RaiseRecorder[F] {
      def onRaise: OnRaise[F] = or
    }
}

trait LowPriorityRaiseRecorder {

  /** Lower priority: used only when no `OnRaise[F]` is available. Records the typed
    * error's runtime class name and rendered message as span fields via `Trace[F]`,
    * under a `raise.*` key prefix that is deliberately distinct from the
    * `exception.*`/error fields a backend derives from `attachError` — those still
    * receive cats-mtl's opaque `Submarine` wrapper on an unhandled raise, unchanged by
    * this milestone.
    */
  implicit def fromTrace[F[_]](implicit T: Trace[F]): RaiseRecorder[F] =
    new RaiseRecorder[F] {
      def onRaise: OnRaise[F] = new OnRaise[F] {
        def apply[E](e: E): F[Unit] =
          T.put(
            RaiseRecorder.ErrorTypeKey -> e.getClass.getName,
            RaiseRecorder.ErrorMessageKey -> e.toString
          )
      }
    }
}
