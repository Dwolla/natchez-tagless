package com.dwolla.tracing.otel4s
package syntax

import cats.Functor
import cats.syntax.all._
import cats.tagless.aop._
import cats.tagless.syntax.all._
import org.typelevel.otel4s.trace.TracerProvider

trait ToInstrumentableAndTraceableOps {
  implicit def toInstrumentableAndTraceableOps[Alg[_[_]], F[_]](alg: Alg[F]): InstrumentableAndTraceableOps[F, Alg] =
    new InstrumentableAndTraceableOps(alg)
}

class InstrumentableAndTraceableOps[F[_], Alg[_[_]]](val alg: Alg[F]) extends AnyVal {
  /** Wraps every method of the algebra in a child span named
    * `algebraName.methodName`, and records nothing else. Running the returned
    * `F` obtains this library's tracer from the ambient `TracerProvider[F]`
    * and yields the wrapped algebra. See `TracerInstrumentation`.
    */
  def instrumentAndTrace(implicit I: Instrument[Alg], F: Functor[F], T: TracerProvider[F]): F[Alg[F]] =
    TracerInstrumentation[F].map(interpreter => alg.instrument.mapK(interpreter))
}
