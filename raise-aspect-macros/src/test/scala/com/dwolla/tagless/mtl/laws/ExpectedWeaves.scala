package com.dwolla.tagless.mtl
package laws

import LawsInstances._

/** Task 5 — the cross-compiler agreement data.
  *
  * These are the `RenderedWeave`s the derivation must produce for a fixed set of
  * `TestAlg` calls. A Scala 2 spec and a Scala 3 spec each assert their derived
  * instance reproduces exactly this list, so the two derivations are comparable
  * assertion-for-assertion rather than only transitively through the M1 reference.
  *
  * Shared across 2.12, 2.13 and 3, so this file must stay free of version-specific
  * syntax — no `using`, no `@experimental`, no derivation.
  */
object ExpectedWeaves {

  /** Fixed arguments, chosen so every rendered value is distinguishable. */
  val expected: List[RenderedWeave] = List(
    RenderedWeave("TestAlg", "a", List(List("i" -> "7"))),
    RenderedWeave("TestAlg", "b", List(List("x" -> "ab", "y" -> "2"))),
    RenderedWeave("TestAlg", "c", List(List("i" -> "3"))),
    RenderedWeave("TestAlg", "d", List(List("i" -> "4"), List("j" -> "5"))),
    // every parameter of `e` is a capability, so it contributes no clause at all
    RenderedWeave("TestAlg", "e", Nil)
  )

  /** The same calls, rendered from an actual woven algebra. */
  def rendered(woven: TestAlg[Woven]): List[RenderedWeave] = List(
    WeaveRenderer.render(woven.a(7)(raiseWoven)),
    WeaveRenderer.render(woven.b("ab", 2)(raiseWoven)),
    WeaveRenderer.render(woven.c(3)),
    WeaveRenderer.render(woven.d(4)(5)(raiseWoven)),
    WeaveRenderer.render(woven.e(raiseWoven, raiseWoven))
  )
}
