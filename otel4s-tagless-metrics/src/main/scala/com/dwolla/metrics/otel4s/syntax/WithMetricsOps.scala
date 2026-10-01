package com.dwolla.metrics.otel4s
package syntax

import cats.effect.kernel.MonadCancelThrow
import cats.syntax.all._
import cats.tagless.aop.Instrument
import cats.tagless.syntax.all._
import org.typelevel.otel4s.metrics.MeterProvider

trait ToWithMetricsOps {
  implicit def toWithMetricsOps[Alg[_[_]], F[_]](alg: Alg[F]): WithMetricsOps[Alg, F] =
    new WithMetricsOps(alg)
}

/** Records every method call's duration, in seconds, through the ambient
  * `MeterProvider[F]`. Each overload returns `F[Alg[F]]`: running it obtains
  * this library's meter, creates the histogram once, and yields the wrapped
  * algebra; nothing is created per call.
  *
  * Overloads rather than default arguments: a method with defaults can't gain a
  * parameter, or a same-named sibling with defaults, without breaking binary
  * compatibility. The zero-argument form's explicit `()` keeps it distinct from
  * an implicit-only parameter list during overload resolution.
  *
  * To also trace, apply tracing ''last'' so each measurement is recorded while
  * its span is active (letting a backend attach the trace as an exemplar) and
  * the duration excludes the span's own overhead:
  *
  * {{{
  *   import cats.effect.IO
  *   import cats.tagless.aop.Instrument
  *   import com.dwolla.metrics.otel4s.{RpcRole, RpcService, RpcSystem}
  *   import com.dwolla.metrics.otel4s.syntax._
  *   import com.dwolla.tracing.otel4s.syntax._
  *   import org.typelevel.otel4s.metrics.MeterProvider
  *   import org.typelevel.otel4s.trace.Tracer
  *
  *   def internal[Alg[_[_]]: Instrument](alg: Alg[IO])(implicit M: MeterProvider[IO], T: Tracer[IO]): IO[Alg[IO]] =
  *     alg.withMetrics().map(_.instrumentAndTrace)
  *
  *   def thriftServer[Alg[_[_]]: Instrument](impl: Alg[IO])(implicit M: MeterProvider[IO], T: Tracer[IO]): IO[Alg[IO]] =
  *     impl
  *       .withMetrics(RpcRole.Server, RpcSystem("thrift"), RpcService("com.example.FooService"))
  *       .map(_.instrumentAndTrace)
  * }}}
  */
class WithMetricsOps[Alg[_[_]], F[_]](val alg: Alg[F]) extends AnyVal {
  /** For in-process calls: records every call's duration, in seconds, to
    * `com.dwolla.code.function.duration`, with
    * `code.function.name = <algebraName>.<methodName>` and, for a failed call,
    * `error.type` (the error's class name, or `"canceled"`). Every algebra
    * wrapped this way shares that one metric. The histogram is created once,
    * when the returned `F` runs, under this library's own instrumentation
    * scope (`com.dwolla.metrics.otel4s`, versioned). To change its bucket
    * boundaries, configure an SDK View on the instrument name. Errors and
    * cancellation propagate unchanged.
    */
  def withMetrics()(implicit I: Instrument[Alg], F: MonadCancelThrow[F], M: MeterProvider[F]): F[Alg[F]] =
    MeterInstrumentation[F]().map(interpreter => alg.instrument.mapK(interpreter))

  /** For an algebra that is an RPC server implementation or client: records to
    * `rpc.server.call.duration` or `rpc.client.call.duration` (chosen by
    * `role`) per the OpenTelemetry RPC semantic conventions, with
    * `rpc.system.name = system.name` and
    * `rpc.method = <service.name>/<methodName>`, plus `error.type` for a failed
    * call. The histogram is created once, when the returned `F` runs.
    *
    * natchez-smithy4s's `withMetrics` records the same metric with an
    * identical descriptor under its own scope, so the two are directly
    * comparable, told apart by `rpc.system.name`.
    */
  def withMetrics(role: RpcRole, system: RpcSystem, service: RpcService)
                 (implicit I: Instrument[Alg], F: MonadCancelThrow[F], M: MeterProvider[F]): F[Alg[F]] =
    RpcMeterInstrumentation[F](role, system, service).map(interpreter => alg.instrument.mapK(interpreter))
}
