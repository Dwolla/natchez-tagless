package com.dwolla.tagless.mtl
package laws

import cats.mtl.Raise
import cats.syntax.all._
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

  private val derived: RaiseAspect[EdgeAlg, Render, Render, Render] =
    DeriveRaise.aspect[EdgeAlg, Render, Render, Render]

  private val impl = EdgeAlg.either

  test("a capability method inherited from a parent trait is woven") {
    val recorder = new RecordingFk[Result, Render, Render]
    val instrumented = derived.intercept(impl)(recorder.fk, OnRaise.noop[Result, Render])

    val out = instrumented.inherited(2)(using raiseResult)
    val rendered = WeaveRenderer.render(recorder.weaves.last.weave)
    assertEquals(rendered.algebraName, "EdgeAlg")
    assertEquals(rendered.methodName, "inherited")
    assertEquals(rendered.domain, List(List("i" -> "2")))
    assertEquals(out, impl.inherited(2)(using raiseResult))
  }

  test("the inherited capability is transported, so raises survive erasure") {
    val recorder = new RecordingFk[Result, Render, Render]
    val instrumented = derived.intercept(impl)(recorder.fk, OnRaise.noop[Result, Render])

    assertEquals(
      instrumented.inherited(-4)(using raiseResult),
      NegativeInput(-4).asLeft[String].leftWiden[TestError]
    )
    assertEquals(instrumented.inherited(-4)(using raiseResult), impl.inherited(-4)(using raiseResult))
  }

  test("a nullary def returning F[A] is woven with an empty domain") {
    val recorder = new RecordingFk[Result, Render, Render]
    val instrumented = derived.intercept(impl)(recorder.fk, OnRaise.noop[Result, Render])

    val out = instrumented.nullary
    val rendered = WeaveRenderer.render(recorder.weaves.last.weave)
    assertEquals(rendered.methodName, "nullary")
    assertEquals(rendered.domain, List.empty[List[(String, String)]])
    assertEquals(out, 42.asRight[TestError])
  }

  test("overloads are woven independently, each keeping its own parameter type") {
    val recorder = new RecordingFk[Result, Render, Render]
    val instrumented = derived.intercept(impl)(recorder.fk, OnRaise.noop[Result, Render])

    val outInt = instrumented.overloaded(7)
    val renderedInt = WeaveRenderer.render(recorder.weaves.last.weave)
    val outString = instrumented.overloaded("z")
    val renderedString = WeaveRenderer.render(recorder.weaves.last.weave)

    assertEquals(renderedInt.domain, List(List("i" -> "7")))
    assertEquals(renderedString.domain, List(List("s" -> "z")))
    assertEquals(outInt, "int:7".asRight[TestError])
    assertEquals(outString, "string:z".asRight[TestError])
  }

  test("an abstract val returning F[A] is woven eagerly, at construction, unlike on Scala 2") {
    // `constant` is a `val`, not a `def`: its weave is built when `intercept`
    // constructs the instrumented algebra, not deferred until the field is
    // read. Today's path is already eager in the same way (a `val` member is
    // evaluated once, at construction), so nothing about *when* the weave
    // reaches `fk` changes — but this is the first place it's directly
    // observable, since a recording `fk` is watching.
    val recorder = new RecordingFk[Result, Render, Render]
    val instrumented = derived.intercept(impl)(recorder.fk, OnRaise.noop[Result, Render])

    assertEquals(recorder.weaves.map(_.weave.codomain.name), List("constant"))

    val out = instrumented.constant
    val rendered = WeaveRenderer.render(recorder.weaves.last.weave)
    assertEquals(rendered.methodName, "constant")
    assertEquals(rendered.domain, List.empty[List[(String, String)]])
    assertEquals(out, "constant".asRight[TestError])
    // reading the val again does not re-run the weave
    assertEquals(recorder.weaves.map(_.weave.codomain.name), List("constant"))
  }

  test("a capability-free method on the same algebra is woven unchanged") {
    val recorder = new RecordingFk[Result, Render, Render]
    val instrumented = derived.intercept(impl)(recorder.fk, OnRaise.noop[Result, Render])

    val out = instrumented.own(5)
    assertEquals(WeaveRenderer.render(recorder.weaves.last.weave).domain, List(List("i" -> "5")))
    assertEquals(out, 15.asRight[TestError])
  }
