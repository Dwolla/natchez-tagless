package com.dwolla.tracing.otel4s

import cats.effect.IO
import cats.syntax.all._
import cats.tagless.syntax.all._
import io.opentelemetry.sdk.trace.data.SpanData
import munit.CatsEffectSuite
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.oteljava.AttributeConverters._
import org.typelevel.otel4s.oteljava.testkit.trace.TracesTestkit
import org.typelevel.otel4s.trace.Tracer

/** Span ''content'', asserted against a real SDK.
  *
  * JVM-only: every otel4s span type is sealed with a `private[otel4s]`
  * `Unsealed` variant, so a recording `Tracer` cannot be hand-rolled, and the
  * cross-platform testkit (`otel4s-sdk-trace-testkit`) has not been released at
  * 1.0.x. Transparency is covered on every platform by `TracerTransparencySpec`.
  *
  * `TracesTestkit.inMemory[IO]()` needs no extra wiring: its
  * `LocalContextProvider[IO]` — i.e. `LocalProvider[IO, oteljava.Context]` —
  * comes from `LocalProvider.liftFromLiftIO`, which needs only
  * `MonadCancelThrow[IO]`, `LiftIO[IO]` and the `Contextual[Context]` instance
  * in oteljava's `Context` companion.
  */
class SpanContentSpec extends CatsEffectSuite {
  /** Runs `f` with a recording `Tracer[IO]` and returns the spans it finished. */
  protected def spansFrom(f: Tracer[IO] => IO[Unit]): IO[List[SpanData]] =
    TracesTestkit.inMemory[IO]().use { testkit =>
      testkit.tracerProvider.get("otel4s-tagless-test").flatMap(f) >> testkit.finishedSpans
    }

  /** Decodes a span's attributes back into the otel4s model.
    *
    * `AttributeConverters` is public and round-trips `AttributeType.VALUE` into
    * `AnyValue`, so assertions can compare structured values with `AnyValue`'s
    * own equality instead of walking the Java `Value` tree. Never assert on
    * `Value.asString` or any `toString`: an `AnyValue` map is backed by a Scala
    * `Map` and its key order is not preserved.
    */
  protected def attributesOf(span: SpanData): Attributes =
    span.getAttributes.toScala

  protected val underlying: Foo[IO] = new Foo[IO] {
    override def greet(name: String, times: Int): IO[String] = IO.pure(s"hello $name" * times)
    override def ping(): IO[Unit] = IO.unit
  }

  test("each method call opens one span named algebraName.methodName") {
    spansFrom { implicit tracer =>
      underlying.instrument.mapK(TracerInstrumentation[IO]).greet("world", 2).void
    }.map(spans => assertEquals(spans.map(_.getName), List("Foo.greet")))
  }

  test("TracerInstrumentation records no attributes of its own") {
    spansFrom { implicit tracer =>
      underlying.instrument.mapK(TracerInstrumentation[IO]).greet("world", 2).void
    }.map(spans => assertEquals(spans.map(attributesOf(_).size), List(0)))
  }
}
