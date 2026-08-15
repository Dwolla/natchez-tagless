package com.dwolla.tagless.mtl
package laws

import cats.effect.SyncIO
import munit.CatsEffectSuite

import scala.annotation.experimental

import LawsInstances.*

/** The Scala 3 half — the derived instance reproduces the shared expected
  * renderings. The Scala 2 spec of the same name asserts the same list, so the two
  * derivations are directly comparable in CI.
  */
@experimental
class CrossVersionAgreementSpec extends CatsEffectSuite with HandleTestSyntax:
  testWithHandle[SyncIO, TestError]("the Scala 3 derivation matches the shared expected weave renderings") { implicit H =>
    val derived = DeriveRaise.aspect[TestAlg, Render, Render, Render]

    for {
      recorder <- RecordingFk[SyncIO, Render, Render]
      instrumented = derived.intercept(new GenericTestAlg[SyncIO](0))(recorder.fk, OnRaise.noop[SyncIO, Render])
      rendered <- ExpectedWeaves.rendered(instrumented, recorder)
    } yield assertEquals(rendered, ExpectedWeaves.expected)
  }
