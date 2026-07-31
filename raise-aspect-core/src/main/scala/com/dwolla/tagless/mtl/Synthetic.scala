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
  * ==This is a live defect, not merely an API-surface wart==
  *
  * An earlier revision of this comment said the substitution was "a property
  * of the API surface rather than a live defect", on the grounds that nothing
  * in the derivation or in cats-mtl's own `Raise` defaults reaches `functor`.
  * That reasoning was wrong: `Raise#functor` is public *precisely so external
  * generic code can recover the algebra bundled with the capability*, so
  * cats-mtl not calling it says nothing about whether anyone calls it.
  *
  * Demonstrated, not argued. The synthesized `Functor` **fails the functor
  * identity law**. `FunctorTests` was run against it at three equivalences:
  *
  *   - structural (`Weave` is a case class, `Aspect.Advice` a plain trait with
  *     reference equality) — all five laws fail, because `map` always
  *     allocates a fresh `Advice`;
  *   - structural modulo `Advice` identity — `covariant identity` and
  *     `invariant identity` fail, isolating the `Cod` substitution;
  *   - the equivalence laws L6a–L6d use — all five pass, because that
  *     equivalence never compares `codomain.instance`, which is the one
  *     component the implementation gets wrong.
  *
  * The realistic failure is a generic helper of the shape
  * `R.functor.map(fa)(f)` — the member's designed purpose — applied to a real
  * woven value. On a **successful** call, the codomain's real rendering is
  * replaced by this instance's. No error, no warning, every other attribute
  * intact.
  *
  * ==What that means for an implementor==
  *
  * **A `Synthetic` instance must not reveal anything about the value it stands
  * in for.** With this repo's constant sentinels the worst case is a
  * recognizably wrong span attribute. `Synthetic` is a public extension point,
  * and a rendering instance — `a => StringValue(a.toString)`, the obvious
  * first guess — converts that into a redaction hole: a `TraceableValue[Card]`
  * that deliberately renders `****1111` was demonstrated emitting the full
  * number instead.
  *
  * ==The structural limit==
  *
  * A lawful `Functor[Weave[F, Dom, Cod, *]]` at the modulo-`Advice`
  * equivalence requires exactly a `Functor[Cod]` — verified by building one.
  * `cats.tagless.Trivial` has one, so the `Cod = Trivial` path is already
  * lawful today. `natchez.TraceableValue` cannot: its type parameter appears
  * only in negative position, so no covariant `Functor` exists, and
  * `Invariant`/`Contravariant` do not supply what `map` needs. Not exposing a
  * `Functor` at all is impossible — `Raise` declares it abstract.
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
