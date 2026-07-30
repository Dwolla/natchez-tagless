package com.dwolla.tagless.mtl

import cats.Apply
import cats.Functor
import cats.arrow.FunctionK
import cats.mtl.Raise
import cats.syntax.all._
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
  def raisePull[F[_], Dom[_], Cod[_], Err[_]](implicit
      F: Functor[F]
  ): RaisePull[Aspect.Weave[F, Dom, Cod, *], F, Err] =
    new RaisePull[Aspect.Weave[F, Dom, Cod, *], F, Err] {
      def apply[E](rw: Raise[Aspect.Weave[F, Dom, Cod, *], E])(implicit ev: Err[E]): Raise[F, E] =
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
    *
    * The `Err[E]` this method receives and the `Cod[A]` it synthesizes mean
    * opposite things, despite looking alike: the error value is real, so its
    * evidence is resolved by the caller and can genuinely render it; the
    * success value never comes into existence, so its instance can be made up.
    */
  def raiseLift[F[_], Dom[_], Cod[_], Err[_]](implicit
      F: Functor[F],
      syn: Synthetic[Cod]
  ): RaisePull[F, Aspect.Weave[F, Dom, Cod, *], Err] =
    new RaisePull[F, Aspect.Weave[F, Dom, Cod, *], Err] {
      def apply[E](rf: Raise[F, E])(implicit ev: Err[E]): Raise[Aspect.Weave[F, Dom, Cod, *], E] =
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

  /** As [[raiseLift]], but sequences the given `onRaise` hook's effect before
    * the raised value crosses the interception point — see M6's typed-error
    * span recording. The synthesized `functor` member is identical to the
    * no-hook overload's; only the shell target's effect changes.
    *
    * `Apply[F]`, not `Functor[F]`, is what sequencing the hook's effect with
    * `rf.raise` needs; `Apply[F] extends Functor[F]` in cats, so this still
    * satisfies `syntheticWeaveFunctor`'s `Functor[F]` requirement.
    */
  def raiseLift[F[_], Dom[_], Cod[_], Err[_]](onRaise: OnRaise[F, Err])(implicit
      F: Apply[F],
      syn: Synthetic[Cod]
  ): RaisePull[F, Aspect.Weave[F, Dom, Cod, *], Err] =
    new RaisePull[F, Aspect.Weave[F, Dom, Cod, *], Err] {
      def apply[E](rf: Raise[F, E])(implicit ev: Err[E]): Raise[Aspect.Weave[F, Dom, Cod, *], E] =
        new Raise[Aspect.Weave[F, Dom, Cod, *], E] {
          val functor: Functor[Aspect.Weave[F, Dom, Cod, *]] =
            syntheticWeaveFunctor[F, Dom, Cod]

          def raise[E2 <: E, A](e: E2): Aspect.Weave[F, Dom, Cod, A] =
            Aspect.Weave[F, Dom, Cod, A](
              RaiseName,
              Nil,
              Aspect.Advice[F, Cod, A](
                RaiseName,
                // `e` is an `E2 <: E` and the evidence in scope is `Err[E]`,
                // so the hook is invoked at `E` and `e` widens. This is why
                // `Err` needs no variance annotation.
                onRaise.apply[E](e)(ev) *> rf.raise[E2, A](e)
              )(syn.apply[A])
            )
        }
    }

  /** The full erasure morphism `Weave[F, Dom, Cod, *] ⇒ F`. */
  def eraseWeave[F[_], Dom[_], Cod[_], Err[_]](implicit
      F: Functor[F],
      syn: Synthetic[Cod]
  ): RaiseArrow[Aspect.Weave[F, Dom, Cod, *], F, Err] =
    RaiseArrow(codomainTarget[F, Dom, Cod], raiseLift[F, Dom, Cod, Err])

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
