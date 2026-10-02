package com.dwolla.tracing.otel4s

import cats.Functor
import cats.syntax.all._
import cats.tagless.aop.Instrumentation
import cats.~>
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}

/**
 * Use this `FunctionK` when you have an algebra in `Instrumentation[F, *]` and you
 * want each method call on the algebra to introduce a new child span. Each child
 * span will be named using the algebra name and method name as captured in the
 * `Instrumentation[F, A]`. Each span also carries
 * `code.function.name = <algebraName>.<methodName>`.
 *
 * Running the returned `F` obtains this library's tracer from the ambient
 * `TracerProvider[F]`, under the instrumentation scope
 * `com.dwolla.tracing.otel4s` (versioned), and yields the interpreter; nothing
 * is obtained per call.
 */
object TracerInstrumentation {
  def apply[F[_]: Functor: TracerProvider]: F[Instrumentation[F, *] ~> F] =
    LibraryTracer[F].map { implicit tracer =>
      val interpreter: Instrumentation[F, *] ~> F = new TracerInstrumentation[F]
      interpreter
    }
}

private[otel4s] final class TracerInstrumentation[F[_]: Tracer] extends (Instrumentation[F, *] ~> F) {
  override def apply[A](fa: Instrumentation[F, A]): F[A] = {
    val name = s"${fa.algebraName}.${fa.methodName}"
    Tracer[F]
      .spanBuilder(name)
      .modifyState(_.withFinalizationStrategy(SpanFinalization.strategy).addAttributes(FunctionCallAttributes.codeFunctionName(name)))
      .build
      .surround(fa.value)
  }
}
