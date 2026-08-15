package com.dwolla.tagless.mtl
package laws

import cats.ApplicativeThrow
import cats.effect.testkit.TestInstances
import cats.effect.{Ref, Sync, SyncIO}
import cats.kernel.laws.discipline.SerializableTests
import cats.mtl.syntax.all.*
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import cats.tagless.Trivial
import com.dwolla.tagless.mtl.TestError.*
import com.dwolla.tagless.mtl.laws.LawsInstances.*
import com.dwolla.tagless.mtl.laws.discipline.RaiseAspectTests
import munit.{CatsEffectSuite, DisciplineSuite}
import org.scalacheck.{Arbitrary, Gen}

/** The complete law test suite for a `RaiseAspect[TestAlg, Render, Render, Render]`.
  */
abstract class RaiseAspectSuite extends CatsEffectSuite with DisciplineSuite with TestInstances with HandleTestSyntax {

  /** The instance under test. Override this and nothing else. */
  def instance: RaiseAspect[TestAlg, Render, Render, Render]

  private implicit def instanceUnderTest: RaiseAspect[TestAlg, Render, Render, Render] = instance

  private implicit val arbTestAlgResult: Arbitrary[TestAlg[Result]] =
    Arbitrary(Gen.oneOf(-1, 0, 1).map(new EitherTestAlg(_)))

  private implicit val arbIdArrow: Arbitrary[RaiseArrow[Result, Result, Render]] =
    Arbitrary(Gen.const(RaiseArrow.id[Result, Render]))

  private implicit val arbIdArrowSyncIO: Arbitrary[RaiseArrow[SyncIO, SyncIO, Render]] =
    Arbitrary(Gen.const(RaiseArrow.id[SyncIO, Render]))

  // ------------------------------------------------ L1, L2, L3′ (discipline)

  // L3′ lives at the base effect: `intercept` is carrier-preserving, so the
  // effect it erases into is the one the algebra already speaks.
  checkAll(
    "RaiseAspect[TestAlg, Render, Render, Render]",
    RaiseAspectTests[TestAlg, Render, Render, Render].raiseAspect[Result, Result, Result]
  )

  // L1/L2 again over a genuinely non-trivial arrow: `CarrierArrows.resultToSyncIO`
  // is a real change of effect — `Either[TestError, *]` to `SyncIO` — with a
  // real pull in the opposite direction. Testing mapK only at the identity
  // arrow would be a coverage loss disguised as a deletion.
  //
  // `checkAll` registers its properties as a side effect at the point it's
  // called, so the `Handle.allowF` scope that provides the implicit
  // `Handle[SyncIO, TestError]` for the arrow/`Arbitrary` resolution must run
  // *now*, at class construction — `unsafeRunSync()` forces that.
  Handle.allowF[SyncIO, TestError] { implicit H => SyncIO {
    checkAll(
      "RaiseFunctorK[TestAlg] over a genuine carrier change",
      laws.discipline.RaiseFunctorKTests[TestAlg, Render].raiseFunctorK[Result, SyncIO, SyncIO]
    )
  }}.rescue { testError =>
    SyncIO {
      fail(s"unexpected TestError $testError")
    }
  }.unsafeRunSync()

  // ----------------------------------------------------------------- L4, L7

  property("L4 arrow coherence for the carrier-change arrow") {
    forAllErrors { e => implicit H: Handle[SyncIO, TestError] =>
      val law = RaiseArrowLaws.arrowCoherence[Result, SyncIO, Render, TestError, Int](
        CarrierArrows.resultToSyncIO[Render],
        H,
        e
      )
      (law.lhs.attemptHandle, law.rhs.attemptHandle).mapN { (l, r) => assertEquals(l, r); true }
    }
  }

  property("L4 arrow coherence for the identity arrow") {
    forAllErrors { e => (_: Handle[SyncIO, TestError]) =>
      val law = RaiseArrowLaws.arrowCoherence[Result, Result, Render, TestError, Int](
        RaiseArrow.id[Result, Render],
        raiseResult,
        e
      )
      assertEquals(law.lhs, law.rhs)
      // assertEquals already threw if unequal; forAllErrors just needs a SyncIO[Boolean] to satisfy the shared signature.
      true.pure[SyncIO]
    }
  }

