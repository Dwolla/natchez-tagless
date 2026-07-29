package com.dwolla.tagless.mtl
package laws

import cats.Functor
import cats.tagless.aop.Aspect

/** The hand-written `RaiseAspect[PlainAlg, Dom, Cod]`, written by following the
  * same §3.4 expansion spec as M1's `TestAlgReference`.
  *
  * `PlainAlg` has no capability parameters, so this is the instance law L9
  * compares against upstream `cats.tagless.Derive.aspect` to show our
  * derivation is a conservative extension of theirs.
  *
  * Like the M1 reference instance, this is a permanent fixture — do not delete
  * or regenerate it from a macro.
  */
object PlainAlgReference {

  def referenceRaiseAspect[Dom[_], Cod[_]](implicit
      domInt: Dom[Int],
      codString: Cod[String]
  ): RaiseAspect[PlainAlg, Dom, Cod] =
    new RaiseAspect[PlainAlg, Dom, Cod] {

      def weave[F[_]](af: PlainAlg[F])(implicit F: Functor[F]): PlainAlg[Aspect.Weave[F, Dom, Cod, *]] = {
        type WF[A] = Aspect.Weave[F, Dom, Cod, A]

        new PlainAlg[WF] {
          def p(i: Int): WF[String] =
            Aspect.Weave[F, Dom, Cod, String](
              "PlainAlg",
              List(List(Aspect.Advice.byValue[Dom, Int]("i", i))),
              Aspect.Advice[F, Cod, String]("p", af.p(i))
            )
        }
      }

      def mapK[F[_], G[_]](af: PlainAlg[F])(arrow: RaiseArrow[F, G]): PlainAlg[G] =
        new PlainAlg[G] {
          def p(i: Int): G[String] = arrow.fk(af.p(i))
        }
    }
}
