package com.dwolla.tagless.mtl

import cats.Functor
import cats.tagless.aop.Aspect

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
  * that natchez-tagless's existing `Weave ~> F` interpreters keep working.
  *
  * There is deliberately no `E` parameter: transport is uniform in the error
  * type, so each method is handled with whatever error types it declares,
  * including several `Raise` parameters on one method.
  *
  * `Err` is not an `E` parameter. It is a per-error-type ''evidence'' type
  * class — `RaisePull#apply` still quantifies over `E` itself and merely
  * demands an `Err[E]` at each application — so transport stays uniform while
  * an interception point gains something better than `toString` to render a
  * raised error with. Use `cats.tagless.Trivial` for `Err` when no evidence is
  * wanted.
  */
trait RaiseAspect[Alg[_[_]], Dom[_], Cod[_], Err[_]] extends RaiseFunctorK[Alg, Err] {
  def weave[F[_]](af: Alg[F])(implicit F: Functor[F]): Alg[Aspect.Weave[F, Dom, Cod, *]]
}
