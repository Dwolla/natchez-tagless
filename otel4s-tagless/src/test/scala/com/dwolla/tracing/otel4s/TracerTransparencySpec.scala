package com.dwolla.tracing.otel4s

import cats.Id
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
  * released at 1.0.x. What is left here is transparency, and it is not nothing:
  * `Tracer.noop`'s `build.use(f)` is `f(span)`, so `surround(fa)` reduces to
  * `fa` and any interference by the interpreter shows up immediately.
  */
class TracerTransparencySpec extends FunSuite {
  private implicit val tracer: Tracer[Id] = Tracer.noop[Id]

  private val underlying: Foo[Id] = new Foo[Id] {
    override def greet(name: String, times: Int): Id[String] = s"hello $name" * times
    override def ping(): Id[Unit] = ()
  }

  test("an instrumented call returns exactly what the underlying call returns") {
    val instrumented: Foo[Id] = underlying.instrument.mapK(TracerInstrumentation[Id])

    assertEquals(instrumented.greet("world", 2), underlying.greet("world", 2))
    assertEquals(instrumented.ping(), underlying.ping())
  }
}
