package com.dwolla.tracing.otel4s

import cats.effect.IO
import org.typelevel.otel4s.sdk.testkit.trace.TracesTestkit
import org.typelevel.otel4s.sdk.trace.data.SpanData
import org.typelevel.otel4s.trace.TracerProvider

/** `TracerScopeSuite` on the otel4s-sdk SDK. Cross-platform, so it is the span-scope coverage on Scala.js. */
class OtelSdkTracerScopeSpec extends TracerScopeSuite {
  override protected def spansFrom[A](f: TracerProvider[IO] => IO[A]): IO[(A, List[RecordedSpan])] =
    TracesTestkit.inMemory[IO]().use { testkit =>
      for {
        a <- f(testkit.tracerProvider)
        spans <- testkit.finishedSpans
      } yield (a, spans.map(recorded))
    }

  private def recorded(span: SpanData): RecordedSpan =
    RecordedSpan(
      name = span.name,
      traceId = span.spanContext.traceIdHex,
      spanId = span.spanContext.spanIdHex,
      parentSpanId = span.parentSpanContext.filter(_.isValid).map(_.spanIdHex),
      scopeName = span.instrumentationScope.name,
      scopeVersion = span.instrumentationScope.version,
    )
}
