package com.dwolla.tracing.otel4s.mtl

import cats.effect.IO
import cats.mtl.Handle
import cats.syntax.all.*
import com.dwolla.tagless.mtl.RaiseRecorder
import com.dwolla.tracing.otel4s.mtl.syntax.*
import io.opentelemetry.sdk.trace.data.SpanData
import munit.CatsEffectSuite
import org.typelevel.otel4s.oteljava.AttributeConverters.*
import org.typelevel.otel4s.oteljava.testkit.trace.TracesTestkit
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.{AnyValue, Attribute, Attributes}

import scala.annotation.experimental

/** Drives a `derives AnyValueRaiseAspect` algebra through the real `Tracer`
  * and `traceWithInputsAndOutputs`, and asserts the span it produces.
  *
  * `DerivesFooRaiseSpec` compares the derived instance against a hand-written
  * one through `intercept` directly — a differential oracle that never
  * reaches a `Tracer`. This is the other half: it proves the derived instance
  * is usable at the actual tracing call site and that the derivation's
  * algebra and method names are what reach the span.
  *
  * Both axes at once, which is why it lives in `src/test/scala-3-jvm`:
  * `derives` is Scala 3 only and span content needs the JVM-only oteljava
  * testkit (see `RaiseSpanContentSpec` for why).
  */
@experimental
class DerivesFooSpanContentSpec extends CatsEffectSuite {

  private def resultAndSpansFrom[A](f: Tracer[IO] => IO[A]): IO[(A, List[SpanData])] =
    TracesTestkit.inMemory[IO]().use { testkit =>
      testkit.tracerProvider
        .get("otel4s-tagless-mtl-test")
        .flatMap(f)
        .flatMap(a => testkit.finishedSpans.map((a, _)))
    }

  test("a derived instance traces its raise onto its own span") {
    resultAndSpansFrom { implicit tracer =>
      Handle.allowF[IO, FooError] { implicit h =>
        DerivesFoo[IO].traceWithInputsAndOutputs.foo(-1)
      }.rescue { case FooError.Negative(n) => s"rescued:$n".pure[IO] }
    }.map { (result, spans) =>
      assertEquals(result, "rescued:-1")
      // The span name comes from the derivation, not from a hand-written
      // Weave: it is the algebra's own name, not `Foo`'s.
      assertEquals(spans.map(_.getName), List("DerivesFoo.foo"))
      assertEquals(
        spans.head.getAttributes.toScala,
        Attributes(
          Attribute("code.function.name", "DerivesFoo.foo"),
          Attribute[AnyValue](
            "com.dwolla.code.function.arguments",
            AnyValue.map(Map("i" -> AnyValue.long(-1L))),
          ),
          Attribute(RaiseRecorder.ErrorTypeKey, classOf[FooError.Negative].getName),
          Attribute(RaiseRecorder.ErrorValueKey, "negative:-1"),
        )
      )
    }
  }

  test("a derived instance records the return value and no raise attributes on success") {
    resultAndSpansFrom { implicit tracer =>
      Handle.allowF[IO, FooError] { implicit h =>
        DerivesFoo[IO].traceWithInputsAndOutputs.foo(5)
      }.rescue(e => IO.raiseError(new AssertionError(s"unexpected raise: $e")))
    }.map { (result, spans) =>
      assertEquals(result, "foo:5")
      assertEquals(spans.map(_.getName), List("DerivesFoo.foo"))
      assertEquals(
        spans.head.getAttributes.toScala,
        Attributes(
          Attribute("code.function.name", "DerivesFoo.foo"),
          Attribute[AnyValue](
            "com.dwolla.code.function.arguments",
            AnyValue.map(Map("i" -> AnyValue.long(5L))),
          ),
          Attribute("com.dwolla.code.function.return_value", "foo:5"),
        )
      )
    }
  }
}
