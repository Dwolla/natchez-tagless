package com.dwolla.tagless.mtl

import cats.mtl.Raise

/** The error hierarchy for the M1/M2 fixtures.
  *
  * `ErrA` and `ErrB` are distinct branches under a single `TestError`, so that
  * `Either[TestError, *]` can carry both. Because cats-mtl's `Raise[F, -E]` is
  * contravariant in `E`, one `Raise[Either[TestError, *], TestError]` supplies
  * both `Raise[F, ErrA]` and `Raise[F, ErrB]`.
  */
sealed trait TestError extends Product with Serializable

sealed trait ErrA extends TestError
sealed trait ErrB extends TestError

object TestError {
  final case class NegativeInput(value: Int) extends ErrA
  final case class EmptyInput(field: String) extends ErrB
}

/** A tiny `Dom`/`Cod` type class for the smoke tests, standing in for natchez's
  * `TraceableValue`. Deliberately defined here so `raise-aspect-core` never
  * depends on natchez.
  */
trait Render[A] extends Serializable {
  def render(a: A): String
}

object Render {
  def apply[A](implicit ev: Render[A]): Render[A] = ev

  implicit val renderInt: Render[Int] = (a: Int) => a.toString
  implicit val renderString: Render[String] = (a: String) => a
  implicit val renderUnit: Render[Unit] = (_: Unit) => "()"
}

/** The fixture algebra. Shapes are fixed by milestone M1 and M2 depends on them
  * exactly: a capability with a strict parameter, a by-name parameter with a
  * different error type, a capability-free method, multiple parameter lists,
  * and two capabilities on one method.
  */
trait TestAlg[F[_]] {
  def a(i: Int)(implicit R: Raise[F, ErrA]): F[String]
  def b(x: String, y: => Int)(implicit R: Raise[F, ErrB]): F[Int]
  def c(i: Int): F[Int]
  def d(i: Int)(j: Int)(implicit R: Raise[F, ErrA]): F[Int]
  def e(implicit R1: Raise[F, ErrA], R2: Raise[F, ErrB]): F[Unit]
}

/** Capability-free algebra, used by law L9 (conservative extension) in M2. */
trait PlainAlg[F[_]] {
  def p(i: Int): F[String]
}

/** A concrete `TestAlg` that raises on designated inputs so the error paths are
  * exercised: negative numbers and empty strings fail, everything else
  * succeeds.
  *
  * @param eOutcome
  *   selects which capability `e` exercises — negative raises `ErrA` through
  *   `R1`, positive raises `ErrB` through `R2`, zero succeeds.
  */
final class EitherTestAlg(eOutcome: Int) extends TestAlg[Either[TestError, *]] {
  import TestError._

  private type F[A] = Either[TestError, A]

  def a(i: Int)(implicit R: Raise[F, ErrA]): F[String] =
    if (i < 0) R.raise(NegativeInput(i)) else Right(s"a:$i")

  def b(x: String, y: => Int)(implicit R: Raise[F, ErrB]): F[Int] =
    if (x.isEmpty) R.raise(EmptyInput("x")) else Right(x.length + y)

  def c(i: Int): F[Int] = Right(i * 2)

  def d(i: Int)(j: Int)(implicit R: Raise[F, ErrA]): F[Int] =
    if (i + j < 0) R.raise(NegativeInput(i + j)) else Right(i + j)

  def e(implicit R1: Raise[F, ErrA], R2: Raise[F, ErrB]): F[Unit] =
    if (eOutcome < 0) R1.raise(NegativeInput(eOutcome))
    else if (eOutcome > 0) R2.raise(EmptyInput("e"))
    else Right(())
}

/** A concrete `PlainAlg`. Having no capability parameter, it fails by returning
  * a `Left` directly rather than through a `Raise`.
  */
object EitherPlainAlg extends PlainAlg[Either[TestError, *]] {
  def p(i: Int): Either[TestError, String] =
    if (i < 0) Left(TestError.NegativeInput(i)) else Right(s"p:$i")
}
