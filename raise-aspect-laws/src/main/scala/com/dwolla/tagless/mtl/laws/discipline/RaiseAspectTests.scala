package com.dwolla.tagless.mtl
package laws
package discipline

import cats.{Applicative, Eq}
import cats.laws.discipline._
import org.scalacheck.Arbitrary
import org.scalacheck.Prop._
import org.typelevel.discipline.Laws

/** Discipline `RuleSet` for law L3′, intercept erasure.
  */
trait RaiseAspectTests[Alg[_[_]], Dom[_], Cod[_], Err[_]] extends Laws {
  def laws: RaiseAspectLaws[Alg, Dom, Cod, Err]

  def raiseAspect[A[_]](implicit
      ArbAlgA: Arbitrary[Alg[A]],
      EqAlgA: Eq[Alg[A]],
      ApplicativeA: Applicative[A]
  ): RuleSet =
    new DefaultRuleSet(
      name = "raiseAspect",
      parent = None,
      "intercept erasure" -> forAll((af: Alg[A]) => laws.interceptErasure[A](af)(ApplicativeA))
    )
}

object RaiseAspectTests {
  def apply[Alg[_[_]], Dom[_], Cod[_], Err[_]](implicit
      ev: RaiseAspect[Alg, Dom, Cod, Err]
  ): RaiseAspectTests[Alg, Dom, Cod, Err] =
    new RaiseAspectTests[Alg, Dom, Cod, Err] { val laws = RaiseAspectLaws[Alg, Dom, Cod, Err] }
}
