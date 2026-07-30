package com.dwolla.tagless.mtl

import cats.arrow.FunctionK
import cats.mtl.Raise
import cats.~>

/** Transports a `Raise` capability backward along a mapping of effects:
  * `∀E. Err[E] ?=> Raise[G, E] => Raise[F, E]`.
  *
  * This is possible because `Raise` only ever ''produces'' `F` values — `raise`
  * has no `F[A]` parameter — so the capability can follow a value-level mapping
  * in the opposite direction. `Handle` is not transportable for exactly this
  * reason: `handleWith` consumes an `F[A]`.
  *
  * Transport is uniform in `E` — `apply` keeps its own type parameter, so one
  * pull serves a method declaring several `Raise` parameters with different
  * error types. `Err` supplies per-error-type evidence at each application,
  * which is what lets an interception point render the error through a type
  * class instead of `toString`. Use `cats.tagless.Trivial` for `Err` when no
  * evidence is wanted; its universal instance makes the constraint vacuous.
  */
trait RaisePull[G[_], F[_], Err[_]] extends Serializable {
  def apply[E](rg: Raise[G, E])(implicit ev: Err[E]): Raise[F, E]
}

object RaisePull {
  def id[F[_], Err[_]]: RaisePull[F, F, Err] =
    new RaisePull[F, F, Err] {
      def apply[E](rg: Raise[F, E])(implicit ev: Err[E]): Raise[F, E] = rg
    }
}

/** A morphism `F ⇒ G` in the category algebras with `Raise` parameters are
  * functorial over: values travel forward along `fk`, `Raise` capabilities
  * travel backward along `pull`.
  */
final case class RaiseArrow[F[_], G[_], Err[_]](fk: F ~> G, pull: RaisePull[G, F, Err]) {
  def andThen[H[_]](that: RaiseArrow[G, H, Err]): RaiseArrow[F, H, Err] =
    RaiseArrow(
      that.fk.compose(fk),
      new RaisePull[H, F, Err] {
        def apply[E](rh: Raise[H, E])(implicit ev: Err[E]): Raise[F, E] =
          pull(that.pull(rh))
      }
    )
}

object RaiseArrow {
  def id[F[_], Err[_]]: RaiseArrow[F, F, Err] =
    RaiseArrow(FunctionK.id[F], RaisePull.id[F, Err])
}
