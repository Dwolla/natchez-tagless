package com.dwolla.tagless.mtl

/** A backend's fallback [[OnRaise]] hook, deliberately a distinct type from
  * `OnRaise` itself — with identical content — so that implicit priority can
  * tell "the backend's fallback" apart from "the user's hook". Two `OnRaise`
  * instances at the same priority would be ambiguous whenever both were in
  * scope, which is the whole reason [[RaiseRecorder]] exists: a backend
  * module supplies a `DefaultOnRaise[F, Err]` instance, and `RaiseRecorder`
  * resolves to a user-supplied `OnRaise[F, Err]` instead whenever one is also
  * in scope.
  */
trait DefaultOnRaise[F[_], Err[_]] extends Serializable {
  def onRaise: OnRaise[F, Err]
}
