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

  /** `observed`, not `instrumented`: the recorder's hook fires into the same
    * log as the weave arrivals, so the comparison below covers whether each
    * capability was decorated at all and with which `Err` evidence. Under
    * `OnRaise.noop` a derivation that dropped `RaiseAspect.observing` entirely
    * is indistinguishable from a correct one.
    */
  private def observed(
      instance: RaiseAspect[TestAlg, Render, Render, Render],
      outcome: Int
  ): (TestAlg[Result], RecordingFk[Result, Render, Render]) =
    LawsInstances.observed(instance, outcome)

  test("the derived instance is structurally identical to the reference, for every method and sample") {
    outcomes.foreach { outcome =>
      val (d, dRec) = observed(derived, outcome)
      val (r, rRec) = observed(reference, outcome)

      exhaustiveInt.allValues.foreach { i =>
        assertEquals(d.a(i)(raiseResult), r.a(i)(raiseResult))
        assertEquals(d.c(i), r.c(i))
        assertEquals(d.e(raiseResult, raiseResult), r.e(raiseResult, raiseResult))

        exhaustiveInt.allValues.foreach(j => assertEquals(d.d(i)(j)(raiseResult), r.d(i)(j)(raiseResult)))
        exhaustiveString.allValues.foreach(s => assertEquals(d.b(s, i)(raiseResult), r.b(s, i)(raiseResult)))
      }

      assertEquals(LawsInstances.renderedWeaves(dRec), LawsInstances.renderedWeaves(rRec))
      // Weave arrivals and hook firings in one log: the derived instance and
      // the reference must agree on *when* things happen, not only on what
      // they produce. `Result` is eager and `Aspect.Advice`'s target is a
      // strict parameter, so a raise is logged before the weave it belongs to
      // reaches `fk` — a fact no value-level comparison can see.
      assertEquals(dRec.events, rRec.events)
      // The latch on the comparison above, not a test of the hook. With a hook
      // that writes nothing — `OnRaise.noop`, or a `record` call reduced to a
      // constant — the two logs still match and the oracle silently returns to
      // its pre-M12 blindness to a dropped `RaiseAspect.observing`. `a(-2)`,
      // `a(-1)` and several `d` samples raise for every `eOutcome`, so a log
      // with no `raise:` line means the fixture stopped observing raises.
      assert(
        rRec.events.exists(_.startsWith("raise:")),
        s"no raise reached the hook for eOutcome $outcome — the events comparison above is vacuous"
      )
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

  /** The successor to the pre-M12 "mapK under the erasure arrow" comparison.
    * `eraseWeave` went from the woven carrier to `Result` and both endpoints
    * are gone, but the row it filled — the two `mapK`s compared over an arrow
    * that is not the identity — has to stay filled. `RaiseArrow.id`'s `pull`
    * is the identity too, so at that arrow the comparison cannot see a `pull`
    * that was composed wrongly.
    */
  test("the derived mapK agrees with the reference under a genuine carrier change") {
    val eqAlg = eqTestAlg[Lazily]
    val arrow = CarrierArrows.resultToLazily[Render]
    outcomes.foreach { outcome =>
      val impl = new EitherTestAlg(outcome)
      assert(
        eqAlg.eqv(derived.mapK(impl)(arrow), reference.mapK(impl)(arrow)),
        s"mapK under the carrier-change arrow differs for eOutcome $outcome"
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
