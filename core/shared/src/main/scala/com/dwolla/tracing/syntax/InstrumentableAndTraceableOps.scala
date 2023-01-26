package com.dwolla.tracing
package syntax

import cats.effect.{Trace => _}
import cats.tagless.aop._
import cats.tagless.syntax.all._
import natchez.Trace

trait ToInstrumentableAndTraceableOps {
  implicit def toInstrumentableAndTraceableOps[Alg[_[_]], F[_]](alg: Alg[F]): InstrumentableAndTraceableOps[F, Alg] =
    new InstrumentableAndTraceableOps(alg)
}

class InstrumentableAndTraceableOps[F[_], Alg[_[_]]](val alg: Alg[F]) extends AnyVal {
  def instrumentAndTrace(implicit I: Instrument[Alg], T: Trace[F]): Alg[F] =
    alg.instrument.mapK(TraceInstrumentation[F])
}
