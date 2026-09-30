package com.dwolla.tracing.otel4s

import cats.effect.IO
import cats.tagless.aop.Aspect
import com.dwolla.tracing.otel4s.syntax.*
import io.opentelemetry.sdk.trace.data.SpanData
import munit.CatsEffectSuite
import org.typelevel.otel4s.oteljava.AttributeConverters.*
import org.typelevel.otel4s.oteljava.testkit.trace.TracesTestkit
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.{AnyValue, Attribute, Attributes}

import scala.annotation.experimental

/** An algebra that says `derives AnyValueAspect` and nothing else traces
  * exactly as one with a hand-declared `Aspect` does — the otel4s counterpart
  * of `com.dwolla.tracing.TraceableAspectTracingSpec`, asserted against a real
  * SDK the way `SpanContentSpec` is: span *content* needs the testkit, because
  * every otel4s span type is sealed and a recording `Tracer` cannot be
  * hand-rolled.
  *
  * Both axes at once, which is why it lives in `src/test/scala-3-jvm`:
  * `derives` is Scala 3 only and span content needs the JVM-only oteljava
  * testkit.
  */
@experimental
class AnyValueAspectTracingSpec extends CatsEffectSuite {
  private def resultAndSpansFrom[A](f: Tracer[IO] => IO[A]): IO[(A, List[SpanData])] =
    TracesTestkit.inMemory[IO]().use { testkit =>
      testkit.tracerProvider
        .get("otel4s-tagless-test")
        .flatMap(f)
        .flatMap(a => testkit.finishedSpans.map((a, _)))
    }

  private def attributesOf(span: SpanData): Attributes = span.getAttributes.toScala

  private def expectedAttributes(alg: String): Attributes =
    Attributes(
      Attribute[AnyValue](
        s"$alg.get.parameters",
        AnyValue.map(Map("key" -> AnyValue.string("k"))),
      ),
      Attribute(s"$alg.get.returnValue", "v:k"),
    )

  test("an algebra deriving AnyValueAspect captures span, input, and output") {
    resultAndSpansFrom { implicit tracer =>
      DerivesLookup[IO].traceWithInputsAndOutputs.get("k")
    }.map { case (result, spans) =>
      assertEquals(result, "v:k")
      assertEquals(spans.map(_.getName), List("DerivesLookup.get"))
      assertEquals(spans.map(attributesOf), List(expectedAttributes("DerivesLookup")))
    }
  }

  test("...identically to the same algebra with a hand-written Aspect") {
    implicit val a: Aspect[Lookup, ToAnyValue, ToAnyValue] = HandWrittenLookupAspect.instance

    resultAndSpansFrom { implicit tracer =>
      Lookup[IO].traceWithInputsAndOutputs.get("k")
    }.map { case (result, spans) =>
      assertEquals(result, "v:k")
      assertEquals(spans.map(_.getName), List("Lookup.get"))
      assertEquals(spans.map(attributesOf), List(expectedAttributes("Lookup")))
    }
  }
}
