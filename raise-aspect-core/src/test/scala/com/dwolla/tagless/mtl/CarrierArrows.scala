package com.dwolla.tagless.mtl

import cats.*
import cats.effect.*
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*

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
  */
object CarrierArrows {
  type Result[A] = Either[TestError, A]

  def resultToLazily[Err[_]](implicit H: Handle[SyncIO, TestError]): RaiseArrow[Result, SyncIO, Err] =
    RaiseArrow(
      new (Result ~> SyncIO) {
        def apply[A](fa: Result[A]): SyncIO[A] = fa.fold(H.raise, H.applicative.pure)
      },
      new RaisePull[SyncIO, Result, Err] {
        def apply[E](rg: Raise[SyncIO, E])(implicit ev: Err[E]): Raise[Result, E] =
          new Raise[Result, E] {
            val functor: Functor[Result] = Functor[Result]

            def raise[E2 <: E, A](e: E2): Result[A] =
              rg.raise[E2, A](e).map(_.asRight).unsafeRunSync() // TODO is this sound? should `e` be part of a `TestError` somehow and the result be a Left?
          }
      }
    )
}
