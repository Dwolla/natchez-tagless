package com.dwolla.tracing.otel4s

import cats.Id
import cats.tagless.aop.Aspect
import cats.tagless.syntax.all._
import munit.FunSuite
import org.typelevel.otel4s.trace.Tracer

/** What can be checked without a testkit, on every platform.
  *
  * `Tracer[F]`, `Span[F]`, `SpanOps[F]`, `SpanBuilder[F]` and `Span.Backend[F]`
  * are all sealed and their `Unsealed` variants are `private[otel4s]`, so a
  * recording `Tracer` cannot be hand-rolled the way this repo hand-rolls a
  * `natchez.Trace`. Span ''content'' is therefore asserted in `SpanContentSpec`,
  * which is JVM-only because otel4s's cross-platform SDK testkit has not been
  * released at 1.0.x.
  *
  * Two things make this suite worth having anyway, and neither is the obvious
  * one. Parametricity already forbids an interpreter with signature
  * `apply[A](fa: Weave[F, ToAnyValue, Cod, A]): F[A]` and nothing but
  * `Tracer[F]` from inventing an `A`, so "the value survives" is close to a
  * tautology at the type level. What is not tautological:
  *
  *  1. It is the only place the interpreters' return values are checked at all
  *     — `SpanContentSpec` exists to read spans, and until this milestone it
  *     could not even see a returned value.
  *  1. It pins the '''constraint set''' at compile time. `Id` has no
  *     `MonadError[Id, Throwable]`, so the moment an interpreter picks up a
  *     `MonadCancelThrow` or `Async` bound, this file stops compiling — where a
  *     suite written over `IO` would sail through and the extra constraint
  *     would reach users unnoticed.
  *
  * `Tracer.noop`'s `build.use(f)` is `f(span)`, so `surround(fa)` reduces to
  * `fa`, and its `SpanBuilder#modifyState` returns `this` without ever applying
  * the function — which is what makes the "never encoded" assertion below
  * meaningful.
  */
class TracerTransparencySpec extends FunSuite {
  private implicit val tracer: Tracer[Id] = Tracer.noop[Id]

  /** `Id` cannot defer anything, so the count records the call itself. */
  private def underlyingFoo(counts: FooCallCounts): Foo[Id] =
    Foo.counting[Id](counts)(f => f())

  test("an instrumented call returns exactly what the underlying call returns") {
    val counts = new FooCallCounts
    val underlying = underlyingFoo(counts)
    val instrumented: Foo[Id] = underlying.instrument.mapK(TracerInstrumentation[Id])

    // The counts are read before `underlying` is called directly for the
    // expected values, because those direct calls bump the same counters.
    val greeting = instrumented.greet("world", 2)
    val pong = instrumented.ping()
    assertEquals((counts.greet, counts.ping), (1, 1))

    assertEquals(greeting, underlying.greet("world", 2))
    assertEquals(pong, underlying.ping())
  }

  test("TracerWeaveCapturingInputs returns exactly what the underlying call returns") {
    val counts = new FooCallCounts
    val underlying = underlyingFoo(counts)
    val traced: Foo[Id] = underlying.weave.mapK(TracerWeaveCapturingInputs[Id, ToAnyValue])

    val greeting = traced.greet("world", 2)
    val pong = traced.ping()
    assertEquals((counts.greet, counts.ping), (1, 1))

    assertEquals(greeting, underlying.greet("world", 2))
    assertEquals(pong, underlying.ping())
  }

  // Tracer.noop's SpanBuilder#modifyState is `this` — it never applies the
  // function — so with tracing disabled the parameters are never encoded at
  // all. That only holds if the interpreter builds `asAttributes` *inside* the
  // modifyState lambda. Hoisting it to a val outside would break this test,
  // which is the point of having it.
  test("with a noop Tracer the parameter encoding is never performed") {
    var forced = 0
    val weave = Aspect.Weave[Id, ToAnyValue, ToAnyValue, String](
      "Foo",
      List(List(Aspect.Advice.byName[ToAnyValue, String]("lazyParam", { forced += 1; "x" }))),
      Aspect.Advice[Id, ToAnyValue, String]("greet", "hi")
    )

    assertEquals(TracerWeaveCapturingInputs[Id, ToAnyValue].apply(weave), "hi")
    assertEquals(forced, 0)
  }
}
