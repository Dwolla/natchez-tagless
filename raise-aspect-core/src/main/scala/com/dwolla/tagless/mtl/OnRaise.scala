package com.dwolla.tagless.mtl

import cats.Applicative
import cats.syntax.all._

/** A hook invoked with the typed value at the moment a `Raise[F, E].raise`
  * crosses an interception point. In the fused derivation that point is the
  * decorator [[RaiseAspect.observing]] wraps around each capability a method
  * receives, so the hook's effect is sequenced before the underlying `raise`
  * on the caller's own carrier.
  *
  * Universally quantified in `E`, with per-`E` evidence supplied by `Err`: a
  * hook can render the error through `Err[E]` rather than matching on the
  * value. Behavior that genuinely depends on the concrete error type still
  * lives inside the hook implementation.
  */
trait OnRaise[F[_], Err[_]] extends Serializable {
  def apply[E](e: E)(implicit ev: Err[E]): F[Unit]
}

object OnRaise {
  /** Does nothing. The default for callers who don't want observation. */
  def noop[F[_], Err[_]](implicit F: Applicative[F]): OnRaise[F, Err] =
    new OnRaise[F, Err] {
      def apply[E](e: E)(implicit ev: Err[E]): F[Unit] = ().pure[F]
    }
}
