package com.dwolla.tagless.mtl
package laws

import cats.Functor
import cats.laws._
import cats.mtl.Raise
import cats.tagless.aop.Aspect

/** Value-level laws about arrows and the canonical `WeaveArrows` pair: L4, L5,
  * L6 and L7. These are properties of arrows rather than of an algebra, so they
  * are plain functions rather than a type class-indexed law trait.
  */
object RaiseArrowLaws {

  /** L4 — arrow coherence. Pulling a capability backward and then pushing the
    * raised value forward is the same as raising on the far side directly.
    * Holds for `RaiseArrow.id`, for `eraseWeave`, and for their composites.
    */
  def arrowCoherence[F[_], G[_], E, A](
      arrow: RaiseArrow[F, G],
      rg: Raise[G, E],
      e: E
  ): IsEq[G[A]] =
    arrow.fk(arrow.pull(rg).raise[E, A](e)) <-> rg.raise[E, A](e)

  /** L5 — section/retraction on the canonical pair: lifting a capability into
    * the woven carrier and pulling it back out is the identity on raises.
    */
  def sectionRetraction[F[_], Dom[_], Cod[_], E, A](rf: Raise[F, E], e: E)(implicit
      F: Functor[F],
      syn: Synthetic[Cod]
  ): IsEq[F[A]] = {
    val there = WeaveArrows.raiseLift[F, Dom, Cod].apply(rf)
    val back = WeaveArrows.raisePull[F, Dom, Cod].apply(there)
    back.raise[E, A](e) <-> rf.raise[E, A](e)
  }

  /** L6a — the synthesized weave functor maps the codomain target with the
    * ambient `Functor[F]`.
    */
  def liftedFunctorMapsTarget[F[_], Dom[_], Cod[_], E, A, B](
      rf: Raise[F, E],
      w: Aspect.Weave[F, Dom, Cod, A],
      f: A => B
  )(implicit F: Functor[F], syn: Synthetic[Cod]): IsEq[F[B]] =
    lifted[F, Dom, Cod, E](rf).functor.map(w)(f).codomain.target <-> F.map(w.codomain.target)(f)

  /** L6b — the synthesized weave functor preserves `algebraName`. */
  def liftedFunctorPreservesAlgebraName[F[_], Dom[_], Cod[_], E, A, B](
      rf: Raise[F, E],
      w: Aspect.Weave[F, Dom, Cod, A],
      f: A => B
  )(implicit F: Functor[F], syn: Synthetic[Cod]): IsEq[String] =
    lifted[F, Dom, Cod, E](rf).functor.map(w)(f).algebraName <-> w.algebraName

  /** L6c — the synthesized weave functor preserves `codomain.name`. */
  def liftedFunctorPreservesCodomainName[F[_], Dom[_], Cod[_], E, A, B](
      rf: Raise[F, E],
      w: Aspect.Weave[F, Dom, Cod, A],
      f: A => B
  )(implicit F: Functor[F], syn: Synthetic[Cod]): IsEq[String] =
    lifted[F, Dom, Cod, E](rf).functor.map(w)(f).codomain.name <-> w.codomain.name

  /** L6d — the synthesized weave functor preserves the `domain`.
    *
    * Compared by advice name, because `Aspect.Advice` defines no `equals` and
    * so cannot be compared structurally here. Full structural comparison of the
    * domain — names ''and'' rendered targets — is covered by the L8 suite via
    * [[WeaveRenderer]].
    */
  def liftedFunctorPreservesDomain[F[_], Dom[_], Cod[_], E, A, B](
      rf: Raise[F, E],
      w: Aspect.Weave[F, Dom, Cod, A],
      f: A => B
  )(implicit F: Functor[F], syn: Synthetic[Cod]): IsEq[List[List[String]]] =
    lifted[F, Dom, Cod, E](rf).functor.map(w)(f).domain.map(_.map(_.name)) <->
      w.domain.map(_.map(_.name))

  /** L7 — the `Raise[F, E]` handed to the underlying implementation reports a
    * functor extensionally equal to the ambient `Functor[F]`, rather than one
    * routed through the weave.
    */
  def pulledFunctorIsAmbient[F[_], Dom[_], Cod[_], E, A, B](
      rw: Raise[Aspect.Weave[F, Dom, Cod, *], E],
      fa: F[A],
      f: A => B
  )(implicit F: Functor[F]): IsEq[F[B]] =
    WeaveArrows.raisePull[F, Dom, Cod].apply(rw).functor.map(fa)(f) <-> F.map(fa)(f)

  private def lifted[F[_], Dom[_], Cod[_], E](rf: Raise[F, E])(implicit
      F: Functor[F],
      syn: Synthetic[Cod]
  ): Raise[Aspect.Weave[F, Dom, Cod, *], E] =
    WeaveArrows.raiseLift[F, Dom, Cod].apply(rf)
}
