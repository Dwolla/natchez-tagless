package com.dwolla.tagless.mtl

import cats.effect.{Ref, SyncIO}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import com.dwolla.tagless.mtl.TestError.*
import munit.CatsEffectSuite

class EvidenceThreadingSpec extends CatsEffectSuite with HandleTestSyntax {
  // The single-error-type case (`observing` decorates `Raise[F, ErrA]`, the
  // hook renders `NegativeInput(-3)` as `"errA:NegativeInput(-3)"`) is covered
  // by `ObservingCapabilitySpec`'s "the hook renders the raised error through
  // its Err evidence, exactly once", assertion for assertion. This spec keeps
  // only the case that test doesn't cover: two error types on the same
  // carrier, each resolving its own `Err` evidence.
  testWithHandle[SyncIO, TestError]("a second error type on the same carrier gets its own evidence") { implicit H =>
    for {
      rendered <- Ref.of[SyncIO, Vector[String]](Vector.empty)
      hook = new OnRaise[SyncIO, Render] {
        def apply[E](e: E)(implicit ev: Render[E]): SyncIO[Unit] = rendered.update(_ :+ ev.render(e))
      }
      decoratedA = RaiseAspect.observing[SyncIO, ErrA, Render](Raise[SyncIO, ErrA], hook)
      decoratedB = RaiseAspect.observing[SyncIO, ErrB, Render](Raise[SyncIO, ErrB], hook)
      _ <- decoratedA.raise[ErrA, Int](NegativeInput(-1)).attemptHandle.void
      _ <- decoratedB.raise[ErrB, Int](EmptyInput("f")).attemptHandle.void
      seen <- rendered.get
    } yield assertEquals(seen.toList, List("errA:NegativeInput(-1)", "errB:EmptyInput(f)"))
  }
}
