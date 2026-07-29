package com.dwolla.tagless.mtl

import cats.Functor
import cats.arrow.FunctionK
import cats.mtl.Raise
import cats.tagless.aop.Aspect
import cats.~>

/** The canonical arrows between a woven algebra's carrier
  * `Aspect.Weave[F, Dom, Cod, *]` and the underlying `F`.
  */
object WeaveArrows {

  /** The advice and algebra name carried by the shell `Weave`s that
    * [[raiseLift]] builds. Fixed, because a lifted `raise` has no method or
    * parameter to name.
    */
  private val RaiseName: String = "raise"

  /** Forward: forget the metadata. */
  def codomainTarget[F[_], Dom[_], Cod[_]]: Aspect.Weave[F, Dom, Cod, *] ~> F =
    FunctionK.liftFunction[Aspect.Weave[F, Dom, Cod, *], F](_.codomain.target)

  /** Backward, used ''inside'' derived `weave` methods: converts the capability
    * a woven method receives into the one the underlying method needs.
    *
    * The produced `Raise` reports the ambient `Functor[F]`, never a functor
    * routed through the weave — law L7.
    */
  def raisePull[F[_], Dom[_], Cod[_]](implicit
      F: Functor[F]
  ): RaisePull[Aspect.Weave[F, Dom, Cod, *], F] =
    new RaisePull[Aspect.Weave[F, Dom, Cod, *], F] {
      def apply[E](rw: Raise[Aspect.Weave[F, Dom, Cod, *], E]): Raise[F, E] =
        new Raise[F, E] {
          val functor: Functor[F] = F

          def raise[E2 <: E, A](e: E2): F[A] =
            rw.raise[E2, A](e).codomain.target
        }
    }

  /** Backward, used on the interpretation side: lifts a capability on `F` into
    * one on the woven carrier by wrapping raised values in a shell `Weave`.
    *
    * The `Cod` instance on that shell is synthesized. It is sound because the
    * shell is unwrapped immediately via `codomain.target`, and a raised `F[A]`
    * never yields an `A` for anything to render.
    */
  def raiseLift[F[_], Dom[_], Cod[_]](implicit
      F: Functor[F],
      syn: Synthetic[Cod]
  ): RaisePull[F, Aspect.Weave[F, Dom, Cod, *]] =
    new RaisePull[F, Aspect.Weave[F, Dom, Cod, *]] {
      def apply[E](rf: Raise[F, E]): Raise[Aspect.Weave[F, Dom, Cod, *], E] =
        new Raise[Aspect.Weave[F, Dom, Cod, *], E] {
          val functor: Functor[Aspect.Weave[F, Dom, Cod, *]] =
            syntheticWeaveFunctor[F, Dom, Cod]

          def raise[E2 <: E, A](e: E2): Aspect.Weave[F, Dom, Cod, A] =
            Aspect.Weave[F, Dom, Cod, A](
              RaiseName,
              Nil,
              Aspect.Advice[F, Cod, A](RaiseName, rf.raise[E2, A](e))(syn.apply[A])
            )
        }
    }

  /** The full erasure morphism `Weave[F, Dom, Cod, *] ⇒ F`. */
  def eraseWeave[F[_], Dom[_], Cod[_]](implicit
      F: Functor[F],
      syn: Synthetic[Cod]
  ): RaiseArrow[Aspect.Weave[F, Dom, Cod, *], F] =
    RaiseArrow(codomainTarget[F, Dom, Cod], raiseLift[F, Dom, Cod])

  /** Satisfies `Raise`'s abstract `functor` member on shell weaves. Maps the
    * codomain target with the ambient `Functor[F]`, substitutes a synthesized
    * `Cod` instance for the new result type, and preserves `algebraName`,
    * `domain`, and `codomain.name` — law L6.
    */
  private def syntheticWeaveFunctor[F[_], Dom[_], Cod[_]](implicit
      F: Functor[F],
      syn: Synthetic[Cod]
  ): Functor[Aspect.Weave[F, Dom, Cod, *]] =
    new Functor[Aspect.Weave[F, Dom, Cod, *]] {
      def map[A, B](w: Aspect.Weave[F, Dom, Cod, A])(f: A => B): Aspect.Weave[F, Dom, Cod, B] =
        Aspect.Weave[F, Dom, Cod, B](
          w.algebraName,
          w.domain,
          Aspect.Advice[F, Cod, B](w.codomain.name, F.map(w.codomain.target)(f))(syn.apply[B])
        )
    }
}
