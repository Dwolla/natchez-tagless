package com.dwolla.metrics.otel4s.mtl

import com.dwolla.metrics.otel4s.OtelJavaHistograms

import scala.annotation.experimental

/** `DerivesFooMeasurementContentSuite` on the oteljava SDK. JVM-only because oteljava is. */
@experimental
class OtelJavaDerivesFooMeasurementContentSpec extends DerivesFooMeasurementContentSuite with OtelJavaHistograms
