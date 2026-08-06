package com.dwolla.tagless.mtl
package laws

import cats.Applicative
import cats.data.EitherT
import cats.effect.SyncIO
import cats.mtl.Raise
import cats.syntax.all._
import munit.CatsEffectSuite

import com.dwolla.tagless.mtl.SyncIOTestSyntax._

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
  def instance[F[_]: Applicative]: EdgeAlg[F] = new EdgeAlg[F]:
    def inherited(i: Int)(using R: Raise[F, ErrA]): F[String] =
      if i < 0 then R.raise(NegativeInput(i)) else s"inherited:$i".pure[F]
    def own(i: Int): F[Int] = (i * 3).pure[F]
    def nullary: F[Int] = 42.pure[F]
    def overloaded(i: Int): F[String] = s"int:$i".pure[F]
    def overloaded(s: String): F[String] = s"string:$s".pure[F]
    val constant: F[String] = "constant".pure[F]

@experimental
class EdgeCaseDerivationSpec extends CatsEffectSuite:

  private val derived: RaiseAspect[EdgeAlg, Render, Render, Render] =
    DeriveRaise.aspect[EdgeAlg, Render, Render, Render]

  private val impl = EdgeAlg.instance[Lazily]

  private val raiseLazily: Raise[Lazily, TestError] = Raise[Lazily, TestError]

  test("a capability method inherited from a parent trait is woven") {
    (for {
      recorder <- RecordingFk[Lazily, Render, Render]
      instrumented = derived.intercept(impl)(recorder.fk, OnRaise.noop[Lazily, Render])
      out <- instrumented.inherited(2)(using raiseLazily)
      w1 <- recorder.weaves
      rendered = WeaveRenderer.render(w1.last.weave)
      _ = assertEquals(rendered.algebraName, "EdgeAlg")
      _ = assertEquals(rendered.methodName, "inherited")
      _ = assertEquals(rendered.domain, List(List("i" -> "2")))
      implOut <- impl.inherited(2)(using raiseLazily)
      _ = assertEquals(out, implOut)
    } yield ()).runOrFail
  }

  test("the inherited capability's raise survives intercept unchanged") {
    (for {
      recorder <- RecordingFk[Lazily, Render, Render]
      instrumented = derived.intercept(impl)(recorder.fk, OnRaise.noop[Lazily, Render])
      out <- EitherT.liftF[SyncIO, TestError, Result[String]](
        instrumented.inherited(-4)(using raiseLazily).value
      )
      _ = assertEquals(out, NegativeInput(-4).asLeft[String].leftWiden[TestError])
      implOut <- EitherT.liftF[SyncIO, TestError, Result[String]](
        impl.inherited(-4)(using raiseLazily).value
      )
      _ = assertEquals(out, implOut)
    } yield ()).runOrFail
  }

  test("a nullary def returning F[A] is woven with an empty domain") {
    (for {
      recorder <- RecordingFk[Lazily, Render, Render]
      instrumented = derived.intercept(impl)(recorder.fk, OnRaise.noop[Lazily, Render])
      out <- instrumented.nullary
      w1 <- recorder.weaves
      rendered = WeaveRenderer.render(w1.last.weave)
      _ = assertEquals(rendered.methodName, "nullary")
      _ = assertEquals(rendered.domain, List.empty[List[(String, String)]])
      _ = assertEquals(out, 42)
    } yield ()).runOrFail
  }

  test("overloads are woven independently, each keeping its own parameter type") {
    (for {
      recorder <- RecordingFk[Lazily, Render, Render]
      instrumented = derived.intercept(impl)(recorder.fk, OnRaise.noop[Lazily, Render])
      outInt <- instrumented.overloaded(7)
      w1 <- recorder.weaves
      renderedInt = WeaveRenderer.render(w1.last.weave)
      outString <- instrumented.overloaded("z")
      w2 <- recorder.weaves
      renderedString = WeaveRenderer.render(w2.last.weave)
      _ = assertEquals(renderedInt.domain, List(List("i" -> "7")))
      _ = assertEquals(renderedString.domain, List(List("s" -> "z")))
      _ = assertEquals(outInt, "int:7")
      _ = assertEquals(outString, "string:z")
    } yield ()).runOrFail
  }

  test("an abstract val returning F[A] is woven eagerly, at construction, unlike on Scala 2") {
    // `constant` is a `val`, not a `def`: the derivation builds its `Aspect.Weave`
    // exactly once, when `intercept` constructs the instrumented algebra, and every
    // read of the field replays that same built weave rather than deriving a fresh
    // one — unlike `own` or `overloaded`, which are woven afresh on every call. On
    // `Result` that was observable as a call count, because `Either`'s strict
    // `flatMap` ran the recording the instant `fk` was invoked; `Lazily`'s
    // `EitherT[SyncIO, _, _]` doesn't run anything until forced, so the same weave
    // gets recorded once per read here, not once ever — but it is still the *same*
    // `Weave` object both times, which is what "built once, at construction" means
    // once evaluation is no longer eager. That reference identity, not a raw
    // weave count, is the direct trace of the val/def distinction under a real `F`.
    (for {
      recorder <- RecordingFk[Lazily, Render, Render]
      instrumented = derived.intercept(impl)(recorder.fk, OnRaise.noop[Lazily, Render])
      out <- instrumented.constant
      w1 <- recorder.weaves
      rendered = WeaveRenderer.render(w1.last.weave)
      _ = assertEquals(rendered.methodName, "constant")
      _ = assertEquals(rendered.domain, List.empty[List[(String, String)]])
      _ = assertEquals(out, "constant")
      out2 <- instrumented.constant
      w2 <- recorder.weaves
      _ = assertEquals(out2, "constant")
      // reading the val again replays the same weave rather than building a new one
      _ = assert(w1.last.weave.asInstanceOf[AnyRef] eq w2.last.weave.asInstanceOf[AnyRef])
    } yield ()).runOrFail
  }

  test("a capability-free method on the same algebra is woven unchanged") {
    (for {
      recorder <- RecordingFk[Lazily, Render, Render]
      instrumented = derived.intercept(impl)(recorder.fk, OnRaise.noop[Lazily, Render])
      out <- instrumented.own(5)
      w1 <- recorder.weaves
      _ = assertEquals(WeaveRenderer.render(w1.last.weave).domain, List(List("i" -> "5")))
      _ = assertEquals(out, 15)
    } yield ()).runOrFail
  }
