package com.dwolla.tagless.mtl
package laws

import cats.arrow.FunctionK
import cats.data.EitherT
import cats.effect.SyncIO
import cats.mtl.Raise
import cats.syntax.all._
import munit.CatsEffectSuite

import LawsInstances._
import SyncIOTestSyntax._

/** The differential oracle.
  *
  * The handwritten reference instance is the specification for what the macro
  * must emit. The laws prove the derived instance is ''correct''; this proves
  * it is ''identical'', method by method and argument by argument, which is
  * stricter: L1–L3 compare behavior and are blind to metadata drift.
  */
class DifferentialOracleSpec extends CatsEffectSuite {

  private val derived: RaiseAspect[TestAlg, Render, Render, Render] =
    DeriveRaise.aspect[TestAlg, Render, Render, Render]

  private val reference: RaiseAspect[TestAlg, Render, Render, Render] =
    TestAlgReference.referenceRaiseAspect[Render, Render, Render]

  private val outcomes = List(-1, 0, 1)

  private val raiseLazily: Raise[Lazily, TestError] = Raise[Lazily, TestError]

  /** `observed`, not `instrumented`: the recorder's hook fires into the same
    * log as the weave arrivals, so the comparison below covers whether each
    * capability was decorated at all and with which `Err` evidence. Under
    * `OnRaise.noop` a derivation that dropped `RaiseAspect.observing` entirely
    * is indistinguishable from a correct one.
    */
  private def observed(
      instance: RaiseAspect[TestAlg, Render, Render, Render],
      outcome: Int
  ): Lazily[(TestAlg[Lazily], RecordingFk[Lazily, Render, Render])] =
    LawsInstances.observed(instance, outcome)

  test("the derived instance is structurally identical to the reference, for every method and sample") {
    outcomes.toList.traverse_ { outcome =>
      for {
        dPair <- observed(derived, outcome)
        (d, dRec) = dPair
        rPair <- observed(reference, outcome)
        (r, rRec) = rPair
        _ <- EitherT.liftF[SyncIO, TestError, Unit](
          exhaustiveInt.allValues.toList.traverse_ { i =>
            for {
              da <- d.a(i)(raiseLazily).value
              ra <- r.a(i)(raiseLazily).value
              _ = assertEquals(da, ra)
              dc <- d.c(i).value
              rc <- r.c(i).value
              _ = assertEquals(dc, rc)
              de <- d.e(raiseLazily, raiseLazily).value
              re <- r.e(raiseLazily, raiseLazily).value
              _ = assertEquals(de, re)
              _ <- exhaustiveInt.allValues.toList.traverse_ { j =>
                for {
                  dd <- d.d(i)(j)(raiseLazily).value
                  rd <- r.d(i)(j)(raiseLazily).value
                } yield assertEquals(dd, rd)
              }
              _ <- exhaustiveString.allValues.traverse_ { s =>
                for {
                  db <- d.b(s, i)(raiseLazily).value
                  rb <- r.b(s, i)(raiseLazily).value
                } yield assertEquals(db, rb)
              }
            } yield ()
          }
        )
        dRendered <- LawsInstances.renderedWeaves(dRec)
        rRendered <- LawsInstances.renderedWeaves(rRec)
        // Weave arrivals and hook firings in one log: the derived instance and
        // the reference must agree on *when* things happen, not only on what
        // they produce. `Aspect.Advice`'s target is a strict parameter, so a
        // raise is logged before the weave it belongs to reaches `fk` — a fact
        // no value-level comparison can see.
        _ = assertEquals(dRendered, rRendered)
        dEvents <- dRec.events
        rEvents <- rRec.events
        _ = assertEquals(dEvents.toList, rEvents.toList)
        // The latch on the comparison above, not a test of the hook. With a hook
        // that writes nothing — `OnRaise.noop`, or a `record` call reduced to a
        // constant — the two logs still match and the oracle silently returns to
        // its pre-M12 blindness to a dropped `RaiseAspect.observing`. `a(-2)`,
        // `a(-1)` and several `d` samples raise for every `eOutcome`, so a log
        // with no `raise:` line means the fixture stopped observing raises.
        _ = assert(
          rEvents.exists(_.startsWith("raise:")),
          s"no raise reached the hook for eOutcome $outcome — the events comparison above is vacuous"
        )
      } yield ()
    }.runOrFail
  }

  test("the derived mapK agrees with the reference under the identity arrow") {
    val eqAlg = eqTestAlg[Result]
    outcomes.foreach { outcome =>
      val impl = new EitherTestAlg(outcome)
      assert(
        eqAlg.eqv(derived.mapK(impl)(RaiseArrow.id[Result, Render]), reference.mapK(impl)(RaiseArrow.id[Result, Render])),
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
}
