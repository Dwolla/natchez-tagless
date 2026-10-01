package com.dwolla.metrics.otel4s

import cats.effect.kernel.{MonadCancelThrow, Resource}
import cats.syntax.all._
import cats.tagless.aop.Instrumentation
import cats.~>
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.{Histogram, MeterProvider}
import org.typelevel.otel4s.semconv.attributes.CodeAttributes

import scala.concurrent.duration.SECONDS

private[otel4s] object MeterInstrumentation {
  /** One metric for every in-process algebra: OTel's pattern is a fixed name
    * with the operation in attributes, so the name never varies by algebra.
    */
  val MetricName: String = "com.dwolla.code.function.duration"
  val Description: String = "Measures the duration of calls to instrumented functions."

  /** The name is fixed, so the histogram is created here, once. */
  def apply[F[_]: MonadCancelThrow: MeterProvider](): F[MeterInstrumentation[F]] =
    CallDuration.meter[F].flatMap { implicit meter =>
      CallDuration
        .histogram[F](MetricName, Description, CallDuration.DefaultBucketBoundaries)
        .map(new MeterInstrumentation[F](_))
    }
}

/** Records each call's duration, in seconds, to `com.dwolla.code.function.duration`
  * with `code.function.name = <algebraName>.<methodName>`; a failed call also
  * carries `error.type` (the error's type name, computed by `ErrorTypeName`; Scala 3 enum cases as `<Enum>\$<Case>`, or `"canceled"`). Errors and
  * cancellation propagate unchanged.
  */
private[otel4s] class MeterInstrumentation[F[_]: MonadCancelThrow](callDuration: Histogram[F, Double])
  extends (Instrumentation[F, *] ~> F) {

  override def apply[A](fa: Instrumentation[F, A]): F[A] = {
    val codeFunctionName = Attribute(CodeAttributes.CodeFunctionName, s"${fa.algebraName}.${fa.methodName}")
    val attributesFor: Resource.ExitCase => List[Attribute[_]] =
      exitCase => codeFunctionName :: CallDuration.errorType(exitCase).toList

    callDuration.recordDuration(SECONDS, attributesFor).surround(fa.value)
  }
}