  property("L4 arrow coherence for the carrier-change arrow andThen id") {
    forAllErrors { e => implicit H: Handle[SyncIO, TestError] =>
      val law = RaiseArrowLaws.arrowCoherence[Result, SyncIO, Render, TestError, Int](
        CarrierArrows.resultToSyncIO[Render].andThen(RaiseArrow.id[SyncIO, Render]),
        H,
        e
      )
      (law.lhs.attemptHandle, law.rhs.attemptHandle).mapN { (l, r) => assertEquals(l, r); true }
    }
  }

  test("L7 the decorated capability reports the caller's own Functor instance") {
    val caller = raiseResult
    val decorated = RaiseAspect.observing[Result, TestError, Render](caller, OnRaise.noop[Result, Render])
    assert(decorated.functor eq caller.functor)
  }

  // ------------------------------------------------- L4 at Err = Trivial (∀E)

  // `arrowCoherence` takes `implicit ev: Err[E]`, which narrows it from "for
  // all E" to "for all E for which Err[E] exists". `Trivial`'s instance is
  // universal, so instantiating at `Err = Trivial` restores the original
  // quantifier. (The `Render` instantiations above cover the evidence-carrying
  // path.)
  //
  // It must run over a ''non-identity'' arrow to say anything at all: at
  // `RaiseArrow.id` the law reduces to `FunctionK.id(rg.raise(e)) <-> rg.raise(e)`,
  // the same expression on both sides, which holds for every instance and would
  // still hold if the derivation were `???`.
  property("L4 arrow coherence for the carrier-change arrow, at Err = Trivial") {
    forAllErrors { e => implicit H: Handle[SyncIO, TestError] =>
      val law = RaiseArrowLaws.arrowCoherence[Result, SyncIO, Trivial, TestError, Int](
        CarrierArrows.resultToSyncIO[Trivial],
        H,
        e
      )
      (law.lhs.attemptHandle, law.rhs.attemptHandle).mapN { (l, r) => assertEquals(l, r); true }
    }
  }

  testWithHandle[SyncIO, TestError]("L8 the interpreter receives one weave per call, naming the algebra and the method") { implicit H =>
    for {
      pair <- LawsInstances.instrumented[SyncIO](instance, 0)
      (w, recorder) = pair
      _ <- w.a(1)
      _ <- w.b("x", 1)
      _ <- w.c(1)
      _ <- w.d(1)(2)
      _ <- w.e
      weaves <- recorder.weaves
      _ = assertEquals(weaves.map(_.weave.algebraName).toList, List.fill(5)("TestAlg"))
      rendered <- LawsInstances.renderedWeaves(recorder)
      _ = assertEquals(rendered.map(_.methodName), List("a", "b", "c", "d", "e"))
    } yield ()
  }

  testWithHandle[SyncIO, TestError]("L8 the domain matches the declared parameter lists, capabilities absent") { implicit H =>
    for {
      pair <- LawsInstances.instrumented[SyncIO](instance, 0)
      (w, recorder) = pair
      _ <- w.a(7)
      _ <- w.b("ab", 2)
      _ <- w.c(3)
      _ <- w.d(4)(5)
      _ <- w.e
      rendered <- LawsInstances.renderedWeaves(recorder)
      _ = assertEquals(
        rendered.map(_.domain),
        List(
          List(List("i" -> "7")),
          List(List("x" -> "ab", "y" -> "2")),
          List(List("i" -> "3")),
          List(List("i" -> "4"), List("j" -> "5")),
          // every parameter of `e` is a capability, so it contributes no clause
          List.empty[List[(String, String)]]
        )
      )
    } yield ()
  }

  testWithHandle[SyncIO, TestError]("L8 intercepting does not force a by-name argument") { implicit H =>
    for {
      pair <- LawsInstances.instrumented[SyncIO](instance, 0)
      (w, recorder) = pair
      // the empty string makes the underlying implementation raise without
      // touching `y`, so nothing but the weaving itself could force it
      out <- w.b("", throw new RuntimeException("by-name argument was forced")).attemptHandle
      _ = assertEquals(out, EmptyInput("x").asLeft[Int].leftWiden[TestError])
      weaves <- recorder.weaves
      _ = intercept[RuntimeException](weaves.head.weave.domain.head(1).target.value)
    } yield ()
  }

