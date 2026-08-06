package com.dwolla.tagless.mtl
package laws

import munit.CatsEffectSuite

import scala.annotation.experimental

import LawsInstances._
import SyncIOTestSyntax._

/** Task 5, Scala 3 half — the derived instance reproduces the shared expected
  * renderings. The Scala 2 spec of the same name asserts the same list, so the two
  * derivations are directly comparable in CI.
  */
@experimental
class CrossVersionAgreementSpec extends CatsEffectSuite:
  test("the Scala 3 derivation matches the shared expected weave renderings") {
    val derived = DeriveRaise.aspect[TestAlg, Render, Render, Render]

    (for {
      recorder <- RecordingFk[Lazily, Render, Render]
      instrumented = derived.intercept(new GenericTestAlg[Lazily](0))(recorder.fk, OnRaise.noop[Lazily, Render])
      rendered <- ExpectedWeaves.rendered(instrumented, recorder)
    } yield assertEquals(rendered, ExpectedWeaves.expected)).runOrFail
  }
