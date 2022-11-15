package com.dwolla

import cats._
import cats.data._
import cats.effect.{Trace => _, _}
import cats.tagless.aop.Aspect.Weave
import cats.tagless.aop._
import cats.tagless.syntax.all._
import natchez.{EntryPoint, Span, Trace, TraceValue}

package object tracing extends ToTraceWeaveOps {
  implicit def toInstrumentableAndTraceableInKleisliOps[Alg[_[_]], F[_]](
      alg: Alg[Kleisli[F, Span[F], *]]
  ): InstrumentableAndTraceableInKleisliOps[F, Alg] =
    new InstrumentableAndTraceableInKleisliOps(alg)

  implicit def toInstrumentableAndTraceableOps[Alg[_[_]], F[_]](
      alg: Alg[F]
  ): InstrumentableAndTraceableOps[F, Alg] =
    new InstrumentableAndTraceableOps(alg)

  implicit def toTraceParamsOps[F[_], Cod[_], A](
      fa: Weave[F, ToTraceValue, Cod, A]
  ): TraceParamsOps[F, Cod, A] =
    new TraceParamsOps(fa)

  implicit def toResourceInitializationSpanOps[F[_], A](
      rsrc: Resource[F, A]
  ): ResourceInitializationSpanOps[F, A] =
    new ResourceInitializationSpanOps(rsrc)
}

package tracing {
  class ResourceInitializationSpanOps[F[_], A](val rsrc: Resource[F, A])
      extends AnyVal {
    def initializeWithSpan[G[_]](span: Span[G])(implicit
        F: MonadCancel[F, _],
        G: MonadCancel[G, _],
        gk: Span[G] => (F ~> G)
    ): Resource[G, A] =
      rsrc.mapK(gk(span))
  }

  class InstrumentableAndTraceableInKleisliOps[F[_], Alg[_[_]]](
      val alg: Alg[Kleisli[F, Span[F], *]]
  ) extends AnyVal {
    def instrumentAndTraceWithRootSpans(
        entryPoint: EntryPoint[F]
    )(implicit F: MonadCancelThrow[F], I: Instrument[Alg]): Alg[F] =
      alg.instrument.mapK(RootSpanProvidingFunctionK(entryPoint))
  }

  class InstrumentableAndTraceableOps[F[_], Alg[_[_]]](val alg: Alg[F])
      extends AnyVal {
    def instrumentAndTrace(implicit I: Instrument[Alg], T: Trace[F]): Alg[F] =
      alg.instrument.mapK(TraceInstrumentation[F])
  }

  class TraceParamsOps[F[_], Cod[_], A](val fa: Weave[F, ToTraceValue, Cod, A])
      extends AnyVal {
    def asTraceParams: List[(String, TraceValue)] =
      fa.domain.flatMap { l =>
        l.map { advice =>
          // TODO these attribute names are kind of verbose, but the attribute naming spec says to namespace everything. Not sure what to do.
          // https://opentelemetry.io/docs/reference/specification/common/attribute-naming/
          s"${fa.algebraName}.${fa.codomain.name}.${advice.name}" -> advice.instance
            .toTraceValue(advice.target.value)
        }
      }
  }
}
