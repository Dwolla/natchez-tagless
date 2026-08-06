package com.dwolla.tagless.mtl

import cats.data.EitherT
import cats.effect.{Ref, SyncIO}
import cats.mtl.Raise
import cats.tagless.aop.Aspect
import cats.~>
import munit.CatsEffectSuite

import CarrierArrows.Lazily
import SyncIOTestSyntax._
import TestError._

class WeaveInterpreterSpec extends CatsEffectSuite {
  private type F[A] = Either[TestError, A]
  private type W[A] = Aspect.Weave[F, Render, Render, A]

  // The library's own forgetful arrow, rather than a hand-rolled one — the
  // interpreter a caller supplies in production is this shape.
  private val erase: W ~> F = WeaveArrows.codomainTarget[F, Render, Render]

  test("an Aspect instance outranks a RaiseAspect instance for the same algebra") {
    import WeaveInterpreterFixtures._

    val interpreted =
      WeaveInterpreter[PlainAlg, Render, Render, Render, F]
        .apply(EitherPlainAlg)(erase, OnRaise.noop[F, Render])

    // The poison RaiseAspect throws on any use, so reaching a result at all
    // proves the Aspect path ran.
    assertEquals(interpreted.p(2), Right("p:2"))
  }

  test("a RaiseAspect-only algebra resolves to the RaiseAspect instance and runs the hook") {
    implicit val reference: RaiseAspect[TestAlg, Render, Render, Render] =
      TestAlgReference.referenceRaiseAspect[Render, Render, Render]

    type WLazily[A] = Aspect.Weave[Lazily, Render, Render, A]
    val eraseLazily: WLazily ~> Lazily = WeaveArrows.codomainTarget[Lazily, Render, Render]

    (for {
      rendered <- Ref.of[Lazily, Vector[String]](Vector.empty)
      hook = new OnRaise[Lazily, Render] {
        def apply[E](e: E)(implicit ev: Render[E]): Lazily[Unit] = rendered.update(_ :+ ev.render(e))
      }
      interpreted =
        WeaveInterpreter[TestAlg, Render, Render, Render, Lazily]
          .apply(new GenericTestAlg[Lazily](0))(eraseLazily, hook)
      r1 <- EitherT.liftF[SyncIO, TestError, Either[TestError, String]](interpreted.a(3)(Raise[Lazily, ErrA]).value)
      _ = assertEquals(r1, Right("a:3"))
      seen1 <- rendered.get
      _ = assertEquals(seen1.toList, Nil)
      r2 <- EitherT.liftF[SyncIO, TestError, Either[TestError, String]](interpreted.a(-3)(Raise[Lazily, ErrA]).value)
      _ = assertEquals(r2, Left(NegativeInput(-3)): F[String])
      seen2 <- rendered.get
      _ = assertEquals(seen2.toList, List("errA:NegativeInput(-3)"))
    } yield ()).runOrFail
  }
}
