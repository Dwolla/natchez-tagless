package com.dwolla.tagless.mtl

import cats.Apply
import cats.tagless.aop.Aspect
import cats.tagless.aop.Aspect.Weave
import cats.~>

/** `PlainAlg` has no capability parameters, so it can carry both an `Aspect`
  * and a `RaiseAspect` — the situation `WeaveInterpreter`'s implicit priority
  * exists to resolve. The `RaiseAspect` here is a poison instance: it throws
  * if it is ever invoked, so priority resolving the wrong way fails loudly
  * rather than producing plausible-looking wrong output. Same technique as
  * `natchez-tagless-mtl`'s `AspectPriorityFixtures`.
  */
object WeaveInterpreterFixtures {

  /** The correct instance — the one priority must select. */
  implicit val plainAspect: Aspect[PlainAlg, Render, Render] =
    new Aspect[PlainAlg, Render, Render] {
      def weave[F[_]](af: PlainAlg[F]): PlainAlg[Weave[F, Render, Render, *]] =
        new PlainAlg[Weave[F, Render, Render, *]] {
          def p(i: Int): Weave[F, Render, Render, String] =
            Weave("PlainAlg", List(List(Aspect.Advice.byValue("i", i))), Aspect.Advice("p", af.p(i)))
        }

      def mapK[F[_], G[_]](af: PlainAlg[F])(fk: F ~> G): PlainAlg[G] =
        new PlainAlg[G] {
          def p(i: Int): G[String] = fk(af.p(i))
        }
    }

  implicit val plainRaiseAspectPoison: RaiseAspect[PlainAlg, Render, Render, Render] =
    new RaiseAspect[PlainAlg, Render, Render, Render] {
      def intercept[F[_]](af: PlainAlg[F])(
          fk: Weave[F, Render, Render, *] ~> F,
          onRaise: OnRaise[F, Render]
      )(implicit F: Apply[F]): PlainAlg[F] =
        throw new AssertionError("priority resolved to RaiseAspect instead of Aspect")

      def mapK[F[_], G[_]](af: PlainAlg[F])(arrow: RaiseArrow[F, G, Render]): PlainAlg[G] =
        throw new AssertionError("priority resolved to RaiseAspect instead of Aspect")
    }
}
