package com.dwolla.tagless.mtl

import cats.Functor
import cats.effect.testkit.TestInstances
import cats.effect.{Ref, SyncIO}
import cats.mtl.syntax.all.*
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import com.dwolla.tagless.mtl.TestError.*
import munit.{CatsEffectSuite, ScalaCheckSuite}
import org.scalacheck.Prop.forAll

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}

/** `RaiseAspect.observing` is the entire capability-side surface of the fused
  * derivation; this suite exercises the hook behavior it provides.
  *
  * The first test below proves the decorated capability reports the caller's
  * ''own'' `Functor[F]`, so there is no synthesized functor for a generic
  * `R.functor.map(fa)(f)` to corrupt.
  */
class ObservingCapabilitySpec extends CatsEffectSuite with ScalaCheckSuite with TestInstances with HandleTestSyntax {
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

  testWithHandle[SyncIO, TestError]("the hook renders the raised error through its Err evidence, exactly once") { implicit H =>
    for {
      rendered <- Ref.of[SyncIO, Vector[String]](Vector.empty)
      hook = new OnRaise[SyncIO, Render] {
        def apply[E](e: E)(implicit ev: Render[E]): SyncIO[Unit] = rendered.update(_ :+ ev.render(e))
      }
      decorated = RaiseAspect.observing[SyncIO, ErrA, Render](Raise[SyncIO, ErrA], hook)
      _ <- decorated.raise[NegativeInput, Unit](NegativeInput(-3)).handle { (out: TestError) =>
        assertEquals(out, NegativeInput(-3))
      }
      seen <- rendered.get
      // `errA:` proves the Render instance ran; `toString` alone would give
      // "NegativeInput(-3)".
      _ = assertEquals(seen.toList, List("errA:NegativeInput(-3)"))
    } yield ()
  }

  testWithHandle[SyncIO, TestError]("the hook's effect is sequenced before the raise, and neither runs until the value is forced") { implicit H =>
    for {
      counter <- Ref.of[SyncIO, Int](0)
      log <- Ref.of[SyncIO, Vector[String]](Vector.empty)
      hook = new OnRaise[SyncIO, Render] {
        def apply[E](e: E)(implicit ev: Render[E]): SyncIO[Unit] =
          counter.update(_ + 1) *> log.update(_ :+ s"hook:${ev.render(e)}")
      }
      caller = new Raise[SyncIO, ErrA] {
        val functor: Functor[SyncIO] = Functor[SyncIO]
        def raise[E2 <: ErrA, A](e: E2): SyncIO[A] =
          log.update(_ :+ "raise") *> H.raise(e: TestError)
      }
      decorated = RaiseAspect.observing[SyncIO, ErrA, Render](caller, hook)
      // `raised` is built but not yet forced -- nothing has run.
      raised = decorated.raise[NegativeInput, Unit](NegativeInput(-1))
      c0 <- counter.get
      l0 <- log.get
      _ = assertEquals(c0, 0, "building the raised value must run no effects")
      _ = assertEquals(l0.toList, List.empty[String])
      _ <- raised.handle { (result: TestError) =>
        assertEquals(result, NegativeInput(-1))
      }
      c1 <- counter.get
      _ = assertEquals(c1, 1, "the hook must run exactly once")
      l1 <- log.get
      _ = assertEquals(l1.toList, List("hook:errA:NegativeInput(-1)", "raise"))
    } yield ()
  }

  private def countingOnRaise[G[_]](counter: Ref[G, Int]): OnRaise[G, Render] =
    new OnRaise[G, Render] {
      def apply[E](e: E)(implicit ev: Render[E]): G[Unit] = counter.update(_ + 1)
    }

  /** The decorator tests above exercise `observing` in isolation; this one
    * instead proves the property through a full `intercept` round trip, over
    * a whole `Int`
    * domain rather than one fixed input. `WeaveInterpreterSpec`'s
    * `"a RaiseAspect-only algebra resolves to the RaiseAspect instance and runs
    * the hook"` test makes the same success-path-is-silent point on fixed
    * inputs, through the public `WeaveInterpreter` entry point rather than
    * `RaiseAspect.intercept` directly.
    */
  property("the hook never runs on a success path, and runs exactly once per raise, through a full intercept round trip") {
    forAll { (i: Int) =>
      Handle.allowF[SyncIO, TestError] { implicit H =>
        for {
          counter <- Ref.of[SyncIO, Int](0)
          ref = TestAlgReference.referenceRaiseAspect[Render, Render, Render]
          recorder <- RecordingFk[SyncIO, Render, Render]
          intercepted = ref.intercept(new GenericTestAlg[SyncIO](0))(recorder.fk, countingOnRaise(counter))
          result <- intercepted.a(i).attemptHandle
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
        } yield true
      }.rescue { e =>
        fail(s"raised unexpectedly: $e")
      }
    }
  }

  /** `cats.mtl.Raise` extends `Serializable`, and `observing` is the only
    * place in this module that builds a decorated `Raise`, so this pins that
    * property here. Following `OnRaiseSpec`'s hand-rolled round trip (no
    * cats-laws dependency in this module) rather than letting the property go
    * untested.
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
