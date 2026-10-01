package com.dwolla.metrics.otel4s

import cats.effect.kernel.Resource
import cats.syntax.all._
import com.dwolla.tagless.{ErrorTypeName, RaisedError}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.{BucketBoundaries, Histogram, Meter, MeterProvider}
import org.typelevel.otel4s.semconv.attributes.ErrorAttributes

/** What both interpreters share: every call-duration histogram is in seconds,
  * and is created the same way.
  *
  * The SDK returns the existing instrument for an identical descriptor (name,
  * unit, description), so creating the same histogram again aggregates into
  * the same metric, whether it comes from another interpreter or from the other
  * flavor. Bucket
  * boundaries are advice, not part of that identity, and the advice is the same
  * everywhere; a View overrides it.
  */
private[otel4s] object CallDuration {
  val DurationUnit: String = "s"

  /** This library's instrumentation scope. A stream's identity includes its
    * scope, and the scope names the instrumenting library, so the module obtains
    * its own `Meter` rather than recording through an application-wide one.
    */
  val ScopeName: String = "com.dwolla.metrics.otel4s"

  def meter[F[_]: MeterProvider]: F[Meter[F]] =
    MeterProvider[F].meter(ScopeName).withVersion(BuildInfo.version).get

  /** The OpenTelemetry-recommended boundaries for RPC call durations, in
    * seconds: 5 ms to 10 s. The SDK's own default boundaries are shaped for
    * milliseconds and would put nearly every call in the first bucket.
    */
  val DefaultBucketBoundaries: BucketBoundaries =
    BucketBoundaries(0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0)

  /** `error.type` for a canceled call. Not a value the semantic conventions
    * define; they allow a low-cardinality, instrumentation-specific one.
    */
  val CanceledErrorType: String = "canceled"

  /** Absent on success, as the semantic conventions require; on failure, the
    * class of error the call ended with — for a cats-mtl raise that escaped the
    * call, the domain error rather than cats-mtl's `Submarine` wrapper — named by
    * `ErrorTypeName` (so Scala 3 enum cases stay distinct); and `"canceled"` on
    * cancellation.
    */
  def errorType(exitCase: Resource.ExitCase): Option[Attribute[String]] =
    exitCase match {
      case Resource.ExitCase.Succeeded => None
      case Resource.ExitCase.Errored(RaisedError(error)) => Attribute(ErrorAttributes.ErrorType, ErrorTypeName(error)).some
      case Resource.ExitCase.Errored(e) => Attribute(ErrorAttributes.ErrorType, ErrorTypeName(e)).some
      case Resource.ExitCase.Canceled => Attribute(ErrorAttributes.ErrorType, CanceledErrorType).some
    }

  def histogram[F[_]: Meter](name: String, description: String, buckets: BucketBoundaries): F[Histogram[F, Double]] =
    Meter[F]
      .histogram[Double](name)
      .withUnit(DurationUnit)
      .withDescription(description)
      .withExplicitBucketBoundaries(buckets)
      .create
}
