package com.dwolla.tagless.mtl
package laws

import cats.Applicative
import cats.data.EitherT
import cats.effect.SyncIO
import cats.mtl.Raise
import cats.syntax.all._
import munit.CatsEffectSuite

import com.dwolla.tagless.mtl.SyncIOTestSyntax._

import LawsInstances._
import TestError._

/** Shapes the existing fixtures don't cover: members inherited from a parent
  * trait, a nullary def returning `F[A]`, and overloads.
  *
  * New fixtures in a new file; the existing fixture sources are untouched.
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
  def instance[F[_]](implicit F: Applicative[F]): EdgeAlg[F] = new EdgeAlg[F] {
    def inherited(i: Int)(implicit R: Raise[F, ErrA]): F[String] =
      if (i < 0) R.raise(NegativeInput(i)) else s"inherited:$i".pure[F]
    def own(i: Int): F[Int] = (i * 3).pure[F]
    def nullary: F[Int] = 42.pure[F]
    def overloaded(i: Int): F[String] = s"int:$i".pure[F]
    def overloaded(s: String): F[String] = s"string:$s".pure[F]
  }
}

class EdgeCaseDerivationSpec extends CatsEffectSuite {

  private val derived: RaiseAspect[EdgeAlg, Render, Render, Render] =
    DeriveRaise.aspect[EdgeAlg, Render, Render, Render]

  private val impl = EdgeAlg.instance[Lazily]

  private val raiseLazily: Raise[Lazily, TestError] = Raise[Lazily, TestError]

  test("a capability method inherited from a parent trait is woven") {
    (for {
      recorder <- RecordingFk[Lazily, Render, Render]
      instrumented = derived.intercept(impl)(recorder.fk, OnRaise.noop[Lazily, Render])
      out <- instrumented.inherited(2)(raiseLazily)
      w1 <- recorder.weaves
      rendered = WeaveRenderer.render(w1.last.weave)
      _ = assertEquals(rendered.algebraName, "EdgeAlg")
      _ = assertEquals(rendered.methodName, "inherited")
      _ = assertEquals(rendered.domain, List(List("i" -> "2")))
      implOut <- impl.inherited(2)(raiseLazily)
      _ = assertEquals(out, implOut)
    } yield ()).runOrFail
  }

  test("the inherited capability's raise survives intercept unchanged") {
    (for {
      recorder <- RecordingFk[Lazily, Render, Render]
      instrumented = derived.intercept(impl)(recorder.fk, OnRaise.noop[Lazily, Render])
      out <- EitherT.liftF[SyncIO, TestError, Result[String]](
        instrumented.inherited(-4)(raiseLazily).value
      )
      _ = assertEquals(out, NegativeInput(-4).asLeft[String].leftWiden[TestError])
      implOut <- EitherT.liftF[SyncIO, TestError, Result[String]](
        impl.inherited(-4)(raiseLazily).value
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
}
