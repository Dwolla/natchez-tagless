package com.dwolla.tracing.otel4s

import cats.effect.IO
import cats.mtl.Handle
import cats.tagless.aop.Aspect
import cats.~>
import com.dwolla.tagless.WeaveKnot
import com.dwolla.tracing.otel4s.syntax._
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.trace.data.SpanData
import munit.CatsEffectSuite
import org.typelevel.otel4s.oteljava.AttributeConverters._
import org.typelevel.otel4s.oteljava.testkit.trace.TracesTestkit
import org.typelevel.otel4s.semconv.attributes.{CodeAttributes, ErrorAttributes}
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.{AnyValue, Attribute, Attributes}

import scala.jdk.CollectionConverters._

/** A domain error for the raise tests; deliberately not a `Throwable`. */
final class NotFound(val id: Int)

/** Span ''content'', asserted against a real SDK.
  *
  * JVM-only: every otel4s span type is sealed with a `private[otel4s]`
  * `Unsealed` variant, so a recording `Tracer` cannot be hand-rolled, and the
  * JVM oteljava testkit is used because the cross-platform
  * `otel4s-sdk-trace-testkit` would add an otel4s-sdk backend. Transparency is covered on every platform by `TracerTransparencySpec`.
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
    * The result is returned so a test can name the expected value once and then
    * assert that the span records that same value — '''not''' as a defense
    * against an interpreter that hands back something else. Parametricity
    * already forbids that: `apply[A](fa: Weave[…, A]): F[A]` has no way to
    * conjure an `A`, and the only `F[A]` in scope is `fa.codomain.target`.
    * Adding `FlatMap[F]` does not change that; it only makes it possible to
    * evaluate the target more than ''once'', and for this deterministic fixture
    * every evaluation yields the same value, so equality cannot see it. The
    * `FooCallCounts` assertions are what catch that, and they only work here,
    * over `IO` — see `FooCallCounts` for why `Id` cannot host them.
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

  /** A fresh counting algebra per test, so the counts start at zero. */
  protected def underlyingFoo(counts: FooCallCounts[IO]): Foo[IO] =
    Foo.counting[IO](counts)

  /** The `WeaveKnot` wiring from `TracerWeaveCapturingInputsAndOutputs`'s
    * doctest, in the same shape: `self.value` is the ''traced'' algebra, so the
    * two `inner` calls made from inside `outer` go back through the interpreter
    * and each opens its own span. Constructing the implementation directly and
    * calling `.traceWithInputsAndOutputs` on it would produce only the outer
    * span.
    */
  protected def tracedNested(implicit tracer: Tracer[IO]): Nested[IO] =
    WeaveKnot.weave[Nested, IO, ToAnyValue, ToAnyValue](
      self => new Nested[IO] {
        override def inner(name: String): IO[String] = IO.pure("hello " + name)

        override def outer(name: String): IO[String] =
          self.value.inner(name).flatMap(a => self.value.inner(name).map(b => a + " " + b))
      },
      TracerWeaveCapturingInputsAndOutputs[IO]
    )

  private def codeFunctionNames(spans: List[SpanData]): List[Option[String]] =
    spans.map(attributesOf(_).get(CodeAttributes.CodeFunctionName).map(_.value))

  private def raisingFoo(h: Handle[IO, NotFound]): Foo[IO] =
    new Foo[IO] {
      override def greet(name: String, times: Int): IO[String] = h.raise(new NotFound(42))
      override def ping(): IO[Unit] = IO.unit
    }

  private def onlySpan(spans: List[SpanData]): SpanData =
    spans match {
      case List(span) => span
      case other => fail(s"expected exactly one span, got ${other.map(_.getName)}")
    }

  private def assertReportedAsDomainError(result: Either[NotFound, String], spans: List[SpanData]): Unit = {
    assert(result.left.exists(_.id == 42), s"expected the raised NotFound(42) back, got $result")
    val span = onlySpan(spans)
    assertEquals(span.getStatus.getStatusCode, StatusCode.ERROR)
    assertEquals(span.getEvents.asScala.toList.map(_.getName), Nil)
    assertEquals(attributesOf(span).get(ErrorAttributes.ErrorType).map(_.value), Some(classOf[NotFound].getName))
  }

  test("instrumentAndTrace reports an escaped raise as the domain error, not cats-mtl's Submarine") {
    resultAndSpansFrom { implicit tracer =>
      Handle.allowF[IO, NotFound](h => raisingFoo(h).instrumentAndTrace.greet("world", 1)).attempt
    }.map { case (result, spans) => assertReportedAsDomainError(result, spans) }
  }

  test("traceWithInputs reports an escaped raise as the domain error, not cats-mtl's Submarine") {
    resultAndSpansFrom { implicit tracer =>
      Handle.allowF[IO, NotFound](h => raisingFoo(h).traceWithInputs[ToAnyValue].greet("world", 1)).attempt
    }.map { case (result, spans) => assertReportedAsDomainError(result, spans) }
  }

  test("traceWithInputsAndOutputs reports an escaped raise as the domain error, not cats-mtl's Submarine") {
    resultAndSpansFrom { implicit tracer =>
      Handle.allowF[IO, NotFound](h => raisingFoo(h).traceWithInputsAndOutputs.greet("world", 1)).attempt
    }.map { case (result, spans) => assertReportedAsDomainError(result, spans) }
  }

  test("an escaped raise of null is still rescued by the caller and ends the span with error.type = null") {
    resultAndSpansFrom { implicit tracer =>
      Handle.allowF[IO, NotFound] { h =>
        new Foo[IO] {
          override def greet(name: String, times: Int): IO[String] = h.raise[NotFound, String](null)
          override def ping(): IO[Unit] = IO.unit
        }.instrumentAndTrace.greet("world", 1).as(false)
      }.rescue(e => IO.pure(e == null))
    }.map { case (rescuedNull, spans) =>
      assert(rescuedNull, "expected the raised null back from rescue")
      val span = onlySpan(spans)
      assertEquals(span.getStatus.getStatusCode, StatusCode.ERROR)
      assertEquals(attributesOf(span).get(ErrorAttributes.ErrorType).map(_.value), Some("null"))
    }
  }

  test("a canceled call keeps otel4s's reportAbnormal: status ERROR described as canceled, with no error.type") {
    resultAndSpansFrom { implicit tracer =>
      new Foo[IO] {
        override def greet(name: String, times: Int): IO[String] = IO.canceled.as("unreachable")
        override def ping(): IO[Unit] = IO.unit
      }.instrumentAndTrace.greet("world", 1).start.flatMap(_.join)
    }.map { case (outcome, spans) =>
      assert(outcome.isCanceled, s"expected a canceled outcome, got $outcome")
      val span = onlySpan(spans)
      assertEquals(span.getStatus.getStatusCode, StatusCode.ERROR)
      assertEquals(span.getStatus.getDescription, "canceled")
      assertEquals(attributesOf(span).get(ErrorAttributes.ErrorType), None)
    }
  }

  test("a raise rescued inside the method leaves the span OK, with no error.type") {
    resultAndSpansFrom { implicit tracer =>
      Handle.allowF[IO, NotFound] { h =>
        val rescuing = new Foo[IO] {
          override def greet(name: String, times: Int): IO[String] =
            h.handleWith(h.raise[NotFound, String](new NotFound(1)))(_ => IO.pure("recovered"))
          override def ping(): IO[Unit] = IO.unit
        }
        rescuing.traceWithInputsAndOutputs.greet("world", 1)
      }.attempt
    }.map { case (result, spans) =>
      assertEquals(result, Right("recovered"))
      val span = onlySpan(spans)
      assertEquals(span.getStatus.getStatusCode, StatusCode.UNSET)
      assertEquals(attributesOf(span).get(ErrorAttributes.ErrorType), None)
    }
  }

  test("an ordinary thrown exception is still recorded by otel4s as an exception event with status ERROR") {
    val boom = new IllegalStateException("boom")
    resultAndSpansFrom { implicit tracer =>
      new Foo[IO] {
        override def greet(name: String, times: Int): IO[String] = IO.raiseError(boom)
        override def ping(): IO[Unit] = IO.unit
      }.instrumentAndTrace.greet("world", 1).attempt
    }.map { case (result, spans) =>
      assertEquals(result, Left(boom))
      val span = onlySpan(spans)
      assertEquals(span.getStatus.getStatusCode, StatusCode.ERROR)
      assertEquals(span.getEvents.asScala.toList.map(_.getName), List("exception"))
    }
  }

  test("instrumentAndTrace records code.function.name = <Algebra>.<method>, even for a zero-parameter method") {
    spansFrom { implicit tracer => Foo.plain[IO].instrumentAndTrace.ping() }
      .map(spans => assertEquals(codeFunctionNames(spans), List(Some("Foo.ping"))))
  }

  test("traceWithInputs records code.function.name = <Algebra>.<method>, even for a zero-parameter method") {
    spansFrom { implicit tracer => Foo.plain[IO].traceWithInputs[ToAnyValue].ping() }
      .map(spans => assertEquals(codeFunctionNames(spans), List(Some("Foo.ping"))))
  }

  test("traceWithInputsAndOutputs records code.function.name = <Algebra>.<method>, even for a zero-parameter method") {
    spansFrom { implicit tracer => Foo.plain[IO].traceWithInputsAndOutputs.ping() }
      .map(spans => assertEquals(codeFunctionNames(spans), List(Some("Foo.ping"))))
  }

  test("parameters and the return value are recorded under fixed, OTel-style keys") {
    spansFrom { implicit tracer => Foo.plain[IO].traceWithInputsAndOutputs.greet("world", 2) }
      .map { spans =>
        val keys = spans.flatMap(attributesOf(_).map(_.key.name)).toSet
        assertEquals(keys, Set("code.function.name", "com.dwolla.code.function.arguments", "com.dwolla.code.function.return_value"))
      }
  }

  test("each method call opens one span named algebraName.methodName") {
    for {
      counts <- FooCallCounts.of[IO]
      result <- resultAndSpansFrom { implicit tracer =>
        underlyingFoo(counts).instrumentAndTrace.greet("world", 2)
      }
      (greeting, spans) = result
      _ = assertEquals(greeting, "hello worldhello world")
      g <- counts.greet
      _ = assertEquals(g, 1)
      _ = assertEquals(spans.map(_.getName), List("Foo.greet"))
    } yield ()
  }

  test("TracerInstrumentation records only code.function.name") {
    for {
      counts <- FooCallCounts.of[IO]
      spans <- spansFrom { implicit tracer =>
        underlyingFoo(counts).instrumentAndTrace.greet("world", 2)
      }
      _ = assertEquals(spans.map(attributesOf), List(Attributes(Attribute("code.function.name", "Foo.greet"))))
    } yield ()
  }

  // ping() is the empty-parameter-list, Unit-returning edge. Under
  // TracerInstrumentation it is unremarkable — the interpreter records only
  // code.function.name for any method — but it is the baseline the
  // TracerWeaveCapturingInputs case below has to match.
  test("a zero-parameter, Unit-returning method is spanned like any other") {
    for {
      counts <- FooCallCounts.of[IO]
      result <- resultAndSpansFrom { implicit tracer =>
        underlyingFoo(counts).instrumentAndTrace.ping()
      }
      (pong, spans) = result
      _ = assertEquals(pong, ())
      p <- counts.ping
      _ = assertEquals(p, 1)
      _ = assertEquals(spans.map(_.getName), List("Foo.ping"))
      _ = assertEquals(spans.map(attributesOf), List(Attributes(Attribute("code.function.name", "Foo.ping"))))
    } yield ()
  }

  test("TracerWeaveCapturingInputs records every parameter as one structured attribute") {
    for {
      counts <- FooCallCounts.of[IO]
      result <- resultAndSpansFrom { implicit tracer =>
        underlyingFoo(counts).traceWithInputs[ToAnyValue].greet("world", 2)
      }
      (greeting, spans) = result
      _ = assertEquals(greeting, "hello worldhello world")
      g <- counts.greet
      _ = assertEquals(g, 1)
      _ = assertEquals(spans.map(_.getName), List("Foo.greet"))
      // The decoded tree, not the rendered text: `times` is a LongValue, and
      // an AnyValue map does not preserve key order.
      _ = assertEquals(
        spans.map(attributesOf(_)),
        List(Attributes(
          Attribute("code.function.name", "Foo.greet"),
          Attribute[AnyValue](
          "com.dwolla.code.function.arguments",
          AnyValue.map(Map(
            "name" -> AnyValue.string("world"),
            "times" -> AnyValue.long(2L),
          )),
        ))
        )
      )
    } yield ()
  }

  test("TracerWeaveCapturingInputs records only code.function.name for a method with no parameters") {
    for {
      counts <- FooCallCounts.of[IO]
      result <- resultAndSpansFrom { implicit tracer =>
        underlyingFoo(counts).traceWithInputs[ToAnyValue].ping()
      }
      (pong, spans) = result
      _ = assertEquals(pong, ())
      p <- counts.ping
      _ = assertEquals(p, 1)
      _ = assertEquals(spans.map(_.getName), List("Foo.ping"))
      _ = assertEquals(spans.map(attributesOf), List(Attributes(Attribute("code.function.name", "Foo.ping"))))
    } yield ()
  }

  test("TracerWeaveCapturingInputsAndOutputs records the parameters and the return value") {
    for {
      counts <- FooCallCounts.of[IO]
      result <- resultAndSpansFrom { implicit tracer =>
        underlyingFoo(counts).traceWithInputsAndOutputs.greet("world", 2)
      }
      (greeting, spans) = result
      _ = assertEquals(greeting, "hello worldhello world")
      // This interpreter is the first in the module that *could* run the
      // underlying effect twice — it has a FlatMap[F] and threads the value
      // through `flatTap` — so the count is load-bearing here in a way it is
      // not above. It has to be asserted in this suite: see FooCallCounts.
      g <- counts.greet
      _ = assertEquals(g, 1)
      _ = assertEquals(spans.map(_.getName), List("Foo.greet"))
      // The decoded tree, not the rendered text: `times` is a LongValue, and
      // an AnyValue map does not preserve key order.
      //
      // com.dwolla.code.function.return_value comes back as a plain String attribute, not an AnyValue
      // one, and that is the Java SDK doing what it documents:
      // AttributesBuilder#put(AttributeKey, Object) automatically narrows an
      // AttributeType.VALUE whose Value has a simple equivalent, so
      // `put(valueKey(k), Value.of("a"))` *is* `put(stringKey(k), "a")`.
      // The parameters map has no simple equivalent, so it stays AnyValue.
      // The interpreter passes AnyValue in both cases; the narrowing is
      // downstream of it and is good news — backends index simple attributes.
      _ = assertEquals(
        spans.map(attributesOf(_)),
        List(Attributes(
          Attribute("code.function.name", "Foo.greet"),
          Attribute[AnyValue](
            "com.dwolla.code.function.arguments",
            AnyValue.map(Map(
              "name" -> AnyValue.string("world"),
              "times" -> AnyValue.long(2L),
            )),
          ),
          Attribute("com.dwolla.code.function.return_value", "hello worldhello world"),
        ))
      )
    } yield ()
  }

  // The omit-when-empty rule applied to the *return value* as well as the
  // parameters: ping() is both zero-parameter and Unit-returning, so both
  // candidate attributes would be empty and both are omitted rather than
  // recorded as `MapValue({})`/`EmptyValue`. ToAnyValue[Unit] still encodes
  // `()` to AnyValue.empty; the omission is the interpreter's decision not to
  // spend an attribute slot on it.
  test("TracerWeaveCapturingInputsAndOutputs records only code.function.name for ping()") {
    for {
      counts <- FooCallCounts.of[IO]
      result <- resultAndSpansFrom { implicit tracer =>
        underlyingFoo(counts).traceWithInputsAndOutputs.ping()
      }
      (pong, spans) = result
      _ = assertEquals(pong, ())
      p <- counts.ping
      _ = assertEquals(p, 1)
      _ = assertEquals(spans.map(_.getName), List("Foo.ping"))
      _ = assertEquals(spans.map(attributesOf), List(Attributes(Attribute("code.function.name", "Foo.ping"))))
    } yield ()
  }

  test("WeaveKnot nests each inner call inside the outer call's span") {
    resultAndSpansFrom { implicit tracer =>
      tracedNested.outer("world")
    }.map { case (greeting, spans) =>
      assertEquals(greeting, "hello world hello world")
      assertEquals(spans.size, 3)

      val outer = spans.filter(_.getName == "Nested.outer")
      val inner = spans.filter(_.getName == "Nested.inner")
      assertEquals(outer.size, 1)
      assertEquals(inner.size, 2)

      val parentSpanId = outer.head.getSpanId
      // Both children name the outer span as their parent, and they really are
      // two distinct spans rather than one span recorded twice.
      assertEquals(inner.map(_.getParentSpanId), List(parentSpanId, parentSpanId))
      assertEquals(inner.map(_.getSpanId).distinct.size, 2)

      // One trace, rooted at the outer span.
      assertEquals(spans.map(_.getTraceId).distinct, List(outer.head.getTraceId))
      assert(!outer.head.getParentSpanContext.isValid, "the outer span should be a root span")
    }
  }
}

