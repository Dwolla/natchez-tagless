package com.dwolla.tagless.mtl

import cats.tagless.Trivial
import cats.tagless.aop.Aspect
import cats.{Eval, Functor}
import cats.mtl.Raise
import cats.syntax.all._
import munit.FunSuite

import TestError._

class WeaveArrowsSpec extends FunSuite {
  private type F[A] = Either[TestError, A]
  private type W[A] = Aspect.Weave[F, Render, Render, A]

  private val F: Functor[F] = Functor[F]
  private val raiseF: Raise[F, TestError] = Raise[F, TestError]

  private implicit val syntheticRender: Synthetic[Render] =
    new Synthetic[Render] {
      def apply[A]: Render[A] = (_: A) => "<synthetic>"
    }

  private val liftedRaise: Raise[W, TestError] =
    WeaveArrows.raiseLift[F, Render, Render](F, syntheticRender).apply(raiseF)

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

  test("raisePull uses the ambient Functor[F], not the weave's functor") {
    val pulled = WeaveArrows.raisePull[F, Render, Render](F).apply(liftedRaise)
    assert(pulled.functor eq F, "raisePull must route through the ambient Functor[F] (law L7)")
  }

  test("raisePull raises into F by unwrapping the shell weave's codomain target") {
    val pulled = WeaveArrows.raisePull[F, Render, Render](F).apply(liftedRaise)
    val err = NegativeInput(-1)
    assertEquals(pulled.raise[NegativeInput, Int](err), err.asLeft[Int].leftWiden[TestError])
  }

  test("raiseLift builds a shell weave named \"raise\" with an empty domain") {
    val err = NegativeInput(-2)
    val shell = liftedRaise.raise[NegativeInput, Int](err)

    assertEquals(shell.algebraName, "raise")
    assertEquals(shell.domain, List.empty[List[Aspect.Advice[Eval, Render]]])
    assertEquals(shell.codomain.name, "raise")
    assertEquals(shell.codomain.target, err.asLeft[Int].leftWiden[TestError])
  }

  test("the synthesized weave functor maps the target and preserves the metadata (law L6)") {
    val w = weaveOf(3.asRight[TestError])
    val mapped = liftedRaise.functor.map(w)(_ + 1)

    assertEquals(mapped.codomain.target, 4.asRight[TestError])
    assertEquals(mapped.algebraName, w.algebraName)
    assertEquals(mapped.domain, w.domain)
    assertEquals(mapped.codomain.name, w.codomain.name)
  }

  test("raisePull after raiseLift is the identity on raised errors (law L5)") {
    val err = NegativeInput(-3)
    val roundTripped = WeaveArrows
      .raisePull[F, Render, Render](F)
      .apply(liftedRaise)
      .raise[NegativeInput, Int](err)

    assertEquals(roundTripped, raiseF.raise[NegativeInput, Int](err))
  }

  test("RaisePull.id returns the capability unchanged") {
    assert(RaisePull.id[F].apply(raiseF) eq raiseF)
  }

  test("RaiseArrow.id maps values and capabilities unchanged") {
    val arrow = RaiseArrow.id[F]
    assertEquals(arrow.fk(3.asRight[TestError]), 3.asRight[TestError])
    assert(arrow.pull(raiseF) eq raiseF)
  }

  test("RaiseArrow.andThen sends values forward and capabilities backward") {
    val arrow = WeaveArrows.eraseWeave[F, Render, Render].andThen(RaiseArrow.id[F])
    val err = NegativeInput(-4)

    assertEquals(arrow.fk(weaveOf(5.asRight[TestError])), 5.asRight[TestError])
    assertEquals(
      arrow.pull(raiseF).raise[NegativeInput, Int](err).codomain.target,
      err.asLeft[Int].leftWiden[TestError]
    )
  }

  test("eraseWeave pairs codomainTarget with raiseLift") {
    val arrow = WeaveArrows.eraseWeave[F, Render, Render]
    assertEquals(arrow.fk(weaveOf("x".asRight[TestError])), "x".asRight[TestError])
    assertEquals(arrow.pull(raiseF).raise[NegativeInput, Int](NegativeInput(-5)).codomain.name, "raise")
  }

  test("Synthetic[Trivial] supplies an instance for any type") {
    assertEquals(Synthetic[Trivial].apply[Int], Trivial.instance[Int])
  }
}
