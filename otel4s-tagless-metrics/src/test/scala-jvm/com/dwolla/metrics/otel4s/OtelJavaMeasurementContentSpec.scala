package com.dwolla.metrics.otel4s

import cats.effect.IO
import io.opentelemetry.sdk.metrics.data.MetricData
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.oteljava.AttributeConverters._
import org.typelevel.otel4s.oteljava.testkit.metrics.MetricsTestkit

import scala.jdk.CollectionConverters._

/** `MeasurementContentSuite` on the oteljava SDK. JVM-only because oteljava is. */
class OtelJavaMeasurementContentSpec extends MeasurementContentSuite {
  override protected def histogramsFrom[A](f: Meter[IO] => IO[A]): IO[(A, List[RecordedHistogram])] =
    MetricsTestkit.inMemory[IO]().use { testkit =>
      for {
        meter <- testkit.meterProvider.get("otel4s-tagless-metrics-test")
        a <- f(meter)
        metrics <- testkit.collectMetrics
      } yield (a, metrics.map(recorded))
    }

  private def recorded(metric: MetricData): RecordedHistogram =
    RecordedHistogram(
      name = metric.getName,
      unit = metric.getUnit,
      description = metric.getDescription,
      points = metric.getHistogramData.getPoints.asScala.toList.map { point =>
        RecordedPoint(
          attributes = point.getAttributes.toScala,
          count = point.getCount,
          sum = point.getSum,
          boundaries = point.getBoundaries.asScala.toList.map(_.doubleValue),
        )
      },
    )
}
