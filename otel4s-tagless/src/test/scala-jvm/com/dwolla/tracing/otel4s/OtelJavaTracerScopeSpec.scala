package com.dwolla.tracing.otel4s

import cats.effect.IO
import io.opentelemetry.sdk.trace.data.SpanData
import org.typelevel.otel4s.oteljava.testkit.trace.TracesTestkit
import org.typelevel.otel4s.trace.TracerProvider

/** `TracerScopeSuite` on the oteljava SDK. JVM-only because oteljava is. */
class OtelJavaTracerScopeSpec extends TracerScopeSuite {
  override protected def spansFrom[A](f: TracerProvider[IO] => IO[A]): IO[(A, List[RecordedSpan])] =
    TracesTestkit.inMemory[IO]().use { testkit =>
      for {
        a <- f(testkit.tracerProvider)
        spans <- testkit.finishedSpans
      } yield (a, spans.map(recorded))
    }

  private def recorded(span: SpanData): RecordedSpan =
    RecordedSpan(
      name = span.getName,
      traceId = span.getTraceId,
      spanId = span.getSpanId,
      parentSpanId = Option(span.getParentSpanContext).filter(_.isValid).map(_.getSpanId),
      scopeName = span.getInstrumentationScopeInfo.getName,
      scopeVersion = Option(span.getInstrumentationScopeInfo.getVersion),
    )
}
