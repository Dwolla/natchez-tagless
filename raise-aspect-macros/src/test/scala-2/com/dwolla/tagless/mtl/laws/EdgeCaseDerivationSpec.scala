package com.dwolla.tagless.mtl
package laws

import cats.mtl.Raise
import cats.syntax.all._
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
  private val recorder = new RecordingFk[Result, Render, Render]
  private val instrumented: EdgeAlg[Result] =
    derived.intercept(impl)(recorder.fk, OnRaise.noop[Result, Render])

  test("a capability method inherited from a parent trait is woven") {
    val out = instrumented.inherited(2)(raiseResult)
    val rendered = WeaveRenderer.render(recorder.weaves.last.weave)
    assertEquals(rendered.algebraName, "EdgeAlg")
    assertEquals(rendered.methodName, "inherited")
    assertEquals(rendered.domain, List(List("i" -> "2")))
    assertEquals(out, impl.inherited(2)(raiseResult))
  }

  test("the inherited capability is transported, so raises survive erasure") {
    assertEquals(instrumented.inherited(-4)(raiseResult), NegativeInput(-4).asLeft[String].leftWiden[TestError])
    assertEquals(instrumented.inherited(-4)(raiseResult), impl.inherited(-4)(raiseResult))
  }

  test("a nullary def returning F[A] is woven with an empty domain") {
    val out = instrumented.nullary
    val rendered = WeaveRenderer.render(recorder.weaves.last.weave)
    assertEquals(rendered.methodName, "nullary")
    assertEquals(rendered.domain, List.empty[List[(String, String)]])
    assertEquals(out, 42.asRight[TestError])
  }

  test("overloads are woven independently, each keeping its own parameter type") {
    val outInt = instrumented.overloaded(7)
    val renderedInt = WeaveRenderer.render(recorder.weaves.last.weave)
    val outString = instrumented.overloaded("z")
    val renderedString = WeaveRenderer.render(recorder.weaves.last.weave)

    assertEquals(renderedInt.domain, List(List("i" -> "7")))
    assertEquals(renderedString.domain, List(List("s" -> "z")))
    assertEquals(outInt, "int:7".asRight[TestError])
    assertEquals(outString, "string:z".asRight[TestError])
  }

  test("a capability-free method on the same algebra is woven unchanged") {
    val out = instrumented.own(5)
    assertEquals(WeaveRenderer.render(recorder.weaves.last.weave).domain, List(List("i" -> "5")))
    assertEquals(out, 15.asRight[TestError])
  }
}
