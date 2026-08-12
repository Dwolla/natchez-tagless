package com.dwolla.tagless.mtl
package laws

import cats.mtl.Raise

import LawsInstances._

/** The cross-compiler agreement data.
  *
  * These are the `RenderedWeave`s the derivation must produce for a fixed set of
  * `TestAlg` calls. A Scala 2 spec and a Scala 3 spec each assert their derived
  * instance reproduces exactly this list, so the two derivations are comparable
  * assertion-for-assertion rather than only transitively.
  *
  * Shared across 2.12, 2.13 and 3, so this file must stay free of version-specific
  * syntax — no `using`, no `@experimental`, no derivation.
  */
object ExpectedWeaves {

  /** Fixed arguments, chosen so every rendered value is distinguishable.
    *
    * ==This is already an arrival-order pin, not just a content pin.==
    * [[rendered]] reads the recorder's arrival-ordered buffer instead
    * of constructing weaves, and `RenderedWeave.methodName` is the same
    * `codomain.name` the recorder's log line carries, so no derivation can
    * reorder the five calls below without this list's order failing too — a
    * separate `expectedOrder` constant asserting `recorder.events` would add
    * no coverage over this one. The property is spelled out here so a future
    * reader does not have to rediscover that `expected` now carries it.
    *
    * The spec that asserts this passes `OnRaise.noop`, which cannot write to
    * the recorder's log whatever the arguments are, so nothing about the hook
    * complicates this reasoning.
    */
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
    * `expected` describes what a woven call produces; this method gets hold
    * of the same weaves by reading a recording `fk`, not by inspecting an
    * `Alg[Weave[…]]` directly.
    */
  def rendered(
      instrumented: TestAlg[Lazily],
      recorder: RecordingFk[Lazily, Render, Render]
  ): Lazily[List[RenderedWeave]] =
    for {
      _ <- instrumented.a(7)(Raise[Lazily, ErrA])
      _ <- instrumented.b("ab", 2)(Raise[Lazily, ErrB])
      _ <- instrumented.c(3)
      _ <- instrumented.d(4)(5)(Raise[Lazily, ErrA])
      _ <- instrumented.e(Raise[Lazily, ErrA], Raise[Lazily, ErrB])
      weaves <- recorder.weaves
    } yield weaves.map(r => WeaveRenderer.render(r.weave)).toList
}
