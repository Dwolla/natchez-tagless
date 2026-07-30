package com.dwolla.tagless.mtl
package laws

import cats.data.EitherT
import cats.kernel.laws.discipline.SerializableTests
import cats.mtl.Raise
import cats.syntax.all._
import cats.tagless.Trivial
import cats.tagless.aop.Aspect
import cats.{Eval, Functor}
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

  private val functorResult: Functor[Result] = Functor[Result]

  private def wovenAlg(eOutcome: Int): TestAlg[Woven] =
    instance.weave(new EitherTestAlg(eOutcome))(functorResult)

  private implicit val arbTestAlgWoven: Arbitrary[TestAlg[Woven]] =
    Arbitrary(Gen.oneOf(-1, 0, 1).map(wovenAlg))

  private implicit val arbTestAlgResult: Arbitrary[TestAlg[Result]] =
    Arbitrary(Gen.oneOf(-1, 0, 1).map(new EitherTestAlg(_)))

  private implicit val arbEraseArrow: Arbitrary[RaiseArrow[Woven, Result, Render]] =
    Arbitrary(Gen.const(WeaveArrows.eraseWeave[Result, Render, Render, Render]))

  private implicit val arbIdArrow: Arbitrary[RaiseArrow[Result, Result, Render]] =
    Arbitrary(Gen.const(RaiseArrow.id[Result, Render]))

  // ------------------------------------------------- L1, L2, L3 (discipline)

  // L3 lives at the base effect: `weave` needs a `Functor` for the effect it is
  // weaving, and weaving an already-woven algebra is not a thing we support.
  checkAll(
    "RaiseAspect[TestAlg, Render, Render, Render]",
    RaiseAspectTests[TestAlg, Render, Render, Render].raiseAspect[Result, Result, Result]
  )

  // ...and L1/L2 again over the genuinely non-trivial arrow, `eraseWeave`,
  // which the all-identity instantiation above cannot exercise.
  checkAll(
    "RaiseFunctorK[TestAlg] over erasure arrows",
    laws.discipline.RaiseFunctorKTests[TestAlg, Render].raiseFunctorK[Woven, Result, Result]
  )

  // ------------------------------------------------------------ L4, L5, L6, L7

  property("L4 arrow coherence for eraseWeave") {
    forAllErrors { e =>
      val law = RaiseArrowLaws.arrowCoherence[Woven, Result, Render, TestError, Int](
        WeaveArrows.eraseWeave[Result, Render, Render, Render],
        raiseResult,
        e
      )
      assertEquals(law.lhs, law.rhs)
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

  property("L4 arrow coherence for eraseWeave andThen id") {
    forAllErrors { e =>
      val law = RaiseArrowLaws.arrowCoherence[Woven, Result, Render, TestError, Int](
        WeaveArrows.eraseWeave[Result, Render, Render, Render].andThen(RaiseArrow.id[Result, Render]),
        raiseResult,
        e
      )
      assertEquals(law.lhs, law.rhs)
    }
  }

  property("L5 raisePull is a retraction of raiseLift") {
    forAllErrors { e =>
      val law = RaiseArrowLaws.sectionRetraction[Result, Render, Render, Render, TestError, Int](raiseResult, e)
      assertEquals(law.lhs, law.rhs)
    }
  }

  // ------------------------------------------- L4/L5 at Err = Trivial (∀E)

  // Adding `implicit ev: Err[E]` to the laws narrows them from "for all E" to
  // "for all E for which Err[E] exists". `Trivial`'s instance is universal, so
  // this instantiation restores the original quantifier. The `Render`
  // instantiations above cover the evidence-carrying path; these cover the
  // strength the laws had before M10.
  property("L4 arrow coherence for eraseWeave, at Err = Trivial") {
    forAllErrors { e =>
      val law = RaiseArrowLaws.arrowCoherence[Woven, Result, Trivial, TestError, Int](
        WeaveArrows.eraseWeave[Result, Render, Render, Trivial],
        raiseResult,
        e
      )
      assertEquals(law.lhs, law.rhs)
    }
  }

  property("L5 section/retraction, at Err = Trivial") {
    forAllErrors { e =>
      val law = RaiseArrowLaws.sectionRetraction[Result, Render, Render, Trivial, TestError, Int](
        raiseResult,
        e
      )
      assertEquals(law.lhs, law.rhs)
    }
  }

  property("L6a the synthesized functor maps the codomain target") {
    forAllInts { i =>
      val law = RaiseArrowLaws.liftedFunctorMapsTarget[Result, Render, Render, Render, TestError, Int, Int](
        raiseResult,
        sampleWeave(i),
        _ + 1
      )
      assertEquals(law.lhs, law.rhs)
    }
  }

  property("L6b/c/d the synthesized functor preserves the weave metadata") {
    forAllInts { i =>
      val w = sampleWeave(i)
      val algebraName =
        RaiseArrowLaws.liftedFunctorPreservesAlgebraName[Result, Render, Render, Render, TestError, Int, Int](
          raiseResult,
          w,
          _ + 1
        )
      val codomainName =
        RaiseArrowLaws.liftedFunctorPreservesCodomainName[Result, Render, Render, Render, TestError, Int, Int](
          raiseResult,
          w,
          _ + 1
        )
      val domain =
        RaiseArrowLaws.liftedFunctorPreservesDomain[Result, Render, Render, Render, TestError, Int, Int](
          raiseResult,
          w,
          _ + 1
        )

      assertEquals(algebraName.lhs, algebraName.rhs)
      assertEquals(codomainName.lhs, codomainName.rhs)
      assertEquals(domain.lhs, domain.rhs)
    }
  }

  property("L7 the pulled capability reports the ambient Functor[F]") {
    forAllInts { i =>
      val law = RaiseArrowLaws.pulledFunctorIsAmbient[Result, Render, Render, Render, TestError, Int, Int](
        raiseWoven,
        i.asRight[TestError],
        _ + 1
      )
      assertEquals(law.lhs, law.rhs)
    }
  }

  test("L7 the pulled capability uses the ambient Functor instance itself") {
    val pulled = WeaveArrows.raisePull[Result, Render, Render, Render](functorResult).apply(raiseWoven)
    assert(pulled.functor eq functorResult)
  }

  // ------------------------------------------ L8 weave structure fidelity

  test("L8 the woven algebra reports the algebra name and method names") {
    val w = wovenAlg(0)
    assertEquals(WeaveRenderer.render(w.a(1)(raiseWoven)).algebraName, "TestAlg")
    assertEquals(WeaveRenderer.render(w.a(1)(raiseWoven)).methodName, "a")
    assertEquals(WeaveRenderer.render(w.b("x", 1)(raiseWoven)).methodName, "b")
    assertEquals(WeaveRenderer.render(w.c(1)).methodName, "c")
    assertEquals(WeaveRenderer.render(w.d(1)(2)(raiseWoven)).methodName, "d")
    assertEquals(WeaveRenderer.render(w.e(raiseWoven, raiseWoven)).methodName, "e")
  }

  test("L8 the domain matches the declared parameter lists, capabilities absent") {
    val w = wovenAlg(0)
    assertEquals(WeaveRenderer.render(w.a(7)(raiseWoven)).domain, List(List("i" -> "7")))
    assertEquals(WeaveRenderer.render(w.b("ab", 2)(raiseWoven)).domain, List(List("x" -> "ab", "y" -> "2")))
    assertEquals(WeaveRenderer.render(w.c(3)).domain, List(List("i" -> "3")))
    assertEquals(WeaveRenderer.render(w.d(4)(5)(raiseWoven)).domain, List(List("i" -> "4"), List("j" -> "5")))
    // every parameter of `e` is a capability, so it contributes no clause
    assertEquals(WeaveRenderer.render(w.e(raiseWoven, raiseWoven)).domain, List.empty[List[(String, String)]])
  }

  test("L8 weaving does not force a by-name argument") {
    val w = wovenAlg(0)
    // the empty string makes the underlying implementation raise without
    // touching `y`, so nothing but the weaving itself could force it
    val weave = w.b("", throw new RuntimeException("by-name argument was forced"))(raiseWoven)

    assertEquals(weave.codomain.target, EmptyInput("x").asLeft[Int].leftWiden[TestError])
    intercept[RuntimeException](weave.domain.head(1).target.value)
  }

  test("L8 the codomain target is the underlying call with the capability pulled") {
    val impl = new EitherTestAlg(0)
    val w = instance.weave(impl)(functorResult)

    exhaustiveInt.allValues.foreach { i =>
      assertEquals(w.a(i)(raiseWoven).codomain.target, impl.a(i)(raiseResult))
      assertEquals(w.c(i).codomain.target, impl.c(i))
    }
  }

  test("L8 the synthesized Cod instance never appears in a woven method's domain") {
    val w = wovenAlg(0)
    val rendered = List(
      WeaveRenderer.render(w.a(1)(raiseWoven)),
      WeaveRenderer.render(w.b("x", 1)(raiseWoven)),
      WeaveRenderer.render(w.d(1)(2)(raiseWoven))
    )
    assert(!rendered.flatMap(_.domain.flatten.map(_._2)).contains("<synthetic>"))
  }

  // ------------------------------------------------- L10 laziness parity

  test("L10 weaving performs no effects until the result is run") {
    val counter = new java.util.concurrent.atomic.AtomicInteger(0)
    val impl = countingAlg(counter)

    val woven = instance.weave(impl)(Functor[Lazily])
    val weave = woven.a(1)(liftedLazily)
    assertEquals(counter.get(), 0, "weaving must not run the underlying effect")

    val _ = weave.codomain.target.value.value
    assertEquals(counter.get(), 1, "running the woven result must run the effect exactly once")
  }

  test("L10 the woven path runs the same number of effects as the unwoven one") {
    val wovenCounter = new java.util.concurrent.atomic.AtomicInteger(0)
    val plainCounter = new java.util.concurrent.atomic.AtomicInteger(0)

    val woven = instance.weave(countingAlg(wovenCounter))(Functor[Lazily])
    val plain = countingAlg(plainCounter)

    exhaustiveInt.allValues.foreach { i =>
      val throughWoven = woven.a(i)(liftedLazily).codomain.target.value.value
      val throughPlain = plain.a(i)(raiseLazily).value.value
      assertEquals(throughWoven, throughPlain, s"woven and unwoven results differ for input $i")
    }

    assertEquals(wovenCounter.get(), plainCounter.get())
  }

  // ----------------------------------------------------------- Serializable

  checkAll("Synthetic[Trivial].serializable", SerializableTests.serializable(Synthetic.trivial))
  checkAll("RaisePull.id.serializable", SerializableTests.serializable(RaisePull.id[Result, Render]))
  checkAll("RaiseArrow.id.serializable", SerializableTests.serializable(RaiseArrow.id[Result, Render]))
  checkAll(
    "WeaveArrows.eraseWeave.serializable",
    SerializableTests.serializable(WeaveArrows.eraseWeave[Result, Render, Render, Render])
  )

  // ------------------------------------------------------------- helpers

  private def sampleWeave(i: Int): Aspect.Weave[Result, Render, Render, Int] =
    wovenAlg(0).c(i)

  private val raiseLazily: Raise[Lazily, TestError] = Raise[Lazily, TestError]

  private val liftedLazily: Raise[Aspect.Weave[Lazily, Render, Render, *], TestError] =
    WeaveArrows.raiseLift[Lazily, Render, Render, Render].apply(raiseLazily)

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

  private def forAllInts(f: Int => Unit): org.scalacheck.Prop =
    org.scalacheck.Prop.forAll(Gen.oneOf(exhaustiveInt.allValues))(i => { f(i); true })
}
