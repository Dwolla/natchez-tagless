package com.dwolla.tracing

import cats._
import cats.syntax.all._
import cats.tagless.aop.Aspect.Weave
import natchez.Trace

class TraceWeaveCapturingInputsAndOutputs[F[_] : FlatMap : Trace] extends (Weave[F, ToTraceValue, ToTraceValue, *] ~> F) {
  override def apply[A](fa: Weave[F, ToTraceValue, ToTraceValue, A]): F[A] =
    Trace[F].span(s"${fa.algebraName}.${fa.codomain.name}") {
      for {
        _ <- Trace[F].put(fa.asTraceParams: _*)
        out <- fa.codomain.target
        _ <- Trace[F].put(s"${fa.algebraName}.${fa.codomain.name}.returnValue" -> fa.codomain.instance.toTraceValue(out))
      } yield out
    }
}
