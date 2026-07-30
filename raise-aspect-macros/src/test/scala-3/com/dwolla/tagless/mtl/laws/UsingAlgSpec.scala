package com.dwolla.tagless.mtl
package laws

import cats.Functor
import cats.mtl.Raise
import cats.syntax.all._
import munit.FunSuite

import scala.annotation.experimental

import LawsInstances._
import TestError._

/** Task 3 — `using` clauses.
  *
  * `UsingAlg` mirrors `TestAlg` exactly, written with `using` rather than
  * `implicit`, so its derived renderings must equal the same shared expected
  * values. `MultiUsingAlg` covers something Scala 2 cannot even express: two
  * separate `using` clauses on one method. Upstream's Scala 3 machinery filters
  * *every* given/implicit clause, where the Scala 2 macro only inspects the last —
  * this is the fixture that proves it.
  */
trait UsingAlg[F[_]]:
  def a(i: Int)(using R: Raise[F, ErrA]): F[String]
  def b(x: String, y: => Int)(using R: Raise[F, ErrB]): F[Int]
  def c(i: Int): F[Int]
  def d(i: Int)(j: Int)(using R: Raise[F, ErrA]): F[Int]
  def e(using R1: Raise[F, ErrA], R2: Raise[F, ErrB]): F[Unit]

trait MultiUsingAlg[F[_]]:
  def m(i: Int)(using R1: Raise[F, ErrA])(using R2: Raise[F, ErrB]): F[String]

object UsingAlg:
  /** Behaviourally identical to `EitherTestAlg`, so renderings must match too. */
  def either(eOutcome: Int): UsingAlg[LawsInstances.Result] = new UsingAlg[LawsInstances.Result]:
    def a(i: Int)(using R: Raise[Result, ErrA]): Result[String] =
      if i < 0 then R.raise(NegativeInput(i)) else Right(s"a:$i")
    def b(x: String, y: => Int)(using R: Raise[Result, ErrB]): Result[Int] =
      if x.isEmpty then R.raise(EmptyInput("x")) else Right(x.length + y)
    def c(i: Int): Result[Int] = Right(i * 2)
    def d(i: Int)(j: Int)(using R: Raise[Result, ErrA]): Result[Int] =
      if i + j < 0 then R.raise(NegativeInput(i + j)) else Right(i + j)
    def e(using R1: Raise[Result, ErrA], R2: Raise[Result, ErrB]): Result[Unit] =
      if eOutcome < 0 then R1.raise(NegativeInput(eOutcome))
      else if eOutcome > 0 then R2.raise(EmptyInput("e"))
      else Right(())

object MultiUsingAlg:
  def either: MultiUsingAlg[LawsInstances.Result] = new MultiUsingAlg[LawsInstances.Result]:
    def m(i: Int)(using R1: Raise[Result, ErrA])(using R2: Raise[Result, ErrB]): Result[String] =
      if i < 0 then R1.raise(NegativeInput(i)) else Right(s"m:$i")

@experimental
class UsingAlgSpec extends FunSuite:

  private val derived: RaiseAspect[UsingAlg, Render, Render, Render] =
    DeriveRaise.aspect[UsingAlg, Render, Render, Render]

  private val multi: RaiseAspect[MultiUsingAlg, Render, Render, Render] =
    DeriveRaise.aspect[MultiUsingAlg, Render, Render, Render]

  test("a using-based algebra renders exactly like the implicit-based TestAlg") {
    val woven = derived.weave(UsingAlg.either(0))(Functor[Result])
    val rendered = List(
      WeaveRenderer.render(woven.a(7)),
      WeaveRenderer.render(woven.b("ab", 2)),
      WeaveRenderer.render(woven.c(3)),
      WeaveRenderer.render(woven.d(4)(5)),
      WeaveRenderer.render(woven.e)
    )
    // the same expected values TestAlg produces, modulo the algebra name
    assertEquals(rendered.map(_.copy(algebraName = "TestAlg")), ExpectedWeaves.expected)
    assert(rendered.forall(_.algebraName == "UsingAlg"))
  }

  test("a using-based algebra survives the erasure round trip, raising included") {
    val impl = UsingAlg.either(1)
    val erased =
      derived.mapK(derived.weave(impl)(Functor[Result]))(WeaveArrows.eraseWeave[Result, Render, Render, Render])

    assertEquals(erased.a(3), impl.a(3))
    assertEquals(erased.a(-3), impl.a(-3))
    assertEquals(erased.b("", 1), impl.b("", 1))
    assertEquals(erased.c(4), impl.c(4))
    assertEquals(erased.d(-2)(-3), impl.d(-2)(-3))
    assertEquals(erased.e, impl.e)
    assertEquals(erased.e, EmptyInput("e").asLeft[Unit].leftWiden[TestError])
  }

  test("two separate using clauses are both dropped from the domain") {
    val woven = multi.weave(MultiUsingAlg.either)(Functor[Result])
    val rendered = WeaveRenderer.render(woven.m(7))

    assertEquals(rendered.algebraName, "MultiUsingAlg")
    assertEquals(rendered.methodName, "m")
    assertEquals(rendered.domain, List(List("i" -> "7")))
  }

  test("both capabilities from separate using clauses are transported") {
    val impl = MultiUsingAlg.either
    val erased =
      multi.mapK(multi.weave(impl)(Functor[Result]))(WeaveArrows.eraseWeave[Result, Render, Render, Render])

    assertEquals(erased.m(5), impl.m(5))
    assertEquals(erased.m(-5), NegativeInput(-5).asLeft[String].leftWiden[TestError])
  }
