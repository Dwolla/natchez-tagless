package com.dwolla.tagless.mtl

import cats.Apply
import cats.tagless.aop.Aspect
import cats.~>

/** Resolves which of the two available strategies interprets an `Alg[F]`
  * whose methods have been woven: cats-tagless's own `Aspect`, or this
  * library's `RaiseAspect`, when only the latter exists.
  *
  * Both strategies are expressed as instances of this one sealed type class so
  * that the usual low-priority-trait mechanism can order them. Expressing them
  * as two separate conversions would not work: there is no supertype
  * relationship between `Aspect` and `RaiseAspect` to make one win.
  *
  * Nothing here is specific to tracing, or to any particular backend. `Dom`,
  * `Cod`, `Err`, the `Weave ~> F` interpreter, and the `OnRaise` hook are all
  * supplied by the caller, so a natchez module and an otel4s module — which
  * share no rendering type class — use the same instance resolution.
  */
sealed trait WeaveInterpreter[Alg[_[_]], Dom[_], Cod[_], Err[_], F[_]] {
  def apply(alg: Alg[F])(
      fk: Aspect.Weave[F, Dom, Cod, *] ~> F,
      onRaise: OnRaise[F, Err]
  ): Alg[F]
}

object WeaveInterpreter extends LowPriorityWeaveInterpreter {
  def apply[Alg[_[_]], Dom[_], Cod[_], Err[_], F[_]](implicit
      ev: WeaveInterpreter[Alg, Dom, Cod, Err, F]
  ): WeaveInterpreter[Alg, Dom, Cod, Err, F] = ev

  /** Higher priority: the `Aspect`-based strategy wins whenever both an
    * `Aspect` and a `RaiseAspect` exist for the same algebra.
    *
    * `onRaise` is ignored, and that is correct rather than a gap: an algebra
    * with an `Aspect` instance has no `Raise` capability parameters, so the
    * hook's domain is empty and it can never fire. Note the ordering is
    * consistent with this — the only algebras for which this instance
    * outranks [[LowPriorityWeaveInterpreter.fromRaiseAspect]] are exactly the
    * ones with no raise to intercept.
    *
    * This isn't a convention a hand-written instance could violate — it's
    * structural. `FunctorK#mapK`'s `fk: F ~> G` runs one way only, so plain
    * `Aspect` can't implement a method that receives a `Raise[F, E]`
    * parameter at all, for any algebra. `RaiseAspect#intercept` sidesteps
    * that by never changing the carrier.
    *
    * No effect constraint: `Aspect.weave` takes no implicit and `Aspect.mapK`
    * takes only a `FunctionK`.
    */
  implicit def fromAspect[Alg[_[_]], Dom[_], Cod[_], Err[_], F[_]](implicit
      A: Aspect[Alg, Dom, Cod]
  ): WeaveInterpreter[Alg, Dom, Cod, Err, F] =
    new WeaveInterpreter[Alg, Dom, Cod, Err, F] {
      def apply(alg: Alg[F])(
          fk: Aspect.Weave[F, Dom, Cod, *] ~> F,
          onRaise: OnRaise[F, Err]
      ): Alg[F] = A.mapK(A.weave(alg))(fk)
    }
}

trait LowPriorityWeaveInterpreter {

  /** Lower priority: used only when no `Aspect` instance is available. The
    * caller's interpreter and hook go straight to `RaiseAspect#intercept` —
    * this type class's `apply` and `intercept` are the same signature.
    *
    * `Apply[F]` is `intercept`'s own constraint, needed to sequence the hook.
    */
  implicit def fromRaiseAspect[Alg[_[_]], Dom[_], Cod[_], Err[_], F[_]](implicit
      F: Apply[F],
      A: RaiseAspect[Alg, Dom, Cod, Err]
  ): WeaveInterpreter[Alg, Dom, Cod, Err, F] =
    new WeaveInterpreter[Alg, Dom, Cod, Err, F] {
      def apply(alg: Alg[F])(
          fk: Aspect.Weave[F, Dom, Cod, *] ~> F,
          onRaise: OnRaise[F, Err]
      ): Alg[F] = A.intercept(alg)(fk, onRaise)
    }
}
