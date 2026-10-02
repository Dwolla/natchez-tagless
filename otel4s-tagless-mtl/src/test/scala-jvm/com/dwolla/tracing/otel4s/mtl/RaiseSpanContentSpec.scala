package com.dwolla.tracing.otel4s.mtl

import cats.effect.IO
import cats.mtl.{Handle, Raise}
import cats.syntax.all._
import cats.tagless.aop.Aspect
import cats.{Applicative, Apply, ~>}
import com.dwolla.tagless.mtl.{OnRaise, RaiseAspect}
import com.dwolla.tracing.otel4s.ToAnyValue
import com.dwolla.tracing.otel4s.mtl.syntax._
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.trace.data.SpanData
import munit.CatsEffectSuite
import org.typelevel.otel4s.oteljava.AttributeConverters._
import org.typelevel.otel4s.oteljava.testkit.trace.TracesTestkit
import org.typelevel.otel4s.trace.TracerProvider
import org.typelevel.otel4s.{AnyValue, Attribute, Attributes}

import scala.jdk.CollectionConverters._

/** Span ''content'' for the raise path, asserted against a real SDK.
  *
  * JVM-only for the reason `otel4s-tagless`'s `SpanContentSpec` documents:
  * every otel4s span type is sealed with a `private[otel4s]` `Unsealed`
  * variant, so a recording `Tracer` cannot be hand-rolled, and span content
  * beyond the instrumentation scope is asserted on the JVM oteljava testkit.
  * Transparency is covered on every platform by `RaiseTracerTransparencySpec`,
  * and resolution by `RaiseRecorderPrioritySpec`.
  */
class RaiseSpanContentSpec extends CatsEffectSuite {

  /** The harness from `otel4s-tagless`'s `SpanContentSpec`, repeated rather
    * than shared: that suite lives in another module's `Test` configuration,
    * and `otel4sTaglessMtl` depends on `otel4sTagless`'s `Compile` only.
    */
  private def resultAndSpansFrom[A](f: TracerProvider[IO] => IO[A]): IO[(A, List[SpanData])] =
    TracesTestkit.inMemory[IO]().use { testkit =>
      f(testkit.tracerProvider).flatMap(a => testkit.finishedSpans.map((a, _)))
    }

  /** Runs `fa` inside a span named `outer`, opened by the application's own
    * tracer: a different instrumentation scope from this library's, on the
    * same provider.
    */
  private def insideOuterSpan[A](fa: IO[A])(implicit tracerProvider: TracerProvider[IO]): IO[A] =
    tracerProvider.get("com.example.FooService").flatMap(_.span("outer").surround(fa))

  private def attributesOf(span: SpanData): Attributes =
    span.getAttributes.toScala

  private def spanNamed(spans: List[SpanData], name: String): SpanData =
    spans.find(_.getName == name)
      .getOrElse(fail(s"no span named $name among ${spans.map(_.getName)}"))

  /** Runs `alg.foo(i)` under an ad-hoc `Handle[IO, FooError]`, rescuing any
    * raise — the boundary pattern the module's scaladoc documents.
    */
  private def viaHandle(alg: Foo[IO], i: Int): IO[String] =
    Handle.allowF[IO, FooError] { implicit h => alg.foo(i) }
      .rescue { case FooError.Negative(n) => s"rescued:$n".pure[IO] }

  private val expectedErrorType: String = classOf[FooError.Negative].getName

  test("a raise records com.dwolla.raise.error.type and com.dwolla.raise.error.value on the method's span") {
    resultAndSpansFrom { implicit tracerProvider =>
      Foo[IO].traceWithInputsAndOutputs.flatMap(viaHandle(_, -1))
    }.map { case (result, spans) =>
      assertEquals(result, "rescued:-1")
      assertEquals(spans.map(_.getName), List("Foo.foo"))

      // com.dwolla.raise.error.value arrives as a plain STRING attribute, not an AnyValue
      // one: the Java SDK narrows an AttributeType.VALUE whose Value has a
      // simple equivalent, exactly as it does for
      // com.dwolla.code.function.return_value in otel4s-tagless's SpanContentSpec.
      assertEquals(
        attributesOf(spans.head),
        Attributes(
          Attribute("code.function.name", "Foo.foo"),
          Attribute[AnyValue](
            "com.dwolla.code.function.arguments",
            AnyValue.map(Map("i" -> AnyValue.long(-1L))),
          ),
          Attribute("com.dwolla.raise.error.type", expectedErrorType),
          Attribute("com.dwolla.raise.error.value", "negative:-1"),
          Attribute("error.type", expectedErrorType),
        )
      )
    }
  }

