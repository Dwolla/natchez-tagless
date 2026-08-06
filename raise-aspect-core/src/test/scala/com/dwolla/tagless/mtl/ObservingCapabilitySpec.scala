package com.dwolla.tagless.mtl

import cats.data.EitherT
import cats.effect.{Ref, SyncIO}
import cats.effect.testkit.TestInstances
import cats.mtl.Raise
import cats.syntax.all._
import cats.Functor
import munit.{CatsEffectSuite, ScalaCheckSuite}
import org.scalacheck.Prop.forAll

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}

import CarrierArrows.Lazily
import SyncIOTestSyntax._
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
class ObservingCapabilitySpec extends CatsEffectSuite with ScalaCheckSuite with TestInstances {
  private type F[A] = Either[TestError, A]

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
    (for {
      rendered <- Ref.of[Lazily, Vector[String]](Vector.empty)
      hook = new OnRaise[Lazily, Render] {
        def apply[E](e: E)(implicit ev: Render[E]): Lazily[Unit] = rendered.update(_ :+ ev.render(e))
      }
      decorated = RaiseAspect.observing[Lazily, ErrA, Render](Raise[Lazily, ErrA], hook)
      out <- EitherT.liftF[SyncIO, TestError, Either[TestError, Int]](decorated.raise[NegativeInput, Int](NegativeInput(-3)).value)
      _ = assertEquals(out, NegativeInput(-3).asLeft[Int].leftWiden[TestError])
      seen <- rendered.get
      // `errA:` proves the Render instance ran; `toString` alone would give
      // "NegativeInput(-3)".
      _ = assertEquals(seen.toList, List("errA:NegativeInput(-3)"))
    } yield ()).runOrFail
  }

  test("the hook's effect is sequenced before the raise, and neither runs until the value is forced") {
    (for {
      counter <- Ref.of[Lazily, Int](0)
      log <- Ref.of[Lazily, Vector[String]](Vector.empty)
      hook = new OnRaise[Lazily, Render] {
        def apply[E](e: E)(implicit ev: Render[E]): Lazily[Unit] =
          counter.update(_ + 1) *> log.update(_ :+ s"hook:${ev.render(e)}")
      }
      caller = new Raise[Lazily, ErrA] {
        val functor: Functor[Lazily] = Functor[Lazily]
        def raise[E2 <: ErrA, A](e: E2): Lazily[A] =
          log.update(_ :+ "raise") *> EitherT.leftT[SyncIO, A](e: TestError)
      }
      decorated = RaiseAspect.observing[Lazily, ErrA, Render](caller, hook)
      // `raised` is built but not yet forced -- nothing has run.
      raised = decorated.raise[NegativeInput, Int](NegativeInput(-1))
      c0 <- counter.get
      l0 <- log.get
      _ = assertEquals(c0, 0, "building the raised value must run no effects")
      _ = assertEquals(l0.toList, List.empty[String])
      result <- EitherT.liftF[SyncIO, TestError, Either[TestError, Int]](raised.value)
      _ = assertEquals(result, NegativeInput(-1).asLeft[Int].leftWiden[TestError])
      c1 <- counter.get
      _ = assertEquals(c1, 1, "the hook must run exactly once")
      l1 <- log.get
      _ = assertEquals(l1.toList, List("hook:errA:NegativeInput(-1)", "raise"))
    } yield ()).runOrFail
  }

  // ------------------------------- the hook through a full interception

  private def countingOnRaise(counter: Ref[Lazily, Int]): OnRaise[Lazily, Render] =
    new OnRaise[Lazily, Render] {
      def apply[E](e: E)(implicit ev: Render[E]): Lazily[Unit] = counter.update(_ + 1)
    }

  private val ambientRaise: Raise[Lazily, TestError] =
    new Raise[Lazily, TestError] {
      val functor: Functor[Lazily] = Functor[Lazily]

      def raise[E2 <: TestError, A](e: E2): Lazily[A] = EitherT.leftT[SyncIO, A](e: TestError)
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
      (for {
        counter <- Ref.of[Lazily, Int](0)
        ref = TestAlgReference.referenceRaiseAspect[Render, Render, Render]
        recorder <- RecordingFk[Lazily, Render, Render]
        intercepted = ref.intercept(new GenericTestAlg[Lazily](0))(recorder.fk, countingOnRaise(counter))
        result <- EitherT.liftF[SyncIO, TestError, Either[TestError, String]](intercepted.a(i)(ambientRaise).value)
        events <- recorder.events
        // The weave reaches the interpreter on both branches; only the hook is
        // conditional.
        _ = assertEquals(events.toList, List("weave:TestAlg.a"))
        c <- counter.get
        _ =
          if (i < 0) {
            assertEquals(c, 1, s"the hook must run exactly once when raising for i=$i")
            assertEquals(result, NegativeInput(i).asLeft[String].leftWiden[TestError])
          } else {
            assertEquals(c, 0, s"the hook must not run on the success path for i=$i")
            assertEquals(result, s"a:$i".asRight[TestError])
          }
      } yield true).value.map(_.getOrElse(false))
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
