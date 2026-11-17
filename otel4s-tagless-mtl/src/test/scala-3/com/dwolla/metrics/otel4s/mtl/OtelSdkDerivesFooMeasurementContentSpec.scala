package com.dwolla.metrics.otel4s.mtl

import com.dwolla.metrics.otel4s.OtelSdkHistograms

import scala.annotation.experimental

/** `DerivesFooMeasurementContentSuite` on the otel4s-sdk SDK. Cross-platform. */
@experimental
class OtelSdkDerivesFooMeasurementContentSpec extends DerivesFooMeasurementContentSuite with OtelSdkHistograms
