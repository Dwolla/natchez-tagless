package com.dwolla.tagless.mtl
package laws

import cats.mtl.Raise
import cats.syntax.all._
import munit.FunSuite

import scala.annotation.experimental
import scala.collection.mutable.ListBuffer

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
    val recorder = new RecordingFk[Result, Render, Render]
    val instrumented = derived.intercept(UsingAlg.either(0))(recorder.fk, OnRaise.noop[Result, Render])

    instrumented.a(7)(using raiseResult)
    instrumented.b("ab", 2)(using raiseResult)
    instrumented.c(3)
    instrumented.d(4)(5)(using raiseResult)
    instrumented.e(using raiseResult, raiseResult)

    val rendered = recorder.weaves.map(r => WeaveRenderer.render(r.weave))
    // the same expected values TestAlg produces, modulo the algebra name
    assertEquals(rendered.map(_.copy(algebraName = "TestAlg")), ExpectedWeaves.expected)
    assert(rendered.forall(_.algebraName == "UsingAlg"))
  }

  test("a using-based algebra transports every capability, raising included") {
    val recorder = new RecordingFk[Result, Render, Render]
    val impl = UsingAlg.either(1)
    val instrumented = derived.intercept(impl)(recorder.fk, OnRaise.noop[Result, Render])

    assertEquals(instrumented.a(3)(using raiseResult), impl.a(3)(using raiseResult))
    assertEquals(instrumented.a(-3)(using raiseResult), impl.a(-3)(using raiseResult))
    assertEquals(instrumented.b("", 1)(using raiseResult), impl.b("", 1)(using raiseResult))
    assertEquals(instrumented.c(4), impl.c(4))
    assertEquals(instrumented.d(-2)(-3)(using raiseResult), impl.d(-2)(-3)(using raiseResult))
    assertEquals(instrumented.e(using raiseResult, raiseResult), impl.e(using raiseResult, raiseResult))
    assertEquals(instrumented.e(using raiseResult, raiseResult), EmptyInput("e").asLeft[Unit].leftWiden[TestError])
  }

  test("two separate using clauses are both dropped from the domain") {
    val recorder = new RecordingFk[Result, Render, Render]
    val instrumented = multi.intercept(MultiUsingAlg.either)(recorder.fk, OnRaise.noop[Result, Render])

    instrumented.m(7)(using raiseResult)(using raiseResult)
    val rendered = WeaveRenderer.render(recorder.weaves.last.weave)

    assertEquals(rendered.algebraName, "MultiUsingAlg")
    assertEquals(rendered.methodName, "m")
    assertEquals(rendered.domain, List(List("i" -> "7")))
  }

  test("both capabilities from separate using clauses are transported") {
    val recorder = new RecordingFk[Result, Render, Render]
    val impl = MultiUsingAlg.either
    val instrumented = multi.intercept(impl)(recorder.fk, OnRaise.noop[Result, Render])

    assertEquals(instrumented.m(5)(using raiseResult)(using raiseResult), impl.m(5)(using raiseResult)(using raiseResult))
    assertEquals(
      instrumented.m(-5)(using raiseResult)(using raiseResult),
      NegativeInput(-5).asLeft[String].leftWiden[TestError]
    )
  }

  test("a raise through R1 and a raise through R2 each reach the hook rendered through their own Err instance") {
    // `e` takes two capability clauses with different error types on one
    // method. Before M12 the two capabilities were transported and this
    // evidence was invisible from the test's side; `intercept` decorates each
    // one in place, so a hook watching both sees which capability actually
    // fired, rendered through *that* capability's own `Err[E]` — never
    // `toString`, and never the other error type's instance.
    val log = ListBuffer.empty[String]
    val hook: OnRaise[Result, Render] = new OnRaise[Result, Render]:
      def apply[E](e: E)(implicit ev: Render[E]): Result[Unit] =
        log += ev.render(e)
        Right(())

    val recorderA = new RecordingFk[Result, Render, Render]
    val viaR1 = derived.intercept(UsingAlg.either(-9))(recorderA.fk, hook)
    viaR1.e(using raiseResult, raiseResult)
    assertEquals(log.toList, List("errA:NegativeInput(-9)"))

    val recorderB = new RecordingFk[Result, Render, Render]
    val viaR2 = derived.intercept(UsingAlg.either(9))(recorderB.fk, hook)
    viaR2.e(using raiseResult, raiseResult)
    assertEquals(log.toList, List("errA:NegativeInput(-9)", "errB:EmptyInput(e)"))
  }
