package com.dwolla.metrics.otel4s

import cats.effect.kernel.{MonadCancelThrow, Resource}
import cats.syntax.all._
import cats.tagless.aop.Instrumentation
import cats.~>
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.{Histogram, MeterProvider}

import scala.concurrent.duration.SECONDS

private[otel4s] object RpcMeterInstrumentation {
  /** The RPC metric's name is fixed by `role`, so its histogram is created here,
    * once, and every call only records into it.
    */
  def apply[F[_]: MonadCancelThrow: MeterProvider](role: RpcRole, system: RpcSystem, service: RpcService): F[RpcMeterInstrumentation[F]] =
    CallDuration.meter[F].flatMap { implicit meter =>
      CallDuration
        .histogram[F](role.callDurationMetricName, role.callDurationDescription, CallDuration.DefaultBucketBoundaries)
        .map(new RpcMeterInstrumentation[F](system, service, _))
    }
}

/** Records each call's duration, in seconds, to the RPC call-duration histogram
  * it was built with, following the OpenTelemetry RPC semantic conventions:
  * `rpc.system.name = system.name`, `rpc.method = <service.name>/<methodName>`,
  * and, for a failed call, `error.type` (the error's class name, or
  * `"canceled"`). Errors and cancellation propagate unchanged.
  */
private[otel4s] class RpcMeterInstrumentation[F[_]: MonadCancelThrow](system: RpcSystem,
                                                                       service: RpcService,
                                                                       callDuration: Histogram[F, Double])
  extends (Instrumentation[F, *] ~> F) {

  private val rpcSystemName: Attribute[String] = Attribute(RpcSemanticConventions.RpcSystemName, system.name)

  override def apply[A](fa: Instrumentation[F, A]): F[A] = {
    val rpcMethod = Attribute(RpcSemanticConventions.RpcMethod, s"${service.name}/${fa.methodName}")
    val attributesFor: Resource.ExitCase => List[Attribute[_]] =
      exitCase => rpcSystemName :: rpcMethod :: CallDuration.errorType(exitCase).toList

    callDuration.recordDuration(SECONDS, attributesFor).surround(fa.value)
  }
}
