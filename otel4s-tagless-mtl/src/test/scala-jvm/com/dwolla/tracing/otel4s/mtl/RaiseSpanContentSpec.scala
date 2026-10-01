package com.dwolla.tracing.otel4s.mtl

import cats.effect.IO
import cats.mtl.{Handle, Raise}
import cats.syntax.all._
import cats.tagless.aop.Aspect
import cats.{Applicative, Apply, ~>}
import com.dwolla.tagless.mtl.{OnRaise, RaiseAspect, RaiseRecorder}
import com.dwolla.tracing.otel4s.ToAnyValue
import com.dwolla.tracing.otel4s.mtl.syntax._
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.trace.data.SpanData
import munit.CatsEffectSuite
import org.typelevel.otel4s.oteljava.AttributeConverters._
import org.typelevel.otel4s.oteljava.testkit.trace.TracesTestkit
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.{AnyValue, Attribute, Attributes}

import scala.jdk.CollectionConverters._

/** Span ''content'' for the raise path, asserted against a real SDK.
  *
  * JVM-only for the reason `otel4s-tagless`'s `SpanContentSpec` documents:
  * every otel4s span type is sealed with a `private[otel4s]` `Unsealed`
  * variant, so a recording `Tracer` cannot be hand-rolled, and the
  * cross-platform testkit (`otel4s-sdk-trace-testkit`) has not been released at
  * 1.0.x. Transparency is covered on every platform by
  * `RaiseTracerTransparencySpec`, and resolution by `RaiseRecorderPrioritySpec`.
  */
class RaiseSpanContentSpec extends CatsEffectSuite {

  /** The harness from `otel4s-tagless`'s `SpanContentSpec`, repeated rather
    * than shared: that suite lives in another module's `Test` configuration,
    * and `otel4sTaglessMtl` depends on `otel4sTagless`'s `Compile` only.
    */
  private def resultAndSpansFrom[A](f: Tracer[IO] => IO[A]): IO[(A, List[SpanData])] =
    TracesTestkit.inMemory[IO]().use { testkit =>
      testkit.tracerProvider
        .get("otel4s-tagless-mtl-test")
        .flatMap(f)
        .flatMap(a => testkit.finishedSpans.map((a, _)))
    }

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

  test("a raise records raise.error.type and raise.error.value on the method's span") {
    resultAndSpansFrom { implicit tracer =>
      viaHandle(Foo[IO].traceWithInputsAndOutputs, -1)
    }.map { case (result, spans) =>
      assertEquals(result, "rescued:-1")
      assertEquals(spans.map(_.getName), List("Foo.foo"))

      // raise.error.value arrives as a plain STRING attribute, not an AnyValue
      // one: the Java SDK narrows an AttributeType.VALUE whose Value has a
      // simple equivalent, exactly as it does for returnValue in
      // otel4s-tagless's SpanContentSpec.
      assertEquals(
        attributesOf(spans.head),
        Attributes(
          Attribute("code.function.name", "Foo.foo"),
          Attribute[AnyValue](
            "com.dwolla.code.function.arguments",
            AnyValue.map(Map("i" -> AnyValue.long(-1L))),
          ),
          Attribute(RaiseRecorder.ErrorTypeKey, expectedErrorType),
          Attribute(RaiseRecorder.ErrorValueKey, "negative:-1"),
        )
      )
    }
  }

