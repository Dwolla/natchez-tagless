package com.dwolla.tagless.mtl
package laws

import cats.laws._

/** Laws L1 and L2: `RaiseFunctorK` is a functor over the category whose objects
  * are effects and whose morphisms are [[RaiseArrow]]s.
  */
trait RaiseFunctorKLaws[Alg[_[_]]] {
  implicit def F: RaiseFunctorK[Alg]

  /** L1 — mapping by the identity arrow changes nothing. */
  def mapKIdentity[A[_]](af: Alg[A]): IsEq[Alg[A]] =
    F.mapK(af)(RaiseArrow.id[A]) <-> af

  /** L2 — mapping by two arrows in sequence is mapping by their composite. */
  def mapKComposition[A[_], B[_], C[_]](
      af: Alg[A],
      f: RaiseArrow[A, B],
      g: RaiseArrow[B, C]
  ): IsEq[Alg[C]] =
    F.mapK(F.mapK(af)(f))(g) <-> F.mapK(af)(f.andThen(g))
}

object RaiseFunctorKLaws {
  def apply[Alg[_[_]]](implicit ev: RaiseFunctorK[Alg]): RaiseFunctorKLaws[Alg] =
    new RaiseFunctorKLaws[Alg] { val F = ev }
}
