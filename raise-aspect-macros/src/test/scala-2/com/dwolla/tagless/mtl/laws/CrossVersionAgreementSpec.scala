package com.dwolla.tagless.mtl
package laws

import munit.FunSuite

import LawsInstances._

/** Task 5, Scala 2 half — the derived instance reproduces the shared expected
  * renderings. The Scala 3 spec of the same name asserts the same list, so the two
  * derivations are directly comparable in CI.
  */
class CrossVersionAgreementSpec extends FunSuite {
  test("the Scala 2 derivation matches the shared expected weave renderings") {
    val derived = DeriveRaise.aspect[TestAlg, Render, Render, Render]
    val recorder = new RecordingFk[Result, Render, Render]
    val instrumented = derived.intercept(new EitherTestAlg(0))(recorder.fk, OnRaise.noop[Result, Render])

    assertEquals(ExpectedWeaves.rendered(instrumented, recorder), ExpectedWeaves.expected)
    assertEquals(recorder.events, ExpectedWeaves.expectedOrder)
  }
}
