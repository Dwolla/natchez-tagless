package com.dwolla.metrics.otel4s

import cats.effect.IO
import io.opentelemetry.sdk.metrics.data.MetricData
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.oteljava.AttributeConverters._
import org.typelevel.otel4s.oteljava.testkit.metrics.MetricsTestkit

import scala.jdk.CollectionConverters._

/** A metric-content suite's `histogramsFrom`, backed by the oteljava SDK.
  * JVM-only because oteljava is. Mixed into every suite that asserts on
  * recorded histograms, here and in otel4s-tagless-mtl.
  */
trait OtelJavaHistograms {
  protected def histogramsFrom[A](f: MeterProvider[IO] => IO[A]): IO[(A, List[RecordedHistogram])] =
    MetricsTestkit.inMemory[IO]().use { testkit =>
      for {
        a <- f(testkit.meterProvider)
        metrics <- testkit.collectMetrics
      } yield (a, metrics.map(recorded))
    }

  private def recorded(metric: MetricData): RecordedHistogram =
    RecordedHistogram(
      name = metric.getName,
      unit = metric.getUnit,
      description = metric.getDescription,
      scopeName = metric.getInstrumentationScopeInfo.getName,
      scopeVersion = Option(metric.getInstrumentationScopeInfo.getVersion),
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
