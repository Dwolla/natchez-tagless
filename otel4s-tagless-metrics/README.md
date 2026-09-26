# otel4s-tagless-metrics

Call-duration metrics for any cats-tagless algebra with an `Instrument`
instance, recorded through an otel4s `Meter`. Syntax in
`com.dwolla.metrics.otel4s.syntax`; the RPC parameter types in
`com.dwolla.metrics.otel4s`.

This module depends on `otel4s-core-metrics`, the stable `otel4s-semconv`,
cats, and cats-tagless — never on natchez, `otel4s-core-trace`, or an otel4s
backend. A `Meter[F]` comes from a backend's `MeterProvider[F]`
(`otel4s-oteljava` on the JVM, `otel4s-sdk` cross-platform).

## Two flavors, one method

```scala
import com.dwolla.metrics.otel4s._
import com.dwolla.metrics.otel4s.syntax._

internal.withMetrics()                  // F[Alg[F]], in-process calls
internal.withMetrics(buckets)           // …with your own bucket boundaries
thriftImpl.withMetrics(RpcRole.Server, RpcSystem("thrift"), RpcService("com.dwolla.crypto.EncryptionService"))
```

Every form returns `F[Alg[F]]`: running it sets up the interpreter once and
yields the wrapped algebra. Errors and cancellation propagate unchanged.

| | `withMetrics()` / `withMetrics(buckets)` | `withMetrics(role, system, service)` |
| --- | --- | --- |
| For | in-process calls | an RPC server implementation or client |
| Needs | `Instrument[Alg]`, `MonadCancelThrow[F]`, `Ref.Make[F]`, `Meter[F]` | `Instrument[Alg]`, `MonadCancelThrow[F]`, `Meter[F]` |
| Metric | `<algebraName>.duration` | `rpc.server.call.duration` / `rpc.client.call.duration` |
| Unit | `s` | `s` |
| Buckets | 5 ms – 10 s, or yours | 5 ms – 10 s (the RPC recommendation) |
| Attributes | `code.function.name = Alg.method` | `rpc.system.name`, `rpc.method = <service>/<method>` |
| On failure | `error.type` = the error's class name, or `canceled` | same |

There is no call counter: a histogram's count is the call count.

## Stacking with tracing

Apply tracing last:

```scala
alg.withMetrics().map(_.instrumentAndTrace)
```

Each measurement is then recorded while its span is active, so a backend that
supports exemplars can link a slow measurement to its trace, and the duration
excludes the span's own overhead.

## Things to know

- **The generic histogram is created on the first call.** Its name comes from
  the algebra name cats-tagless reports with each call, so it can't be created
  sooner; the wrapped algebra keeps it after that. The RPC histogram is created
  when the returned `F` runs.
- **Buckets are fixed by the first creation of a metric name.** Bucket
  boundaries are instrument *advice*, not identity, so wrapping another instance
  of the same algebra with different boundaries records into the first one's
  buckets. Tune buckets centrally with an SDK View.
- **The RPC metric is shared.** Anything else recording
  `rpc.server.call.duration` through the same `Meter` — e.g. natchez-smithy4s's
  `withMetrics` — lands in the same histogram, told apart by `rpc.system.name`
  and `rpc.method`.
- **`code.function.name` is not fully qualified.** cats-tagless knows only an
  algebra's simple name, so it records `EncryptionService.encrypt`.
