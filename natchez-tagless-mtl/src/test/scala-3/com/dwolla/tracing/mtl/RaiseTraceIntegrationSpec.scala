package com.dwolla.tracing.mtl

import cats.tagless.Trivial
import com.dwolla.tagless.mtl.{DeriveRaise, RaiseAspect}
import natchez.TraceableValue

import scala.annotation.experimental

@experimental
class RaiseTraceIntegrationSpec extends RaiseTraceIntegrationSuite {
  implicit def barRaiseAspect: RaiseAspect[Bar, TraceableValue, TraceableValue, TraceableValue] =
    DeriveRaise.aspect[Bar, TraceableValue, TraceableValue, TraceableValue]

  implicit def barRaiseAspectTrivialCod: RaiseAspect[Bar, TraceableValue, Trivial, TraceableValue] =
    DeriveRaise.aspect[Bar, TraceableValue, Trivial, TraceableValue]
}

@experimental
class RaiseTraceValueSpec extends RaiseTraceValueSuite {
  implicit def barRaiseAspect: RaiseAspect[Bar, TraceableValue, TraceableValue, TraceableValue] =
    DeriveRaise.aspect[Bar, TraceableValue, TraceableValue, TraceableValue]
}
