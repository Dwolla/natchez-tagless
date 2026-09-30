package com.dwolla.tagless.mtl
package laws

import cats.Applicative
import cats.laws._

/** Law L3′, the load-bearing one: intercepting an algebra with the forgetful
  * interpreter and no hook recovers the original algebra, including on inputs
  * that raise.
  */
trait RaiseAspectLaws[Alg[_[_]], Dom[_], Cod[_], Err[_]] {
  implicit def F: RaiseAspect[Alg, Dom, Cod, Err]

  /** L3′ — `intercept(af)(codomainTarget, OnRaise.noop) <-> af`.
    *
    * The analogue of upstream's Aspect-consistency law: at `A =
    * Either[TestError, *]`, a raise must come back as the identical `Left`
    * through the instrumented path.
    *
    * `Applicative[A]` rather than `Functor[A]`: `intercept` needs `Apply` to
    * sequence the hook and `OnRaise.noop` needs `Applicative` to produce one.
    */
  def interceptErasure[A[_]](af: Alg[A])(implicit A: Applicative[A]): IsEq[Alg[A]] =
    F.intercept(af)(WeaveArrows.codomainTarget[A, Dom, Cod], OnRaise.noop[A, Err]) <-> af
}

object RaiseAspectLaws {
  def apply[Alg[_[_]], Dom[_], Cod[_], Err[_]](implicit
      ev: RaiseAspect[Alg, Dom, Cod, Err]
  ): RaiseAspectLaws[Alg, Dom, Cod, Err] =
    new RaiseAspectLaws[Alg, Dom, Cod, Err] { val F = ev }
}
