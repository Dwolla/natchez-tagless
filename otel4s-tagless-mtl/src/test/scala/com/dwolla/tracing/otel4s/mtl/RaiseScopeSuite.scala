package com.dwolla.tracing.otel4s.mtl

import cats.FlatMap
import cats.effect.IO
import cats.mtl.Handle
import cats.syntax.all._
import com.dwolla.tagless.mtl.OnRaise
import com.dwolla.tracing.otel4s.{BuildInfo, ToAnyValue}
import com.dwolla.tracing.otel4s.mtl.syntax._
import munit.CatsEffectSuite
import org.typelevel.otel4s.{AnyValue, Attribute, Attributes}
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}

/** Which span a raise is recorded on, and by which hook, asserted against a
  * real SDK whose spans come from more than one instrumentation scope.
  *
  * Every property lives here; a subclass supplies only `spansFrom`, which runs
  * a function against a real `TracerProvider[IO]` and reports the spans it
  * finished. It runs against otel4s-sdk (`OtelSdkRaiseScopeSpec`, every
  * platform) and oteljava (`OtelJavaRaiseScopeSpec`, JVM).
  */
abstract class RaiseScopeSuite extends CatsEffectSuite {

  /** Runs `f` with a `TracerProvider[IO]` backed by a real SDK and returns what
    * `f` produced plus every span it finished.
    */
  protected def spansFrom[A](f: TracerProvider[IO] => IO[A]): IO[(A, List[RecordedSpan])]

  private val AppScope: String = "com.example.FooService"
  private val CustomHookKey: String = "com.example.raise.hook"
  private val DefaultHookKeys: Set[String] = Set("com.dwolla.raise.error.type", "com.dwolla.raise.error.value")

  private def onlySpanNamed(name: String, spans: List[RecordedSpan]): RecordedSpan =
    spans.filter(_.name == name) match {
      case List(span) => span
      case other => fail(s"expected exactly one span named $name, found ${other.size} among ${spans.map(_.name)}")
    }

  private def keysOf(span: RecordedSpan): Set[String] =
    span.attributes.map(_.key.name).toSet

  /** A string-valued attribute, whichever way the backend stores it: the Java
    * SDK narrows a string `AnyValue` to a plain string attribute, while
    * otel4s-sdk keeps the `AnyValue`.
    */
  private def stringAttribute(span: RecordedSpan, key: String): Option[String] =
    span.attributes.find(_.key.name == key).map(_.value).collect {
      case s: String => s
      case s: AnyValue.StringValue => s.value
    }

  /** Runs `fa` inside a span named `outer`, opened by the application's own
    * tracer: a different instrumentation scope from this library's, on the
    * same provider.
    */
  private def insideOuterSpan[A](fa: IO[A])(implicit tracerProvider: TracerProvider[IO]): IO[A] =
    tracerProvider.get(AppScope).flatMap(_.span("outer").surround(fa))

  /** Traces `Foo` and calls it with a raising input inside the outer span,
    * rescuing the raise — the boundary pattern the module's scaladoc documents.
    * The `RaiseRecorder` is resolved here, so the hooks in lexical scope at the
    * caller decide which one records.
    */
  private def raiseInsideOuterSpan(traced: IO[Foo[IO]])(implicit tracerProvider: TracerProvider[IO]): IO[String] =
    insideOuterSpan {
      traced.flatMap { foo =>
        Handle.allowF[IO, FooError](implicit h => foo.foo(-1))
          .rescue { case FooError.Negative(n) => s"rescued:$n".pure[IO] }
      }
    }

  // The default hook reaches its span through `currentSpanOrNoop` on this
  // library's tracer, which is only the method's own span if `SpanOps#use` has
  // made it current for the body. If it has not, `currentSpanOrNoop` returns
  // whatever *was* current — here the outer span, opened by the application's
  // own tracer — and the attributes land one level up.
  //
  // Asserted in both directions on purpose: that the child carries them, and
  // that the parent carries none. Either half alone passes under the failure
  // mode the other catches.
  test("the default hook records on the method's own span, not on the application's enclosing span") {
    spansFrom { implicit tracerProvider =>
      raiseInsideOuterSpan(Foo[IO].traceWithInputsAndOutputs)
    }.map { case (result, spans) =>
      assertEquals(result, "rescued:-1")
      val child = onlySpanNamed("Foo.foo", spans)
      val parent = onlySpanNamed("outer", spans)

      // The two really are parent and child, across instrumentation scopes —
      // otherwise "the parent has no raise attributes" would be trivially true.
      assertEquals(child.parentSpanId, Some(parent.spanId))
      assertEquals((parent.scopeName, parent.scopeVersion), (AppScope, None))
      assertEquals((child.scopeName, child.scopeVersion), ("com.dwolla.tracing.otel4s", Some(BuildInfo.version)))

      assertEquals(stringAttribute(child, "com.dwolla.raise.error.type"), Some(classOf[FooError.Negative].getName))
      assertEquals(stringAttribute(child, "com.dwolla.raise.error.value"), Some("negative:-1"))
      assertEquals(parent.attributes, Attributes.empty)
    }
  }

  test("a custom hook asking for TracerProvider wins at a call site that has only the provider") {
    spansFrom { implicit tracerProvider =>
      implicit def providerHook[F[_]: FlatMap: TracerProvider]: OnRaise[F, ToAnyValue] =
        new OnRaise[F, ToAnyValue] {
          def apply[E](e: E)(implicit ev: ToAnyValue[E]): F[Unit] =
            TracerProvider[F].get(AppScope).flatMap(_.currentSpanOrNoop).flatMap {
              _.backend.addAttributes(Attributes(Attribute(CustomHookKey, "recorded")))
            }
        }

      raiseInsideOuterSpan(Foo[IO].traceWithInputsAndOutputs)
    }.map { case (result, spans) =>
      assertEquals(result, "rescued:-1")
      val child = onlySpanNamed("Foo.foo", spans)
      assertEquals(stringAttribute(child, CustomHookKey), Some("recorded"))
      assertEquals(keysOf(child).intersect(DefaultHookKeys), Set.empty[String])
      assertEquals(onlySpanNamed("outer", spans).attributes, Attributes.empty)
    }
  }

  // Pins the documented trap rather than endorsing it: a hook needing a
  // Tracer[F] is not applicable where only the TracerProvider[F] is implicit,
  // so implicit search skips it without error and the default records. If
  // this ever starts failing, the "counts as absent" docs need revisiting.
  test("a custom hook asking for Tracer is skipped at a call site that has only the provider, and the default records") {
    spansFrom { implicit tracerProvider =>
      implicit def tracerHook[F[_]: FlatMap: Tracer]: OnRaise[F, ToAnyValue] =
        new OnRaise[F, ToAnyValue] {
          def apply[E](e: E)(implicit ev: ToAnyValue[E]): F[Unit] =
            Tracer[F].currentSpanOrNoop.flatMap {
              _.backend.addAttributes(Attributes(Attribute(CustomHookKey, "recorded")))
            }
        }

      raiseInsideOuterSpan(Foo[IO].traceWithInputsAndOutputs)
    }.map { case (_, spans) =>
      val child = onlySpanNamed("Foo.foo", spans)
      assert(!keysOf(child).contains(CustomHookKey), keysOf(child))
      assertEquals(keysOf(child).intersect(DefaultHookKeys), DefaultHookKeys)
    }
  }
}
