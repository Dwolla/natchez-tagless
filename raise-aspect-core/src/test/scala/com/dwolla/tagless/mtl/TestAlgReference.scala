package com.dwolla.tagless.mtl

import cats.FlatMap
import cats.mtl.Raise
import cats.tagless.aop.Aspect
import cats.~>

/** The hand-written `RaiseAspect[TestAlg, Dom, Cod, Err]`.
  *
  * ==THIS IS A PERMANENT TEST FIXTURE. DO NOT DELETE OR REGENERATE IT.==
  *
  * This instance is the '''differential oracle''' for the derivation macros:
  * it is written by mechanically; regenerating it from a macro would make
  * that comparison circular and worthless.
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

      def intercept[F[_]](af: TestAlg[F])(
          fk: Aspect.Weave[F, Dom, Cod, *] ~> F,
          onRaise: OnRaise[F, Err]
      )(implicit F: FlatMap[F]): TestAlg[F] =
        new TestAlg[F] {
          def a(i: Int)(implicit R: Raise[F, ErrA]): F[String] =
            fk(
              Aspect.Weave[F, Dom, Cod, String](
                "TestAlg",
                List(List(Aspect.Advice.byValue[Dom, Int]("i", i))),
                Aspect.Advice[F, Cod, String]("a", af.a(i)(RaiseAspect.observing(R, onRaise)))
              )
            )

          def b(x: String, y: => Int)(implicit R: Raise[F, ErrB]): F[Int] =
            fk(
              Aspect.Weave[F, Dom, Cod, Int](
                "TestAlg",
                List(
                  List(
                    Aspect.Advice.byValue[Dom, String]("x", x),
                    Aspect.Advice.byName[Dom, Int]("y", y)
                  )
                ),
                Aspect.Advice[F, Cod, Int]("b", af.b(x, y)(RaiseAspect.observing(R, onRaise)))
              )
            )

          def c(i: Int): F[Int] =
            fk(
              Aspect.Weave[F, Dom, Cod, Int](
                "TestAlg",
                List(List(Aspect.Advice.byValue[Dom, Int]("i", i))),
                Aspect.Advice[F, Cod, Int]("c", af.c(i))
              )
            )

          def d(i: Int)(j: Int)(implicit R: Raise[F, ErrA]): F[Int] =
            fk(
              Aspect.Weave[F, Dom, Cod, Int](
                "TestAlg",
                List(
                  List(Aspect.Advice.byValue[Dom, Int]("i", i)),
                  List(Aspect.Advice.byValue[Dom, Int]("j", j))
                ),
                Aspect.Advice[F, Cod, Int]("d", af.d(i)(j)(RaiseAspect.observing(R, onRaise)))
              )
            )

          // `e`'s only parameter clause holds nothing but capabilities, so it
          // contributes no clause to the domain at all.
          def e(implicit R1: Raise[F, ErrA], R2: Raise[F, ErrB]): F[Unit] =
            fk(
              Aspect.Weave[F, Dom, Cod, Unit](
                "TestAlg",
                Nil,
                Aspect.Advice[F, Cod, Unit](
                  "e",
                  af.e(RaiseAspect.observing(R1, onRaise), RaiseAspect.observing(R2, onRaise))
                )
              )
            )
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
