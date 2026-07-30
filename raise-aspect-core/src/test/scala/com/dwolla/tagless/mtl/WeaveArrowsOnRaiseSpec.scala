package com.dwolla.tagless.mtl

import cats.data.EitherT
import cats.mtl.Raise
import cats.syntax.all._
import cats.tagless.aop.Aspect
import cats.{Eval, Functor}
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ListBuffer

import TestError._

/** Property tests for [[WeaveArrows.raiseLift]]'s `OnRaise`-hook overload
  * (milestone M6, task 2). `WeaveArrowsSpec` already covers the existing
  * no-hook `raiseLift`, whose behavior this overload must not disturb; these
  * tests are purely additive.
  *
  * `raise-aspect-laws` is frozen and cannot be extended to exercise this new
  * overload, so its property coverage (including the noop-equivalence and
  * Serializable checks that the laws module would otherwise carry for a
  * `raiseLift` variant) lives here instead, reusing the M1 fixtures.
  */
class WeaveArrowsOnRaiseSpec extends ScalaCheckSuite {
  private type F[A] = Either[TestError, A]
  private type W[A] = Aspect.Weave[F, Render, Render, A]

  private implicit val syntheticRender: Synthetic[Render] =
    new Synthetic[Render] {
      def apply[A]: Render[A] = (_: A) => "<synthetic>"
    }

  private val raiseF: Raise[F, TestError] = Raise[F, TestError]

  // -------------------------------------------------- noop equivalence

  private val noHookArrow: RaiseArrow[W, F, Render] =
    WeaveArrows.eraseWeave[F, Render, Render, Render]

  private val noopHookArrow: RaiseArrow[W, F, Render] =
    RaiseArrow(
      WeaveArrows.codomainTarget[F, Render, Render],
      WeaveArrows.raiseLift[F, Render, Render, Render](OnRaise.noop[F, Render])
    )

  property(
    "L4 arrow coherence: raiseLift and raiseLift(noop) agree on arrow.fk(arrow.pull(rg).raise(e))"
  ) {
    forAll { (n: Int, s: String) =>
      val errA = NegativeInput(n)
      val errB = EmptyInput(s)

      assertEquals(
        noHookArrow.fk(noHookArrow.pull(raiseF).raise[NegativeInput, Int](errA)),
        noopHookArrow.fk(noopHookArrow.pull(raiseF).raise[NegativeInput, Int](errA))
      )
      assertEquals(
        noHookArrow.fk(noHookArrow.pull(raiseF).raise[EmptyInput, String](errB)),
        noopHookArrow.fk(noopHookArrow.pull(raiseF).raise[EmptyInput, String](errB))
      )
    }
  }

  property(
    "L5 section/retraction: raisePull(raiseLift(r)) and raisePull(raiseLift(noop)(r)) both retract to r"
  ) {
    forAll { (n: Int) =>
      val err = NegativeInput(n)

      val pulledNoHook =
        WeaveArrows
          .raisePull[F, Render, Render, Render]
          .apply(WeaveArrows.raiseLift[F, Render, Render, Render].apply(raiseF))
      val pulledNoopHook =
        WeaveArrows
          .raisePull[F, Render, Render, Render]
          .apply(WeaveArrows.raiseLift[F, Render, Render, Render](OnRaise.noop[F, Render]).apply(raiseF))

      val expected = raiseF.raise[NegativeInput, Int](err)
      assertEquals(pulledNoHook.raise[NegativeInput, Int](err), expected)
      assertEquals(pulledNoopHook.raise[NegativeInput, Int](err), expected)
    }
  }

  property(
    "raiseLift and raiseLift(noop) erase a woven TestAlg identically, on both raising and non-raising inputs"
  ) {
    forAll { (i: Int, x: String, y: Int, j: Int, eOutcome: Int) =>
      val impl = new EitherTestAlg(eOutcome)
      val ref = TestAlgReference.referenceRaiseAspect[Render, Render, Render]
      val woven = ref.weave(impl)(Functor[F])

      val erasedNoHook = ref.mapK(woven)(noHookArrow)
      val erasedNoopHook = ref.mapK(woven)(noopHookArrow)

      assertEquals(erasedNoHook.a(i)(raiseF), erasedNoopHook.a(i)(raiseF))
      assertEquals(erasedNoHook.b(x, y)(raiseF), erasedNoopHook.b(x, y)(raiseF))
      assertEquals(erasedNoHook.c(i), erasedNoopHook.c(i))
      assertEquals(erasedNoHook.d(i)(j)(raiseF), erasedNoopHook.d(i)(j)(raiseF))
      assertEquals(erasedNoHook.e(raiseF, raiseF), erasedNoopHook.e(raiseF, raiseF))
    }
  }

  // ------------------------------------------- exactly-once, raise-only

  /** A deferred effect shaped like the overview's L10 laziness-parity fixture
    * (`raise-aspect-laws`' frozen `RaiseAspectSuite` uses the same
    * `EitherT[Eval, TestError, *]` shape for its own counting fixture) —
    * replicated here, not imported, since that module is off-limits.
    */
  private type Lazily[A] = EitherT[Eval, TestError, A]

  private def loggingOnRaise(counter: AtomicInteger, log: ListBuffer[String]): OnRaise[Lazily, Render] =
    new OnRaise[Lazily, Render] {
      def apply[E](e: E)(implicit ev: Render[E]): Lazily[Unit] =
        EitherT(Eval.always {
          counter.incrementAndGet()
          log += "hook"
          ().asRight[TestError]
        })
    }

