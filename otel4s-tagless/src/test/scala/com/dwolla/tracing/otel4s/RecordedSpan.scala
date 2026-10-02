package com.dwolla.tracing.otel4s

/** What a backend recorded for one span, in terms that don't mention any
  * backend's types, so `TracerScopeSuite` can assert on oteljava and
  * otel4s-sdk alike without change. Ids are lowercase hex; a root span has no
  * `parentSpanId`.
  */
final case class RecordedSpan(name: String,
                              traceId: String,
                              spanId: String,
                              parentSpanId: Option[String],
                              scopeName: String,
                              scopeVersion: Option[String])
