package com.dwolla.tagless.mtl
package laws

import cats.arrow.FunctionK
import cats.effect.{Sync, SyncIO}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import com.dwolla.tagless.mtl.laws.LawsInstances.*
import munit.CatsEffectSuite

import scala.annotation.experimental

/** The Scala 3 differential oracle.
  *
  * The law suite proves the derived instance is ''correct''; this proves it
  * is ''identical'' to the hand-written reference, method by method. Since
  * the Scala 2 oracle asserts the same thing against the same reference, the
  * two derivations agree transitively — and `CrossVersionAgreementSpec` pins
  * it directly.
  */
@experimental
class DifferentialOracleSpec extends CatsEffectSuite with HandleTestSyntax:

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
  private def observed[F[_] : Sync](
      instance: RaiseAspect[TestAlg, Render, Render, Render],
      outcome: Int
  ): F[(TestAlg[F], RecordingFk[F, Render, Render])] =
    LawsInstances.observed(instance, outcome)

  testWithHandle[SyncIO, TestError]("the derived instance is structurally identical to the reference, for every method and sample") { implicit H =>
    outcomes.traverse_ { outcome =>
      for {
        dPair <- observed[SyncIO](derived, outcome)
        (d, dRec) = dPair
        rPair <- observed[SyncIO](reference, outcome)
        (r, rRec) = rPair
        _ <-
          exhaustiveInt.allValues.traverse_ { i =>
            for {
              da <- d.a(i).attemptHandle
              ra <- r.a(i).attemptHandle
              _ = assertEquals(da, ra)
              dc <- d.c(i).attemptHandle
              rc <- r.c(i).attemptHandle
              _ = assertEquals(dc, rc)
              de <- d.e.attemptHandle
              re <- r.e.attemptHandle
              _ = assertEquals(de, re)
              _ <- exhaustiveInt.allValues.traverse_ { j =>
                for {
                  dd <- d.d(i)(j).attemptHandle
                  rd <- r.d(i)(j).attemptHandle
                } yield assertEquals(dd, rd)
              }
              _ <- exhaustiveString.allValues.traverse_ { s =>
                for {
                  db <- d.b(s, i).attemptHandle
                  rb <- r.b(s, i).attemptHandle
                } yield assertEquals(db, rb)
              }
            } yield ()
          }
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
        // constant — the two logs still match even if the oracle went blind to
        // a dropped `RaiseAspect.observing`. `a(-2)`, `a(-1)` and several `d`
        // samples raise for every `eOutcome`, so a log with no `raise:` line
        // means the fixture stopped observing raises.
        _ = assert(
          rEvents.exists(_.startsWith("raise:")),
          s"no raise reached the hook for eOutcome $outcome — the events comparison above is vacuous"
        )
      } yield ()
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

  /** Covers what the identity-arrow comparison above cannot: `RaiseArrow.id`'s
    * `pull` is the identity too, so at that arrow the comparison can't see a
    * `pull` that was composed wrongly. This one uses a genuine carrier-change
    * arrow instead.
    */
  testWithHandle[SyncIO, TestError]("the derived mapK agrees with the reference under a genuine carrier change") { implicit H =>
    val eqAlg = eqTestAlg[SyncIO]
    val arrow = CarrierArrows.resultToSyncIO[Render]

    SyncIO {
      outcomes.foreach { outcome =>
        val impl = new EitherTestAlg(outcome)
        assert(
          eqAlg.eqv(derived.mapK(impl)(arrow), reference.mapK(impl)(arrow)),
          s"mapK under the carrier-change arrow differs for eOutcome $outcome"
        )
      }
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