  // The hook reaches its span through `currentSpanOrNoop` on this library's
  // tracer, which is only the method's own span if `SpanOps#use` has made it
  // current for the body. If it has not, `currentSpanOrNoop` returns whatever
  // *was* current — here the outer span, opened by the application's own
  // tracer — and the attributes land one level up.
  //
  // Asserted in both directions on purpose: that the child carries them, and
  // that the parent carries none. Either half alone passes under the failure
  // mode the other catches.
  test("the raise attributes land on the method's own span, not on the parent") {
    resultAndSpansFrom { implicit tracerProvider =>
      insideOuterSpan {
        Foo[IO].traceWithInputsAndOutputs.flatMap(viaHandle(_, -1))
      }
    }.map { case (result, spans) =>
      assertEquals(result, "rescued:-1")
      assertEquals(spans.map(_.getName).sorted, List("Foo.foo", "outer"))

      val child = spanNamed(spans, "Foo.foo")
      val parent = spanNamed(spans, "outer")

      // The two really are parent and child, not two roots — otherwise
      // "the parent has no raise attributes" would be trivially true.
      assertEquals(child.getParentSpanId, parent.getSpanId)

      // ...across instrumentation scopes: the parent is the application's,
      // the child this library's own, versioned.
      assertEquals(parent.getInstrumentationScopeInfo.getName, "com.example.FooService")
      assertEquals(child.getInstrumentationScopeInfo.getName, "com.dwolla.tracing.otel4s")
      assertEquals(Option(child.getInstrumentationScopeInfo.getVersion), Some(com.dwolla.tracing.otel4s.BuildInfo.version))

      val childAttributes = attributesOf(child)
      assertEquals(
        childAttributes.get[String]("com.dwolla.raise.error.type").map(_.value),
        Some(expectedErrorType)
      )
      assertEquals(
        childAttributes.get[String]("com.dwolla.raise.error.value").map(_.value),
        Some("negative:-1")
      )

      val parentAttributes = attributesOf(parent)
      assertEquals(parentAttributes.get[String]("com.dwolla.raise.error.type"), None)
      assertEquals(parentAttributes.get[String]("com.dwolla.raise.error.value"), None)
      assertEquals(parentAttributes, Attributes.empty)
    }
  }

  test("an error rendering to AnyValue.empty records the type and no value") {
    resultAndSpansFrom { implicit tracerProvider =>
      Quiet[IO].traceWithInputsAndOutputs.flatMap { quiet =>
        Handle.allowF[IO, QuietError] { implicit h =>
          quiet.hush(-1)
        }.rescue { case QuietError.Silent => "rescued".pure[IO] }
      }
    }.map { case (result, spans) =>
      assertEquals(result, "rescued")
      assertEquals(spans.map(_.getName), List("Quiet.hush"))

      assertEquals(
        attributesOf(spans.head),
        Attributes(
          Attribute("code.function.name", "Quiet.hush"),
          Attribute[AnyValue](
            "com.dwolla.code.function.arguments",
            AnyValue.map(Map("i" -> AnyValue.long(-1L))),
          ),
          Attribute("com.dwolla.raise.error.type", QuietError.Silent.getClass.getName),
          Attribute("error.type", QuietError.Silent.getClass.getName),
        )
      )
      assertEquals(attributesOf(spans.head).get[String]("com.dwolla.raise.error.value"), None)
    }
  }

