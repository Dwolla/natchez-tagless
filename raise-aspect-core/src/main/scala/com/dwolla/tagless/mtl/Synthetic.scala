package com.dwolla.tagless.mtl

import cats.tagless.Trivial

/** Produces a `Cod` instance for any type.
  *
  * Used inside the shell `Weave` that `WeaveArrows.raiseLift` builds. In the
  * derived flow that shell is unwrapped immediately via `codomain.target`, and
  * a raised `F[A]` never yields an `A`, so a synthesized instance never reaches
  * an interpreter. Laws L5–L7 pin that down.
  *
  * ==What is, and is not, guaranteed==
  *
  * The property that holds is about the ''derivation'', not about the type: the
  * expansion invokes nothing but `Raise`'s abstract producing member, so no
  * synthesized instance escapes along the derived path. It is '''not''' true
  * that a synthesized instance is unobservable through the public API in
  * general — an earlier version of this comment claimed that, and it is false.
  *
  * `Raise` exposes its evidence member publicly, and
  * `raiseLift(rf).functor.map(w)(identity)` returns a `Weave` whose real
  * `Cod` instance has been replaced by a synthesized one. This is demonstrable
  * today. It is a consequence of `Functor`'s shape rather than a bug: a
  * `Functor` cannot derive a `Cod[B]` from a `Cod[A]`, so `map` has nothing
  * else to put there, and no law over `Functor` alone can forbid it. Laws
  * L6a–L6d deliberately compare `algebraName`, `domain` and `codomain.name`
  * and not `codomain.instance`, because a law comparing the instance would be
  * unsatisfiable.
  *
  * Nothing in the derivation, and nothing in cats-mtl 1.7's own `Raise`
  * defaults or syntax, reaches that member — verified during M8's Phase 1
  * research — so this is a property of the API surface rather than a live
  * defect. Implementors should still treat it as a real constraint: **a
  * `Synthetic` instance must not reveal anything about the value it stands in
  * for.** The sentinel-string instances this repo ships satisfy that by
  * construction; a `Synthetic` that rendered its argument would not.
  */
trait Synthetic[Cod[_]] extends Serializable {
  def apply[A]: Cod[A]
}

object Synthetic {
  def apply[Cod[_]](implicit ev: Synthetic[Cod]): Synthetic[Cod] = ev

  implicit val trivial: Synthetic[Trivial] =
    new Synthetic[Trivial] {
      def apply[A]: Trivial[A] = Trivial.instance[A]
    }
}
