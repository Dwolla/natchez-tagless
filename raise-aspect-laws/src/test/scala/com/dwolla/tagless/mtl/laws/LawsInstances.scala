package com.dwolla.tagless.mtl
package laws

import cats.Eq
import cats.data.EitherT
import cats.laws.discipline.ExhaustiveCheck
import cats.mtl.Raise
import cats.Eval
import org.scalacheck.{Arbitrary, Gen}

/** Shared `Eq`, `ExhaustiveCheck` and rendering instances for the law suites. */
object LawsInstances {

  type Result[A] = Either[TestError, A]
  type Lazily[A] = EitherT[Eval, TestError, A]

  /** Small exhaustive domains. Both include values that make the fixture raise
    * — negatives for `a`/`d`, the empty string for `b` — so L3′ and L4 exercise
    * the error path rather than only the happy one.
    */
  implicit val exhaustiveInt: ExhaustiveCheck[Int] =
    ExhaustiveCheck.instance(List(-2, -1, 0, 1, 3))

  implicit val exhaustiveString: ExhaustiveCheck[String] =
    ExhaustiveCheck.instance(List("", "x", "ab"))

  /** `Render` is the test stand-in for natchez's `TraceableValue`. */
  implicit val renderableRender: Renderable[Render] =
    new Renderable[Render] {
      def render[A](instance: Render[A])(a: A): String = instance.render(a)
    }

  /** The error hierarchy is all case classes, so structural equality is right. */
  implicit val eqTestError: Eq[TestError] = Eq.fromUniversalEquals

  /** Deliberately not `implicit`. `Raise`'s companion already supplies
    * `Raise[Result, TestError]` — and, because `Raise` is contravariant in `E`,
    * the `Raise[Result, ErrA]` and `Raise[Result, ErrB]` that [[eqTestAlg]] asks
    * for — so nothing about `Result` needs to be in implicit scope here. The val
    * exists to name one instance for the value-level laws, which take the
    * capability as an explicit parameter.
    *
    * Adding `implicit` would also be a trap: the summon on the right would then
    * resolve to the val being defined and initialize it to `null`, surfacing
    * much later as an NPE at the first `raise`.
    */
  val raiseResult: Raise[Result, TestError] = Raise[Result, TestError]

  /** The non-identity `RaiseArrow` L1/L2 are exercised over.
    *
    * Until M12 that role belonged to `WeaveArrows.eraseWeave`, whose source
    * carrier was the woven one; fusion deletes both the arrow and the carrier.
    * [[CarrierArrows.resultToLazily]] is a genuine change of effect —
    * `Either[TestError, *]` to `EitherT[Eval, TestError, *]` — with a real
    * capability transport in the opposite direction, so `mapK` stays tested at
    * something other than the identity.
    */
  implicit val arbResultToLazily: Arbitrary[RaiseArrow[Result, Lazily, Render]] =
    Arbitrary(Gen.const(CarrierArrows.resultToLazily))

  /** Intercept the algebra under test with a recording interpreter. The pair
    * is the fused replacement for `weave` — the algebra behaves as though it
    * had been woven and immediately erased, and the recorder holds the
    * structure that used to be inspectable on the returned value.
    */
  def instrumented(
      instance: RaiseAspect[TestAlg, Render, Render, Render],
      eOutcome: Int
  ): (TestAlg[Result], RecordingFk[Result, Render, Render]) = {
    val recorder = new RecordingFk[Result, Render, Render]
    (instance.intercept(new EitherTestAlg(eOutcome))(recorder.fk, OnRaise.noop[Result, Render]), recorder)
  }

  /** What the interpreter saw, rendered — the fused analogue of mapping
    * `WeaveRenderer.render` over a list of returned weaves, and stricter,
    * because the list is in arrival order.
    */
  def renderedWeaves(recorder: RecordingFk[Result, Render, Render]): List[RenderedWeave] =
    recorder.weaves.map(r => WeaveRenderer.render(r.weave))

  /** `Eq` for the fixture algebra by sampling: two algebras are equal when
    * every method agrees on every input drawn from the exhaustive domains.
    */
  implicit def eqTestAlg[F[_]](implicit
      eqString: Eq[F[String]],
      eqInt: Eq[F[Int]],
      eqUnit: Eq[F[Unit]],
      rA: Raise[F, ErrA],
      rB: Raise[F, ErrB]
  ): Eq[TestAlg[F]] =
    Eq.instance { (x, y) =>
      exhaustiveInt.allValues.forall(i => eqString.eqv(x.a(i)(rA), y.a(i)(rA))) &&
      exhaustiveString.allValues.forall { s =>
        exhaustiveInt.allValues.forall(i => eqInt.eqv(x.b(s, i)(rB), y.b(s, i)(rB)))
      } &&
      exhaustiveInt.allValues.forall(i => eqInt.eqv(x.c(i), y.c(i))) &&
      exhaustiveInt.allValues.forall { i =>
        exhaustiveInt.allValues.forall(j => eqInt.eqv(x.d(i)(j)(rA), y.d(i)(j)(rA)))
      } &&
      eqUnit.eqv(x.e(rA, rB), y.e(rA, rB))
    }

  implicit def eqPlainAlg[F[_]](implicit eqString: Eq[F[String]]): Eq[PlainAlg[F]] =
    Eq.instance((x, y) => exhaustiveInt.allValues.forall(i => eqString.eqv(x.p(i), y.p(i))))

  implicit def eqEitherTEval[A: Eq]: Eq[Lazily[A]] =
    Eq.by(_.value.value)
}
