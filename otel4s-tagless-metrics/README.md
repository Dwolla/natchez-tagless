# otel4s-tagless-metrics

Call-duration metrics for any cats-tagless algebra with an `Instrument`
instance, recorded through an otel4s `MeterProvider`; the module obtains its own
`Meter`. Syntax in `com.dwolla.metrics.otel4s.syntax`; the RPC parameter types
in `com.dwolla.metrics.otel4s`.

This module depends on `otel4s-core-metrics`, the stable `otel4s-semconv`,
cats, cats-tagless, and `tagless-core` — never on natchez, `otel4s-core-trace`, or an otel4s
backend. A `MeterProvider[F]` comes from a backend
(`otel4s-oteljava` on the JVM, `otel4s-sdk` cross-platform).

## Two flavors, one method

```scala
import com.dwolla.metrics.otel4s._
import com.dwolla.metrics.otel4s.syntax._

internal.withMetrics()                  // F[Alg[F]], in-process calls
thriftImpl.withMetrics(RpcRole.Server, RpcSystem("thrift"), RpcService("com.example.FooService"))
```

Every form returns `F[Alg[F]]`: running it sets up the interpreter once and
yields the wrapped algebra. Errors and cancellation propagate unchanged.

| | `withMetrics()` | `withMetrics(role, system, service)` |
| --- | --- | --- |
| For | in-process calls | an RPC server implementation or client |
| Needs | `Instrument[Alg]`, `MonadCancelThrow[F]`, `MeterProvider[F]` | `Instrument[Alg]`, `MonadCancelThrow[F]`, `MeterProvider[F]` |
| Metric | `com.dwolla.code.function.duration` (shared by every in-process algebra) | `rpc.server.call.duration` / `rpc.client.call.duration` |
| Unit | `s` | `s` |
| Buckets | 5 ms – 10 s (tune with an SDK View) | 5 ms – 10 s (the RPC recommendation) |
| Attributes | `code.function.name = Alg.method` | `rpc.system.name`, `rpc.method = <service>/<method>` |
| On failure | `error.type` = the error's class name (for an escaped cats-mtl raise, the domain error's class, not cats-mtl's internal wrapper; Scala 3 enum cases named `<Enum>.<Case>`), or `canceled` | same |

There is no call counter: a histogram's count is the call count.

On Scala.js, recognizing an escaped cats-mtl raise relies on runtime class
names, which Scala.js keeps by default; an application whose linker strips them
(`runtimeClassNameMapper`) falls back to recording the raw exception's class.

## Stacking with tracing

Apply tracing last:

```scala
alg.withMetrics().map(_.instrumentAndTrace)
```

Each measurement is then recorded while its span is active, so a backend that
supports exemplars can link a slow measurement to its trace, and the duration
excludes the span's own overhead.

## Things to know

- **Every in-process algebra shares one metric.** `withMetrics()` records to
  `com.dwolla.code.function.duration` and puts the operation in
  `code.function.name`, OpenTelemetry's fixed-name-plus-attributes pattern.
  To change bucket boundaries, configure an SDK View on that instrument name;
  Views select instruments by name, type, unit, or meter, not by attribute, so
  every algebra shares the boundaries. SDK-wide base-2 exponential histograms
  are an alternative where your backend supports querying them.
- **One cardinality budget.** Series are methods × error types across all
  wrapped algebras. The OpenTelemetry Java SDK's default limit is 2000 series
  per metric stream; beyond it, points collapse into `otel.metric.overflow`.
  Raise it with a View if a very large service needs more.
- **The RPC metric is comparable across libraries.** natchez-smithy4s's
  `withMetrics` records the same OpenTelemetry RPC metric with an identical
  name, unit, description, and buckets, so thrift and smithy4s calls in one
  service can be compared directly, told apart by `rpc.system.name`.
- **Instrumentation scope.** The module obtains its own meter from the
  `MeterProvider`, named `com.dwolla.metrics.otel4s` and versioned with the
  library, so its streams never collide with other instrumentation's. Some
  backends turn the scope version into a series label, which starts new series
  when you upgrade the library; aggregating queries are unaffected.
- **Import the RPC types by name next to natchez-smithy4s.** Its metrics
  module has its own `RpcRole`, so `import com.dwolla.metrics.otel4s._` and
  `import com.dwolla.metrics.smithy._` together make `RpcRole` ambiguous.
  Import `com.dwolla.metrics.otel4s.{RpcRole, RpcService, RpcSystem}` (or
  rename one, e.g. `RpcRole => TaglessRpcRole`) instead. With the `RpcRole` types
  imported by name, the two libraries' `withMetrics` syntaxes coexist in one file
  on Scala 2.13 and 3.
- **`code.function.name` is not fully qualified.** cats-tagless knows only an
  algebra's simple name, so it records `FooService.greet`.
