package com.dwolla.tracing.otel4s
package syntax

import cats.{FlatMap, Functor}
import cats.syntax.all._
import cats.tagless.aop.Aspect
import cats.tagless.syntax.all._
import org.typelevel.otel4s.trace.TracerProvider

trait ToTracerWeaveOps {
  implicit def toTracerWeaveOps[Alg[_[_]], F[_]](alg: Alg[F]): TracerWeaveOps[Alg, F] =
    new TracerWeaveOps(alg)
}

class TracerWeaveOps[Alg[_[_]], F[_]](val alg: Alg[F]) extends AnyVal {
  /** Wraps every method of the algebra in a child span carrying the call's
    * parameters. Running the returned `F` obtains this library's tracer from
    * the ambient `TracerProvider[F]` and yields the wrapped algebra. See
    * `TracerWeaveCapturingInputs` for what is recorded and what is not.
    */
  def traceWithInputs[Cod[_]](implicit
                              F: Functor[F],
                              T: TracerProvider[F],
                              A: Aspect[Alg, ToAnyValue, Cod]): F[Alg[F]] =
    TracerWeaveCapturingInputs[F, Cod].map(interpreter => alg.weave.mapK(interpreter))

  /** Wraps every method of the algebra in a child span carrying both the call's
    * parameters and its return value. Running the returned `F` obtains this
    * library's tracer from the ambient `TracerProvider[F]` and yields the
    * wrapped algebra. See `TracerWeaveCapturingInputsAndOutputs` for what is
    * recorded and what is not.
    */
  def traceWithInputsAndOutputs(implicit
                                F: FlatMap[F],
                                T: TracerProvider[F],
                                A: Aspect[Alg, ToAnyValue, ToAnyValue]): F[Alg[F]] =
    TracerWeaveCapturingInputsAndOutputs[F].map(interpreter => alg.weave.mapK(interpreter))
}
