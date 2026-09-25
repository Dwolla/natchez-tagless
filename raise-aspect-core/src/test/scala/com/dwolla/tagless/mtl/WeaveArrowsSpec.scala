package com.dwolla.tagless.mtl

import cats.tagless.aop.Aspect
import cats.syntax.all.*

class WeaveArrowsSpec extends munit.CatsEffectSuite {
  private type F[A] = Either[TestError, A]
  private type W[A] = Aspect.Weave[F, Render, Render, A]

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
}
