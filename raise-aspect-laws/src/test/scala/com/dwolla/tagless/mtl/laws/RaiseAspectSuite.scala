package com.dwolla.tagless.mtl
package laws

import cats.data.EitherT
import cats.effect.{Ref, Sync, SyncIO}
import cats.effect.testkit.TestInstances
import cats.kernel.laws.discipline.SerializableTests
import cats.mtl.Raise
import cats.syntax.all.*
import cats.tagless.Trivial
import com.dwolla.tagless.mtl.TestError.*
import com.dwolla.tagless.mtl.laws.LawsInstances.*
import com.dwolla.tagless.mtl.laws.discipline.RaiseAspectTests
import munit.{CatsEffectSuite, DisciplineSuite}
import org.scalacheck.{Arbitrary, Gen}

import SyncIOTestSyntax.*

/** The complete law test suite for a `RaiseAspect[TestAlg, Render, Render, Render]`.
  */
abstract class RaiseAspectSuite extends CatsEffectSuite with DisciplineSuite with TestInstances {

  /** The instance under test. Override this and nothing else. */
  def instance: RaiseAspect[TestAlg, Render, Render, Render]

  private implicit def instanceUnderTest: RaiseAspect[TestAlg, Render, Render, Render] = instance

  private val raiseLazily: Raise[Lazily, TestError] = Raise[Lazily, TestError]

  private implicit val arbTestAlgResult: Arbitrary[TestAlg[Result]] =
    Arbitrary(Gen.oneOf(-1, 0, 1).map(new EitherTestAlg(_)))

  private implicit val arbIdArrow: Arbitrary[RaiseArrow[Result, Result, Render]] =
    Arbitrary(Gen.const(RaiseArrow.id[Result, Render]))

  private implicit val arbIdArrowLazily: Arbitrary[RaiseArrow[Lazily, Lazily, Render]] =
    Arbitrary(Gen.const(RaiseArrow.id[Lazily, Render]))

  // ------------------------------------------------ L1, L2, L3′ (discipline)

  // L3′ lives at the base effect: `intercept` is carrier-preserving, so the
  // effect it erases into is the one the algebra already speaks.
  checkAll(
    "RaiseAspect[TestAlg, Render, Render, Render]",
    RaiseAspectTests[TestAlg, Render, Render, Render].raiseAspect[Result, Result, Result]
  )

  // ...and L1/L2 again over a genuinely non-trivial arrow. Before M12 that was
  // `eraseWeave`, between the woven carrier and `Result`; fusion deletes both
  // ends of it. `CarrierArrows.resultToLazily` is a real change of effect —
  // `Either[TestError, *]` to `EitherT[SyncIO, TestError, *]` — with a real pull
  // in the opposite direction. Testing mapK only at the identity arrow would
  // be a coverage loss disguised as a deletion.
  checkAll(
    "RaiseFunctorK[TestAlg] over a genuine carrier change",
    laws.discipline.RaiseFunctorKTests[TestAlg, Render].raiseFunctorK[Result, Lazily, Lazily]
  )

  // ----------------------------------------------------------------- L4, L7

  property("L4 arrow coherence for the carrier-change arrow") {
    forAllErrors { e =>
      val law = RaiseArrowLaws.arrowCoherence[Result, Lazily, Render, TestError, Int](
        CarrierArrows.resultToLazily[Render],
        raiseLazily,
        e
      )
      (law.lhs.value, law.rhs.value).mapN { (l, r) => assertEquals(l, r); true }
    }
  }

