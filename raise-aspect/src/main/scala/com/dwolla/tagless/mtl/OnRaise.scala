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
  *
  * '''A lawful hook must not itself fail through the capability it is
  * observing.''' `observing` sequences `apply(e)` ahead of the real
  * `raise(e)` with only `Apply[F]`, which gives it no way to catch or
  * discard the hook's own outcome. If the hook's `F[Unit]` doesn't represent
  * unconditional success, the real raised value is no longer guaranteed to
  * reach the caller unchanged: under a short-circuiting `F` a failing hook
  * ''replaces'' the raise with its own failure, and under an accumulating
  * `Applicative` (`Validated`, `Ior`) it gets folded into the raise via
  * `Semigroup`. Either way, observing a raise would change what it produces
  * — exactly what a hook exists not to do. Every hook this library ships
  * (`Otel4sDefaultOnRaise`, natchez's default, [[OnRaise.noop]]) satisfies
  * this trivially: none of them ever raises through the capability it
  * observes. See `ARCHAEOLOGY.md`, "Widening `intercept`'s `Apply[F]` to
  * `FlatMap[F]`" for why this is a documented precondition rather than a
  * type-level constraint.
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
