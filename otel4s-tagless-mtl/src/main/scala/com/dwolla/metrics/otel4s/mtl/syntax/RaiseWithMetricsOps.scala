package com.dwolla.metrics.otel4s.mtl
package syntax

import cats.effect.kernel.MonadCancelThrow
import cats.syntax.all._
import com.dwolla.metrics.otel4s.{MeterInstrumentation, RpcMeterInstrumentation, RpcRole, RpcService, RpcSystem}
import org.typelevel.otel4s.metrics.MeterProvider

trait ToRaiseWithMetricsOps {
  implicit def toRaiseWithMetricsOps[Alg[_[_]], F[_]](alg: Alg[F]): RaiseWithMetricsOps[Alg, F] =
    new RaiseWithMetricsOps(alg)
}

/** `com.dwolla.metrics.otel4s.syntax.WithMetricsOps`'s `withMetrics`, for
  * algebras whose methods take `cats.mtl.Raise` capabilities as well as plain
  * ones: each overload resolves a [[com.dwolla.metrics.otel4s.mtl.Meterable]],
  * preferring a cats-tagless `Instrument` and otherwise using a
  * `RaiseAspect[Alg, ToAnyValue, ToAnyValue, ToAnyValue]`. Either way it
  * records exactly what `WithMetricsOps` records, to the same metrics, through
  * the same interpreters; see that class for the metric names, attributes,
  * and when the histogram is created. A raise carried as a failure of `F`
  * (`Handle.allowF` over a `MonadThrow`, say) that escapes a call records the
  * raised value's type as `error.type`. A raise carried in `F`'s success
  * channel (`EitherT`, say), or one the method rescues itself, is a success
  * with no `error.type`, as with `WithMetricsOps`.
  *
  * Import `com.dwolla.metrics.otel4s.mtl.syntax._` ''in place of''
  * `com.dwolla.metrics.otel4s.syntax._`, never beside it: both provide
  * `withMetrics` on every `Alg[F]`, so importing both makes the call
  * ambiguous.
  *
  * Overloads rather than default arguments, with an explicit `()` on the
  * zero-argument form, for the binary-compatibility reasons given on
  * `WithMetricsOps`.
  *
  * As with `WithMetricsOps`, apply tracing ''last'', here with
  * `com.dwolla.tracing.otel4s.mtl.syntax`, which can be imported alongside
  * this one:
  *
  * {{{
  *   import cats.effect.IO
  *   import com.dwolla.metrics.otel4s.mtl.syntax._
  *   import com.dwolla.tagless.mtl.RaiseAspect
  *   import com.dwolla.tracing.otel4s.ToAnyValue
  *   import com.dwolla.tracing.otel4s.mtl.syntax._
  *   import org.typelevel.otel4s.metrics.MeterProvider
  *   import org.typelevel.otel4s.trace.TracerProvider
  *
  *   def meteredAndTraced[Alg[_[_]]](alg: Alg[IO])
  *                                  (implicit A: RaiseAspect[Alg, ToAnyValue, ToAnyValue, ToAnyValue],
  *                                   M: MeterProvider[IO],
  *                                   T: TracerProvider[IO]): IO[Alg[IO]] =
  *     alg.withMetrics().flatMap(_.traceWithInputs[ToAnyValue])
  * }}}
  */
class RaiseWithMetricsOps[Alg[_[_]], F[_]](val alg: Alg[F]) extends AnyVal {
  /** For in-process calls: records to `com.dwolla.code.function.duration`
    * with `code.function.name = <algebraName>.<methodName>`, plus `error.type`
    * for a failed call.
    */
  def withMetrics()(implicit A: Meterable[Alg], F: MonadCancelThrow[F], M: MeterProvider[F]): F[Alg[F]] =
    MeterInstrumentation[F]().map(A(alg)(_))

  /** For an algebra that is an RPC server implementation or client: records to
    * `rpc.server.call.duration` or `rpc.client.call.duration` (chosen by
    * `role`) with `rpc.system.name = system.name` and
    * `rpc.method = <service.name>/<methodName>`, plus `error.type` for a failed
    * call.
    */
  def withMetrics(role: RpcRole, system: RpcSystem, service: RpcService)
                 (implicit A: Meterable[Alg], F: MonadCancelThrow[F], M: MeterProvider[F]): F[Alg[F]] =
    RpcMeterInstrumentation[F](role, system, service).map(A(alg)(_))
}
