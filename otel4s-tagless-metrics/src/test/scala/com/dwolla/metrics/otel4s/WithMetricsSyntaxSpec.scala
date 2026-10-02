package com.dwolla.metrics.otel4s

import cats.effect.IO
import com.dwolla.metrics.otel4s.syntax._
import com.dwolla.tracing.otel4s.syntax._
import munit.CatsEffectSuite
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.Tracer

/** Pins, on every platform and Scala version, that each `withMetrics` overload
  * resolves — they differ only by arity, with no default arguments — and that
  * the documented stacking with otel4s-tagless compiles with both syntax
  * imports in scope. What they record is covered, via the interpreters they
  * build, by `MeasurementContentSuite`.
  */
class WithMetricsSyntaxSpec extends CatsEffectSuite {
  private implicit val meterProvider: MeterProvider[IO] = MeterProvider.noop[IO]
  private implicit val tracer: Tracer[IO] = Tracer.noop[IO]

  private val foo: Foo[IO] = Foo[IO](name => IO.pure(s"hello $name"), IO.unit)

  test("withMetrics() yields the wrapped algebra") {
    foo.withMetrics().flatMap(_.greet("world")).assertEquals("hello world")
  }

  test("withMetrics(role, system, service) yields the wrapped algebra") {
    foo.withMetrics(RpcRole.Server, RpcSystem("thrift"), RpcService("com.example.FooService"))
      .flatMap(_.greet("world"))
      .assertEquals("hello world")
  }

  test("withMetrics(...).map(_.instrumentAndTrace) stacks metrics inside the span") {
    foo.withMetrics().map(_.instrumentAndTrace).flatMap(_.greet("world")).assertEquals("hello world")
  }
}
