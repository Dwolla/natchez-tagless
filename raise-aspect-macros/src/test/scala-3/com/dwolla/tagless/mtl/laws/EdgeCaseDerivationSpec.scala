package com.dwolla.tagless.mtl
package laws

import cats.Applicative
import cats.effect.{Ref, SyncIO}
import cats.mtl.syntax.all.*
import cats.mtl.Raise
import cats.syntax.all.*
import cats.tagless.Trivial
import com.dwolla.tagless.mtl.TestError.*
import com.dwolla.tagless.mtl.laws.LawsInstances.*
import munit.CatsEffectSuite

import scala.annotation.experimental

/** The Scala 3 half — the same shapes the Scala 2 spec covers, plus an
  * abstract `val` returning `F[A]`, which derives correctly here and does
  * ''not'' on Scala 2.
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

/** An abstract type member, exercising `DeriveRaiseMacros.newTypeAlias` — the
  * reflection-into-compiler-internals path with no counterpart on Scala 2.
  * `Cod`/`Dom` are pinned at `Trivial` because `Out` is unconstrained: no
  * concrete typeclass could possibly have an instance for an abstract type
  * with no upper bound, only `Trivial`'s universal one.
  */
trait TypeMemberAlg[F[_]]:
  type Out
  def get: F[Out]

object TypeMemberAlg:
  def instance(counter: Ref[SyncIO, Int]): TypeMemberAlg[SyncIO] = new TypeMemberAlg[SyncIO]:
    type Out = Int
    def get: SyncIO[Out] = counter.getAndUpdate(_ + 1)

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
class EdgeCaseDerivationSpec extends CatsEffectSuite with HandleTestSyntax:

  private val derived: RaiseAspect[EdgeAlg, Render, Render, Render] =
    DeriveRaise.aspect[EdgeAlg, Render, Render, Render]

  private val impl = EdgeAlg.instance[SyncIO]

  private val typeMemberDerived: RaiseAspect[TypeMemberAlg, Trivial, Trivial, Trivial] =
    DeriveRaise.aspect[TypeMemberAlg, Trivial, Trivial, Trivial]

  testWithHandle[SyncIO, TestError]("a capability method inherited from a parent trait is woven") { implicit H =>
    for {
      recorder <- RecordingFk[SyncIO, Render, Render]
      instrumented = derived.intercept(impl)(recorder.fk, OnRaise.noop[SyncIO, Render])
      out <- instrumented.inherited(2)
      w1 <- recorder.weaves
      rendered = WeaveRenderer.render(w1.last.weave)
      _ = assertEquals(rendered.algebraName, "EdgeAlg")
      _ = assertEquals(rendered.methodName, "inherited")
      _ = assertEquals(rendered.domain, List(List("i" -> "2")))
      implOut <- impl.inherited(2)
      _ = assertEquals(out, implOut)
    } yield ()
  }

  testWithHandle[SyncIO, TestError]("the inherited capability's raise survives intercept unchanged") { implicit H =>
    for {
      recorder <- RecordingFk[SyncIO, Render, Render]
      instrumented = derived.intercept(impl)(recorder.fk, OnRaise.noop[SyncIO, Render])
      out <- instrumented.inherited(-4).attemptHandle
      _ = assertEquals(out, NegativeInput(-4).asLeft[String].leftWiden[TestError])
      implOut <- impl.inherited(-4).attemptHandle
      _ = assertEquals(out, implOut)
    } yield ()
  }

  testWithHandle[SyncIO, TestError]("a nullary def returning F[A] is woven with an empty domain") { implicit H =>
    for {
      recorder <- RecordingFk[SyncIO, Render, Render]
      instrumented = derived.intercept(impl)(recorder.fk, OnRaise.noop[SyncIO, Render])
      out <- instrumented.nullary
      w1 <- recorder.weaves
      rendered = WeaveRenderer.render(w1.last.weave)
      _ = assertEquals(rendered.methodName, "nullary")
      _ = assertEquals(rendered.domain, List.empty[List[(String, String)]])
      _ = assertEquals(out, 42)
    } yield ()
  }

  testWithHandle[SyncIO, TestError]("overloads are woven independently, each keeping its own parameter type") { implicit H =>
    for {
      recorder <- RecordingFk[SyncIO, Render, Render]
      instrumented = derived.intercept(impl)(recorder.fk, OnRaise.noop[SyncIO, Render])
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
    } yield ()
  }

  testWithHandle[SyncIO, TestError]("an abstract val returning F[A] is woven eagerly, at construction, unlike on Scala 2") { implicit H =>
    // `constant` is a `val`, not a `def`: the derivation builds its `Aspect.Weave`
    // exactly once, when `intercept` constructs the instrumented algebra, and every
    // read of the field replays that same built weave rather than deriving a fresh
    // one — unlike `own` or `overloaded`, which are woven afresh on every call. On
    // `Result` that was observable as a call count, because `Either`'s strict
    // `flatMap` ran the recording the instant `fk` was invoked; `SyncIO` doesn't run
    // anything until forced, so the same weave gets recorded once per read here, not
    // once ever — but it is still the *same* `Weave` object both times, which is
    // what "built once, at construction" means once evaluation is no longer eager.
    // That reference identity, not a raw weave count, is the direct trace of the
    // val/def distinction under a real `F`.
    for {
      recorder <- RecordingFk[SyncIO, Render, Render]
      instrumented = derived.intercept(impl)(recorder.fk, OnRaise.noop[SyncIO, Render])
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
      // contrast: a def (`nullary`) derives a fresh `Weave` on every call, unlike the `val`
      _ <- instrumented.nullary
      w3 <- recorder.weaves
      _ <- instrumented.nullary
      w4 <- recorder.weaves
      _ = assert(!(w3.last.weave.asInstanceOf[AnyRef] eq w4.last.weave.asInstanceOf[AnyRef]))
    } yield ()
  }

  testWithHandle[SyncIO, TestError]("a capability-free method on the same algebra is woven unchanged") { implicit H =>
    for {
      recorder <- RecordingFk[SyncIO, Render, Render]
      instrumented = derived.intercept(impl)(recorder.fk, OnRaise.noop[SyncIO, Render])
      out <- instrumented.own(5)
      w1 <- recorder.weaves
      _ = assertEquals(WeaveRenderer.render(w1.last.weave).domain, List(List("i" -> "5")))
      _ = assertEquals(out, 15)
    } yield ()
  }

  testWithHandle[SyncIO, TestError]("an algebra with an abstract type member derives and weaves") { implicit H =>
    for {
      counter <- Ref.of[SyncIO, Int](0)
      recorder <- RecordingFk[SyncIO, Trivial, Trivial]
      instrumented = typeMemberDerived.intercept(TypeMemberAlg.instance(counter))(recorder.fk, OnRaise.noop[SyncIO, Trivial])
      _ <- instrumented.get
      count <- counter.get
      _ = assertEquals(count, 1, "intercept must still call through to the underlying implementation")
      w1 <- recorder.weaves
      _ = assertEquals(w1.last.weave.algebraName, "TypeMemberAlg")
      _ = assertEquals(w1.last.weave.codomain.name, "get")
    } yield ()
  }
