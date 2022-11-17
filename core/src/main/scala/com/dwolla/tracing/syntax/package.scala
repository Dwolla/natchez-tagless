package com.dwolla.tracing

package object syntax
  extends ToTraceWeaveOps
    with ToResourceInitializationSpanOps
    with ToInstrumentableAndTraceableInKleisliOps
    with ToInstrumentableAndTraceableOps
    with ToTraceParamsOps
