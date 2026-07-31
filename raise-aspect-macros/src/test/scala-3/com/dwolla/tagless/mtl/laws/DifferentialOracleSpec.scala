package com.dwolla.tagless.mtl
package laws

import cats.arrow.FunctionK
import munit.FunSuite

import scala.annotation.experimental

import LawsInstances._

/** Task 4/5 — the Scala 3 differential oracle.
  *
  * The M2 laws prove the derived instance is ''correct''; this proves it is
  * ''identical'' to M1's hand-written reference, method by method. Since the Scala 2
  * oracle asserts the same thing against the same reference, the two derivations
  * agree transitively — and `CrossVersionAgreementSpec` pins it directly.
  */
@experimental
class DifferentialOracleSpec extends FunSuite:

  private val derived: RaiseAspect[TestAlg, Render, Render, Render] =
    DeriveRaise.aspect[TestAlg, Render, Render, Render]

  private val reference: RaiseAspect[TestAlg, Render, Render, Render] =
    TestAlgReference.referenceRaiseAspect[Render, Render, Render]

  private val outcomes = List(-1, 0, 1)

  private def instrumented(
      instance: RaiseAspect[TestAlg, Render, Render, Render],
      outcome: Int
  ): (TestAlg[Result], RecordingFk[Result, Render, Render]) =
    LawsInstances.instrumented(instance, outcome)

  test("the derived instance is structurally identical to the reference, for every method and sample") {
    outcomes.foreach { outcome =>
      val (d, dRec) = instrumented(derived, outcome)
      val (r, rRec) = instrumented(reference, outcome)

      exhaustiveInt.allValues.foreach { i =>
        assertEquals(d.a(i)(raiseResult), r.a(i)(raiseResult))
        assertEquals(d.c(i), r.c(i))
        assertEquals(d.e(raiseResult, raiseResult), r.e(raiseResult, raiseResult))

        exhaustiveInt.allValues.foreach(j => assertEquals(d.d(i)(j)(raiseResult), r.d(i)(j)(raiseResult)))
        exhaustiveString.allValues.foreach(s => assertEquals(d.b(s, i)(raiseResult), r.b(s, i)(raiseResult)))
      }

      assertEquals(LawsInstances.renderedWeaves(dRec), LawsInstances.renderedWeaves(rRec))
      assertEquals(dRec.events, rRec.events)
    }
  }

  test("the derived mapK agrees with the reference under the identity arrow") {
    val eqAlg = eqTestAlg[Result]
    outcomes.foreach { outcome =>
      val impl = new EitherTestAlg(outcome)
      assert(
        eqAlg.eqv(
          derived.mapK(impl)(RaiseArrow.id[Result, Render]),
          reference.mapK(impl)(RaiseArrow.id[Result, Render])
        ),
        s"mapK under the identity arrow differs for eOutcome $outcome"
      )
    }
  }

  test("the derived intercept agrees with the reference under the forgetful interpreter") {
    val eqAlg = eqTestAlg[Result]
    val erase = WeaveArrows.codomainTarget[Result, Render, Render]
    outcomes.foreach { outcome =>
      val impl = new EitherTestAlg(outcome)
      assert(
        eqAlg.eqv(
          derived.intercept(impl)(erase, OnRaise.noop[Result, Render]),
          reference.intercept(impl)(erase, OnRaise.noop[Result, Render])
        ),
        s"intercept under the forgetful interpreter differs for eOutcome $outcome"
      )
    }
  }

  test("the derived functorK agrees with the derived aspect's mapK") {
    val functorK = DeriveRaise.functorK[TestAlg, Render]
    val eqAlg = eqTestAlg[Result]
    outcomes.foreach { outcome =>
      val impl = new EitherTestAlg(outcome)
      val arrow = RaiseArrow(FunctionK.id[Result], RaisePull.id[Result, Render])
      assert(eqAlg.eqv(functorK.mapK(impl)(arrow), derived.mapK(impl)(arrow)))
    }
  }
