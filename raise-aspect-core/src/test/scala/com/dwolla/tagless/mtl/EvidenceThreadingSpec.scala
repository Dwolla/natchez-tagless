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

  test("the OnRaise hook renders the raised error through its Err evidence") {
    val rendered = ListBuffer.empty[String]

    val hook: OnRaise[F, Render] = new OnRaise[F, Render] {
      def apply[E](e: E)(implicit ev: Render[E]): F[Unit] = {
        rendered += ev.render(e)
        Right(())
      }
    }

    val decorated = RaiseAspect.observing[F, ErrA, Render](Raise[F, ErrA], hook)

    val out = decorated.raise[ErrA, Int](NegativeInput(-3))

    assertEquals(out, Left(NegativeInput(-3)): F[Int])
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

    val decoratedA = RaiseAspect.observing[F, ErrA, Render](Raise[F, ErrA], hook)
    val decoratedB = RaiseAspect.observing[F, ErrB, Render](Raise[F, ErrB], hook)

    decoratedA.raise[ErrA, Int](NegativeInput(-1))
    decoratedB.raise[ErrB, Int](EmptyInput("f"))

    assertEquals(rendered.toList, List("errA:NegativeInput(-1)", "errB:EmptyInput(f)"))
  }
}
