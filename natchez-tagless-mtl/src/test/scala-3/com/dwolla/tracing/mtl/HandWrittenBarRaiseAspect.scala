package com.dwolla.tracing.mtl

import cats.Apply
import cats.mtl.Raise
import cats.tagless.aop.Aspect
import cats.~>
import com.dwolla.tagless.mtl.{OnRaise, RaiseAspect}
import natchez.TraceableValue

/** The differential reference to check `derives TraceableRaiseAspect`
  * against. Deliberately hand-written and macro-free: if this and the derived
  * instance disagree, the disagreement is about the derivation, not about two
  * copies of the same macro output.
  */
object HandWrittenBarRaiseAspect {
  val instance: RaiseAspect[Bar, TraceableValue, TraceableValue, TraceableValue] =
    new RaiseAspect[Bar, TraceableValue, TraceableValue, TraceableValue] {
      def intercept[F[_]](af: Bar[F])(
          fk: Aspect.Weave[F, TraceableValue, TraceableValue, *] ~> F,
          onRaise: OnRaise[F, TraceableValue]
      )(implicit F: Apply[F]): Bar[F] =
        new Bar[F] {
          def bar(i: Int)(implicit R: Raise[F, BarError]): F[String] =
            fk(
              Aspect.Weave[F, TraceableValue, TraceableValue, String](
                "Bar",
                List(List(Aspect.Advice.byValue[TraceableValue, Int]("i", i))),
                Aspect.Advice[F, TraceableValue, String](
                  "bar",
                  af.bar(i)(RaiseAspect.observing(R, onRaise))
                )
              )
            )
        }
    }
}
