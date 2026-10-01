package com.dwolla.metrics.otel4s

import cats.effect.IO
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.sdk.metrics.data.{MetricData, MetricPoints, PointData}
import org.typelevel.otel4s.sdk.testkit.metrics.MetricsTestkit

/** `MeasurementContentSuite` on the otel4s-sdk SDK. Cross-platform, so it is the metric-content coverage on Scala.js. */
class OtelSdkMeasurementContentSpec extends MeasurementContentSuite {
  override protected def histogramsFrom[A](f: MeterProvider[IO] => IO[A]): IO[(A, List[RecordedHistogram])] =
    MetricsTestkit.inMemory[IO]().use { testkit =>
      for {
        a <- f(testkit.meterProvider)
        metrics <- testkit.collectMetrics
      } yield (a, metrics.flatMap(recorded))
    }

  private def recorded(metric: MetricData): Option[RecordedHistogram] =
    metric.data match {
      case histogram: MetricPoints.Histogram =>
        Some(
          RecordedHistogram(
            name = metric.name,
            unit = metric.unit.getOrElse(""),
            description = metric.description.getOrElse(""),
            scopeName = metric.instrumentationScope.name,
            scopeVersion = metric.instrumentationScope.version,
            points = histogram.points.toVector.toList.map(recordedPoint),
          )
        )
      case _ => None
    }

  private def recordedPoint(point: PointData.Histogram): RecordedPoint =
    RecordedPoint(
      attributes = point.attributes,
      count = point.stats.fold(0L)(_.count),
      sum = point.stats.fold(0.0)(_.sum),
      boundaries = point.boundaries.boundaries.toList,
    )
}
