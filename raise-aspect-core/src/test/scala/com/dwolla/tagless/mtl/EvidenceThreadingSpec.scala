package com.dwolla.tagless.mtl

import cats.mtl.Raise
import munit.FunSuite

import scala.collection.mutable.ListBuffer

import TestError._

class EvidenceThreadingSpec extends FunSuite {
  private type F[A] = Either[TestError, A]

  // The single-error-type case (`observing` decorates `Raise[F, ErrA]`, the
  // hook renders `NegativeInput(-3)` as `"errA:NegativeInput(-3)"`) is covered
  // by `ObservingCapabilitySpec`'s "the hook renders the raised error through
  // its Err evidence, exactly once", assertion for assertion. This spec keeps
  // only the case that test doesn't cover: two error types on the same
  // carrier, each resolving its own `Err` evidence.
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