  private def loggingRaise(log: ListBuffer[String]): Raise[Lazily, TestError] =
    new Raise[Lazily, TestError] {
      val functor: Functor[Lazily] = Functor[Lazily]

      def raise[E2 <: TestError, A](e: E2): Lazily[A] =
        EitherT(Eval.always {
          log += "raise"
          e.asLeft[A].leftWiden[TestError]
        })
    }

  property("the hook runs exactly once, and its effect is sequenced before the raised value becomes observable") {
    forAll { (n: Int) =>
      val counter = new AtomicInteger(0)
      val log = ListBuffer.empty[String]
      val err = NegativeInput(n)

      val shell =
        WeaveArrows.raiseLift[Lazily, Render, Render, Render](loggingOnRaise(counter, log))
          .apply(loggingRaise(log))
          .raise[NegativeInput, Int](err)

      assertEquals(counter.get(), 0, "constructing the shell weave must not run the hook")
      assertEquals(log.toList, List.empty[String], "constructing the shell weave must not run any effect")

      val forced = shell.codomain.target.value.value

      assertEquals(counter.get(), 1, "the hook must run exactly once")
      assertEquals(log.toList, List("hook", "raise"), "the hook must be sequenced before the raise")
      assertEquals(forced, err.asLeft[Int].leftWiden[TestError])
    }
  }

  /** A `TestAlg[Lazily]` whose raising branches actually call through the
    * `Raise` capability they're given (unlike a pure laziness fixture),
    * so a full weave+erase round trip exercises `raiseLift(onRaise)`'s own
    * interception point rather than bypassing it.
    */
  private val countingLazilyAlg: TestAlg[Lazily] =
    new TestAlg[Lazily] {
      def a(i: Int)(implicit R: Raise[Lazily, ErrA]): Lazily[String] =
        if (i < 0) R.raise(NegativeInput(i)) else EitherT(Eval.always(s"a:$i".asRight[TestError]))

      def b(x: String, y: => Int)(implicit R: Raise[Lazily, ErrB]): Lazily[Int] =
        if (x.isEmpty) R.raise(EmptyInput("x")) else EitherT(Eval.always((x.length + y).asRight[TestError]))

      def c(i: Int): Lazily[Int] = EitherT(Eval.always((i * 2).asRight[TestError]))

      def d(i: Int)(j: Int)(implicit R: Raise[Lazily, ErrA]): Lazily[Int] =
        if (i + j < 0) R.raise(NegativeInput(i + j)) else EitherT(Eval.always((i + j).asRight[TestError]))

      def e(implicit R1: Raise[Lazily, ErrA], R2: Raise[Lazily, ErrB]): Lazily[Unit] =
        EitherT(Eval.always(().asRight[TestError]))
    }

  property(
    "the hook never runs on a success path, and runs exactly once per raise, through a full weave+erase round trip"
  ) {
    forAll { (i: Int) =>
      val counter = new AtomicInteger(0)
      val log = ListBuffer.empty[String]

      val ref = TestAlgReference.referenceRaiseAspect[Render, Render, Render]
      val woven = ref.weave(countingLazilyAlg)(Functor[Lazily])
      val arrow = RaiseArrow(
        WeaveArrows.codomainTarget[Lazily, Render, Render],
        WeaveArrows.raiseLift[Lazily, Render, Render, Render](loggingOnRaise(counter, log))
      )
      val erased = ref.mapK(woven)(arrow)

      val result = erased.a(i)(loggingRaise(log)).value.value

      if (i < 0) {
        assertEquals(counter.get(), 1, s"the hook must run exactly once when raising for i=$i")
        assertEquals(result, NegativeInput(i).asLeft[String].leftWiden[TestError])
      } else {
        assertEquals(counter.get(), 0, s"the hook must not run on the success path for i=$i")
        assertEquals(result, s"a:$i".asRight[TestError])
      }
    }
  }

  // ------------------------------------------------------- Serializable

  /** `raise-aspect-laws`' frozen `RaiseAspectSuite` already checks the
    * no-hook `raiseLift`'s Serializable contract (via `eraseWeave`), but it
    * cannot be extended to cover this new overload. Following the hand-rolled
    * approach `OnRaiseSpec` uses for the same reason (no cats-laws dependency
    * in this module), this is the only place this overload's result can get
    * that coverage.
    */
  test("the raiseLift(onRaise) result is Serializable") {
    // Only meaningful on the JVM: java.io.ObjectOutputStream/ObjectInputStream
    // don't exist in Scala.js's java.io emulation, and Platform.isJvm is a
    // compile-time constant, so scalac constant-folds this branch away
    // entirely before the Scala.js linker ever sees it.
    if (Platform.isJvm) {
      val lifted: RaisePull[F, W, Render] =
        WeaveArrows.raiseLift[F, Render, Render, Render](OnRaise.noop[F, Render])

      val bytes = {
        val bos = new ByteArrayOutputStream()
        val oos = new ObjectOutputStream(bos)
        oos.writeObject(lifted)
        oos.close()
        bos.toByteArray
      }

      val deserialized = {
        val bis = new ByteArrayInputStream(bytes)
        val ois = new ObjectInputStream(bis)
        val obj = ois.readObject().asInstanceOf[RaisePull[F, W, Render]]
        ois.close()
        obj
      }

      val err = NegativeInput(-9)
      assertEquals(
        deserialized.apply(raiseF).raise[NegativeInput, Int](err).codomain.target,
        err.asLeft[Int].leftWiden[TestError]
      )
    }
  }
}