  // The hook reaches its span through `Tracer[F].currentSpanOrNoop`, which is only
  // the method's own span if `SpanOps#use` has made it current for the body. If it
  // has not, `currentSpanOrNoop` returns whatever *was* current — here the outer
  // span — and the attributes land one level up.
  //
  // Asserted in both directions on purpose: that the child carries them, and
  // that the parent carries none. Either half alone passes under the failure
  // mode the other catches.
  test("the raise attributes land on the method's own span, not on the parent") {
    resultAndSpansFrom { implicit tracer =>
      tracer.span("outer").surround {
        viaHandle(Foo[IO].traceWithInputsAndOutputs, -1)
      }
    }.map { case (result, spans) =>
      assertEquals(result, "rescued:-1")
      assertEquals(spans.map(_.getName).sorted, List("Foo.foo", "outer"))

      val child = spanNamed(spans, "Foo.foo")
      val parent = spanNamed(spans, "outer")

      // The two really are parent and child, not two roots — otherwise
      // "the parent has no raise attributes" would be trivially true.
      assertEquals(child.getParentSpanId, parent.getSpanId)

      val childAttributes = attributesOf(child)
      assertEquals(
        childAttributes.get[String](RaiseRecorder.ErrorTypeKey).map(_.value),
        Some(expectedErrorType)
      )
      assertEquals(
        childAttributes.get[String](RaiseRecorder.ErrorValueKey).map(_.value),
        Some("negative:-1")
      )

      val parentAttributes = attributesOf(parent)
      assertEquals(parentAttributes.get[String](RaiseRecorder.ErrorTypeKey), None)
      assertEquals(parentAttributes.get[String](RaiseRecorder.ErrorValueKey), None)
      assertEquals(parentAttributes, Attributes.empty)
    }
  }

  test("an error rendering to AnyValue.empty records the type and no value") {
    resultAndSpansFrom { implicit tracer =>
      Handle.allowF[IO, QuietError] { implicit h =>
        Quiet[IO].traceWithInputsAndOutputs.hush(-1)
      }.rescue { case QuietError.Silent => "rescued".pure[IO] }
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
          Attribute(RaiseRecorder.ErrorTypeKey, QuietError.Silent.getClass.getName),
        )
      )
      assertEquals(attributesOf(spans.head).get[String](RaiseRecorder.ErrorValueKey), None)
    }
  }

  // What otel4s does with the raise *on its own*, independently of this
  // module's hook — pinned as a test rather than described in prose because
  // the module's scaladoc quotes these values, and the natchez module's
  // superficially similar claim is about entirely different code
  // (`natchez.mtl.LocalTrace#span`'s `attachError`).
  //
  // `Handle.allowF` over `IO` uses cats-mtl's submarine encoding, so `R.raise`
  // really is `IO.raiseError(Submarine(e))`. The traced span therefore exits
  // with `Resource.ExitCase.Errored` and otel4s's default
  // `SpanFinalizer.Strategy.reportAbnormal` records it — as `Submarine`, which
  // says nothing about the domain error. That is what the hook's
  // `raise.error.*` attributes exist to compensate for.
  //
  // The exact class name is asserted, not merely "contains Submarine": the
  // scaladoc quotes it, so a cats-mtl rename should fail here and send someone
  // back to the doc rather than leaving it quietly stale.
  test("a raise crossing the traced wrapper is reported by otel4s as an opaque Submarine") {
    resultAndSpansFrom { implicit tracer =>
      tracer.span("outer").surround {
        viaHandle(Foo[IO].traceWithInputsAndOutputs, -1)
      }
    }.map { case (_, spans) =>
      val child = spanNamed(spans, "Foo.foo")

      assertEquals(child.getStatus.getStatusCode, StatusCode.ERROR)

      val events = child.getEvents.asScala.toList
      assertEquals(events.map(_.getName), List("exception"))
      assertEquals(
        events.head.getAttributes.toScala.get[String]("exception.type").map(_.value),
        Some("cats.mtl.Handle.Submarine")
      )
      // The domain error's own name appears nowhere in otel4s's report of it;
      // only `raise.error.type` carries it.
      assertEquals(
        child.getAttributes.toScala.get[String](RaiseRecorder.ErrorTypeKey).map(_.value),
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
    resultAndSpansFrom { implicit tracer =>
      viaHandle(Foo[IO].traceWithInputsAndOutputs, 5)
    }.map { case (result, spans) =>
      assertEquals(result, "foo:5")
      val span = spanNamed(spans, "Foo.foo")
      assertEquals(span.getStatus.getStatusCode, StatusCode.UNSET)
      assertEquals(span.getEvents.asScala.toList.map(_.getName), List.empty[String])
      // and no raise.error.* either — the hook only fires on a raise
      assertEquals(attributesOf(span).get[String](RaiseRecorder.ErrorTypeKey), None)
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
