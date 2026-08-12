package com.dwolla.tagless.mtl
package laws

import cats.Applicative
import cats.data.EitherT
import cats.effect.{Ref, SyncIO}
import cats.mtl.Raise
import cats.syntax.all._
import munit.CatsEffectSuite

import scala.annotation.experimental

import LawsInstances._
import TestError._
import SyncIOTestSyntax._

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
class UsingAlgSpec extends CatsEffectSuite:

  private val derived: RaiseAspect[UsingAlg, Render, Render, Render] =
    DeriveRaise.aspect[UsingAlg, Render, Render, Render]

  private val multi: RaiseAspect[MultiUsingAlg, Render, Render, Render] =
    DeriveRaise.aspect[MultiUsingAlg, Render, Render, Render]

  test("a using-based algebra renders exactly like the implicit-based TestAlg") {
    (for {
      recorder <- RecordingFk[Lazily, Render, Render]
      instrumented = derived.intercept(UsingAlg.instance[Lazily](0))(recorder.fk, OnRaise.noop[Lazily, Render])
      _ <- instrumented.a(7)(using Raise[Lazily, ErrA])
      _ <- instrumented.b("ab", 2)(using Raise[Lazily, ErrB])
      _ <- instrumented.c(3)
      _ <- instrumented.d(4)(5)(using Raise[Lazily, ErrA])
      _ <- instrumented.e(using Raise[Lazily, ErrA], Raise[Lazily, ErrB])
      rendered <- LawsInstances.renderedWeaves(recorder)
      // the same expected values TestAlg produces, modulo the algebra name
      _ = assertEquals(rendered.map(_.copy(algebraName = "TestAlg")), ExpectedWeaves.expected)
      _ = assert(rendered.forall(_.algebraName == "UsingAlg"))
    } yield ()).runOrFail
  }

  test("a using-based algebra transports every capability, raising included") {
    val impl = UsingAlg.instance[Lazily](1)

    (for {
      recorder <- RecordingFk[Lazily, Render, Render]
      instrumented = derived.intercept(impl)(recorder.fk, OnRaise.noop[Lazily, Render])
      a3 <- EitherT.liftF[SyncIO, TestError, Result[String]](instrumented.a(3)(using Raise[Lazily, ErrA]).value)
      implA3 <- EitherT.liftF[SyncIO, TestError, Result[String]](impl.a(3)(using Raise[Lazily, ErrA]).value)
      _ = assertEquals(a3, implA3)
      aNeg3 <- EitherT.liftF[SyncIO, TestError, Result[String]](instrumented.a(-3)(using Raise[Lazily, ErrA]).value)
      implANeg3 <- EitherT.liftF[SyncIO, TestError, Result[String]](impl.a(-3)(using Raise[Lazily, ErrA]).value)
      _ = assertEquals(aNeg3, implANeg3)
      b <- EitherT.liftF[SyncIO, TestError, Result[Int]](instrumented.b("", 1)(using Raise[Lazily, ErrB]).value)
      implB <- EitherT.liftF[SyncIO, TestError, Result[Int]](impl.b("", 1)(using Raise[Lazily, ErrB]).value)
      _ = assertEquals(b, implB)
      c <- instrumented.c(4)
      implC <- impl.c(4)
      _ = assertEquals(c, implC)
      d <- EitherT.liftF[SyncIO, TestError, Result[Int]](instrumented.d(-2)(-3)(using Raise[Lazily, ErrA]).value)
      implD <- EitherT.liftF[SyncIO, TestError, Result[Int]](impl.d(-2)(-3)(using Raise[Lazily, ErrA]).value)
      _ = assertEquals(d, implD)
      e <- EitherT.liftF[SyncIO, TestError, Result[Unit]](
        instrumented.e(using Raise[Lazily, ErrA], Raise[Lazily, ErrB]).value
      )
      implE <- EitherT.liftF[SyncIO, TestError, Result[Unit]](
        impl.e(using Raise[Lazily, ErrA], Raise[Lazily, ErrB]).value
      )
      _ = assertEquals(e, implE)
      _ = assertEquals(e, EmptyInput("e").asLeft[Unit].leftWiden[TestError])
    } yield ()).runOrFail
  }

  test("two separate using clauses are both dropped from the domain") {
    (for {
      recorder <- RecordingFk[Lazily, Render, Render]
      instrumented = multi.intercept(MultiUsingAlg.instance[Lazily])(recorder.fk, OnRaise.noop[Lazily, Render])
      _ <- instrumented.m(7)(using Raise[Lazily, ErrA])(using Raise[Lazily, ErrB])
      w <- recorder.weaves
      rendered = WeaveRenderer.render(w.last.weave)
      _ = assertEquals(rendered.algebraName, "MultiUsingAlg")
      _ = assertEquals(rendered.methodName, "m")
      _ = assertEquals(rendered.domain, List(List("i" -> "7")))
    } yield ()).runOrFail
  }

  test("both capabilities from separate using clauses are transported") {
    val impl = MultiUsingAlg.instance[Lazily]

    (for {
      recorder <- RecordingFk[Lazily, Render, Render]
      instrumented = multi.intercept(impl)(recorder.fk, OnRaise.noop[Lazily, Render])
      m5 <- EitherT.liftF[SyncIO, TestError, Result[String]](
        instrumented.m(5)(using Raise[Lazily, ErrA])(using Raise[Lazily, ErrB]).value
      )
      implM5 <- EitherT.liftF[SyncIO, TestError, Result[String]](
        impl.m(5)(using Raise[Lazily, ErrA])(using Raise[Lazily, ErrB]).value
      )
      _ = assertEquals(m5, implM5)
      mNeg5 <- EitherT.liftF[SyncIO, TestError, Result[String]](
        instrumented.m(-5)(using Raise[Lazily, ErrA])(using Raise[Lazily, ErrB]).value
      )
      _ = assertEquals(mNeg5, NegativeInput(-5).asLeft[String].leftWiden[TestError])
    } yield ()).runOrFail
  }

  test("a raise through R1 and a raise through R2 each reach the hook rendered through their own Err instance") {
    // `e` takes two capability clauses with different error types on one
    // method. `intercept` decorates each one in place, so a hook watching
    // both sees which capability actually fired, rendered through *that*
    // capability's own `Err[E]` — never `toString`, and never the other
    // error type's instance.
    (for {
      log <- Ref.of[Lazily, Vector[String]](Vector.empty)
      hook = new OnRaise[Lazily, Render] {
        def apply[E](e: E)(implicit ev: Render[E]): Lazily[Unit] = log.update(_ :+ ev.render(e))
      }
      recorderA <- RecordingFk[Lazily, Render, Render]
      viaR1 = derived.intercept(UsingAlg.instance[Lazily](-9))(recorderA.fk, hook)
      _ <- EitherT.liftF[SyncIO, TestError, Result[Unit]](
        viaR1.e(using Raise[Lazily, ErrA], Raise[Lazily, ErrB]).value
      )
      seen1 <- log.get
      _ = assertEquals(seen1.toList, List("errA:NegativeInput(-9)"))
      recorderB <- RecordingFk[Lazily, Render, Render]
      viaR2 = derived.intercept(UsingAlg.instance[Lazily](9))(recorderB.fk, hook)
      _ <- EitherT.liftF[SyncIO, TestError, Result[Unit]](
        viaR2.e(using Raise[Lazily, ErrA], Raise[Lazily, ErrB]).value
      )
      seen2 <- log.get
      _ = assertEquals(seen2.toList, List("errA:NegativeInput(-9)", "errB:EmptyInput(e)"))
    } yield ()).runOrFail
  }
