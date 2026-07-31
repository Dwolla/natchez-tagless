package com.dwolla.tagless.mtl

import cats.data.EitherT
import cats.mtl.Raise
import cats.syntax.all._
import cats.{Eval, Functor}
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ListBuffer

import TestError._

/** `RaiseAspect.observing` is the entire capability-side surface of the fused
  * derivation, and it takes over the hook behaviour that
  * `WeaveArrows.raiseLift(onRaise)` had. Everything `WeaveArrowsOnRaiseSpec`
  * proved about the hook is proved here instead — Task 2 deletes that file
  * only after this one passes.
  *
  * The first test is the one the old design could not have written: the
  * decorated capability reports the caller's ''own'' `Functor[F]`, so there is
  * no synthesized functor for a generic `R.functor.map(fa)(f)` to corrupt.
  * See `22-milestone-M12-fused-derivation.md` for what that corruption was.
  */
class ObservingCapabilitySpec extends ScalaCheckSuite {
  private type F[A] = Either[TestError, A]
  private type Lazily[A] = EitherT[Eval, TestError, A]

  test("the decorated capability reports the caller's own Functor, never a synthesized one") {
    val callerFunctor: Functor[F] = Functor[F]

    val caller: Raise[F, ErrA] = new Raise[F, ErrA] {
      val functor: Functor[F] = callerFunctor
      def raise[E2 <: ErrA, A](e: E2): F[A] = e.asLeft[A].leftWiden[TestError]
    }

    val decorated = RaiseAspect.observing[F, ErrA, Render](caller, OnRaise.noop[F, Render])

    assert(decorated.functor eq callerFunctor)
  }

  test("decorating does not change the raised value") {
    val caller = Raise[F, ErrA]
    val decorated = RaiseAspect.observing[F, ErrA, Render](caller, OnRaise.noop[F, Render])

    assertEquals(
      decorated.raise[NegativeInput, Int](NegativeInput(-7)),
      caller.raise[NegativeInput, Int](NegativeInput(-7))
    )
  }

  test("the hook renders the raised error through its Err evidence, exactly once") {
    val rendered = ListBuffer.empty[String]

    val hook: OnRaise[F, Render] = new OnRaise[F, Render] {
      def apply[E](e: E)(implicit ev: Render[E]): F[Unit] = {
        val _ = rendered += ev.render(e)
        Right(())
      }
    }

    val decorated = RaiseAspect.observing[F, ErrA, Render](Raise[F, ErrA], hook)
    val out = decorated.raise[NegativeInput, Int](NegativeInput(-3))

    assertEquals(out, NegativeInput(-3).asLeft[Int].leftWiden[TestError])
    // `errA:` proves the Render instance ran; `toString` alone would give
    // "NegativeInput(-3)".
    assertEquals(rendered.toList, List("errA:NegativeInput(-3)"))
  }

  test("the hook's effect is sequenced before the raise, and neither runs until the value is forced") {
    val counter = new AtomicInteger(0)
    val log = ListBuffer.empty[String]

    val hook: OnRaise[Lazily, Render] = new OnRaise[Lazily, Render] {
      def apply[E](e: E)(implicit ev: Render[E]): Lazily[Unit] =
        EitherT.liftF(Eval.always {
          counter.incrementAndGet()
          log += s"hook:${ev.render(e)}"
          ()
        })
    }

    val caller: Raise[Lazily, ErrA] = new Raise[Lazily, ErrA] {
      val functor: Functor[Lazily] = Functor[Lazily]
      def raise[E2 <: ErrA, A](e: E2): Lazily[A] =
        EitherT(Eval.always {
          val _ = log += "raise"
          e.asLeft[A].leftWiden[TestError]
        })
    }

    val decorated = RaiseAspect.observing[Lazily, ErrA, Render](caller, hook)
    val raised = decorated.raise[NegativeInput, Int](NegativeInput(-1))

    assertEquals(counter.get(), 0, "building the raised value must run no effects")
    assertEquals(log.toList, List.empty[String])

    assertEquals(raised.value.value, NegativeInput(-1).asLeft[Int].leftWiden[TestError])
    assertEquals(counter.get(), 1, "the hook must run exactly once")
    assertEquals(log.toList, List("hook:errA:NegativeInput(-1)", "raise"))
  }

