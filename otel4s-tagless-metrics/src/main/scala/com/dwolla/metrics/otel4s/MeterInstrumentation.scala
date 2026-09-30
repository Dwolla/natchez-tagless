package com.dwolla.metrics.otel4s

import cats.effect.kernel.{MonadCancelThrow, Ref, Resource}
import cats.syntax.all._
import cats.tagless.aop.Instrumentation
import cats.~>
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.{BucketBoundaries, Histogram, Meter}
import org.typelevel.otel4s.semconv.attributes.CodeAttributes

import scala.concurrent.duration.SECONDS

private[otel4s] object MeterInstrumentation {
  def apply[F[_]: MonadCancelThrow: Ref.Make: Meter](buckets: BucketBoundaries): F[MeterInstrumentation[F]] =
    Ref.of[F, Option[Histogram[F, Double]]](None).map(new MeterInstrumentation[F](buckets, _))
}

/** Records each call's duration to `<algebraName>.duration`, in seconds, with
  * `code.function.name = <algebraName>.<methodName>`; a failed call also
  * carries `error.type` (the error's class name, or `"canceled"`). Errors and
  * cancellation propagate unchanged.
  *
  * The metric name comes from the `Instrumentation`, so the histogram can't be
  * created until the first call; it is created then and kept in `histogram`.
  * That single slot is only correct while one instance serves one algebra,
  * which is why this class is package-private: `withMetrics` builds a fresh
  * instance for exactly one algebra, and nothing else can obtain one. Racing
  * first calls may both create it, which is harmless — see `CallDuration`.
  */
private[otel4s] class MeterInstrumentation[F[_]: MonadCancelThrow: Meter](buckets: BucketBoundaries,
                                                                           histogram: Ref[F, Option[Histogram[F, Double]]])
  extends (Instrumentation[F, *] ~> F) {

  override def apply[A](fa: Instrumentation[F, A]): F[A] = {
    val codeFunctionName = Attribute(CodeAttributes.CodeFunctionName, s"${fa.algebraName}.${fa.methodName}")
    val attributesFor: Resource.ExitCase => List[Attribute[_]] =
      exitCase => codeFunctionName :: CallDuration.errorType(exitCase).toList

    histogramFor(fa.algebraName).flatMap(_.recordDuration(SECONDS, attributesFor).surround(fa.value))
  }

  private def histogramFor(algebraName: String): F[Histogram[F, Double]] =
    histogram.get.flatMap {
      case Some(created) => created.pure[F]
      case None =>
        CallDuration
          .histogram[F](s"$algebraName.duration", s"Duration of calls to methods of $algebraName.", buckets)
          .flatTap(created => histogram.set(created.some))
    }
}
