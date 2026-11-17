package com.dwolla.metrics.otel4s

import cats.effect.IO
import cats.effect.testkit.TestControl
import munit.Assertions
import org.typelevel.otel4s.AttributeKey
import org.typelevel.otel4s.metrics.MeterProvider

/** Records histograms against a real SDK, on virtual time, and picks the
  * recorded histogram and point a test is about.
  *
  * A suite mixes this in and a backend supplies `histogramsFrom`
  * (`OtelSdkHistograms`, every platform; `OtelJavaHistograms`, JVM).
  */
trait HistogramAssertions { self: Assertions =>

  /** Runs `f` with a `MeterProvider[IO]` backed by a real SDK and returns what `f`
    * produced plus every histogram recorded. `measured` calls this inside
    * `TestControl`, so an implementation must allocate its SDK inside the
    * returned `IO`, never eagerly.
    */
  protected def histogramsFrom[A](f: MeterProvider[IO] => IO[A]): IO[(A, List[RecordedHistogram])]

  /** `histogramsFrom` on virtual time: `IO.sleep` inside `f` advances the
    * clock `recordDuration` reads, instantly and exactly.
    */
  protected def measured[A](f: MeterProvider[IO] => IO[A]): IO[(A, List[RecordedHistogram])] =
    TestControl.executeEmbed(histogramsFrom(f))

  protected def histogramNamed(name: String, histograms: List[RecordedHistogram]): RecordedHistogram =
    histograms.filter(_.name == name) match {
      case List(histogram) => histogram
      case other => fail(s"expected exactly one histogram named $name, found ${other.size} among ${histograms.map(_.name)}")
    }

  protected def pointFor(key: AttributeKey[String], value: String, histogram: RecordedHistogram): RecordedPoint =
    histogram.points.filter(_.attributes.get(key).map(_.value).contains(value)) match {
      case List(point) => point
      case other => fail(s"expected exactly one point with $key=$value in ${histogram.name}, found ${other.size}: ${histogram.points}")
    }
}
