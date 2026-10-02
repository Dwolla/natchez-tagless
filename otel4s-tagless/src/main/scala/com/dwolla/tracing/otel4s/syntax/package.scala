package com.dwolla.tracing.otel4s

/** Syntax for the otel4s interpreters.
  *
  * `traceWithInputs`, `traceWithInputsAndOutputs` and `instrumentAndTrace` all
  * take an algebra and give back an `F` of one: running it obtains this
  * library's tracer from the ambient `TracerProvider[F]`, under the
  * instrumentation scope `com.dwolla.tracing.otel4s` (versioned), and yields
  * the traced algebra. Their examples live on the interpreters they delegate
  * to. `asAttributes` is the odd one out — it is the pure
  * function underneath the other two, and it is the shortest way to see exactly
  * what a traced call records.
  */
package object syntax
  extends ToTracerWeaveOps
    with ToInstrumentableAndTraceableOps
    with ToWeaveAttributesOps
