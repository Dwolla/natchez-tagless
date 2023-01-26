package com.dwolla.tracing
package syntax

import cats.effect.{Trace => _}
import cats.tagless.aop.Aspect.Weave
import natchez.{TraceValue, TraceableValue}

trait ToTraceParamsOps {
  implicit def toTraceParamsOps[F[_], Cod[_], A](fa: Weave[F, TraceableValue, Cod, A]): TraceParamsOps[F, Cod, A] =
    new TraceParamsOps(fa)
}

class TraceParamsOps[F[_], Cod[_], A](val fa: Weave[F, TraceableValue, Cod, A]) extends AnyVal {
  def asTraceParams: List[(String, TraceValue)] =
    fa.domain.flatMap { l =>
      l.map { advice =>
        // TODO these attribute names are kind of verbose, but the attribute naming spec says to namespace everything. Not sure what to do.
        // https://opentelemetry.io/docs/reference/specification/common/attribute-naming/
        s"${fa.algebraName}.${fa.codomain.name}.${advice.name}" -> advice.instance.toTraceValue(advice.target.value)
      }
    }
}
