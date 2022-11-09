package com.dwolla.tracing

import cats._
import cats.syntax.all._
import cats.tagless.aop.Aspect.Weave
import natchez.Trace

class TraceWeaveCapturingInputs[F[_] : Apply : Trace, Cod[_]] extends (Weave[F, ToTraceValue, Cod, *] ~> F) {
  override def apply[A](fa: Weave[F, ToTraceValue, Cod, A]): F[A] =
    Trace[F].span(s"${fa.algebraName}.${fa.codomain.name}") {
      Trace[F].put(fa.asTraceParams: _*) *> fa.codomain.target
    }
}
