package com.dwolla.tracing.otel4s

import cats.tagless.aop.Instrumentation
import cats.~>
import org.typelevel.otel4s.trace.Tracer

object TracerInstrumentation {
  def apply[F[_]: Tracer]: Instrumentation[F, *] ~> F = new TracerInstrumentation[F]
}

/**
 * Use this `FunctionK` when you have an algebra in `Instrumentation[F, *]` and you
 * want each method call on the algebra to introduce a new child span, using the
 * ambient `Tracer[F]`. Each child span will be named using the algebra name and
 * method name as captured in the `Instrumentation[F, A]`.
 */
final class TracerInstrumentation[F[_]: Tracer] private[otel4s] () extends (Instrumentation[F, *] ~> F) {
  override def apply[A](fa: Instrumentation[F, A]): F[A] = {
    val name = s"${fa.algebraName}.${fa.methodName}"
    Tracer[F]
      .spanBuilder(name)
      .modifyState(_.addAttributes(FunctionCallAttributes.codeFunctionName(name)))
      .build
      .surround(fa.value)
  }
}
