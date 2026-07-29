package com.dwolla.tagless.mtl
package laws

import cats.Eq
import cats.data.EitherT
import cats.laws.discipline.ExhaustiveCheck
import cats.mtl.Raise
import cats.syntax.all._
import cats.tagless.aop.Aspect
import cats.Eval

/** Shared `Eq`, `ExhaustiveCheck` and rendering instances for the law suites. */
object LawsInstances {

  type Result[A] = Either[TestError, A]
  type Woven[A] = Aspect.Weave[Result, Render, Render, A]
  type Lazily[A] = EitherT[Eval, TestError, A]

  /** Small exhaustive domains. Both include values that make the fixture raise
    * — negatives for `a`/`d`, the empty string for `b` — so L3/L4/L5 exercise
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

  /** The synthesized `Cod` used inside raise shells. Its output is deliberately
    * distinctive: if it ever leaks into an observable position, laws that
    * compare rendered weaves will say so loudly.
    */
  implicit val syntheticRender: Synthetic[Render] =
    new Synthetic[Render] {
      def apply[A]: Render[A] = (_: A) => "<synthetic>"
    }

  /** The error hierarchy is all case classes, so structural equality is right. */
  implicit val eqTestError: Eq[TestError] = Eq.fromUniversalEquals

  /** Named explicitly rather than summoned: `Raise[Result, TestError]` would
    * resolve to this very val and initialize it to `null`.
    */
  implicit val raiseResult: Raise[Result, TestError] = Raise.raiseEither[TestError]

  implicit val raiseWoven: Raise[Woven, TestError] =
    WeaveArrows.raiseLift[Result, Render, Render].apply(raiseResult)

  /** Structural `Eq` for a woven value.
    *
    * `Aspect.Advice` defines no `equals`, so `==` on `Weave` compares by
    * reference and would make every law that touches a weave pass or fail for
    * the wrong reason. Compare the rendered structure plus the codomain target.
    */
  implicit def eqWoven[A](implicit ev: Eq[Result[A]]): Eq[Woven[A]] =
    Eq.instance { (x, y) =>
      WeaveRenderer.render(x) === WeaveRenderer.render(y) &&
      ev.eqv(x.codomain.target, y.codomain.target)
    }

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
