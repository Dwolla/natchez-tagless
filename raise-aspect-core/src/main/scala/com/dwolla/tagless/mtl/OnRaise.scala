package com.dwolla.tagless.mtl

import cats.Applicative
import cats.syntax.all._

/** A hook invoked with the typed value at the moment a `Raise[F, E].raise`
  * crosses a `raiseLift` interception point (see [[WeaveArrows.raiseLift]]).
  * Universally quantified in `E`: `RaisePull#apply[E]` carries no per-`E`
  * evidence, so a per-error-type typeclass cannot reach this point — any
  * error-specific behavior must live inside the hook implementation,
  * matching on the value it receives.
  */
trait OnRaise[F[_]] extends Serializable {
  def apply[E](e: E): F[Unit]
}

object OnRaise {
  /** Does nothing. The default for callers who don't want observation. */
  def noop[F[_]](implicit F: Applicative[F]): OnRaise[F] =
    new OnRaise[F] {
      def apply[E](e: E): F[Unit] = ().pure[F]
    }
}
