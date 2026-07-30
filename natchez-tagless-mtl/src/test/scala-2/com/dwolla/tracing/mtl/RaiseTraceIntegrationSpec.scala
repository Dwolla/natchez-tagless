package com.dwolla.tracing.mtl

import com.dwolla.tagless.mtl.{DeriveRaise, RaiseAspect}
import natchez.TraceableValue

class RaiseTraceIntegrationSpec extends RaiseTraceIntegrationSuite {
  implicit def barRaiseAspect: RaiseAspect[Bar, TraceableValue, TraceableValue, TraceableValue] =
    DeriveRaise.aspect[Bar, TraceableValue, TraceableValue, TraceableValue]
}

class RaiseTraceValueSpec extends RaiseTraceValueSuite {
  implicit def barRaiseAspect: RaiseAspect[Bar, TraceableValue, TraceableValue, TraceableValue] =
    DeriveRaise.aspect[Bar, TraceableValue, TraceableValue, TraceableValue]
}