  // ------------------------------- the hook through a full interception

  private def countingOnRaise(counter: AtomicInteger): OnRaise[Lazily, Render] =
    new OnRaise[Lazily, Render] {
      def apply[E](e: E)(implicit ev: Render[E]): Lazily[Unit] =
        EitherT(Eval.always {
          val _ = counter.incrementAndGet()
          ().asRight[TestError]
        })
    }

  private val ambientRaise: Raise[Lazily, TestError] =
    new Raise[Lazily, TestError] {
      val functor: Functor[Lazily] = Functor[Lazily]

      def raise[E2 <: TestError, A](e: E2): Lazily[A] =
        EitherT(Eval.always(e.asLeft[A].leftWiden[TestError]))
    }

  /** A `TestAlg[Lazily]` whose raising branches actually call through the
    * `Raise` capability they are given, so a full interception round trip
    * exercises the decorated capability rather than bypassing it.
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

  /** Rescued from `WeaveArrowsOnRaiseSpec`, which M12 deletes along with the
    * `raiseLift(onRaise)` overload it was written against. The decorator tests
    * above exercise `observing` in isolation; this one instead proves the
    * property through a full `intercept` round trip, over a whole `Int`
    * domain rather than one fixed input. `WeaveInterpreterSpec`'s
    * `"a RaiseAspect-only algebra resolves to the RaiseAspect instance and runs
    * the hook"` test makes the same success-path-is-silent point on fixed
    * inputs, through the public `WeaveInterpreter` entry point rather than
    * `RaiseAspect.intercept` directly.
    */
  property("the hook never runs on a success path, and runs exactly once per raise, through a full intercept round trip") {
    forAll { (i: Int) =>
      val counter = new AtomicInteger(0)

      val ref = TestAlgReference.referenceRaiseAspect[Render, Render, Render]
      val recorder = new RecordingFk[Lazily, Render, Render]
      val intercepted = ref.intercept(countingLazilyAlg)(recorder.fk, countingOnRaise(counter))

      val result = intercepted.a(i)(ambientRaise).value.value

      // The weave reaches the interpreter on both branches; only the hook is
      // conditional.
      assertEquals(recorder.events, List("weave:TestAlg.a"))

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

  /** `cats.mtl.Raise` extends `Serializable`, and `WeaveArrowsOnRaiseSpec`
    * pinned that property for `WeaveArrows.raiseLift(onRaise)`'s result before
    * M12 deleted both the method and the spec. `observing` is now the only
    * place that builds a decorated `Raise`, so the assertion moves here.
    * Following `OnRaiseSpec`'s hand-rolled round trip (no cats-laws dependency
    * in this module) rather than letting the property go untested.
    */
  test("the observing result is Serializable") {
    // Only meaningful on the JVM: java.io.ObjectOutputStream/ObjectInputStream
    // don't exist in Scala.js's java.io emulation, and Platform.isJvm is a
    // compile-time constant, so scalac constant-folds this branch away
    // entirely before the Scala.js linker ever sees it.
    if (Platform.isJvm) {
      val decorated: Raise[F, ErrA] =
        RaiseAspect.observing[F, ErrA, Render](Raise[F, ErrA], OnRaise.noop[F, Render])

      val bytes = {
        val bos = new ByteArrayOutputStream()
        val oos = new ObjectOutputStream(bos)
        oos.writeObject(decorated)
        oos.close()
        bos.toByteArray
      }

      val deserialized = {
        val bis = new ByteArrayInputStream(bytes)
        val ois = new ObjectInputStream(bis)
        val obj = ois.readObject().asInstanceOf[Raise[F, ErrA]]
        ois.close()
        obj
      }

      val err = NegativeInput(-9)
      assertEquals(deserialized.raise[NegativeInput, Int](err), err.asLeft[Int].leftWiden[TestError])
    }
  }
}
