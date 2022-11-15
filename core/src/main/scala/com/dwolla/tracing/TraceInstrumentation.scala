package com.dwolla.tracing

import cats.tagless.aop._
import cats.~>
import natchez.Trace

object TraceInstrumentation {
  def apply[F[_]: Trace]: Instrumentation[F, *] ~> F =
    new TraceInstrumentation[F]
}

class TraceInstrumentation[F[_]: Trace] extends (Instrumentation[F, *] ~> F) {
  override def apply[A](fa: Instrumentation[F, A]): F[A] =
    Trace[F].span(s"${fa.algebraName}.${fa.methodName}")(fa.value)
}
