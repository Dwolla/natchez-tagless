package com.dwolla.tagless.mtl
package laws
package discipline

import cats.Eq
import cats.laws.discipline._
import org.scalacheck.Arbitrary
import org.scalacheck.Prop._
import org.typelevel.discipline.Laws

/** Discipline `RuleSet` for laws L1 and L2. */
trait RaiseFunctorKTests[Alg[_[_]]] extends Laws {
  def laws: RaiseFunctorKLaws[Alg]

  def raiseFunctorK[A[_], B[_], C[_]](implicit
      ArbAlgA: Arbitrary[Alg[A]],
      ArbArrowAB: Arbitrary[RaiseArrow[A, B]],
      ArbArrowBC: Arbitrary[RaiseArrow[B, C]],
      EqAlgA: Eq[Alg[A]],
      EqAlgC: Eq[Alg[C]]
  ): RuleSet =
    new DefaultRuleSet(
      name = "raiseFunctorK",
      parent = None,
      "mapK identity" -> forAll(laws.mapKIdentity[A](_)),
      "mapK composition" -> forAll(laws.mapKComposition[A, B, C](_, _, _))
    )
}

object RaiseFunctorKTests {
  def apply[Alg[_[_]]](implicit ev: RaiseFunctorK[Alg]): RaiseFunctorKTests[Alg] =
    new RaiseFunctorKTests[Alg] { val laws = RaiseFunctorKLaws[Alg] }
}