  testWithHandle[SyncIO, TestError]("L8 an intercepted method returns what the underlying call returns") { implicit H =>
    val impl = new GenericTestAlg[SyncIO](0)

    for {
      pair <- LawsInstances.instrumented[SyncIO](instance, 0)
      (w, _) = pair
      _ <- exhaustiveInt.allValues.traverse_ { i =>
        for {
          wa <- w.a(i).attemptHandle
          ia <- impl.a(i).attemptHandle
          _ = assertEquals(wa, ia)
          wc <- w.c(i)
          ic <- impl.c(i)
          _ = assertEquals(wc, ic)
        } yield ()
      }
    } yield ()
  }

  // ------------------------------------------------- L10 laziness parity

  testWithHandle[SyncIO, TestError]("L10 intercepting performs no effects until the result is run") { implicit H =>
    for {
      counter <- Ref.of[SyncIO, Int](0)
      recorder <- RecordingFk[SyncIO, Render, Render]
      inst = instance.intercept(countingAlg(counter))(recorder.fk, OnRaise.noop[SyncIO, Render])
      // built but not yet forced -- nothing has run.
      out = inst.a(1)
      c0 <- counter.get
      _ = assertEquals(c0, 0, "intercepting must not run the underlying effect")
      _ <- out
      c1 <- counter.get
      _ = assertEquals(c1, 1, "running the instrumented result must run the effect exactly once")
    } yield ()
  }

  testWithHandle[SyncIO, TestError]("L10 the intercepted path runs the same number of effects as the plain one") { implicit H =>
    for {
      interceptedCounter <- Ref.of[SyncIO, Int](0)
      plainCounter <- Ref.of[SyncIO, Int](0)
      recorder <- RecordingFk[SyncIO, Render, Render]
      inst = instance.intercept(countingAlg(interceptedCounter))(recorder.fk, OnRaise.noop[SyncIO, Render])
      plain = countingAlg(plainCounter)
      _ <- exhaustiveInt.allValues.traverse_ { i =>
          for {
            throughIntercepted <- inst.a(i).attemptHandle
            throughPlain <- plain.a(i).attemptHandle
          } yield assertEquals(throughIntercepted, throughPlain, s"intercepted and plain results differ for input $i")
        }
      ic <- interceptedCounter.get
      pc <- plainCounter.get
      _ = assertEquals(ic, pc)
    } yield ()
  }

  // ----------------------------------------------------------- Serializable

  checkAll("RaisePull.id.serializable", SerializableTests.serializable(RaisePull.id[Result, Render]))
  checkAll("RaiseArrow.id.serializable", SerializableTests.serializable(RaiseArrow.id[Result, Render]))

  // ------------------------------------------------------------- helpers

  /** A fixture whose effects are observable only when the returned `F` is run. */
  private def countingAlg[F[_] : Sync](counter: Ref[F, Int]): TestAlg[F] =
    new TestAlg[F] {
      private def count[A](a: => A): F[A] = counter.update(_ + 1) *> Sync[F].delay(a)

      def a(i: Int)(implicit R: Raise[F, ErrA]): F[String] =
        if (i < 0) counter.update(_ + 1) *> R.raise(NegativeInput(i))
        else count(s"a:$i")

      def b(x: String, y: => Int)(implicit R: Raise[F, ErrB]): F[Int] =
        if (x.isEmpty) R.raise(EmptyInput("x")) else count(x.length + y)

      def c(i: Int): F[Int] = count(i * 2)

      def d(i: Int)(j: Int)(implicit R: Raise[F, ErrA]): F[Int] =
        if (i + j < 0) R.raise(NegativeInput(i + j)) else count(i + j)

      def e(implicit R1: Raise[F, ErrA], R2: Raise[F, ErrB]): F[Unit] =
        count(())
    }

  private def forAllErrors[F[_] : ApplicativeThrow](f: TestError => Handle[F, TestError] => F[Boolean])(implicit FtoP: F[Boolean] => org.scalacheck.Prop): org.scalacheck.Prop = {
    org.scalacheck.Prop.forAll(Gen.oneOf[TestError](NegativeInput(-1), EmptyInput("boom"))) { e =>
      Handle.allowF[F, TestError] {
        f(e)
      }.rescue { testError =>
        new AssertionError(s"test raised unexpectedly: $testError").raiseError[F, Boolean]
      }
    }
  }
}
