package com.dwolla.tracing.otel4s
package syntax

import cats.FlatMap
import cats.tagless.aop.Aspect
import cats.tagless.syntax.all._
import org.typelevel.otel4s.trace.Tracer

trait ToTracerWeaveOps {
  implicit def toTracerWeaveOps[Alg[_[_]], F[_]](alg: Alg[F]): TracerWeaveOps[Alg, F] =
    new TracerWeaveOps(alg)
}

class TracerWeaveOps[Alg[_[_]], F[_]](val alg: Alg[F]) extends AnyVal {
  /** Wraps every method of the algebra in a child span carrying the call's
    * parameters, using the ambient `Tracer[F]`. See
    * `TracerWeaveCapturingInputs` for what is recorded and what is not.
    */
  def traceWithInputs[Cod[_]](implicit
                              T: Tracer[F],
                              A: Aspect[Alg, ToAnyValue, Cod]): Alg[F] =
    alg.weave.mapK(new TracerWeaveCapturingInputs)

  /** Wraps every method of the algebra in a child span carrying both the call's
    * parameters and its return value, using the ambient `Tracer[F]`. See
    * `TracerWeaveCapturingInputsAndOutputs` for what is recorded and what is
    * not.
    */
  def traceWithInputsAndOutputs(implicit
                                F: FlatMap[F],
                                T: Tracer[F],
                                A: Aspect[Alg, ToAnyValue, ToAnyValue]): Alg[F] =
    alg.weave.mapK(new TracerWeaveCapturingInputsAndOutputs)
}