/** A two-method algebra whose `outer` calls its own `inner`, twice.
  *
  * Separate from `Foo` because the point is the self-call: `Foo`'s methods are
  * leaves, so no wiring of `Foo` can produce a nested span. JVM-only alongside
  * `SpanContentSpec` because span structure, like span content, needs the
  * testkit to observe.
  */
trait Nested[F[_]] {
  def outer(name: String): F[String]
  def inner(name: String): F[String]
}

object Nested {
  // Hand-written for the same reason Foo's is: no cats-tagless-macros
  // dependency on Scala 2, and identical on both Scala versions.
  implicit val nestedAspect: Aspect[Nested, ToAnyValue, ToAnyValue] =
    new Aspect[Nested, ToAnyValue, ToAnyValue] {
      override def weave[F[_]](af: Nested[F]): Nested[Aspect.Weave[F, ToAnyValue, ToAnyValue, *]] =
        new Nested[Aspect.Weave[F, ToAnyValue, ToAnyValue, *]] {
          override def outer(name: String): Aspect.Weave[F, ToAnyValue, ToAnyValue, String] =
            Aspect.Weave[F, ToAnyValue, ToAnyValue, String](
              "Nested",
              List(List(Aspect.Advice.byValue[ToAnyValue, String]("name", name))),
              Aspect.Advice[F, ToAnyValue, String]("outer", af.outer(name))
            )

          override def inner(name: String): Aspect.Weave[F, ToAnyValue, ToAnyValue, String] =
            Aspect.Weave[F, ToAnyValue, ToAnyValue, String](
              "Nested",
              List(List(Aspect.Advice.byValue[ToAnyValue, String]("name", name))),
              Aspect.Advice[F, ToAnyValue, String]("inner", af.inner(name))
            )
        }

      override def mapK[F[_], G[_]](af: Nested[F])(fk: F ~> G): Nested[G] =
        new Nested[G] {
          override def outer(name: String): G[String] = fk(af.outer(name))
          override def inner(name: String): G[String] = fk(af.inner(name))
        }
    }
}
