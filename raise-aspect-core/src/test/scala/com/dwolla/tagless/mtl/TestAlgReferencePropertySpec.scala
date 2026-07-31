package com.dwolla.tagless.mtl

import cats.mtl.Raise
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll

/** The property-based form of the erasure smoke test: for arbitrary inputs,
  * intercepting the reference instance with the forgetful interpreter must
  * agree with the underlying algebra, on both success and raise paths.
  *
  * This is deliberately a smoke test, not law L3′ — the formal law suite and
  * its discipline `RuleSet`s are milestone M2's.
  */
class TestAlgReferencePropertySpec extends ScalaCheckSuite {
  private type F[A] = Either[TestError, A]

  private val raiseF: Raise[F, TestError] = Raise[F, TestError]

  private val ref: RaiseAspect[TestAlg, Render, Render, Render] =
    TestAlgReference.referenceRaiseAspect[Render, Render, Render]

  private def erased(impl: TestAlg[F]): TestAlg[F] =
    ref.intercept(impl)(WeaveArrows.codomainTarget[F, Render, Render], OnRaise.noop[F, Render])

  property("erasure preserves the result of every method, raising or not") {
    forAll { (i: Int, x: String, y: Int, j: Int, eOutcome: Int) =>
      val impl = new EitherTestAlg(eOutcome)
      val e = erased(impl)

      assertEquals(e.a(i)(raiseF), impl.a(i)(raiseF))
      assertEquals(e.b(x, y)(raiseF), impl.b(x, y)(raiseF))
      assertEquals(e.c(i), impl.c(i))
      assertEquals(e.d(i)(j)(raiseF), impl.d(i)(j)(raiseF))
      assertEquals(e.e(raiseF, raiseF), impl.e(raiseF, raiseF))
    }
  }

  property("weaving reports the algebra and method names for every input") {
    forAll { (i: Int, eOutcome: Int) =>
      val recorder = new RecordingFk[F, Render, Render]
      val w = ref.intercept(new EitherTestAlg(eOutcome))(recorder.fk, OnRaise.noop[F, Render])
      val _ = w.c(i)

      val weave = recorder.weaves.head.weave
      assertEquals(weave.algebraName, "TestAlg")
      assertEquals(weave.codomain.name, "c")
      assertEquals(weave.domain.map(_.map(_.name)), List(List("i")))
    }
  }
}
