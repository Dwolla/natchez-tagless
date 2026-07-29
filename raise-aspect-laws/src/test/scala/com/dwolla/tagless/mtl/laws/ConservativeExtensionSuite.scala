package com.dwolla.tagless.mtl
package laws

import cats.arrow.FunctionK
import cats.mtl.Raise
import cats.tagless.aop.Aspect
import munit.FunSuite

import LawsInstances._

/** Law L9 — conservative extension.
  *
  * On a capability-free algebra our derivation must agree with upstream
  * `cats.tagless.Derive.aspect`: structurally equal rendered weaves, `Eq`-equal
  * codomain targets, and `mapK` agreeing with `FunctorK.mapK` for ''any'' pull.
  *
  * The upstream instance is abstract because deriving it is version-specific:
  * on Scala 2 it comes from cats-tagless-macros, and on Scala 3 `Derive` is
  * annotated `@experimental`, so the call site must be too.
  */
abstract class ConservativeExtensionSuite extends FunSuite {

  /** `cats.tagless.Derive.aspect[PlainAlg, Render, Render]`, derived at a
    * version-appropriate call site.
    */
  def upstream: Aspect[PlainAlg, Render, Render]

  private val ours: RaiseAspect[PlainAlg, Render, Render] =
    PlainAlgReference.referenceRaiseAspect[Render, Render]

  private val impl: PlainAlg[Result] = EitherPlainAlg

  test("L9 our woven structure matches upstream's, rendered") {
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

  test("L9 our woven codomain targets match upstream's") {
    val ourWoven = ours.weave(impl)
    val theirWoven = upstream.weave(impl)

    exhaustiveInt.allValues.foreach { i =>
      assertEquals(ourWoven.p(i).codomain.target, theirWoven.p(i).codomain.target)
    }
  }

  test("L9 our mapK agrees with upstream's FunctorK.mapK for any pull") {
    // PlainAlg has no capability parameters, so the pull must never be
    // consulted. This one blows up if it ever is.
    val unusablePull = new RaisePull[Result, Result] {
      def apply[E](rg: Raise[Result, E]): Raise[Result, E] =
        fail("mapK must not consult the pull for a capability-free algebra")
    }

    val ourMapped = ours.mapK(impl)(RaiseArrow(FunctionK.id[Result], unusablePull))
    val theirMapped = upstream.mapK(impl)(FunctionK.id[Result])

    exhaustiveInt.allValues.foreach(i => assertEquals(ourMapped.p(i), theirMapped.p(i)))
  }
}
