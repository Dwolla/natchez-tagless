package com.dwolla.metrics.otel4s

import org.typelevel.otel4s.Attributes

/** What a backend recorded for one histogram, in terms that don't mention any
  * backend's types, so `MeasurementContentSuite` can assert on oteljava today
  * and on otel4s-sdk later without change.
  */
final case class RecordedHistogram(name: String, unit: String, description: String, points: List[RecordedPoint])

/** One attribute set's aggregated measurements. */
final case class RecordedPoint(attributes: Attributes, count: Long, sum: Double, boundaries: List[Double])
