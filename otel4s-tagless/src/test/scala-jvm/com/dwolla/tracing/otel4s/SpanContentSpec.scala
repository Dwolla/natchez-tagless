package com.dwolla.tracing.otel4s

import cats.effect.IO
import cats.tagless.syntax.all._
import io.opentelemetry.sdk.trace.data.SpanData
import munit.CatsEffectSuite
import org.typelevel.otel4s.oteljava.AttributeConverters._
import org.typelevel.otel4s.oteljava.testkit.trace.TracesTestkit
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.{AnyValue, Attribute, Attributes}

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
  /** Runs `f` with a recording `Tracer[IO]` and returns both what it produced
    * and the spans it finished.
    *
    * The result is returned, not discarded, because an interpreter that records
    * the right attributes while handing the caller a ''different'' value than
    * the underlying algebra produced would otherwise satisfy every assertion
    * this suite can make.
    */
  protected def resultAndSpansFrom[A](f: Tracer[IO] => IO[A]): IO[(A, List[SpanData])] =
    TracesTestkit.inMemory[IO]().use { testkit =>
      testkit.tracerProvider
        .get("otel4s-tagless-test")
        .flatMap(f)
        .flatMap(a => testkit.finishedSpans.map((a, _)))
    }

  /** Runs `f` with a recording `Tracer[IO]` and returns the spans it finished.
    *
    * `IO[Any]`, not `IO[Unit]`, so a call site need not end in `.void` just to
    * fit the signature.
    */
  protected def spansFrom(f: Tracer[IO] => IO[Any]): IO[List[SpanData]] =
    resultAndSpansFrom(f).map(_._2)

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

  /** A fresh counting algebra per test, so the counts start at zero.
    *
    * `IO(f())` rather than `IO.pure`: the count has to record how many times
    * the effect ''ran'', not how many times it was built.
    */
  protected def underlyingFoo(counts: FooCallCounts): Foo[IO] =
    Foo.counting[IO](counts)(f => IO(f()))

  test("each method call opens one span named algebraName.methodName") {
    val counts = new FooCallCounts

    resultAndSpansFrom { implicit tracer =>
      underlyingFoo(counts).instrument.mapK(TracerInstrumentation[IO]).greet("world", 2)
    }.map { case (greeting, spans) =>
      assertEquals(greeting, "hello worldhello world")
      assertEquals(counts.greet, 1)
      assertEquals(spans.map(_.getName), List("Foo.greet"))
    }
  }

  test("TracerInstrumentation records no attributes of its own") {
    val counts = new FooCallCounts

    spansFrom { implicit tracer =>
      underlyingFoo(counts).instrument.mapK(TracerInstrumentation[IO]).greet("world", 2)
    }.map(spans => assertEquals(spans.map(attributesOf(_).size), List(0)))
  }

  // ping() is the empty-parameter-list, Unit-returning edge. Under
  // TracerInstrumentation it is unremarkable — the interpreter records no
  // attributes for any method — but it is the baseline the
  // TracerWeaveCapturingInputs case below has to match.
  test("a zero-parameter, Unit-returning method is spanned like any other") {
    val counts = new FooCallCounts

    resultAndSpansFrom { implicit tracer =>
      underlyingFoo(counts).instrument.mapK(TracerInstrumentation[IO]).ping()
    }.map { case (pong, spans) =>
      assertEquals(pong, ())
      assertEquals(counts.ping, 1)
      assertEquals(spans.map(_.getName), List("Foo.ping"))
      assertEquals(spans.map(attributesOf(_).size), List(0))
    }
  }

  test("TracerWeaveCapturingInputs records every parameter as one structured attribute") {
    val counts = new FooCallCounts

    resultAndSpansFrom { implicit tracer =>
      underlyingFoo(counts).weave.mapK(TracerWeaveCapturingInputs[IO, ToAnyValue]).greet("world", 2)
    }.map { case (greeting, spans) =>
      assertEquals(greeting, "hello worldhello world")
      assertEquals(counts.greet, 1)
      assertEquals(spans.map(_.getName), List("Foo.greet"))
      // The decoded tree, not the rendered text: `times` is a LongValue, and
      // an AnyValue map does not preserve key order.
      assertEquals(
        spans.map(attributesOf(_)),
        List(Attributes(Attribute[AnyValue](
          "Foo.greet.parameters",
          AnyValue.map(Map(
            "name" -> AnyValue.string("world"),
            "times" -> AnyValue.long(2L),
          )),
        )))
      )
    }
  }

  // D3, corrected 2026-08-02: the parameters attribute is omitted outright when
  // the encoded map would have zero entries, rather than recorded holding
  // `MapValue({})`. ping() takes no parameters, so this is where that rule is
  // exercised against a real SDK — the span carries the same zero attributes
  // TracerInstrumentation produces above.
  test("TracerWeaveCapturingInputs records no parameters attribute for a method with no parameters") {
    val counts = new FooCallCounts

    resultAndSpansFrom { implicit tracer =>
      underlyingFoo(counts).weave.mapK(TracerWeaveCapturingInputs[IO, ToAnyValue]).ping()
    }.map { case (pong, spans) =>
      assertEquals(pong, ())
      assertEquals(counts.ping, 1)
      assertEquals(spans.map(_.getName), List("Foo.ping"))
      assertEquals(spans.map(attributesOf(_).size), List(0))
      assertEquals(spans.map(attributesOf(_)), List(Attributes.empty))
    }
  }
}
