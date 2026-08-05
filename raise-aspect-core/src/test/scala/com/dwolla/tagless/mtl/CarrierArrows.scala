package com.dwolla.tagless.mtl

import cats.Functor
import cats.data.EitherT
import cats.effect.SyncIO
import cats.mtl.Raise
import cats.~>

/** A genuine carrier change, for the tests and laws that need a `RaiseArrow`
  * which is not the identity.
  *
  * The pull transports a `Raise[Lazily, E]` back to `Raise[Result, E]` by
  * running the underlying `SyncIO` synchronously (`.unsafeRunSync()`) —
  * that's what "transport a capability from a suspended `F` back to a strict
  * one" means at the seam; there is no other way to produce a `Result[A]`
  * (an already-resolved `Either`) from a `Lazily[A]` (a suspended
  * computation) without running it. This is fixture/law-checking plumbing,
  * not a test assertion — the same category of forced evaluation as
  * `Eq[SyncIO[A]]`.
  *
  * Polymorphic in `Err` because the pull genuinely does not consult the
  * evidence: transport here is uniform in `E`. That is what lets the law suite
  * instantiate arrow coherence at `Err = Trivial` over a ''non-identity''
  * arrow, which is the only way that instantiation says anything — it is there
  * to show coherence does not secretly depend on having `Err[E]` in hand, and
  * at `RaiseArrow.id` both sides of the law are literally the same expression.
  * `eraseWeave` was parametric in `Err` for the same reason before M12 deleted
  * it.
  */
object CarrierArrows {
  type Result[A] = Either[TestError, A]
  type Lazily[A] = EitherT[SyncIO, TestError, A]

  def resultToLazily[Err[_]]: RaiseArrow[Result, Lazily, Err] =
    RaiseArrow(
      new (Result ~> Lazily) {
        def apply[A](fa: Result[A]): Lazily[A] = EitherT(SyncIO.pure(fa))
      },
      new RaisePull[Lazily, Result, Err] {
        def apply[E](rg: Raise[Lazily, E])(implicit ev: Err[E]): Raise[Result, E] =
          new Raise[Result, E] {
            val functor: Functor[Result] = Functor[Result]

            def raise[E2 <: E, A](e: E2): Result[A] = rg.raise[E2, A](e).value.unsafeRunSync()
          }
      }
    )
}
