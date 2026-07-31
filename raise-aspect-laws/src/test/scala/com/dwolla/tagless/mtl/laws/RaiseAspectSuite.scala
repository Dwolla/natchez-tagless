package com.dwolla.tagless.mtl
package laws

import cats.Eval
import cats.data.EitherT
import cats.kernel.laws.discipline.SerializableTests
import cats.mtl.Raise
import cats.syntax.all._
import cats.tagless.Trivial
import laws.discipline.RaiseAspectTests
import munit.DisciplineSuite
import org.scalacheck.{Arbitrary, Gen}

import LawsInstances._
import TestError._

/** The complete law suite for a `RaiseAspect[TestAlg, Render, Render, Render]`.
  *
  * ==This is the substitution seam.==
  *
  * The instance under test is abstract. M2 runs it against M1's hand-written
  * reference instance ([[ReferenceRaiseAspectSpec]]); M3 and M4 re-run this
  * same suite wholesale by extending it and supplying a macro-derived instance
  * instead. Nothing below may be weakened to make a derived instance pass — a
  * failure here is a finding about the derivation, not about the laws.
  */
abstract class RaiseAspectSuite extends DisciplineSuite {

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
  // `Either[TestError, *]` to `EitherT[Eval, TestError, *]` — with a real pull
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
      assertEquals(law.lhs.value.value, law.rhs.value.value)
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
    }
  }

  property("L4 arrow coherence for the carrier-change arrow andThen id") {
    forAllErrors { e =>
      val law = RaiseArrowLaws.arrowCoherence[Result, Lazily, Render, TestError, Int](
        CarrierArrows.resultToLazily[Render].andThen(RaiseArrow.id[Lazily, Render]),
        raiseLazily,
        e
      )
      assertEquals(law.lhs.value.value, law.rhs.value.value)
    }
  }

  // L7 used to be an extensional property about the functor `raisePull`
  // synthesized for the woven carrier. The fused derivation never puts a
  // capability on that carrier: `RaiseAspect.observing` decorates the caller's
  // own `Raise[F, E]` and takes `functor` straight off it. What was a property
  // to check is now an identity to assert.
  test("L7 the decorated capability reports the caller's own Functor instance") {
    val caller = raiseResult
    val decorated = RaiseAspect.observing[Result, TestError, Render](caller, OnRaise.noop[Result, Render])
    assert(decorated.functor eq caller.functor)
  }

  // ------------------------------------------------- L4 at Err = Trivial (∀E)

  // `arrowCoherence` takes `implicit ev: Err[E]`, which narrows it from "for
  // all E" to "for all E for which Err[E] exists". `Trivial`'s instance is
  // universal, so instantiating at `Err = Trivial` restores the original
  // quantifier. The `Render` instantiations above cover the evidence-carrying
  // path; this one covers the strength the law had before M10.
  //
  // It must run over a ''non-identity'' arrow to say anything at all: at
  // `RaiseArrow.id` the law reduces to `FunctionK.id(rg.raise(e)) <-> rg.raise(e)`,
  // the same expression on both sides, which holds for every instance and would
  // still hold if the derivation were `???`. Before M12 this slot ran over
  // `eraseWeave`, which was parametric in `Err`; `CarrierArrows.resultToLazily`
  // is parametric for the same reason — its pull never consults the evidence —
  // so it fills the slot with the same strength.
  property("L4 arrow coherence for the carrier-change arrow, at Err = Trivial") {
    forAllErrors { e =>
      val law = RaiseArrowLaws.arrowCoherence[Result, Lazily, Trivial, TestError, Int](
        CarrierArrows.resultToLazily[Trivial],
        raiseLazily,
        e
      )
      assertEquals(law.lhs.value.value, law.rhs.value.value)
    }
  }

  // ------------------------------------------ L8 weave structure fidelity

  // After M12 there is no `Alg[Weave[…]]` value to reach into: `intercept`
  // hands each weave to `fk` and returns `F[A]`. What the interpreter receives
  // is therefore the whole observable surface of weaving, and these tests read
  // it off a recorder — which additionally pins arrival order, something
  // inspecting a returned value could not do.

  test("L8 the interpreter receives one weave per call, naming the algebra and the method") {
    val (w, recorder) = LawsInstances.instrumented(instance, 0)

    w.a(1)(raiseResult)
    w.b("x", 1)(raiseResult)
    w.c(1)
    w.d(1)(2)(raiseResult)
    w.e(raiseResult, raiseResult)

    assertEquals(recorder.weaves.map(_.weave.algebraName), List.fill(5)("TestAlg"))
    assertEquals(LawsInstances.renderedWeaves(recorder).map(_.methodName), List("a", "b", "c", "d", "e"))
  }

  test("L8 the domain matches the declared parameter lists, capabilities absent") {
    val (w, recorder) = LawsInstances.instrumented(instance, 0)

    w.a(7)(raiseResult)
    w.b("ab", 2)(raiseResult)
    w.c(3)
    w.d(4)(5)(raiseResult)
    w.e(raiseResult, raiseResult)

    assertEquals(
      LawsInstances.renderedWeaves(recorder).map(_.domain),
      List(
        List(List("i" -> "7")),
        List(List("x" -> "ab", "y" -> "2")),
        List(List("i" -> "3")),
        List(List("i" -> "4"), List("j" -> "5")),
        // every parameter of `e` is a capability, so it contributes no clause
        List.empty[List[(String, String)]]
      )
    )
  }

  test("L8 intercepting does not force a by-name argument") {
    val (w, recorder) = LawsInstances.instrumented(instance, 0)

    // the empty string makes the underlying implementation raise without
    // touching `y`, so nothing but the weaving itself could force it
    val out = w.b("", throw new RuntimeException("by-name argument was forced"))(raiseResult)

    assertEquals(out, EmptyInput("x").asLeft[Int].leftWiden[TestError])
    intercept[RuntimeException](recorder.weaves.head.weave.domain.head(1).target.value)
  }

  test("L8 an intercepted method returns what the underlying call returns") {
    val impl = new EitherTestAlg(0)
    val (w, _) = LawsInstances.instrumented(instance, 0)

    exhaustiveInt.allValues.foreach { i =>
      assertEquals(w.a(i)(raiseResult), impl.a(i)(raiseResult))
      assertEquals(w.c(i), impl.c(i))
    }
  }

  // ------------------------------------------------- L10 laziness parity

  test("L10 intercepting performs no effects until the result is run") {
    val counter = new java.util.concurrent.atomic.AtomicInteger(0)
    val recorder = new RecordingFk[Lazily, Render, Render]

    val inst = instance.intercept(countingAlg(counter))(recorder.fk, OnRaise.noop[Lazily, Render])
    val out = inst.a(1)(raiseLazily)
    assertEquals(counter.get(), 0, "intercepting must not run the underlying effect")

    val _ = out.value.value
    assertEquals(counter.get(), 1, "running the instrumented result must run the effect exactly once")
  }

  test("L10 the intercepted path runs the same number of effects as the plain one") {
    val interceptedCounter = new java.util.concurrent.atomic.AtomicInteger(0)
    val plainCounter = new java.util.concurrent.atomic.AtomicInteger(0)

    val recorder = new RecordingFk[Lazily, Render, Render]
    val inst = instance.intercept(countingAlg(interceptedCounter))(recorder.fk, OnRaise.noop[Lazily, Render])
    val plain = countingAlg(plainCounter)

    exhaustiveInt.allValues.foreach { i =>
      val throughIntercepted = inst.a(i)(raiseLazily).value.value
      val throughPlain = plain.a(i)(raiseLazily).value.value
      assertEquals(throughIntercepted, throughPlain, s"intercepted and plain results differ for input $i")
    }

    assertEquals(interceptedCounter.get(), plainCounter.get())
  }

  // ----------------------------------------------------------- Serializable

  checkAll("RaisePull.id.serializable", SerializableTests.serializable(RaisePull.id[Result, Render]))
  checkAll("RaiseArrow.id.serializable", SerializableTests.serializable(RaiseArrow.id[Result, Render]))

  // ------------------------------------------------------------- helpers

  /** A fixture whose effects are observable only when the `Eval` is forced. */
  private def countingAlg(counter: java.util.concurrent.atomic.AtomicInteger): TestAlg[Lazily] =
    new TestAlg[Lazily] {
      private def count[A](a: => A): Lazily[A] =
        EitherT(Eval.always { val _ = counter.incrementAndGet(); a.asRight[TestError] })

      def a(i: Int)(implicit R: Raise[Lazily, ErrA]): Lazily[String] =
        if (i < 0) EitherT(Eval.always { val _ = counter.incrementAndGet(); NegativeInput(i).asLeft[String].leftWiden[TestError] })
        else count(s"a:$i")

      def b(x: String, y: => Int)(implicit R: Raise[Lazily, ErrB]): Lazily[Int] =
        if (x.isEmpty) R.raise(EmptyInput("x")) else count(x.length + y)

      def c(i: Int): Lazily[Int] = count(i * 2)

      def d(i: Int)(j: Int)(implicit R: Raise[Lazily, ErrA]): Lazily[Int] =
        if (i + j < 0) R.raise(NegativeInput(i + j)) else count(i + j)

      def e(implicit R1: Raise[Lazily, ErrA], R2: Raise[Lazily, ErrB]): Lazily[Unit] =
        count(())
    }

  private def forAllErrors(f: TestError => Unit): org.scalacheck.Prop =
    org.scalacheck.Prop.forAll(Gen.oneOf[TestError](NegativeInput(-1), EmptyInput("boom")))(e => {
      f(e); true
    })
}
