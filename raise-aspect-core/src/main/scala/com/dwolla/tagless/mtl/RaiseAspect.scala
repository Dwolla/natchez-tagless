package com.dwolla.tagless.mtl

import cats.Functor
import cats.tagless.aop.Aspect

/** The `FunctorK` analogue for algebras whose methods take `Raise` capability
  * parameters. Plain `FunctorK` is uninhabited for such algebras, because
  * `mapK` over an `F ~> G` cannot turn the `Raise[G, E]` a `G`-side method
  * receives into the `Raise[F, E]` the underlying method needs.
  */
trait RaiseFunctorK[Alg[_[_]]] extends Serializable {
  def mapK[F[_], G[_]](af: Alg[F])(arrow: RaiseArrow[F, G]): Alg[G]
}

/** The `Aspect` analogue for algebras with `Raise` capability parameters.
  *
  * `Aspect.Weave` and `Aspect.Advice` are reused from cats-tagless verbatim so
  * that natchez-tagless's existing `Weave ~> F` interpreters keep working.
  *
  * There is deliberately no `E` parameter: transport is uniform in the error
  * type, so each method is handled with whatever error types it declares,
  * including several `Raise` parameters on one method.
  */
trait RaiseAspect[Alg[_[_]], Dom[_], Cod[_]] extends RaiseFunctorK[Alg] {
  def weave[F[_]](af: Alg[F])(implicit F: Functor[F]): Alg[Aspect.Weave[F, Dom, Cod, *]]
}
