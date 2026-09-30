package com.dwolla.tracing.otel4s
package syntax

import cats.tagless.aop._
import cats.tagless.syntax.all._
import org.typelevel.otel4s.trace.Tracer

trait ToInstrumentableAndTraceableOps {
  implicit def toInstrumentableAndTraceableOps[Alg[_[_]], F[_]](alg: Alg[F]): InstrumentableAndTraceableOps[F, Alg] =
    new InstrumentableAndTraceableOps(alg)
}

class InstrumentableAndTraceableOps[F[_], Alg[_[_]]](val alg: Alg[F]) extends AnyVal {
  /** Wraps every method of the algebra in a child span named
    * `algebraName.methodName`, using the ambient `Tracer[F]`, and records
    * nothing else. See `TracerInstrumentation`.
    */
  def instrumentAndTrace(implicit I: Instrument[Alg], T: Tracer[F]): Alg[F] =
    alg.instrument.mapK(TracerInstrumentation[F])
}
