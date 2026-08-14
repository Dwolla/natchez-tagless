package com.dwolla.tagless.mtl
package laws

import cats.*
import cats.effect.*
import cats.effect.testkit.TestInstances
import cats.laws.discipline.ExhaustiveCheck
import cats.mtl.Raise
import cats.syntax.all.*
import org.scalacheck.{Arbitrary, Gen}

/** Shared `Eq`, `ExhaustiveCheck` and rendering instances for the law suites.
  *
  * Mixes in `TestInstances` (rather than importing from it) because it's a
  * bare trait with no companion object — this is the same pattern
  * `ObservingCapabilitySpec` uses to bring `eqSyncIOA[A: Eq]: Eq[SyncIO[A]]`
  * into scope for `Eq[Lazily[A]]`.
  */
object LawsInstances extends TestInstances {

  type Result[A] = Either[TestError, A]

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
    */
  implicit def arbResultToLazily(implicit H: cats.mtl.Handle[SyncIO, TestError]): Arbitrary[RaiseArrow[Result, SyncIO, Render]] =
    Arbitrary(Gen.const(CarrierArrows.resultToLazily[Render]))

  /** Intercept the algebra under test with a recording interpreter. The pair
    * is the fused replacement for `weave` — the algebra behaves as though it
    * had been woven and immediately erased, and the recorder holds the
    * structure that used to be inspectable on the returned value.
    */
  def instrumented[F[_]: Sync](
      instance: RaiseAspect[TestAlg, Render, Render, Render],
      eOutcome: Int
  ): F[(TestAlg[F], RecordingFk[F, Render, Render])] =
    RecordingFk[F, Render, Render].map { recorder =>
      (instance.intercept(new GenericTestAlg[F](eOutcome))(recorder.fk, OnRaise.noop[F, Render]), recorder)
    }

  /** Intercept the algebra under test with a recording interpreter ''and'' a
    * hook that appends to the same log, so one buffer holds weave arrivals and
    * hook firings in the order they happened.
    *
    * The differential oracle uses this rather than [[instrumented]]. With
    * `OnRaise.noop` the hook contributes nothing observable — `noop`'s
    * `Right(())` left-sequenced onto a raise gives the identical result — so an
    * oracle built on `instrumented` accepts a derivation that never wrapped a
    * capability in `RaiseAspect.observing` at all. Rendering each firing
    * through the `Err[E]` the derivation resolved also pins ''which'' evidence
    * each capability got: `errA:` and `errB:` are distinguishable from each
    * other and from `toString`.
    */
  def observed[F[_]: Sync](
      instance: RaiseAspect[TestAlg, Render, Render, Render],
      eOutcome: Int
  ): F[(TestAlg[F], RecordingFk[F, Render, Render])] =
    RecordingFk[F, Render, Render].map { recorder =>
      val hook: OnRaise[F, Render] = new OnRaise[F, Render] {
        def apply[E](e: E)(implicit ev: Render[E]): F[Unit] = recorder.record(s"raise:${ev.render(e)}")
      }

      (instance.intercept(new GenericTestAlg[F](eOutcome))(recorder.fk, hook), recorder)
    }

  /** What the interpreter saw, rendered — the fused analogue of mapping
    * `WeaveRenderer.render` over a list of returned weaves, and stricter,
    * because the list is in arrival order.
    */
  def renderedWeaves[F[_]: Functor](recorder: RecordingFk[F, Render, Render]): F[List[RenderedWeave]] =
    recorder.weaves.map(_.map(r => WeaveRenderer.render(r.weave)).toList)

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
}
