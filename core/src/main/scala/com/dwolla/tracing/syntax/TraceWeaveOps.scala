package com.dwolla.tracing
package syntax

import cats._
import cats.tagless.aop.Aspect
import cats.tagless.syntax.all._
import natchez.{Trace, TraceableValue}

trait ToTraceWeaveOps {
  implicit def toTraceWeaveOps[Alg[_[_]], F[_]](alg: Alg[F]): TraceWeaveOps[Alg, F] =
    new TraceWeaveOps(alg)
}

class TraceWeaveOps[Alg[_[_]], F[_]](val alg: Alg[F]) extends AnyVal {
  def traceWithInputs[Cod[_]](implicit
                              F: Apply[F],
                              T: Trace[F],
                              A: Aspect[Alg, TraceableValue, Cod]): Alg[F] =
    alg.weave.mapK(new TraceWeaveCapturingInputs)

  def traceWithInputsAndOutputs(implicit
                                F: FlatMap[F],
                                T: Trace[F],
                                A: Aspect[Alg, TraceableValue, TraceableValue]): Alg[F] =
    alg.weave.mapK(new TraceWeaveCapturingInputsAndOutputs)
}
