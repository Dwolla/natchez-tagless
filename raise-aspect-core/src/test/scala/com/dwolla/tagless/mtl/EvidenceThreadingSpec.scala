package com.dwolla.tagless.mtl

import cats.data.EitherT
import cats.effect.{Ref, SyncIO}
import cats.mtl.Raise
import munit.CatsEffectSuite

import CarrierArrows.Lazily
import SyncIOTestSyntax._
import TestError._

class EvidenceThreadingSpec extends CatsEffectSuite {
  // The single-error-type case (`observing` decorates `Raise[F, ErrA]`, the
  // hook renders `NegativeInput(-3)` as `"errA:NegativeInput(-3)"`) is covered
  // by `ObservingCapabilitySpec`'s "the hook renders the raised error through
  // its Err evidence, exactly once", assertion for assertion. This spec keeps
  // only the case that test doesn't cover: two error types on the same
  // carrier, each resolving its own `Err` evidence.
  test("a second error type on the same carrier gets its own evidence") {
    (for {
      rendered <- Ref.of[Lazily, Vector[String]](Vector.empty)
      hook = new OnRaise[Lazily, Render] {
        def apply[E](e: E)(implicit ev: Render[E]): Lazily[Unit] = rendered.update(_ :+ ev.render(e))
      }
      decoratedA = RaiseAspect.observing[Lazily, ErrA, Render](Raise[Lazily, ErrA], hook)
      decoratedB = RaiseAspect.observing[Lazily, ErrB, Render](Raise[Lazily, ErrB], hook)
      _ <- EitherT.liftF[SyncIO, TestError, Unit](decoratedA.raise[ErrA, Int](NegativeInput(-1)).value.void)
      _ <- EitherT.liftF[SyncIO, TestError, Unit](decoratedB.raise[ErrB, Int](EmptyInput("f")).value.void)
      seen <- rendered.get
    } yield assertEquals(seen.toList, List("errA:NegativeInput(-1)", "errB:EmptyInput(f)"))).runOrFail
  }
}
