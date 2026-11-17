package com.dwolla.metrics.otel4s.mtl

import com.dwolla.metrics.otel4s.OtelSdkHistograms

/** `RaiseMeasurementContentSuite` on the otel4s-sdk SDK. Cross-platform, so it is the metric-content coverage on Scala.js and Scala Native. */
class OtelSdkRaiseMeasurementContentSpec extends RaiseMeasurementContentSuite with OtelSdkHistograms
