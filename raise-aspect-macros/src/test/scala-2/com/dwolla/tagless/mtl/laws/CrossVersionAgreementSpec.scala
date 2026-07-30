package com.dwolla.tagless.mtl
package laws

import cats.Functor
import munit.FunSuite

import LawsInstances._

/** Task 5, Scala 2 half — the derived instance reproduces the shared expected
  * renderings. The Scala 3 spec of the same name asserts the same list, so the two
  * derivations are directly comparable in CI.
  */
class CrossVersionAgreementSpec extends FunSuite {
  test("the Scala 2 derivation matches the shared expected weave renderings") {
    val derived = DeriveRaise.aspect[TestAlg, Render, Render, Render]
    val woven = derived.weave(new EitherTestAlg(0))(Functor[Result])
    assertEquals(ExpectedWeaves.rendered(woven), ExpectedWeaves.expected)
  }
}
