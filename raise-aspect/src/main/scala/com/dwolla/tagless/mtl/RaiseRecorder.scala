package com.dwolla.tagless.mtl

/** Resolves the [[OnRaise]] hook that a `RaiseAspect`-based tracing strategy
  * sequences via `RaiseAspect.observing`: a user-supplied `OnRaise[F, Err]` if
  * one is in scope, or else the backend's [[DefaultOnRaise]].
  *
  * Same sealed-typeclass low-priority-implicit mechanism as
  * [[WeaveInterpreter]] — see that type class's docs for why a single sealed
  * type class, rather than two separate conversions, is what lets one
  * instance take priority over the other: there's no supertype relationship
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
}

trait LowPriorityRaiseRecorder {

  /** Lower priority: used only when no `OnRaise[F, Err]` is available. Falls
    * back to the backend-supplied [[DefaultOnRaise]].
    *
    * Both instances take `[F[_], Err[_]]`, and that alignment is load-bearing
    * on Scala 2.13 specifically. If this one fixes `Err` directly — e.g.
    * returning `RaiseRecorder[F, TraceableValue]` — 2.13 reports the two as
    * ambiguous instead of ordering them by the object-extends-trait rule,
    * while 2.12 and 3 resolve it correctly. The failure appears only when a
    * user hook is also in scope, so the default path keeps working and the
    * break shows up as "my override stopped compiling".
    */
  implicit def fromDefault[F[_], Err[_]](implicit d: DefaultOnRaise[F, Err]): RaiseRecorder[F, Err] =
    new RaiseRecorder[F, Err] {
      def onRaise: OnRaise[F, Err] = d.onRaise
    }
}
