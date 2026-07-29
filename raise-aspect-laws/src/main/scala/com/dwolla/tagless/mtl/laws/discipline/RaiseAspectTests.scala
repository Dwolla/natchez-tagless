package com.dwolla.tagless.mtl
package laws
package discipline

import cats.{Eq, Functor}
import cats.laws.discipline._
import org.scalacheck.Arbitrary
import org.scalacheck.Prop._

/** Discipline `RuleSet` for law L3, extending the `RaiseFunctorK` rule set with
  * the weave-erasure law.
  */
trait RaiseAspectTests[Alg[_[_]], Dom[_], Cod[_]] extends RaiseFunctorKTests[Alg] {
  def laws: RaiseAspectLaws[Alg, Dom, Cod]

  def raiseAspect[A[_], B[_], C[_]](implicit
      ArbAlgA: Arbitrary[Alg[A]],
      ArbArrowAB: Arbitrary[RaiseArrow[A, B]],
      ArbArrowBC: Arbitrary[RaiseArrow[B, C]],
      EqAlgA: Eq[Alg[A]],
      EqAlgC: Eq[Alg[C]],
      FunctorA: Functor[A]
  ): RuleSet =
    new DefaultRuleSet(
      name = "raiseAspect",
      parent = Some(raiseFunctorK[A, B, C]),
      "weave erasure" -> forAll((af: Alg[A]) => laws.weaveErasure[A](af)(FunctorA))
    )
}

object RaiseAspectTests {
  def apply[Alg[_[_]], Dom[_], Cod[_]](implicit
      ev: RaiseAspect[Alg, Dom, Cod],
      syn: Synthetic[Cod]
  ): RaiseAspectTests[Alg, Dom, Cod] =
    new RaiseAspectTests[Alg, Dom, Cod] { val laws = RaiseAspectLaws[Alg, Dom, Cod] }
}
