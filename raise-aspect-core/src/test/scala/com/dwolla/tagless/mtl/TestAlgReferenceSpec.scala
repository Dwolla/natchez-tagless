package com.dwolla.tagless.mtl

import cats.Functor
import cats.mtl.Raise
import cats.tagless.aop.Aspect
import cats.syntax.all._
import munit.FunSuite

import TestError._

/** Smoke tests for the hand-written reference instance. The full law suite is
  * M2; these pin the expansion spec's structure and the erasure round trip.
  */
class TestAlgReferenceSpec extends FunSuite {
  private type F[A] = Either[TestError, A]
  private type W[A] = Aspect.Weave[F, Render, Render, A]

  private implicit val syntheticRender: Synthetic[Render] =
    new Synthetic[Render] {
      def apply[A]: Render[A] = (_: A) => "<synthetic>"
    }

  private val raiseF: Raise[F, TestError] = Raise[F, TestError]
  private val raiseW: Raise[W, TestError] =
    WeaveArrows.raiseLift[F, Render, Render, Render].apply(raiseF)

  private val ref: RaiseAspect[TestAlg, Render, Render, Render] =
    TestAlgReference.referenceRaiseAspect[Render, Render, Render]

  private def wovenOf(eOutcome: Int): TestAlg[W] =
    ref.weave(new EitherTestAlg(eOutcome))(Functor[F])

  private def erasedOf(eOutcome: Int): TestAlg[F] =
    ref.mapK(wovenOf(eOutcome))(WeaveArrows.eraseWeave[F, Render, Render, Render])

  // ---------------------------------------------------------------- erasure

  test("weave then erase agrees with the underlying algebra on success inputs") {
    val impl = new EitherTestAlg(0)
    val erased = erasedOf(0)

    assertEquals(erased.a(3)(raiseF), impl.a(3)(raiseF))
    assertEquals(erased.b("xy", 5)(raiseF), impl.b("xy", 5)(raiseF))
    assertEquals(erased.c(4), impl.c(4))
    assertEquals(erased.d(2)(3)(raiseF), impl.d(2)(3)(raiseF))
    assertEquals(erased.e(raiseF, raiseF), impl.e(raiseF, raiseF))
  }

  test("weave then erase returns the identical Left on raising inputs") {
    val impl = new EitherTestAlg(-1)
    val erased = erasedOf(-1)

    assertEquals(erased.a(-3)(raiseF), NegativeInput(-3).asLeft[String].leftWiden[TestError])
    assertEquals(erased.a(-3)(raiseF), impl.a(-3)(raiseF))
    assertEquals(erased.b("", 5)(raiseF), impl.b("", 5)(raiseF))
    assertEquals(erased.d(-2)(-3)(raiseF), impl.d(-2)(-3)(raiseF))
    assertEquals(erased.e(raiseF, raiseF), impl.e(raiseF, raiseF))
  }

  test("weave then erase routes each capability independently when a method has two") {
    assertEquals(erasedOf(1).e(raiseF, raiseF), EmptyInput("e").asLeft[Unit].leftWiden[TestError])
    assertEquals(erasedOf(-1).e(raiseF, raiseF), NegativeInput(-1).asLeft[Unit].leftWiden[TestError])
    assertEquals(erasedOf(0).e(raiseF, raiseF), ().asRight[TestError])
  }

  test("mapK with the identity arrow leaves the algebra's behaviour unchanged") {
    val impl = new EitherTestAlg(0)
    val mapped = ref.mapK(impl)(RaiseArrow.id[F, Render])

    assertEquals(mapped.a(3)(raiseF), impl.a(3)(raiseF))
    assertEquals(mapped.a(-3)(raiseF), impl.a(-3)(raiseF))
    assertEquals(mapped.c(4), impl.c(4))
  }

  // ------------------------------------------------------- weave structure

  test("every woven method reports the algebra name") {
    val w = wovenOf(0)
    assertEquals(w.a(1)(raiseW).algebraName, "TestAlg")
    assertEquals(w.b("x", 1)(raiseW).algebraName, "TestAlg")
    assertEquals(w.c(1).algebraName, "TestAlg")
    assertEquals(w.d(1)(2)(raiseW).algebraName, "TestAlg")
    assertEquals(w.e(raiseW, raiseW).algebraName, "TestAlg")
  }

  test("the codomain advice is named after the method") {
    val w = wovenOf(0)
    assertEquals(w.a(1)(raiseW).codomain.name, "a")
    assertEquals(w.b("x", 1)(raiseW).codomain.name, "b")
    assertEquals(w.c(1).codomain.name, "c")
    assertEquals(w.d(1)(2)(raiseW).codomain.name, "d")
    assertEquals(w.e(raiseW, raiseW).codomain.name, "e")
  }

  test("the domain preserves parameter-list shape and advice names") {
    val w = wovenOf(0)
    assertEquals(names(w.a(1)(raiseW)), List(List("i")))
    assertEquals(names(w.b("x", 1)(raiseW)), List(List("x", "y")))
    assertEquals(names(w.c(1)), List(List("i")))
    assertEquals(names(w.d(1)(2)(raiseW)), List(List("i"), List("j")))
  }

  test("capability parameters are absent from the domain") {
    val w = wovenOf(0)
    // `e`'s only clause is entirely capabilities, so it contributes no clause.
    assertEquals(names(w.e(raiseW, raiseW)), List.empty[List[String]])
    assert(!names(w.a(1)(raiseW)).flatten.contains("R"))
    assert(!names(w.d(1)(2)(raiseW)).flatten.contains("R"))
  }

  test("strict parameters are captured eagerly and by-name parameters lazily") {
    val w = wovenOf(0)
    val weave = w.b("xy", 7)(raiseW)

    assertEquals(weave.domain.head.head.target.value, "xy": Any)
    assertEquals(weave.domain.head(1).target.value, 7: Any)
  }

  test("weaving does not force a by-name argument") {
    val w = wovenOf(0)
    // x is empty, so the underlying implementation raises without touching y.
    val weave = w.b("", throw new RuntimeException("by-name argument was forced"))(raiseW)

    assertEquals(weave.codomain.target, EmptyInput("x").asLeft[Int].leftWiden[TestError])
    intercept[RuntimeException](weave.domain.head(1).target.value)
  }

  test("the codomain target is the underlying call made with the pulled capability") {
    val impl = new EitherTestAlg(0)
    val w = ref.weave(impl)(Functor[F])

    assertEquals(w.a(3)(raiseW).codomain.target, impl.a(3)(raiseF))
    assertEquals(w.a(-3)(raiseW).codomain.target, impl.a(-3)(raiseF))
    assertEquals(w.c(4).codomain.target, impl.c(4))
  }

  private def names[A](w: W[A]): List[List[String]] =
    w.domain.map(_.map(_.name))
}