  // cats-mtl encodes `Handle.allowF`'s raise over `IO` as its private
  // `Submarine` exception, so `R.raise` really is `IO.raiseError(Submarine(e))`
  // and the traced span exits with `Resource.ExitCase.Errored`. Left to
  // otel4s's default finalizer that would be reported as an opaque `Submarine`
  // exception event; the interpreters' finalization strategy unwraps it and
  // reports the domain error instead: status ERROR, `error.type`, no event.
  // The raise-time `com.dwolla.raise.error.*` attributes are independent of
  // that and still record every raise, including rescued ones.
  test("a raise crossing the traced wrapper is reported as the domain error, not cats-mtl's Submarine") {
    resultAndSpansFrom { implicit tracerProvider =>
      insideOuterSpan {
        Foo[IO].traceWithInputsAndOutputs.flatMap(viaHandle(_, -1))
      }
    }.map { case (_, spans) =>
      val child = spanNamed(spans, "Foo.foo")

      assertEquals(child.getStatus.getStatusCode, StatusCode.ERROR)
      assertEquals(child.getEvents.asScala.toList.map(_.getName), List.empty[String])
      assertEquals(
        child.getAttributes.toScala.get[String]("error.type").map(_.value),
        Some(expectedErrorType)
      )
      assertEquals(
        child.getAttributes.toScala.get[String]("com.dwolla.raise.error.type").map(_.value),
        Some(expectedErrorType)
      )

      // The rescue happens inside the outer span, so nothing abnormal escapes
      // to it: the error marking is confined to the method's own span.
      val parent = spanNamed(spans, "outer")
      assertEquals(parent.getStatus.getStatusCode, StatusCode.UNSET)
      assertEquals(parent.getEvents.asScala.toList.map(_.getName), List.empty[String])
    }
  }

  test("a successful traced call is finalized with no status and no exception event") {
    resultAndSpansFrom { implicit tracerProvider =>
      Foo[IO].traceWithInputsAndOutputs.flatMap(viaHandle(_, 5))
    }.map { case (result, spans) =>
      assertEquals(result, "foo:5")
      val span = spanNamed(spans, "Foo.foo")
      assertEquals(span.getStatus.getStatusCode, StatusCode.UNSET)
      assertEquals(span.getEvents.asScala.toList.map(_.getName), List.empty[String])
      // and no com.dwolla.raise.error.* either — the hook only fires on a raise
      assertEquals(attributesOf(span).get[String]("com.dwolla.raise.error.type"), None)
    }
  }
}

/** An error whose `ToAnyValue` rendering is `AnyValue.empty`, so our omission
  * rule can be exercised through the full tracing path rather than against the
  * hook in isolation.
  *
  * Declared here rather than added as a case to `FooRaiseFixture`'s `FooError`
  * so the cross-platform suites built on that fixture are untouched.
  */
sealed trait QuietError extends Product with Serializable

object QuietError {
  case object Silent extends QuietError

  implicit val quietErrorToAnyValue: ToAnyValue[QuietError] =
    ToAnyValue.instance(_ => AnyValue.empty)
}

trait Quiet[F[_]] {
  def hush(i: Int)(implicit R: Raise[F, QuietError]): F[String]
}

object Quiet {
  def apply[F[_] : Applicative]: Quiet[F] = new Quiet[F] {
    def hush(i: Int)(implicit R: Raise[F, QuietError]): F[String] =
      if (i < 0) R.raise(QuietError.Silent) else s"hush:$i".pure[F]
  }

  /** Hand-written for the reason `Foo`'s is: `DeriveRaise.aspect`'s Scala 3
    * entry point is `@experimental`, and this file compiles on 2.13 too.
    */
  implicit val quietRaiseAspect: RaiseAspect[Quiet, ToAnyValue, ToAnyValue, ToAnyValue] =
    new RaiseAspect[Quiet, ToAnyValue, ToAnyValue, ToAnyValue] {
      def intercept[F[_]](af: Quiet[F])(
          fk: Aspect.Weave[F, ToAnyValue, ToAnyValue, *] ~> F,
          onRaise: OnRaise[F, ToAnyValue]
      )(implicit F: Apply[F]): Quiet[F] =
        new Quiet[F] {
          def hush(i: Int)(implicit R: Raise[F, QuietError]): F[String] =
            fk(
              Aspect.Weave[F, ToAnyValue, ToAnyValue, String](
                "Quiet",
                List(List(Aspect.Advice.byValue[ToAnyValue, Int]("i", i))),
                Aspect.Advice[F, ToAnyValue, String](
                  "hush",
                  af.hush(i)(RaiseAspect.observing(R, onRaise))
                )
              )
            )
        }
    }
}
