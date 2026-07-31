package com.dwolla.tagless.mtl
package laws

import cats.mtl.Raise

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

  /** The same calls, rendered from what the interpreter saw.
    *
    * `expected` is unchanged from M2: fusion changes who holds the weave, not
    * what a woven call produces. Only the way a test gets hold of the weaves
    * moved, from inspecting an `Alg[Weave[…]]` to reading a recording `fk`.
    */
  def rendered(
      instrumented: TestAlg[Result],
      recorder: RecordingFk[Result, Render, Render]
  ): List[RenderedWeave] = {
    // Bare calls rather than `val _ = ...`: 2.12 treats `_` as a real value
    // name, so only one `val _` may appear per block (see `RecordingFk`).
    // These are method calls performed for effect, not pure expressions in
    // statement position, so they warn under neither axis.
    instrumented.a(7)(Raise[Result, ErrA])
    instrumented.b("ab", 2)(Raise[Result, ErrB])
    instrumented.c(3)
    instrumented.d(4)(5)(Raise[Result, ErrA])
    instrumented.e(Raise[Result, ErrA], Raise[Result, ErrB])

    recorder.weaves.map(r => WeaveRenderer.render(r.weave))
  }
}
