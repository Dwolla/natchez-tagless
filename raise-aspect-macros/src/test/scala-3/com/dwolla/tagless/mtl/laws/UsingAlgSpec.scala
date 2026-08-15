package com.dwolla.tagless.mtl
package laws

import cats.Applicative
import cats.effect.{Ref, SyncIO}
import cats.mtl.syntax.all.*
import cats.mtl.Raise
import cats.syntax.all.*
import munit.CatsEffectSuite

import scala.annotation.experimental

import LawsInstances.*
import TestError.*

/** `using` clauses.
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
  def instance[F[_]: Applicative](eOutcome: Int): UsingAlg[F] = new UsingAlg[F]:
    def a(i: Int)(using R: Raise[F, ErrA]): F[String] =
      if i < 0 then R.raise(NegativeInput(i)) else s"a:$i".pure[F]
    def b(x: String, y: => Int)(using R: Raise[F, ErrB]): F[Int] =
      if x.isEmpty then R.raise(EmptyInput("x")) else (x.length + y).pure[F]
    def c(i: Int): F[Int] = (i * 2).pure[F]
    def d(i: Int)(j: Int)(using R: Raise[F, ErrA]): F[Int] =
      if i + j < 0 then R.raise(NegativeInput(i + j)) else (i + j).pure[F]
    def e(using R1: Raise[F, ErrA], R2: Raise[F, ErrB]): F[Unit] =
      if eOutcome < 0 then R1.raise(NegativeInput(eOutcome))
      else if eOutcome > 0 then R2.raise(EmptyInput("e"))
      else ().pure[F]

object MultiUsingAlg:
  def instance[F[_]: Applicative]: MultiUsingAlg[F] = new MultiUsingAlg[F]:
    def m(i: Int)(using R1: Raise[F, ErrA])(using R2: Raise[F, ErrB]): F[String] =
      if i < 0 then R1.raise(NegativeInput(i)) else s"m:$i".pure[F]

@experimental
class UsingAlgSpec extends CatsEffectSuite with HandleTestSyntax:

  private val derived: RaiseAspect[UsingAlg, Render, Render, Render] =
    DeriveRaise.aspect[UsingAlg, Render, Render, Render]

  private val multi: RaiseAspect[MultiUsingAlg, Render, Render, Render] =
    DeriveRaise.aspect[MultiUsingAlg, Render, Render, Render]

  testWithHandle[SyncIO, TestError]("a using-based algebra renders exactly like the implicit-based TestAlg") { implicit H =>
    for {
      recorder <- RecordingFk[SyncIO, Render, Render]
      instrumented = derived.intercept(UsingAlg.instance[SyncIO](0))(recorder.fk, OnRaise.noop[SyncIO, Render])
      _ <- instrumented.a(7)(using H)
      _ <- instrumented.b("ab", 2)(using H)
      _ <- instrumented.c(3)
      _ <- instrumented.d(4)(5)(using H)
      _ <- instrumented.e(using H, H)
      rendered <- LawsInstances.renderedWeaves(recorder)
      // the same expected values TestAlg produces, modulo the algebra name
      _ = assertEquals(rendered.map(_.copy(algebraName = "TestAlg")), ExpectedWeaves.expected)
      _ = assert(rendered.forall(_.algebraName == "UsingAlg"))
    } yield ()
  }

  testWithHandle[SyncIO, TestError]("a using-based algebra transports every capability, raising included") { implicit H =>
    val impl = UsingAlg.instance[SyncIO](1)

    for {
      recorder <- RecordingFk[SyncIO, Render, Render]
      instrumented = derived.intercept(impl)(recorder.fk, OnRaise.noop[SyncIO, Render])
      a3 <- instrumented.a(3)(using H).attemptHandle
      implA3 <- impl.a(3)(using H).attemptHandle
      _ = assertEquals(a3, implA3)
      aNeg3 <- instrumented.a(-3)(using H).attemptHandle
      implANeg3 <- impl.a(-3)(using H).attemptHandle
      _ = assertEquals(aNeg3, implANeg3)
      b <- instrumented.b("", 1)(using H).attemptHandle
      implB <- impl.b("", 1)(using H).attemptHandle
      _ = assertEquals(b, implB)
      c <- instrumented.c(4)
      implC <- impl.c(4)
      _ = assertEquals(c, implC)
      d <- instrumented.d(-2)(-3)(using H).attemptHandle
      implD <- impl.d(-2)(-3)(using H).attemptHandle
      _ = assertEquals(d, implD)
      e <- instrumented.e(using H, H).attemptHandle
      implE <- impl.e(using H, H).attemptHandle
      _ = assertEquals(e, implE)
      _ = assertEquals(e, EmptyInput("e").asLeft[Unit].leftWiden[TestError])
    } yield ()
  }

  testWithHandle[SyncIO, TestError]("two separate using clauses are both dropped from the domain") { implicit H =>
    for {
      recorder <- RecordingFk[SyncIO, Render, Render]
      instrumented = multi.intercept(MultiUsingAlg.instance[SyncIO])(recorder.fk, OnRaise.noop[SyncIO, Render])
      _ <- instrumented.m(7)(using H)(using H)
      w <- recorder.weaves
      rendered = WeaveRenderer.render(w.last.weave)
      _ = assertEquals(rendered.algebraName, "MultiUsingAlg")
      _ = assertEquals(rendered.methodName, "m")
      _ = assertEquals(rendered.domain, List(List("i" -> "7")))
    } yield ()
  }

  testWithHandle[SyncIO, TestError]("both capabilities from separate using clauses are transported") { implicit H =>
    val impl = MultiUsingAlg.instance[SyncIO]

    for {
      recorder <- RecordingFk[SyncIO, Render, Render]
      instrumented = multi.intercept(impl)(recorder.fk, OnRaise.noop[SyncIO, Render])
      m5 <- instrumented.m(5)(using H)(using H).attemptHandle
      implM5 <- impl.m(5)(using H)(using H).attemptHandle
      _ = assertEquals(m5, implM5)
      mNeg5 <- instrumented.m(-5)(using H)(using H).attemptHandle
      _ = assertEquals(mNeg5, NegativeInput(-5).asLeft[String].leftWiden[TestError])
    } yield ()
  }

  testWithHandle[SyncIO, TestError]("a raise through R1 and a raise through R2 each reach the hook rendered through their own Err instance") { implicit H =>
    // `e` takes two capability clauses with different error types on one
    // method. `intercept` decorates each one in place, so a hook watching
    // both sees which capability actually fired, rendered through *that*
    // capability's own `Err[E]` — never `toString`, and never the other
    // error type's instance.
    for {
      log <- Ref.of[SyncIO, Vector[String]](Vector.empty)
      hook = new OnRaise[SyncIO, Render]:
        def apply[E](e: E)(implicit ev: Render[E]): SyncIO[Unit] = log.update(_ :+ ev.render(e))
      recorderA <- RecordingFk[SyncIO, Render, Render]
      viaR1 = derived.intercept(UsingAlg.instance[SyncIO](-9))(recorderA.fk, hook)
      _ <- viaR1.e(using H, H).attemptHandle
      seen1 <- log.get
      _ = assertEquals(seen1.toList, List("errA:NegativeInput(-9)"))
      recorderB <- RecordingFk[SyncIO, Render, Render]
      viaR2 = derived.intercept(UsingAlg.instance[SyncIO](9))(recorderB.fk, hook)
      _ <- viaR2.e(using H, H).attemptHandle
      seen2 <- log.get
      _ = assertEquals(seen2.toList, List("errA:NegativeInput(-9)", "errB:EmptyInput(e)"))
    } yield ()
  }
