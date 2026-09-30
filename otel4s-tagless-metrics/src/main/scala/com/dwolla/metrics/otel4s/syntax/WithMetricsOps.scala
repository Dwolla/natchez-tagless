package com.dwolla.metrics.otel4s
package syntax

import cats.effect.kernel.{MonadCancelThrow, Ref}
import cats.syntax.all._
import cats.tagless.aop.Instrument
import cats.tagless.syntax.all._
import org.typelevel.otel4s.metrics.{BucketBoundaries, Meter}

trait ToWithMetricsOps {
  implicit def toWithMetricsOps[Alg[_[_]], F[_]](alg: Alg[F]): WithMetricsOps[Alg, F] =
    new WithMetricsOps(alg)
}

/** Records every method call's duration, in seconds, through the ambient
  * `Meter[F]`. Each overload returns `F[Alg[F]]`: running it creates what the
  * interpreter needs once, and yields the wrapped algebra; nothing is created
  * after the first call.
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
  *   import org.typelevel.otel4s.metrics.Meter
  *   import org.typelevel.otel4s.trace.Tracer
  *
  *   def internal[Alg[_[_]]: Instrument](alg: Alg[IO])(implicit M: Meter[IO], T: Tracer[IO]): IO[Alg[IO]] =
  *     alg.withMetrics().map(_.instrumentAndTrace)
  *
  *   def thriftServer[Alg[_[_]]: Instrument](impl: Alg[IO])(implicit M: Meter[IO], T: Tracer[IO]): IO[Alg[IO]] =
  *     impl
  *       .withMetrics(RpcRole.Server, RpcSystem("thrift"), RpcService("com.dwolla.crypto.EncryptionService"))
  *       .map(_.instrumentAndTrace)
  * }}}
  */
class WithMetricsOps[Alg[_[_]], F[_]](val alg: Alg[F]) extends AnyVal {
  /** For in-process calls: records to `<algebraName>.duration` with
    * `code.function.name = <algebraName>.<methodName>`, using bucket boundaries
    * from 5 ms to 10 s. A failed call also carries `error.type` (the error's
    * class name, or `"canceled"`). Errors and cancellation propagate unchanged.
    *
    * The histogram is created on the first call, because its name comes from
    * that call.
    */
  def withMetrics()(implicit I: Instrument[Alg], F: MonadCancelThrow[F], R: Ref.Make[F], M: Meter[F]): F[Alg[F]] =
    withMetrics(CallDuration.DefaultBucketBoundaries)

  /** As `withMetrics()`, with caller-chosen bucket boundaries in seconds — for
    * an algebra whose calls are much faster or slower than 5 ms to 10 s.
    *
    * Whichever creation of `<algebraName>.duration` comes first fixes its
    * buckets: wrapping another instance of the same algebra with different
    * boundaries records into the first one's buckets. To tune buckets
    * centrally, configure a View on the SDK instead.
    */
  def withMetrics(buckets: BucketBoundaries)(implicit I: Instrument[Alg], F: MonadCancelThrow[F], R: Ref.Make[F], M: Meter[F]): F[Alg[F]] =
    MeterInstrumentation[F](buckets).map(interpreter => alg.instrument.mapK(interpreter))

  /** For an algebra that is an RPC server implementation or client: records to
    * `rpc.server.call.duration` or `rpc.client.call.duration` (chosen by
    * `role`) per the OpenTelemetry RPC semantic conventions, with
    * `rpc.system.name = system.name` and
    * `rpc.method = <service.name>/<methodName>`, plus `error.type` for a failed
    * call. The histogram is created once, when the returned `F` runs.
    *
    * natchez-smithy4s's `withMetrics` records the same metric with an
    * identical descriptor, so the two are directly comparable, told apart by
    * `rpc.system.name`. Give each library its own `Meter` (instrumentation
    * scope) unless your backend can't aggregate across scopes; see the module
    * README.
    */
  def withMetrics(role: RpcRole, system: RpcSystem, service: RpcService)
                 (implicit I: Instrument[Alg], F: MonadCancelThrow[F], M: Meter[F]): F[Alg[F]] =
    RpcMeterInstrumentation[F](role, system, service).map(interpreter => alg.instrument.mapK(interpreter))
}
