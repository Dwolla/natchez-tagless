package com.dwolla.tracing.otel4s

import cats.effect.kernel.Resource
import cats.syntax.all._
import com.dwolla.tagless.{ErrorTypeName, RaisedError}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.semconv.attributes.ErrorAttributes
import org.typelevel.otel4s.trace.{SpanFinalizer, StatusCode}

/** How the otel4s tracing interpreters finalize a span.
  *
  * otel4s's `reportAbnormal`, except for a cats-mtl raise that escaped the
  * method: under `Handle.allowF` that reaches the span as cats-mtl's private
  * `Submarine` exception, which names neither the error type nor its value.
  * It is reported instead as the domain error it carries: status ERROR and
  * `error.type = ErrorTypeName(<the error>)`, with no exception event.
  * Everything else — thrown exceptions, cancellation — is `reportAbnormal`'s.
  */
private[otel4s] object SpanFinalization {
  private val escapedRaise: SpanFinalizer.Strategy = {
    case Resource.ExitCase.Errored(RaisedError(error)) =>
      SpanFinalizer.addAttribute(Attribute(ErrorAttributes.ErrorType, ErrorTypeName(error))) |+|
        SpanFinalizer.setStatus(StatusCode.Error)
  }

  val strategy: SpanFinalizer.Strategy = escapedRaise.orElse(SpanFinalizer.Strategy.reportAbnormal)
}
