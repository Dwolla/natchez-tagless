package com.dwolla.tagless.mtl

import cats.Functor
import cats.mtl.Raise
import cats.tagless.aop.Aspect

/** The hand-written `RaiseAspect[TestAlg, Dom, Cod, Err]`.
  *
  * ==THIS IS A PERMANENT TEST FIXTURE. DO NOT DELETE OR REGENERATE IT.==
  *
  * This instance is the '''differential oracle''' for the derivation macros:
  * it is written by mechanically following the expansion specification in
  * `docs/plans/raise-aspect/01-overview-design-and-laws.md` §3.4, exactly as
  * the M3 (Scala 2) and M4 (Scala 3) macros must generate it. The M2 laws
  * validate this instance, and both macros must then agree with it
  * output-for-output. Regenerating it from a macro would make that comparison
  * circular and worthless.
  *
  * The `Dom`/`Cod` instances are taken as implicit parameters rather than
  * summoned inside, mirroring what a macro expansion resolves at its call site.
  * The `Err` instances — one per error type appearing in a `Raise` parameter of
  * the algebra — arrive the same way, for the same reason.
  */
object TestAlgReference {

  def referenceRaiseAspect[Dom[_], Cod[_], Err[_]](implicit
      domInt: Dom[Int],
      domString: Dom[String],
      codString: Cod[String],
      codInt: Cod[Int],
      codUnit: Cod[Unit],
      errA: Err[ErrA],
      errB: Err[ErrB]
  ): RaiseAspect[TestAlg, Dom, Cod, Err] =
    new RaiseAspect[TestAlg, Dom, Cod, Err] {

      def weave[F[_]](af: TestAlg[F])(implicit F: Functor[F]): TestAlg[Aspect.Weave[F, Dom, Cod, *]] = {
        type WF[A] = Aspect.Weave[F, Dom, Cod, A]

        // The `Err[E]` a macro resolves at the derivation site arrives here as
        // an implicit parameter, exactly as `Dom`/`Cod` instances already do.
        def pull[E](rw: Raise[WF, E])(implicit ev: Err[E]): Raise[F, E] =
          WeaveArrows.raisePull[F, Dom, Cod, Err].apply(rw)

        new TestAlg[WF] {
          def a(i: Int)(implicit R: Raise[WF, ErrA]): WF[String] =
            Aspect.Weave[F, Dom, Cod, String](
              "TestAlg",
              List(List(Aspect.Advice.byValue[Dom, Int]("i", i))),
              Aspect.Advice[F, Cod, String]("a", af.a(i)(pull(R)))
            )

          def b(x: String, y: => Int)(implicit R: Raise[WF, ErrB]): WF[Int] =
            Aspect.Weave[F, Dom, Cod, Int](
              "TestAlg",
              List(
                List(
                  Aspect.Advice.byValue[Dom, String]("x", x),
                  Aspect.Advice.byName[Dom, Int]("y", y)
                )
              ),
              Aspect.Advice[F, Cod, Int]("b", af.b(x, y)(pull(R)))
            )

          def c(i: Int): WF[Int] =
            Aspect.Weave[F, Dom, Cod, Int](
              "TestAlg",
              List(List(Aspect.Advice.byValue[Dom, Int]("i", i))),
              Aspect.Advice[F, Cod, Int]("c", af.c(i))
            )

          def d(i: Int)(j: Int)(implicit R: Raise[WF, ErrA]): WF[Int] =
            Aspect.Weave[F, Dom, Cod, Int](
              "TestAlg",
              List(
                List(Aspect.Advice.byValue[Dom, Int]("i", i)),
                List(Aspect.Advice.byValue[Dom, Int]("j", j))
              ),
              Aspect.Advice[F, Cod, Int]("d", af.d(i)(j)(pull(R)))
            )

          // `e`'s only parameter clause holds nothing but capabilities, so it
          // contributes no clause to the domain at all.
          def e(implicit R1: Raise[WF, ErrA], R2: Raise[WF, ErrB]): WF[Unit] =
            Aspect.Weave[F, Dom, Cod, Unit](
              "TestAlg",
              Nil,
              Aspect.Advice[F, Cod, Unit]("e", af.e(pull(R1), pull(R2)))
            )
        }
      }

      def mapK[F[_], G[_]](af: TestAlg[F])(arrow: RaiseArrow[F, G, Err]): TestAlg[G] =
        new TestAlg[G] {
          def a(i: Int)(implicit R: Raise[G, ErrA]): G[String] =
            arrow.fk(af.a(i)(arrow.pull(R)))

          def b(x: String, y: => Int)(implicit R: Raise[G, ErrB]): G[Int] =
            arrow.fk(af.b(x, y)(arrow.pull(R)))

          def c(i: Int): G[Int] =
            arrow.fk(af.c(i))

          def d(i: Int)(j: Int)(implicit R: Raise[G, ErrA]): G[Int] =
            arrow.fk(af.d(i)(j)(arrow.pull(R)))

          def e(implicit R1: Raise[G, ErrA], R2: Raise[G, ErrB]): G[Unit] =
            arrow.fk(af.e(arrow.pull(R1), arrow.pull(R2)))
        }
    }
}