  property("L4 arrow coherence for the identity arrow") {
    forAllErrors { e =>
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
    forAllErrors { e =>
      val law = RaiseArrowLaws.arrowCoherence[Result, Lazily, Render, TestError, Int](
        CarrierArrows.resultToLazily[Render].andThen(RaiseArrow.id[Lazily, Render]),
        raiseLazily,
        e
      )
      (law.lhs.value, law.rhs.value).mapN { (l, r) => assertEquals(l, r); true }
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
    forAllErrors { e =>
      val law = RaiseArrowLaws.arrowCoherence[Result, Lazily, Trivial, TestError, Int](
        CarrierArrows.resultToLazily[Trivial],
        raiseLazily,
        e
      )
      (law.lhs.value, law.rhs.value).mapN { (l, r) => assertEquals(l, r); true }
    }
  }

  test("L8 the interpreter receives one weave per call, naming the algebra and the method") {
    (for {
      pair <- LawsInstances.instrumented(instance, 0)
      (w, recorder) = pair
      _ <- w.a(1)(raiseLazily)
      _ <- w.b("x", 1)(raiseLazily)
      _ <- w.c(1)
      _ <- w.d(1)(2)(raiseLazily)
      _ <- w.e(raiseLazily, raiseLazily)
      weaves <- recorder.weaves
      _ = assertEquals(weaves.map(_.weave.algebraName).toList, List.fill(5)("TestAlg"))
      rendered <- LawsInstances.renderedWeaves(recorder)
      _ = assertEquals(rendered.map(_.methodName), List("a", "b", "c", "d", "e"))
    } yield ()).runOrFail
  }

  test("L8 the domain matches the declared parameter lists, capabilities absent") {
    (for {
      pair <- LawsInstances.instrumented(instance, 0)
      (w, recorder) = pair
      _ <- w.a(7)(raiseLazily)
      _ <- w.b("ab", 2)(raiseLazily)
      _ <- w.c(3)
      _ <- w.d(4)(5)(raiseLazily)
      _ <- w.e(raiseLazily, raiseLazily)
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
    } yield ()).runOrFail
  }

  test("L8 intercepting does not force a by-name argument") {
    (for {
      pair <- LawsInstances.instrumented(instance, 0)
      (w, recorder) = pair
      // the empty string makes the underlying implementation raise without
      // touching `y`, so nothing but the weaving itself could force it
      out <- EitherT.liftF[SyncIO, TestError, Either[TestError, Int]](
        w.b("", throw new RuntimeException("by-name argument was forced"))(raiseLazily).value
      )
      _ = assertEquals(out, EmptyInput("x").asLeft[Int].leftWiden[TestError])
      weaves <- recorder.weaves
      _ = intercept[RuntimeException](weaves.head.weave.domain.head(1).target.value)
    } yield ()).runOrFail
  }

  test("L8 an intercepted method returns what the underlying call returns") {
    val impl = new GenericTestAlg[Lazily](0)

    (for {
      pair <- LawsInstances.instrumented(instance, 0)
      (w, _) = pair
      _ <- EitherT.liftF[SyncIO, TestError, Unit](
        exhaustiveInt.allValues.toList.traverse_ { i =>
          for {
            wa <- w.a(i)(raiseLazily).value
            ia <- impl.a(i)(raiseLazily).value
            _ = assertEquals(wa, ia)
            wc <- w.c(i).value
            ic <- impl.c(i).value
            _ = assertEquals(wc, ic)
          } yield ()
        }
      )
    } yield ()).runOrFail
  }

  // ------------------------------------------------- L10 laziness parity

  test("L10 intercepting performs no effects until the result is run") {
    (for {
      counter <- Ref.of[Lazily, Int](0)
      recorder <- RecordingFk[Lazily, Render, Render]
      inst = instance.intercept(countingAlg(counter))(recorder.fk, OnRaise.noop[Lazily, Render])
      // built but not yet forced -- nothing has run.
      out = inst.a(1)(raiseLazily)
      c0 <- counter.get
      _ = assertEquals(c0, 0, "intercepting must not run the underlying effect")
      _ <- out
      c1 <- counter.get
      _ = assertEquals(c1, 1, "running the instrumented result must run the effect exactly once")
    } yield ()).runOrFail
  }

  test("L10 the intercepted path runs the same number of effects as the plain one") {
    (for {
      interceptedCounter <- Ref.of[Lazily, Int](0)
      plainCounter <- Ref.of[Lazily, Int](0)
      recorder <- RecordingFk[Lazily, Render, Render]
      inst = instance.intercept(countingAlg(interceptedCounter))(recorder.fk, OnRaise.noop[Lazily, Render])
      plain = countingAlg(plainCounter)
      _ <- EitherT.liftF[SyncIO, TestError, Unit](
        exhaustiveInt.allValues.toList.traverse_ { i =>
          for {
            throughIntercepted <- inst.a(i)(raiseLazily).value
            throughPlain <- plain.a(i)(raiseLazily).value
          } yield assertEquals(throughIntercepted, throughPlain, s"intercepted and plain results differ for input $i")
        }
      )
      ic <- interceptedCounter.get
      pc <- plainCounter.get
      _ = assertEquals(ic, pc)
    } yield ()).runOrFail
  }

  // ----------------------------------------------------------- Serializable

  checkAll("RaisePull.id.serializable", SerializableTests.serializable(RaisePull.id[Result, Render]))
  checkAll("RaiseArrow.id.serializable", SerializableTests.serializable(RaiseArrow.id[Result, Render]))

  // ------------------------------------------------------------- helpers

  /** A fixture whose effects are observable only when the returned `Lazily` is run. */
  private def countingAlg(counter: Ref[Lazily, Int]): TestAlg[Lazily] =
    new TestAlg[Lazily] {
      private def count[A](a: => A): Lazily[A] = counter.update(_ + 1) *> Sync[Lazily].delay(a)

      def a(i: Int)(implicit R: Raise[Lazily, ErrA]): Lazily[String] =
        if (i < 0) counter.update(_ + 1) *> EitherT.leftT[SyncIO, String](NegativeInput(i): TestError)
        else count(s"a:$i")

      def b(x: String, y: => Int)(implicit R: Raise[Lazily, ErrB]): Lazily[Int] =
        if (x.isEmpty) R.raise(EmptyInput("x")) else count(x.length + y)

      def c(i: Int): Lazily[Int] = count(i * 2)

      def d(i: Int)(j: Int)(implicit R: Raise[Lazily, ErrA]): Lazily[Int] =
        if (i + j < 0) R.raise(NegativeInput(i + j)) else count(i + j)

      def e(implicit R1: Raise[Lazily, ErrA], R2: Raise[Lazily, ErrB]): Lazily[Unit] =
        count(())
    }

  private def forAllErrors(f: TestError => SyncIO[Boolean]): org.scalacheck.Prop =
    org.scalacheck.Prop.forAll(Gen.oneOf[TestError](NegativeInput(-1), EmptyInput("boom")))(f)
}
