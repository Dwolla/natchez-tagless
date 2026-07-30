package com.dwolla.tagless.mtl

import cats.mtl.Raise
import munit.FunSuite

import scala.collection.mutable.ListBuffer

import TestError._

/** The point of M10: the `Err` evidence for the raised error type reaches the
  * `OnRaise` hook, so a hook can render the error through a type class rather
  * than falling back to `toString`.
  */
class EvidenceThreadingSpec extends FunSuite {
  private type F[A] = Either[TestError, A]

  private implicit val syntheticRender: Synthetic[Render] =
    new Synthetic[Render] {
      def apply[A]: Render[A] = (_: A) => "<synthetic>"
    }

  test("the OnRaise hook renders the raised error through its Err evidence") {
    val rendered = ListBuffer.empty[String]

    val hook: OnRaise[F, Render] = new OnRaise[F, Render] {
      def apply[E](e: E)(implicit ev: Render[E]): F[Unit] = {
        rendered += ev.render(e)
        Right(())
      }
    }

    val lifted =
      WeaveArrows.raiseLift[F, Render, Render, Render](hook).apply(Raise[F, ErrA])

    val out = lifted.raise[ErrA, Int](NegativeInput(-3))

    assertEquals(out.codomain.target, Left(NegativeInput(-3)): F[Int])
    assertEquals(rendered.toList, List("errA:NegativeInput(-3)"))
  }

  test("a second error type on the same carrier gets its own evidence") {
    val rendered = ListBuffer.empty[String]

    val hook: OnRaise[F, Render] = new OnRaise[F, Render] {
      def apply[E](e: E)(implicit ev: Render[E]): F[Unit] = {
        rendered += ev.render(e)
        Right(())
      }
    }

    val liftA = WeaveArrows.raiseLift[F, Render, Render, Render](hook).apply(Raise[F, ErrA])
    val liftB = WeaveArrows.raiseLift[F, Render, Render, Render](hook).apply(Raise[F, ErrB])

    liftA.raise[ErrA, Int](NegativeInput(-1))
    liftB.raise[ErrB, Int](EmptyInput("f"))

    assertEquals(rendered.toList, List("errA:NegativeInput(-1)", "errB:EmptyInput(f)"))
  }
}
