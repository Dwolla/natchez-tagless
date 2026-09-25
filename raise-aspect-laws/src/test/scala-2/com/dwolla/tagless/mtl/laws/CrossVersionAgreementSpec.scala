package com.dwolla.tagless.mtl
package laws

import cats.effect.*
import com.dwolla.tagless.mtl.HandleTestSyntax
import munit.CatsEffectSuite

/** The Scala 2 half — the derived instance reproduces the shared expected
  * renderings. The Scala 3 spec of the same name asserts the same list, so the two
  * derivations are directly comparable in CI.
  */
class CrossVersionAgreementSpec extends CatsEffectSuite with HandleTestSyntax {

  testWithHandle[SyncIO, TestError]("the Scala 2 derivation matches the shared expected weave renderings") { implicit H =>
    val derived = DeriveRaise.aspect[TestAlg, Render, Render, Render]

    for {
      recorder <- RecordingFk[SyncIO, Render, Render]
      instrumented = derived.intercept(new GenericTestAlg[SyncIO](0))(recorder.fk, OnRaise.noop[SyncIO, Render])
      rendered <- ExpectedWeaves.rendered(instrumented, recorder)
    } yield assertEquals(rendered, ExpectedWeaves.expected)
  }
}
