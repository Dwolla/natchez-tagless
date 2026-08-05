package com.dwolla.tagless.mtl

import cats.mtl.Raise
import cats.syntax.all._
import munit.FunSuite

import scala.collection.mutable.ListBuffer

import TestError._

class TestAlgReferenceSpec extends FunSuite {
  private type F[A] = Either[TestError, A]

  private val raiseF: Raise[F, TestError] = Raise[F, TestError]

  private val ref: RaiseAspect[TestAlg, Render, Render, Render] =
    TestAlgReference.referenceRaiseAspect[Render, Render, Render]

  /** The differential oracle only ever compares this instance ''against'' a
    * derived one, so a defect the two shared would be invisible there. `e` is
    * where that matters most: it is the only method with two capabilities,
    * and a comparison against the derived instance's return value alone
    * cannot tell which one was decorated — that value is identical whether
    * or not `RaiseAspect.observing` was applied to either. A hook rendering
    * through `Err[E]` separates them: `errA:` can only come from `R1`'s
    * decoration and `errB:` only from `R2`'s.
    */
  test("intercept decorates both of e's capabilities, each with its own Err evidence") {
    val rendered = ListBuffer.empty[String]

    val hook: OnRaise[F, Render] = new OnRaise[F, Render] {
      def apply[E](e: E)(implicit ev: Render[E]): F[Unit] = {
        rendered += ev.render(e)
        Right(())
      }
    }

    def raisedThrough(eOutcome: Int): F[Unit] = {
      val recorder = new RecordingFk[F, Render, Render]
      ref.intercept(new EitherTestAlg(eOutcome))(recorder.fk, hook).e(raiseF, raiseF)
    }

    assertEquals(raisedThrough(-1), NegativeInput(-1).asLeft[Unit].leftWiden[TestError])
    assertEquals(raisedThrough(1), EmptyInput("e").asLeft[Unit].leftWiden[TestError])
    assertEquals(raisedThrough(0), ().asRight[TestError])

    assertEquals(rendered.toList, List("errA:NegativeInput(-1)", "errB:EmptyInput(e)"))
  }
}
