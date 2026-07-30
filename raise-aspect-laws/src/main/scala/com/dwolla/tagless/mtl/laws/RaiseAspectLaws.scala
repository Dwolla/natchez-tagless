package com.dwolla.tagless.mtl
package laws

import cats.Functor
import cats.laws._

/** Law L3, the load-bearing one: weaving an algebra and then erasing the weave
  * recovers the original algebra, including on inputs that raise.
  */
trait RaiseAspectLaws[Alg[_[_]], Dom[_], Cod[_], Err[_]] extends RaiseFunctorKLaws[Alg, Err] {
  implicit def F: RaiseAspect[Alg, Dom, Cod, Err]
  implicit def synthetic: Synthetic[Cod]

  /** L3 — `mapK(weave(af))(eraseWeave) <-> af`.
    *
    * This is the analogue of upstream's Aspect-consistency law. At
    * `A = Either[TestError, *]` a raise must come back as the identical `Left`
    * through the woven path, which is what makes the synthesized `Cod` instance
    * inside the raise shell safe.
    */
  def weaveErasure[A[_]](af: Alg[A])(implicit A: Functor[A]): IsEq[Alg[A]] =
    F.mapK(F.weave(af))(WeaveArrows.eraseWeave[A, Dom, Cod, Err]) <-> af
}

object RaiseAspectLaws {
  def apply[Alg[_[_]], Dom[_], Cod[_], Err[_]](implicit
      ev: RaiseAspect[Alg, Dom, Cod, Err],
      syn: Synthetic[Cod]
  ): RaiseAspectLaws[Alg, Dom, Cod, Err] =
    new RaiseAspectLaws[Alg, Dom, Cod, Err] {
      val F = ev
      val synthetic = syn
    }
}
