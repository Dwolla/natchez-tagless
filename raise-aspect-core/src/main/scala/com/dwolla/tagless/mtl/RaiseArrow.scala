package com.dwolla.tagless.mtl

import cats.arrow.FunctionK
import cats.mtl.Raise
import cats.~>

/** Transports a `Raise` capability backward along a mapping of effects:
  * `∀E. Raise[G, E] => Raise[F, E]`.
  *
  * This is possible because `Raise` only ever ''produces'' `F` values — `raise`
  * has no `F[A]` parameter — so the capability can follow a value-level mapping
  * in the opposite direction. `Handle` is not transportable for exactly this
  * reason: `handleWith` consumes an `F[A]`.
  */
trait RaisePull[G[_], F[_]] extends Serializable {
  def apply[E](rg: Raise[G, E]): Raise[F, E]
}

object RaisePull {
  def id[F[_]]: RaisePull[F, F] =
    new RaisePull[F, F] {
      def apply[E](rg: Raise[F, E]): Raise[F, E] = rg
    }
}

/** A morphism `F ⇒ G` in the category algebras with `Raise` parameters are
  * functorial over: values travel forward along `fk`, `Raise` capabilities
  * travel backward along `pull`.
  */
final case class RaiseArrow[F[_], G[_]](fk: F ~> G, pull: RaisePull[G, F]) {
  def andThen[H[_]](that: RaiseArrow[G, H]): RaiseArrow[F, H] =
    RaiseArrow(
      that.fk.compose(fk),
      new RaisePull[H, F] {
        def apply[E](rh: Raise[H, E]): Raise[F, E] = pull(that.pull(rh))
      }
    )
}

object RaiseArrow {
  def id[F[_]]: RaiseArrow[F, F] = RaiseArrow(FunctionK.id[F], RaisePull.id[F])
}
