package com.dwolla.tracing.otel4s

import cats.tagless.aop.Instrumentation
import cats.~>
import org.typelevel.otel4s.trace.Tracer

object TracerInstrumentation {
  def apply[F[_]: Tracer]: Instrumentation[F, *] ~> F = new TracerInstrumentation[F]
}

/**
 * Use this `FunctionK` when you have an algebra in `Instrumentation[F, *]` and you
 * want each method call on the algebra to introduce a new child span, using the
 * ambient `Tracer[F]`. Each child span will be named using the algebra name and
 * method name as captured in the `Instrumentation[F, A]`.
 *
 * This is the otel4s counterpart of `com.dwolla.tracing.TraceInstrumentation`,
 * and like it, it records the span's ''name'' and nothing else: no attributes
 * are added. Capturing parameters and return values is
 * `TracerWeaveCapturingInputsAndOutputs`'s job.
 *
 * Note if you have an algebra `Alg[F]` for which an `Instrument[Alg]` exists,
 * it can be converted to `Alg[Instrumentation[F, *]]`
 * using `Instrument[Alg].instrument`. A hand-written `Instrument` looks like
 * the one in `TraceInstrumentation`'s scaladoc; on Scala 3 the whole instance
 * collapses to `trait Foo[F[_]] derives Instrument`, which is upstream's own
 * derivation and needs nothing from this library. Either way, the woven
 * algebra is turned back into an `Alg[F]` with
 * `.mapK(TracerInstrumentation[F])`.
 *
 * One difference from the natchez interpreter is worth knowing about: when a
 * `Throwable` escapes the traced effect, otel4s marks the span as errored on
 * its own. `SpanBuilder`'s default finalization strategy is
 * `SpanFinalizer.Strategy.reportAbnormal`, which records the exception and
 * sets the span status for an error or a cancelation; natchez does no such
 * thing, so a natchez span that fails looks the same as one that succeeded
 * unless something else records the failure.
 *
 * `Tracer[F]` is the only constraint: `spanBuilder`, `build` and `surround`
 * ask nothing of `F` at the call site — the same profile as the natchez
 * version, which needs only `Trace[F]`.
 *
 * {{{
 *   import cats.effect.IO
 *   import cats.tagless.aop._
 *   import cats.~>
 *   import com.dwolla.tracing.otel4s.syntax._
 *   import org.typelevel.otel4s.trace.Tracer
 *
 *   trait Foo[F[_]] {
 *     def foo: F[Unit]
 *   }
 *
 *   // hand-written so this compiles on 2.13 as well as 3, where the whole
 *   // instance collapses to `trait Foo[F[_]] derives Instrument` — upstream's
 *   // own derivation, which needs nothing from this library
 *   implicit val fooInstrument: Instrument[Foo] = new Instrument[Foo] {
 *     override def instrument[F[_]](af: Foo[F]): Foo[Instrumentation[F, *]] =
 *     new Foo[Instrumentation[F, *]] {
 *       override def foo: Instrumentation[F, Unit] = Instrumentation(af.foo, "Foo", "foo")
 *     }
 *
 *     override def mapK[F[_], G[_]](af: Foo[F])(fk: F ~> G): Foo[G] =
 *     new Foo[G] {
 *       override def foo: G[Unit] = fk(af.foo)
 *     }
 *   }
 *
 *   val myFoo: Foo[IO] = new Foo[IO] {
 *     override def foo: IO[Unit] = IO.unit
 *   }
 *
 *   // A real application summons this from `TracerProvider[F].get(name)`,
 *   // supplied by a backend module — `otel4s-oteljava` on the JVM,
 *   // `otel4s-sdk` cross-platform. This library never provides one.
 *   implicit val tracer: Tracer[IO] = Tracer.noop[IO]
 *
 *   // every call to `traced.foo` now runs inside a span named `Foo.foo`
 *   val traced: Foo[IO] = myFoo.instrumentAndTrace
 * }}}
 */
class TracerInstrumentation[F[_]: Tracer] extends (Instrumentation[F, *] ~> F) {
  override def apply[A](fa: Instrumentation[F, A]): F[A] =
    Tracer[F]
      .spanBuilder(s"${fa.algebraName}.${fa.methodName}")
      .build
      .surround(fa.value)
}
