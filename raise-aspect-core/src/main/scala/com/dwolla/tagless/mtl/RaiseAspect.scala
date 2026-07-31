package com.dwolla.tagless.mtl

import cats.mtl.Raise
import cats.syntax.all._
import cats.tagless.aop.Aspect
import cats.{Apply, Functor}
import cats.~>

/** The `FunctorK` analogue for algebras whose methods take `Raise` capability
  * parameters. Plain `FunctorK` is uninhabited for such algebras, because
  * `mapK` over an `F ~> G` cannot turn the `Raise[G, E]` a `G`-side method
  * receives into the `Raise[F, E]` the underlying method needs.
  */
trait RaiseFunctorK[Alg[_[_]], Err[_]] extends Serializable {
  def mapK[F[_], G[_]](af: Alg[F])(arrow: RaiseArrow[F, G, Err]): Alg[G]
}

/** The `Aspect` analogue for algebras with `Raise` capability parameters.
  *
  * `Aspect.Weave` and `Aspect.Advice` are reused from cats-tagless verbatim so
  * that natchez-tagless's existing `Weave ~> F` interpreters keep working —
  * but a `Weave` here is ''data'' handed to `fk`, never an effect type. That
  * is the whole point of the operation being fused: no method ever receives a
  * `Raise[Aspect.Weave[F, Dom, Cod, *], E]`, so nothing ever has to synthesize
  * a `Functor` for the woven carrier. See
  * `docs/plans/raise-aspect/22-milestone-M12-fused-derivation.md` for the
  * defect that design removed.
  *
  * There is deliberately no `E` parameter: transport is uniform in the error
  * type, so each method is handled with whatever error types it declares,
  * including several `Raise` parameters on one method.
  *
  * `Err` is not an `E` parameter. It is a per-error-type ''evidence'' type
  * class demanded afresh at each application: `intercept`'s evidence arrives
  * at `RaiseAspect.observing`'s `ev: Err[E]` parameter, and `mapK`'s at
  * `RaisePull#apply`'s. Either way transport stays uniform in `E` while an
  * interception point gains something better than `toString` to render a
  * raised error with. Use `cats.tagless.Trivial` for `Err` when no evidence is
  * wanted.
  */
trait RaiseAspect[Alg[_[_]], Dom[_], Cod[_], Err[_]] extends RaiseFunctorK[Alg, Err] {

  /** Build an `Aspect.Weave` describing each method call and hand it to `fk`,
    * which decides what interception means — tracing it, logging it, or simply
    * forgetting the metadata and running the call.
    *
    * Every `Raise` capability the caller supplies is passed straight through
    * to the underlying implementation, decorated with `onRaise` at that same
    * carrier. Interception is therefore in-place: the result is an `Alg[F]`,
    * the carrier never changes, and the weave is data rather than an effect
    * type.
    *
    * `Apply[F]` is needed only to sequence the hook's effect before the raise.
    */
  def intercept[F[_]](af: Alg[F])(
      fk: Aspect.Weave[F, Dom, Cod, *] ~> F,
      onRaise: OnRaise[F, Err]
  )(implicit F: Apply[F]): Alg[F]
}

object RaiseAspect {

  /** The only capability-side helper the fused expansion needs: decorate a
    * `Raise[F, E]` with the observation hook, ''at the same carrier''.
    *
    * `functor` is `R`'s own — the real `Functor[F]`. Nothing is synthesized,
    * which is why this decorator is sound by construction: it can only produce
    * what `R` produces, prefixed by the hook's effect.
    */
  def observing[F[_], E, Err[_]](R: Raise[F, E], onRaise: OnRaise[F, Err])(implicit
      F: Apply[F],
      ev: Err[E]
  ): Raise[F, E] =
    new Raise[F, E] {
      val functor: Functor[F] = R.functor

      def raise[E2 <: E, A](e: E2): F[A] =
        onRaise.apply[E](e)(ev) *> R.raise[E2, A](e)
    }
}
