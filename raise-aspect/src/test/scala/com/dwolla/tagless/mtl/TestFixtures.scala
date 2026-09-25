package com.dwolla.tagless.mtl

import cats.{Applicative, ApplicativeError}
import cats.mtl.Raise
import cats.syntax.all._

/** The error hierarchy for the test fixtures.
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
  * `TraceableValue`. Deliberately defined here so `raise-aspect` never
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

  /** The error-type instances. `Err = Render` is one of the two
    * instantiations the law suite runs (the other is `Trivial`), and the
    * macro test fixtures derive at `Err = Render`, so all three error types
    * that appear in `TestAlg`'s signatures need an instance. The prefixes
    * make it observable that these instances — rather than `toString` — are
    * what produced a rendering.
    */
  implicit val renderErrA: Render[ErrA] = (a: ErrA) => s"errA:$a"
  implicit val renderErrB: Render[ErrB] = (a: ErrB) => s"errB:$a"
  implicit val renderTestError: Render[TestError] = (a: TestError) => s"testError:$a"
}

/** The fixture algebra. Shapes are fixed: a capability with a strict parameter,
  * a by-name parameter with a different error type, a capability-free method,
  * multiple parameter lists, and two capabilities on one method.
  */
trait TestAlg[F[_]] {
  def a(i: Int)(implicit R: Raise[F, ErrA]): F[String]
  def b(x: String, y: => Int)(implicit R: Raise[F, ErrB]): F[Int]
  def c(i: Int): F[Int]
  def d(i: Int)(j: Int)(implicit R: Raise[F, ErrA]): F[Int]
  def e(implicit R1: Raise[F, ErrA], R2: Raise[F, ErrB]): F[Unit]
}

/** Capability-free algebra, used by law L9 (conservative extension). */
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

/** Same behavior as `EitherTestAlg`, generic in `F` so it can be instantiated
  * at `Lazily` wherever a test needs genuine `Sync` capability (recording,
  * counting) alongside `EitherTestAlg`'s exact shape. `EitherTestAlg` itself
  * stays fixed to `Either` — this is additive, not a replacement.
  */
final class GenericTestAlg[F[_]](eOutcome: Int)(implicit F: Applicative[F]) extends TestAlg[F] {
  import TestError._

  def a(i: Int)(implicit R: Raise[F, ErrA]): F[String] =
    if (i < 0) R.raise(NegativeInput(i)) else s"a:$i".pure[F]

  def b(x: String, y: => Int)(implicit R: Raise[F, ErrB]): F[Int] =
    if (x.isEmpty) R.raise(EmptyInput("x")) else (x.length + y).pure[F]

  def c(i: Int): F[Int] = (i * 2).pure[F]

  def d(i: Int)(j: Int)(implicit R: Raise[F, ErrA]): F[Int] =
    if (i + j < 0) R.raise(NegativeInput(i + j)) else (i + j).pure[F]

  def e(implicit R1: Raise[F, ErrA], R2: Raise[F, ErrB]): F[Unit] =
    if (eOutcome < 0) R1.raise(NegativeInput(eOutcome))
    else if (eOutcome > 0) R2.raise(EmptyInput("e"))
    else ().pure[F]
}

/** Same behavior as `EitherPlainAlg`, generic in `F`. */
final class GenericPlainAlg[F[_]](implicit F: ApplicativeError[F, TestError]) extends PlainAlg[F] {
  def p(i: Int): F[String] =
    if (i < 0) TestError.NegativeInput(i).raiseError[F, String] else s"p:$i".pure[F]
}
