package com.dwolla.tracing.mtl

import cats.Applicative
import cats.mtl.Raise
import cats.syntax.all._

/** Task 4's fixture algebra: a method-level `Raise` capability, in a shape the
  * macro derives (M3/M4) and this milestone traces.
  */
sealed trait BarError extends Product with Serializable

object BarError {
  final case class Negative(i: Int) extends BarError
}

trait Bar[F[_]] {
  def bar(i: Int)(implicit R: Raise[F, BarError]): F[String]
}

object Bar {

  /** Raises on negative input, succeeds otherwise — so both the success and the
    * raise/rescue paths are exercised through the same implementation.
    */
  def apply[F[_]: Applicative]: Bar[F] = new Bar[F] {
    def bar(i: Int)(implicit R: Raise[F, BarError]): F[String] =
      if (i < 0) R.raise(BarError.Negative(i)) else s"bar:$i".pure[F]
  }
}
