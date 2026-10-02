package com.dwolla.tracing.otel4s

import cats.effect.IO
import cats.syntax.all._
import com.dwolla.tracing.otel4s.syntax._
import munit.CatsEffectSuite
import org.typelevel.otel4s.trace.TracerProvider

/** Where the traced algebras' spans come from, asserted against a real SDK.
  *
  * Every property lives here; a subclass supplies only `spansFrom`, which runs
  * a function against a real `TracerProvider[IO]` and reports the spans it
  * finished. It runs against otel4s-sdk (`OtelSdkTracerScopeSpec`, every
  * platform) and oteljava (`OtelJavaTracerScopeSpec`, JVM).
  */
abstract class TracerScopeSuite extends CatsEffectSuite {

  /** Runs `f` with a `TracerProvider[IO]` backed by a real SDK and returns what
    * `f` produced plus every span it finished.
    */
  protected def spansFrom[A](f: TracerProvider[IO] => IO[A]): IO[(A, List[RecordedSpan])]

  private val ThisLibrary: (String, Option[String]) = ("com.dwolla.tracing.otel4s", Some(BuildInfo.version))

  private def onlySpanNamed(name: String, spans: List[RecordedSpan]): RecordedSpan =
    spans.filter(_.name == name) match {
      case List(span) => span
      case other => fail(s"expected exactly one span named $name, found ${other.size} among ${spans.map(_.name)}")
    }

  test("every syntax method records its spans under this library's own instrumentation scope, versioned") {
    spansFrom { implicit tracerProvider =>
      (
        Foo.plain[IO].instrumentAndTrace.flatMap(_.ping()),
        Foo.plain[IO].traceWithInputs[ToAnyValue].flatMap(_.ping()),
        Foo.plain[IO].traceWithInputsAndOutputs.flatMap(_.ping()),
      ).tupled
    }.map { case (_, spans) =>
      assertEquals(spans.size, 3)
      assertEquals(spans.map(s => (s.scopeName, s.scopeVersion)).distinct, List(ThisLibrary))
    }
  }

  test("a span from the application's own tracer on the same provider parents a traced call's span") {
    spansFrom { implicit tracerProvider =>
      for {
        appTracer <- tracerProvider.get("com.example.FooService")
        traced <- Foo.plain[IO].traceWithInputsAndOutputs
        greeting <- appTracer.span("handle-request").surround(traced.greet("world", 1))
      } yield greeting
    }.map { case (greeting, spans) =>
      assertEquals(greeting, "hello world")
      val request = onlySpanNamed("handle-request", spans)
      val greet = onlySpanNamed("Foo.greet", spans)

      assertEquals((request.scopeName, request.scopeVersion), ("com.example.FooService", None))
      assertEquals((greet.scopeName, greet.scopeVersion), ThisLibrary)
      assertEquals(request.parentSpanId, None)
      assertEquals(greet.parentSpanId, Some(request.spanId))
      assertEquals(greet.traceId, request.traceId)
    }
  }
}
