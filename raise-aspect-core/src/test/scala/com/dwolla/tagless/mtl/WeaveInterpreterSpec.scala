package com.dwolla.tagless.mtl

import cats.*
import cats.effect.{Ref, SyncIO}
import cats.mtl.syntax.all.*
import cats.tagless.aop.Aspect
import com.dwolla.tagless.mtl.TestError.*
import munit.CatsEffectSuite

class WeaveInterpreterSpec extends CatsEffectSuite with HandleTestSyntax {
  private type F[A] = Either[TestError, A]
  private type W[A] = Aspect.Weave[F, Render, Render, A]

  // The library's own forgetful arrow, rather than a hand-rolled one — the
  // interpreter a caller supplies in production is this shape.
  private val erase: W ~> F = WeaveArrows.codomainTarget[F, Render, Render]

  test("an Aspect instance outranks a RaiseAspect instance for the same algebra") {
    import WeaveInterpreterFixtures.*

    val interpreted =
      WeaveInterpreter[PlainAlg, Render, Render, Render, F]
        .apply(EitherPlainAlg)(erase, OnRaise.noop[F, Render])

    // The poison RaiseAspect throws on any use, so reaching a result at all
    // proves the Aspect path ran.
    assertEquals(interpreted.p(2), Right("p:2"))
  }

  testWithHandle[SyncIO, TestError]("a RaiseAspect-only algebra resolves to the RaiseAspect instance and runs the hook") { implicit H =>
    implicit val reference: RaiseAspect[TestAlg, Render, Render, Render] =
      TestAlgReference.referenceRaiseAspect[Render, Render, Render]

    type WSyncIO[A] = Aspect.Weave[SyncIO, Render, Render, A]
    val eraseSyncIO: WSyncIO ~> SyncIO = WeaveArrows.codomainTarget[SyncIO, Render, Render]

    for {
      rendered <- Ref.of[SyncIO, Vector[String]](Vector.empty)
      hook = new OnRaise[SyncIO, Render] {
        def apply[E](e: E)(implicit ev: Render[E]): SyncIO[Unit] = rendered.update(_ :+ ev.render(e))
      }
      interpreted =
        WeaveInterpreter[TestAlg, Render, Render, Render, SyncIO]
          .apply(new GenericTestAlg[SyncIO](0))(eraseSyncIO, hook)
      r1 <- interpreted.a(3).attemptHandle
      _ = assertEquals(r1, Right("a:3"))
      seen1 <- rendered.get
      _ = assertEquals(seen1.toList, Nil)
      r2 <- interpreted.a(-3).attemptHandle
      _ = assertEquals(r2, Left(NegativeInput(-3)): F[String])
      seen2 <- rendered.get
      _ = assertEquals(seen2.toList, List("errA:NegativeInput(-3)"))
    } yield ()
  }
}
