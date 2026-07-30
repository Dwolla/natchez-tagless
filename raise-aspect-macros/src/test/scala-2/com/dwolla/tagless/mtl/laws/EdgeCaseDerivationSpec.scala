package com.dwolla.tagless.mtl
package laws

import cats.Functor
import cats.mtl.Raise
import cats.syntax.all._
import cats.tagless.aop.Aspect
import munit.FunSuite

import LawsInstances._
import TestError._

/** Task 7 — shapes M1's fixtures do not cover: members inherited from a parent
  * trait, a nullary def returning `F[A]`, and overloads.
  *
  * New fixtures in a new file; M1's and M2's sources are untouched.
  */
trait ParentAlg[F[_]] {
  def inherited(i: Int)(implicit R: Raise[F, ErrA]): F[String]
}

trait EdgeAlg[F[_]] extends ParentAlg[F] {
  def own(i: Int): F[Int]
  def nullary: F[Int]
  def overloaded(i: Int): F[String]
  def overloaded(s: String): F[String]
}

object EdgeAlg {
  def either: EdgeAlg[LawsInstances.Result] = new EdgeAlg[LawsInstances.Result] {
    def inherited(i: Int)(implicit R: Raise[LawsInstances.Result, ErrA]): LawsInstances.Result[String] =
      if (i < 0) R.raise(NegativeInput(i)) else Right(s"inherited:$i")
    def own(i: Int): LawsInstances.Result[Int] = Right(i * 3)
    def nullary: LawsInstances.Result[Int] = Right(42)
    def overloaded(i: Int): LawsInstances.Result[String] = Right(s"int:$i")
    def overloaded(s: String): LawsInstances.Result[String] = Right(s"string:$s")
  }
}

class EdgeCaseDerivationSpec extends FunSuite {

  private val derived: RaiseAspect[EdgeAlg, Render, Render, Render] =
    DeriveRaise.aspect[EdgeAlg, Render, Render, Render]

  private val impl = EdgeAlg.either
  private val woven: EdgeAlg[Aspect.Weave[Result, Render, Render, *]] =
    derived.weave(impl)(Functor[Result])

  test("a capability method inherited from a parent trait is woven") {
    assertEquals(WeaveRenderer.render(woven.inherited(2)(raiseWoven)).algebraName, "EdgeAlg")
    assertEquals(WeaveRenderer.render(woven.inherited(2)(raiseWoven)).methodName, "inherited")
    assertEquals(WeaveRenderer.render(woven.inherited(2)(raiseWoven)).domain, List(List("i" -> "2")))
    assertEquals(woven.inherited(2)(raiseWoven).codomain.target, impl.inherited(2)(raiseResult))
  }

  test("the inherited capability is transported, so raises survive erasure") {
    val erased = derived.mapK(woven)(WeaveArrows.eraseWeave[Result, Render, Render, Render])
    assertEquals(erased.inherited(-4)(raiseResult), NegativeInput(-4).asLeft[String].leftWiden[TestError])
    assertEquals(erased.inherited(-4)(raiseResult), impl.inherited(-4)(raiseResult))
  }

  test("a nullary def returning F[A] is woven with an empty domain") {
    val rendered = WeaveRenderer.render(woven.nullary)
    assertEquals(rendered.methodName, "nullary")
    assertEquals(rendered.domain, List.empty[List[(String, String)]])
    assertEquals(woven.nullary.codomain.target, 42.asRight[TestError])
  }

  test("overloads are woven independently, each keeping its own parameter type") {
    assertEquals(WeaveRenderer.render(woven.overloaded(7)).domain, List(List("i" -> "7")))
    assertEquals(WeaveRenderer.render(woven.overloaded("z")).domain, List(List("s" -> "z")))
    assertEquals(woven.overloaded(7).codomain.target, "int:7".asRight[TestError])
    assertEquals(woven.overloaded("z").codomain.target, "string:z".asRight[TestError])
  }

  test("a capability-free method on the same algebra is woven unchanged") {
    assertEquals(WeaveRenderer.render(woven.own(5)).domain, List(List("i" -> "5")))
    assertEquals(woven.own(5).codomain.target, 15.asRight[TestError])
  }
}
