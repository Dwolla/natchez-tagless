package com.dwolla.tagless.mtl

import cats.data.EitherT
import cats.effect.{Ref, SyncIO}
import cats.mtl.Raise
import cats.syntax.all._
import munit.CatsEffectSuite

import CarrierArrows.Lazily
import SyncIOTestSyntax._
import TestError._

class TestAlgReferenceSpec extends CatsEffectSuite {
  private val raiseF: Raise[Lazily, TestError] = Raise[Lazily, TestError]

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
    def raisedThrough(eOutcome: Int, rendered: Ref[Lazily, Vector[String]]): Lazily[Unit] = {
      val hook: OnRaise[Lazily, Render] = new OnRaise[Lazily, Render] {
        def apply[E](e: E)(implicit ev: Render[E]): Lazily[Unit] = rendered.update(_ :+ ev.render(e))
      }

      for {
        recorder <- RecordingFk[Lazily, Render, Render]
        out <- ref.intercept(new GenericTestAlg[Lazily](eOutcome))(recorder.fk, hook).e(raiseF, raiseF)
      } yield out
    }

    (for {
      rendered <- Ref.of[Lazily, Vector[String]](Vector.empty)
      r1 <- EitherT.liftF[SyncIO, TestError, Either[TestError, Unit]](raisedThrough(-1, rendered).value)
      r2 <- EitherT.liftF[SyncIO, TestError, Either[TestError, Unit]](raisedThrough(1, rendered).value)
      r3 <- EitherT.liftF[SyncIO, TestError, Either[TestError, Unit]](raisedThrough(0, rendered).value)
      _ = assertEquals(r1, NegativeInput(-1).asLeft[Unit].leftWiden[TestError])
      _ = assertEquals(r2, EmptyInput("e").asLeft[Unit].leftWiden[TestError])
      _ = assertEquals(r3, ().asRight[TestError])
      seen <- rendered.get
    } yield assertEquals(seen.toList, List("errA:NegativeInput(-1)", "errB:EmptyInput(e)"))).runOrFail
  }
}
