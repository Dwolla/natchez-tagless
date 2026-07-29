package com.dwolla.tagless.mtl
package laws

import cats.Functor
import cats.laws._

/** Law L3, the load-bearing one: weaving an algebra and then erasing the weave
  * recovers the original algebra, including on inputs that raise.
  */
trait RaiseAspectLaws[Alg[_[_]], Dom[_], Cod[_]] extends RaiseFunctorKLaws[Alg] {
  implicit def F: RaiseAspect[Alg, Dom, Cod]
  implicit def synthetic: Synthetic[Cod]

  /** L3 — `mapK(weave(af))(eraseWeave) <-> af`.
    *
    * This is the analogue of upstream's Aspect-consistency law. At
    * `A = Either[TestError, *]` a raise must come back as the identical `Left`
    * through the woven path, which is what makes the synthesized `Cod` instance
    * inside the raise shell safe.
    */
  def weaveErasure[A[_]](af: Alg[A])(implicit A: Functor[A]): IsEq[Alg[A]] =
    F.mapK(F.weave(af))(WeaveArrows.eraseWeave[A, Dom, Cod]) <-> af
}

object RaiseAspectLaws {
  def apply[Alg[_[_]], Dom[_], Cod[_]](implicit
      ev: RaiseAspect[Alg, Dom, Cod],
      syn: Synthetic[Cod]
  ): RaiseAspectLaws[Alg, Dom, Cod] =
    new RaiseAspectLaws[Alg, Dom, Cod] {
      val F = ev
      val synthetic = syn
    }
}
