package com.dwolla.tagless.mtl

import cats.effect.{Ref, SyncIO}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import com.dwolla.tagless.mtl.TestError.*
import munit.CatsEffectSuite

class TestAlgReferenceSpec extends CatsEffectSuite with HandleTestSyntax {
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
  testWithHandle[SyncIO, TestError]("intercept decorates both of e's capabilities, each with its own Err evidence") { implicit H =>
    def raisedThrough(eOutcome: Int, rendered: Ref[SyncIO, Vector[String]]): SyncIO[Unit] = {
      val hook: OnRaise[SyncIO, Render] = new OnRaise[SyncIO, Render] {
        def apply[E](e: E)(implicit ev: Render[E]): SyncIO[Unit] = rendered.update(_ :+ ev.render(e))
      }

      for {
        recorder <- RecordingFk[SyncIO, Render, Render]
        out <- ref.intercept(new GenericTestAlg[SyncIO](eOutcome))(recorder.fk, hook).e
      } yield out
    }

    for {
      rendered <- Ref.of[SyncIO, Vector[String]](Vector.empty)
      r1 <- raisedThrough(-1, rendered).attemptHandle
      r2 <- raisedThrough(1, rendered).attemptHandle
      r3 <- raisedThrough(0, rendered).attemptHandle
      _ = assertEquals(r1, NegativeInput(-1).asLeft[Unit].leftWiden[TestError])
      _ = assertEquals(r2, EmptyInput("e").asLeft[Unit].leftWiden[TestError])
      _ = assertEquals(r3, ().asRight[TestError])
      seen <- rendered.get
    } yield assertEquals(seen.toList, List("errA:NegativeInput(-1)", "errB:EmptyInput(e)"))
  }
}
