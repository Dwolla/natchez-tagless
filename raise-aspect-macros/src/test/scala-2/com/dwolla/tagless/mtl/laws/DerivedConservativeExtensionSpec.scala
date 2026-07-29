package com.dwolla.tagless.mtl
package laws

import cats.arrow.FunctionK
import cats.mtl.Raise
import cats.tagless.{Derive => CatsTaglessDerive}
import cats.tagless.aop.Aspect
import munit.FunSuite

import LawsInstances._

/** Law L9 for the ''derived'' instance — our derivation is a conservative
  * extension of upstream's on a capability-free algebra.
  *
  * M2's `ConservativeExtensionSuite` hardcodes the hand-written
  * `PlainAlgReference` as "ours" and only exposes `upstream` as a seam, so it
  * cannot be reused here. The laws module is frozen, so rather than widen that
  * seam this spec restates the three comparisons against the derived instance.
  * (That missing seam is worth fixing whenever the freeze is next lifted.)
  */
class DerivedConservativeExtensionSpec extends FunSuite {

  private val ours: RaiseAspect[PlainAlg, Render, Render] =
    DeriveRaise.aspect[PlainAlg, Render, Render]

  private val upstream: Aspect[PlainAlg, Render, Render] =
    CatsTaglessDerive.aspect[PlainAlg, Render, Render]

  private val impl: PlainAlg[Result] = EitherPlainAlg

  test("L9 the derived woven structure matches upstream's, rendered") {
    val ourWoven = ours.weave(impl)
    val theirWoven = upstream.weave(impl)

    exhaustiveInt.allValues.foreach { i =>
      assertEquals(
        WeaveRenderer.render(ourWoven.p(i)),
        WeaveRenderer.render(theirWoven.p(i)),
        s"rendered weave differs for input $i"
      )
    }
  }

  test("L9 the derived woven codomain targets match upstream's") {
    val ourWoven = ours.weave(impl)
    val theirWoven = upstream.weave(impl)
    exhaustiveInt.allValues.foreach(i => assertEquals(ourWoven.p(i).codomain.target, theirWoven.p(i).codomain.target))
  }

  test("L9 the derived mapK agrees with upstream's FunctorK.mapK for any pull") {
    // PlainAlg has no capability parameters, so the pull must never be consulted.
    val unusablePull = new RaisePull[Result, Result] {
      def apply[E](rg: Raise[Result, E]): Raise[Result, E] =
        fail("mapK must not consult the pull for a capability-free algebra")
    }

    val ourMapped = ours.mapK(impl)(RaiseArrow(FunctionK.id[Result], unusablePull))
    val theirMapped = upstream.mapK(impl)(FunctionK.id[Result])

    exhaustiveInt.allValues.foreach(i => assertEquals(ourMapped.p(i), theirMapped.p(i)))
  }

  test("L9 the derived instance also matches the hand-written PlainAlg reference") {
    val referenceWoven = PlainAlgReference.referenceRaiseAspect[Render, Render].weave(impl)
    val ourWoven = ours.weave(impl)
    exhaustiveInt.allValues.foreach { i =>
      assertEquals(WeaveRenderer.render(ourWoven.p(i)), WeaveRenderer.render(referenceWoven.p(i)))
    }
  }
}
