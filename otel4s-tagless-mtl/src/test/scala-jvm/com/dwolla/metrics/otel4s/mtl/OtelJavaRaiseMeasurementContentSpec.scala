package com.dwolla.metrics.otel4s.mtl

import com.dwolla.metrics.otel4s.OtelJavaHistograms

/** `RaiseMeasurementContentSuite` on the oteljava SDK. JVM-only because oteljava is. */
class OtelJavaRaiseMeasurementContentSpec extends RaiseMeasurementContentSuite with OtelJavaHistograms
