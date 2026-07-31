package com.dwolla.tagless.mtl

import cats.mtl.Raise
import cats.syntax.all._
import munit.FunSuite

import TestError._

/** Smoke tests for the hand-written reference instance. The full law suite is
  * M2; these pin the expansion spec's structure and the erasure round trip.
  */
class TestAlgReferenceSpec extends FunSuite {
  private type F[A] = Either[TestError, A]

  private val raiseF: Raise[F, TestError] = Raise[F, TestError]

  private val ref: RaiseAspect[TestAlg, Render, Render, Render] =
    TestAlgReference.referenceRaiseAspect[Render, Render, Render]

  /** The fused analogue of the old `weave`-then-`mapK` pair: intercept with a
    * recording interpreter, then read the recorded weaves. `codomainTarget`'s
    * behaviour is what `RecordingFk` forwards, so the returned algebra is the
    * erased one and the recorder holds the structure.
    */
  private def interceptedOf(eOutcome: Int): (TestAlg[F], RecordingFk[F, Render, Render]) = {
    val recorder = new RecordingFk[F, Render, Render]
    (ref.intercept(new EitherTestAlg(eOutcome))(recorder.fk, OnRaise.noop[F, Render]), recorder)
  }

  private def erasedOf(eOutcome: Int): TestAlg[F] = interceptedOf(eOutcome)._1

  // ---------------------------------------------------------------- erasure

  test("intercept then erase agrees with the underlying algebra on success inputs") {
    val impl = new EitherTestAlg(0)
    val erased = erasedOf(0)

    assertEquals(erased.a(3)(raiseF), impl.a(3)(raiseF))
    assertEquals(erased.b("xy", 5)(raiseF), impl.b("xy", 5)(raiseF))
    assertEquals(erased.c(4), impl.c(4))
    assertEquals(erased.d(2)(3)(raiseF), impl.d(2)(3)(raiseF))
    assertEquals(erased.e(raiseF, raiseF), impl.e(raiseF, raiseF))
  }

  test("intercept then erase returns the identical Left on raising inputs") {
    val impl = new EitherTestAlg(-1)
    val erased = erasedOf(-1)

    assertEquals(erased.a(-3)(raiseF), NegativeInput(-3).asLeft[String].leftWiden[TestError])
    assertEquals(erased.a(-3)(raiseF), impl.a(-3)(raiseF))
    assertEquals(erased.b("", 5)(raiseF), impl.b("", 5)(raiseF))
    assertEquals(erased.d(-2)(-3)(raiseF), impl.d(-2)(-3)(raiseF))
    assertEquals(erased.e(raiseF, raiseF), impl.e(raiseF, raiseF))
  }

  test("intercept then erase routes each capability independently when a method has two") {
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
    val (w, recorder) = interceptedOf(0)

    w.a(1)(raiseF)
    w.b("x", 1)(raiseF)
    w.c(1)
    w.d(1)(2)(raiseF)
    w.e(raiseF, raiseF)

    assertEquals(recorder.weaves.map(_.weave.algebraName), List.fill(5)("TestAlg"))
    assertEquals(recorder.weaves.map(_.weave.codomain.name), List("a", "b", "c", "d", "e"))
  }

  test("the domain preserves parameter-list shape and advice names") {
    val (w, recorder) = interceptedOf(0)

    w.a(1)(raiseF)
    w.b("x", 1)(raiseF)
    w.c(1)
    w.d(1)(2)(raiseF)

    assertEquals(
      recorder.weaves.map(names),
      List(
        List(List("i")),
        List(List("x", "y")),
        List(List("i")),
        List(List("i"), List("j"))
      )
    )
  }

  test("capability parameters are absent from the domain") {
    val (w, recorder) = interceptedOf(0)

    w.e(raiseF, raiseF)
    w.a(1)(raiseF)
    w.d(1)(2)(raiseF)

    val domains = recorder.weaves.map(names)
    // `e`'s only clause is entirely capabilities, so it contributes no clause.
    assertEquals(domains.head, List.empty[List[String]])
    assert(!domains(1).flatten.contains("R"))
    assert(!domains(2).flatten.contains("R"))
  }

  test("strict parameters are captured eagerly and by-name parameters lazily") {
    val (w, recorder) = interceptedOf(0)

    val _ = w.b("xy", 7)(raiseF)
    val weave = recorder.weaves.head.weave

    assertEquals(weave.domain.head.head.target.value, "xy": Any)
    assertEquals(weave.domain.head(1).target.value, 7: Any)
  }

  test("weaving does not force a by-name argument") {
    val (w, recorder) = interceptedOf(0)

    // x is empty, so the underlying implementation raises without touching y.
    val out = w.b("", throw new RuntimeException("by-name argument was forced"))(raiseF)

    assertEquals(out, EmptyInput("x").asLeft[Int].leftWiden[TestError])
    intercept[RuntimeException](recorder.weaves.head.weave.domain.head(1).target.value)
  }

  test("an intercepted method returns exactly what the underlying call returns") {
    val impl = new EitherTestAlg(0)
    val (w, _) = interceptedOf(0)

    assertEquals(w.a(3)(raiseF), impl.a(3)(raiseF))
    assertEquals(w.a(-3)(raiseF), impl.a(-3)(raiseF))
    assertEquals(w.c(4), impl.c(4))
  }

  private def names(rw: RecordedWeave[F, Render, Render]): List[List[String]] =
    rw.weave.domain.map(_.map(_.name))
}
