package com.dwolla.tracing
package syntax

import cats.data._
import cats.effect.{Trace => _, _}
import cats.tagless.aop._
import cats.tagless.syntax.all._
import natchez.{EntryPoint, Span}

trait ToInstrumentableAndTraceableInKleisliOps {
  implicit def toInstrumentableAndTraceableInKleisliOps[Alg[_[_]], F[_]](alg: Alg[Kleisli[F, Span[F], *]]): InstrumentableAndTraceableInKleisliOps[F, Alg] =
    new InstrumentableAndTraceableInKleisliOps(alg)
}

class InstrumentableAndTraceableInKleisliOps[F[_], Alg[_[_]]](val alg: Alg[Kleisli[F, Span[F], *]]) extends AnyVal {
  def instrumentAndTraceWithRootSpans(entryPoint: EntryPoint[F])
                                     (implicit F: MonadCancelThrow[F], I: Instrument[Alg]): Alg[F] =
    alg.instrument.mapK(RootSpanProvidingFunctionK(entryPoint))
}
