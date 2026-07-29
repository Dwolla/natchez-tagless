package com.dwolla.tagless.mtl
package laws

import cats.Functor
import cats.mtl.Raise
import cats.syntax.all._
import cats.tagless.aop.Aspect
import munit.FunSuite

import scala.annotation.experimental

import LawsInstances._
import TestError._

/** Task 7 on Scala 3 — the same shapes M3 covers, plus an abstract `val`
  * returning `F[A]`, which derives correctly here and does ''not'' on Scala 2.
  * Upstream's Scala 3 `transformTo` handles `transformVal` and
  * `overridableMembers` includes `fieldMembers`; the Scala 2 machinery filters
  * accessors, so the member is never implemented there.
  */
trait ParentAlg[F[_]]:
  def inherited(i: Int)(using R: Raise[F, ErrA]): F[String]

trait EdgeAlg[F[_]] extends ParentAlg[F]:
  def own(i: Int): F[Int]
  def nullary: F[Int]
  def overloaded(i: Int): F[String]
  def overloaded(s: String): F[String]
  val constant: F[String]

object EdgeAlg:
  def either: EdgeAlg[LawsInstances.Result] = new EdgeAlg[LawsInstances.Result]:
    def inherited(i: Int)(using R: Raise[Result, ErrA]): Result[String] =
      if i < 0 then R.raise(NegativeInput(i)) else Right(s"inherited:$i")
    def own(i: Int): Result[Int] = Right(i * 3)
    def nullary: Result[Int] = Right(42)
    def overloaded(i: Int): Result[String] = Right(s"int:$i")
    def overloaded(s: String): Result[String] = Right(s"string:$s")
    val constant: Result[String] = Right("constant")

@experimental
class EdgeCaseDerivationSpec extends FunSuite:

  private val derived: RaiseAspect[EdgeAlg, Render, Render] =
    DeriveRaise.aspect[EdgeAlg, Render, Render]

  private val impl = EdgeAlg.either
  private val woven: EdgeAlg[Aspect.Weave[Result, Render, Render, *]] =
    derived.weave(impl)(Functor[Result])

  test("a capability method inherited from a parent trait is woven") {
    val rendered = WeaveRenderer.render(woven.inherited(2))
    assertEquals(rendered.algebraName, "EdgeAlg")
    assertEquals(rendered.methodName, "inherited")
    assertEquals(rendered.domain, List(List("i" -> "2")))
    assertEquals(woven.inherited(2).codomain.target, impl.inherited(2)(using raiseResult))
  }

  test("the inherited capability is transported, so raises survive erasure") {
    val erased = derived.mapK(woven)(WeaveArrows.eraseWeave[Result, Render, Render])
    assertEquals(erased.inherited(-4)(using raiseResult), NegativeInput(-4).asLeft[String].leftWiden[TestError])
    assertEquals(erased.inherited(-4)(using raiseResult), impl.inherited(-4)(using raiseResult))
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

  test("an abstract val returning F[A] is woven, unlike on Scala 2") {
    val rendered = WeaveRenderer.render(woven.constant)
    assertEquals(rendered.methodName, "constant")
    assertEquals(rendered.domain, List.empty[List[(String, String)]])
    assertEquals(woven.constant.codomain.target, "constant".asRight[TestError])
  }

  test("a capability-free method on the same algebra is woven unchanged") {
    assertEquals(WeaveRenderer.render(woven.own(5)).domain, List(List("i" -> "5")))
    assertEquals(woven.own(5).codomain.target, 15.asRight[TestError])
  }
