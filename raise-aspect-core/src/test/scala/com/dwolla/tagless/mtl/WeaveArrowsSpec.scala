package com.dwolla.tagless.mtl

import cats.tagless.aop.Aspect
import cats.mtl.Raise
import cats.syntax.all.*
import cats.mtl.syntax.all.*
import TestError.*
import cats.effect.SyncIO

class WeaveArrowsSpec extends munit.CatsEffectSuite with HandleTestSyntax {
  private type F[A] = Either[TestError, A]
  private type W[A] = Aspect.Weave[F, Render, Render, A]

  private val raiseF: Raise[F, TestError] = Raise[F, TestError]

  private def weaveOf[A: Render](target: F[A]): W[A] =
    Aspect.Weave[F, Render, Render, A](
      "TestAlg",
      List(List(Aspect.Advice.byValue[Render, Int]("i", 1))),
      Aspect.Advice[F, Render, A]("m", target)
    )

  test("codomainTarget forgets the metadata and yields the codomain target") {
    val w = weaveOf(3.asRight[TestError])
    assertEquals(WeaveArrows.codomainTarget[F, Render, Render].apply(w), 3.asRight[TestError])
  }

  test("RaisePull.id returns the capability unchanged") {
    assert(RaisePull.id[F, Render].apply(raiseF) eq raiseF)
  }

  test("RaiseArrow.id maps values and capabilities unchanged") {
    val arrow = RaiseArrow.id[F, Render]
    assertEquals(arrow.fk(3.asRight[TestError]), 3.asRight[TestError])
    assert(arrow.pull(raiseF) eq raiseF)
  }

  testWithHandle[SyncIO, TestError]("RaiseArrow.andThen sends values forward and capabilities backward") { implicit H =>
    val arrow = CarrierArrows.resultToSyncIO[Render].andThen(RaiseArrow.id[SyncIO, Render])
    val err = NegativeInput(-4)

    arrow.fk(5.asRight[TestError]).attemptHandle.map { v =>
      assertEquals(v, 5.asRight[TestError])
      assertEquals(
        arrow.pull(Raise[SyncIO, TestError]).raise[NegativeInput, Int](err),
        err.asLeft[Int].leftWiden[TestError]
      )
    }
  }
}
