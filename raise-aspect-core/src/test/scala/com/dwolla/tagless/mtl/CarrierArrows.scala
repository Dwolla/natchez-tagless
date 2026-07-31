package com.dwolla.tagless.mtl

import cats.Functor
import cats.data.EitherT
import cats.mtl.Raise
import cats.{Eval, ~>}

/** A genuine carrier change, for the tests and laws that need a `RaiseArrow`
  * which is not the identity.
  *
  * Before M12 that role was played by `WeaveArrows.eraseWeave`, an arrow from
  * the woven carrier back to `F`. Fusion deletes both the arrow and the
  * carrier, and testing `mapK` only at `RaiseArrow.id` would be a real loss:
  * L1 and L2 are about composition of arrows, and the identity arrow satisfies
  * them for reasons that have nothing to do with the derivation.
  *
  * `Eval` is total, so the pull can transport a `Raise[Lazily, E]` back to
  * `Raise[Result, E]` by running it — the canonical construction of a pull
  * from a `G ~> F`.
  */
object CarrierArrows {
  type Result[A] = Either[TestError, A]
  type Lazily[A] = EitherT[Eval, TestError, A]

  val resultToLazily: RaiseArrow[Result, Lazily, Render] =
    RaiseArrow(
      new (Result ~> Lazily) {
        def apply[A](fa: Result[A]): Lazily[A] = EitherT(Eval.now(fa))
      },
      new RaisePull[Lazily, Result, Render] {
        def apply[E](rg: Raise[Lazily, E])(implicit ev: Render[E]): Raise[Result, E] =
          new Raise[Result, E] {
            val functor: Functor[Result] = Functor[Result]

            def raise[E2 <: E, A](e: E2): Result[A] = rg.raise[E2, A](e).value.value
          }
      }
    )
}
