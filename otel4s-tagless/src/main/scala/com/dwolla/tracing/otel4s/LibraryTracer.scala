package com.dwolla.tracing.otel4s

import org.typelevel.otel4s.trace.{Tracer, TracerProvider}

/** This library's own `Tracer`. The instrumentation scope names the
  * instrumenting library, so the module obtains its tracer from the
  * application's `TracerProvider` under its own versioned scope rather than
  * recording through an application-wide `Tracer`. Tracers from one provider
  * share its context, so a span opened through the application's own tracer
  * still parents the spans this one opens.
  */
private[otel4s] object LibraryTracer {
  val ScopeName: String = "com.dwolla.tracing.otel4s"

  def apply[F[_]: TracerProvider]: F[Tracer[F]] =
    TracerProvider[F].tracer(ScopeName).withVersion(BuildInfo.version).get
}
