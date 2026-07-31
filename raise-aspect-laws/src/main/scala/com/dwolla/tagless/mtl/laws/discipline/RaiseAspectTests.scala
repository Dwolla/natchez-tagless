package com.dwolla.tagless.mtl
package laws
package discipline

import cats.{Applicative, Eq}
import cats.laws.discipline._
import org.scalacheck.Arbitrary
import org.scalacheck.Prop._

/** Discipline `RuleSet` for law L3′, extending the `RaiseFunctorK` rule set with
  * the intercept-erasure law.
  */
trait RaiseAspectTests[Alg[_[_]], Dom[_], Cod[_], Err[_]] extends RaiseFunctorKTests[Alg, Err] {
  def laws: RaiseAspectLaws[Alg, Dom, Cod, Err]

  def raiseAspect[A[_], B[_], C[_]](implicit
      ArbAlgA: Arbitrary[Alg[A]],
      ArbArrowAB: Arbitrary[RaiseArrow[A, B, Err]],
      ArbArrowBC: Arbitrary[RaiseArrow[B, C, Err]],
      EqAlgA: Eq[Alg[A]],
      EqAlgC: Eq[Alg[C]],
      ApplicativeA: Applicative[A]
  ): RuleSet =
    new DefaultRuleSet(
      name = "raiseAspect",
      parent = Some(raiseFunctorK[A, B, C]),
      "intercept erasure" -> forAll((af: Alg[A]) => laws.interceptErasure[A](af)(ApplicativeA))
    )
}

object RaiseAspectTests {
  def apply[Alg[_[_]], Dom[_], Cod[_], Err[_]](implicit
      ev: RaiseAspect[Alg, Dom, Cod, Err]
  ): RaiseAspectTests[Alg, Dom, Cod, Err] =
    new RaiseAspectTests[Alg, Dom, Cod, Err] { val laws = RaiseAspectLaws[Alg, Dom, Cod, Err] }
}
