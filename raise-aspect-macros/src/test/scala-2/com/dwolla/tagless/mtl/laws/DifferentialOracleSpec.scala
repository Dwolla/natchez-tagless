package com.dwolla.tagless.mtl
package laws

import cats.{Eq, Functor}
import cats.arrow.FunctionK
import munit.FunSuite

import LawsInstances._

/** Task 6 — the differential oracle.
  *
  * M1's hand-written reference instance is the specification for what the macro
  * must emit. The M2 laws prove the derived instance is ''correct''; this proves
  * it is ''identical'', method by method and argument by argument, which is
  * stricter: L1–L3 compare behaviour and are blind to metadata drift.
  */
class DifferentialOracleSpec extends FunSuite {

  private val derived: RaiseAspect[TestAlg, Render, Render] =
    DeriveRaise.aspect[TestAlg, Render, Render]

  private val reference: RaiseAspect[TestAlg, Render, Render] =
    TestAlgReference.referenceRaiseAspect[Render, Render]

  private val outcomes = List(-1, 0, 1)

  private def agree[A](d: Woven[A], r: Woven[A])(implicit ev: Eq[Result[A]], loc: munit.Location): Unit = {
    assertEquals(WeaveRenderer.render(d), WeaveRenderer.render(r))
    assert(ev.eqv(d.codomain.target, r.codomain.target), "codomain targets differ")
  }

  test("the derived weave is structurally identical to the reference, for every method and sample") {
    outcomes.foreach { outcome =>
      val impl = new EitherTestAlg(outcome)
      val d = derived.weave(impl)(Functor[Result])
      val r = reference.weave(impl)(Functor[Result])

      exhaustiveInt.allValues.foreach { i =>
        agree(d.a(i)(raiseWoven), r.a(i)(raiseWoven))
        agree(d.c(i), r.c(i))
        agree(d.e(raiseWoven, raiseWoven), r.e(raiseWoven, raiseWoven))

        exhaustiveInt.allValues.foreach(j => agree(d.d(i)(j)(raiseWoven), r.d(i)(j)(raiseWoven)))
        exhaustiveString.allValues.foreach(s => agree(d.b(s, i)(raiseWoven), r.b(s, i)(raiseWoven)))
      }
    }
  }

  test("the derived mapK agrees with the reference under the identity arrow") {
    val eqAlg = eqTestAlg[Result]
    outcomes.foreach { outcome =>
      val impl = new EitherTestAlg(outcome)
      assert(
        eqAlg.eqv(derived.mapK(impl)(RaiseArrow.id[Result]), reference.mapK(impl)(RaiseArrow.id[Result])),
        s"mapK under the identity arrow differs for eOutcome $outcome"
      )
    }
  }

  test("the derived mapK agrees with the reference under the erasure arrow") {
    val eqAlg = eqTestAlg[Result]
    val erase = WeaveArrows.eraseWeave[Result, Render, Render]
    outcomes.foreach { outcome =>
      val impl = new EitherTestAlg(outcome)
      assert(
        eqAlg.eqv(
          derived.mapK(derived.weave(impl)(Functor[Result]))(erase),
          reference.mapK(reference.weave(impl)(Functor[Result]))(erase)
        ),
        s"mapK under the erasure arrow differs for eOutcome $outcome"
      )
    }
  }

  test("the derived functorK agrees with the derived aspect's mapK") {
    val functorK = DeriveRaise.functorK[TestAlg]
    val eqAlg = eqTestAlg[Result]
    outcomes.foreach { outcome =>
      val impl = new EitherTestAlg(outcome)
      val arrow = RaiseArrow(FunctionK.id[Result], RaisePull.id[Result])
      assert(eqAlg.eqv(functorK.mapK(impl)(arrow), derived.mapK(impl)(arrow)))
    }
  }
}
