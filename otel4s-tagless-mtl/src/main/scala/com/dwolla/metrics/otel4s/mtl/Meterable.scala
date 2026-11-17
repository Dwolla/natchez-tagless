package com.dwolla.metrics.otel4s.mtl

import cats.Applicative
import cats.tagless.aop.{Aspect, Instrument, Instrumentation}
import cats.~>
import com.dwolla.tagless.mtl.{OnRaise, RaiseAspect}
import com.dwolla.tracing.otel4s.ToAnyValue

import scala.annotation.implicitNotFound

/** Resolves how `withMetrics` routes an `Alg[F]`'s calls through a metrics
  * interpreter: cats-tagless's `Instrument`, or, for an algebra whose methods
  * take `cats.mtl.Raise` capabilities (which can't have an `Instrument`), this
  * library's `RaiseAspect`.
  *
  * Both strategies are instances of this one sealed type class so that the
  * low-priority-trait mechanism orders them, exactly as
  * `com.dwolla.tagless.mtl.WeaveInterpreter` does for tracing.
  */
@implicitNotFound(
  "Can't meter ${Alg}'s calls: withMetrics needs either a cats.tagless.aop.Instrument[${Alg}] " +
    "(e.g. cats.tagless.Derive.instrument), or, for an algebra whose methods take cats.mtl.Raise, " +
    "a com.dwolla.tagless.mtl.RaiseAspect[${Alg}, ToAnyValue, ToAnyValue, ToAnyValue] " +
    "(e.g. DeriveRaise.aspect, or `derives AnyValueRaiseAspect` on Scala 3)"
)
sealed trait Meterable[Alg[_[_]]] {
  def apply[F[_]: Applicative](alg: Alg[F])(interpreter: Instrumentation[F, *] ~> F): Alg[F]
}

object Meterable extends LowPriorityMeterable {

  /** Higher priority: an algebra with an `Instrument` is metered through it,
    * exactly as `com.dwolla.metrics.otel4s.syntax` would, even when a
    * `RaiseAspect` also exists. Such an algebra has no `Raise` parameters
    * (`Instrument`'s `mapK` can't implement a method that takes one), so the
    * `RaiseAspect` path would have nothing extra to do.
    */
  implicit def fromInstrument[Alg[_[_]]](implicit I: Instrument[Alg]): Meterable[Alg] =
    new Meterable[Alg] {
      override def apply[F[_]: Applicative](alg: Alg[F])(interpreter: Instrumentation[F, *] ~> F): Alg[F] =
        I.mapK(I.instrument(alg))(interpreter)
    }
}

trait LowPriorityMeterable {

  /** Lower priority: used only when no `Instrument` is available. Each call's
    * `Aspect.Weave` is reduced to the `Instrumentation` (algebra and method
    * name) the metrics interpreter reads. A raise needs no hook, so
    * `OnRaise.noop` is passed: one carried as a failure of `F` (`Handle.allowF`, say)
    * fails the call's `F`, which the interpreter already records as
    * `error.type`. One carried in `F`'s success channel (`EitherT`, say) ends
    * the call as a success and is recorded without `error.type`.
    *
    * `Dom`, `Cod`, and `Err` are pinned to `ToAnyValue`, the shape the tracing
    * syntax and `AnyValueRaiseAspect` use. A future instance accepting a more
    * general `RaiseAspect` belongs in a new trait one priority level ''below''
    * this one (extended by this trait), never beside this instance: beside it,
    * an algebra with both shapes would make the two ambiguous, while one level
    * lower keeps this pinned instance winning, which is source- and
    * binary-compatible.
    */
  implicit def fromRaiseAspect[Alg[_[_]]](implicit A: RaiseAspect[Alg, ToAnyValue, ToAnyValue, ToAnyValue]): Meterable[Alg] =
    new Meterable[Alg] {
      override def apply[F[_]: Applicative](alg: Alg[F])(interpreter: Instrumentation[F, *] ~> F): Alg[F] =
        A.intercept(alg)(
          interpreter.compose(Aspect.Weave.instrumentationK[F, ToAnyValue, ToAnyValue]),
          OnRaise.noop[F, ToAnyValue]
        )
    }
}
