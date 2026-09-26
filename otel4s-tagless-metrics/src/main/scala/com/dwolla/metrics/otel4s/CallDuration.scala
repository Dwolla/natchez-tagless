package com.dwolla.metrics.otel4s

import org.typelevel.otel4s.metrics.{BucketBoundaries, Histogram, Meter}

/** What both interpreters share: every call-duration histogram is in seconds,
  * and is created the same way.
  *
  * The SDK returns the existing instrument for an identical descriptor (name,
  * unit, description), so creating the same histogram again — from a second
  * interpreter for the same algebra, say — aggregates into the same metric.
  * Bucket boundaries are advice, not part of that identity: whichever creation
  * of a name comes first fixes its buckets.
  */
private[otel4s] object CallDuration {
  val DurationUnit: String = "s"

  /** The OpenTelemetry-recommended boundaries for RPC call durations, in
    * seconds: 5 ms to 10 s. The SDK's own default boundaries are shaped for
    * milliseconds and would put nearly every call in the first bucket.
    */
  val DefaultBucketBoundaries: BucketBoundaries =
    BucketBoundaries(0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0)

  def histogram[F[_]: Meter](name: String, description: String, buckets: BucketBoundaries): F[Histogram[F, Double]] =
    Meter[F]
      .histogram[Double](name)
      .withUnit(DurationUnit)
      .withDescription(description)
      .withExplicitBucketBoundaries(buckets)
      .create
}
