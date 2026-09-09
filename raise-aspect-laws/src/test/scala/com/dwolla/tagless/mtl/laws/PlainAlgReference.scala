package com.dwolla.tagless.mtl
package laws

import cats.Apply
import cats.tagless.aop.Aspect
import cats.~>

/** The hand-written `RaiseAspect[PlainAlg, Dom, Cod]`.
  *
  * `PlainAlg` has no capability parameters, so this is the instance law L9
  * compares against upstream `cats.tagless.Derive.aspect` to show our
  * derivation is a conservative extension of theirs.
  *
  * Like the other reference instances, this is a permanent fixture — do not delete
  * or regenerate it from a macro.
  */
object PlainAlgReference {

  def referenceRaiseAspect[Dom[_], Cod[_], Err[_]](implicit
      domInt: Dom[Int],
      codString: Cod[String]
  ): RaiseAspect[PlainAlg, Dom, Cod, Err] =
    new RaiseAspect[PlainAlg, Dom, Cod, Err] {

      def intercept[F[_]](af: PlainAlg[F])(
          fk: Aspect.Weave[F, Dom, Cod, *] ~> F,
          onRaise: OnRaise[F, Err]
      )(implicit F: Apply[F]): PlainAlg[F] =
        new PlainAlg[F] {
          def p(i: Int): F[String] =
            fk(
              Aspect.Weave[F, Dom, Cod, String](
                "PlainAlg",
                List(List(Aspect.Advice.byValue[Dom, Int]("i", i))),
                Aspect.Advice[F, Cod, String]("p", af.p(i))
              )
            )
        }

      def mapK[F[_], G[_]](af: PlainAlg[F])(arrow: RaiseArrow[F, G, Err]): PlainAlg[G] =
        new PlainAlg[G] {
          def p(i: Int): G[String] = arrow.fk(af.p(i))
        }
    }
}
