package com.dwolla.tracing.otel4s.mtl

import cats.effect.IO
import com.dwolla.tagless.mtl.{OnRaise, RaiseRecorder}
import com.dwolla.tracing.otel4s.ToAnyValue
import munit.CatsEffectSuite
import org.typelevel.otel4s.trace.TracerProvider

/** When a user-supplied `OnRaise[F, Err]` is in scope alongside an
  * ambient `TracerProvider[F]`, `RaiseRecorder[F, Err]` resolution must pick
  * the user's instance over the `TracerProvider`-derived default; when no
  * `OnRaise[F, Err]` is in scope, the `TracerProvider`-derived default must
  * still be reachable.
  * Mirrors `natchez-tagless-mtl`'s `RaiseRecorderPrioritySpec`. Cross-platform
  * — no testkit involved, so this asserts resolution, not span content;
  * `RaiseSpanContentSpec` (JVM-only) covers content.
  */
class RaiseRecorderPrioritySpec extends CatsEffectSuite {
  private implicit val tracerProvider: TracerProvider[IO] = TracerProvider.noop[IO]

  test("the otel4s default is reachable when no OnRaise is in scope") {
    import com.dwolla.tracing.otel4s.mtl.syntax._
    implicitly[RaiseRecorder[IO, ToAnyValue]].onRaise(42).assertEquals(())
  }

  // `interceptMessageIO`, not `.unsafeRunSync()`: cats-effect's `IO#unsafeRunSync`
  // is JVM-only (it needs a blocking-capable `IORuntime`), so this suite runs on
  // Scala.js too — `interceptMessageIO` hands back an `IO[AssertionError]` for
  // munit's own runner to execute, the same way any other `IO`-returning test
  // body in this suite does.
  test("a user-supplied OnRaise wins over the otel4s default, and it is the one that runs") {
    import com.dwolla.tracing.otel4s.mtl.syntax._
    implicit val poison: OnRaise[IO, ToAnyValue] = new OnRaise[IO, ToAnyValue] {
      def apply[E](e: E)(implicit ev: ToAnyValue[E]): IO[Unit] =
        IO.raiseError(new AssertionError("user hook ran"))
    }

    interceptMessageIO[AssertionError]("user hook ran") {
      implicitly[RaiseRecorder[IO, ToAnyValue]].onRaise(42)
    }
  }

  test("resolving with both a user OnRaise and a TracerProvider in scope reports no ambiguous implicit") {
    // The 2.13 shape guard, at the otel4s Err. `raise-aspect`'s
    // `RaiseRecorderSpec` covers the mechanism; this covers this module's
    // actual instantiation of it.
    val errors: String = compileErrors(
      """import cats.effect.IO
import com.dwolla.tagless.mtl.{OnRaise, RaiseRecorder}
import com.dwolla.tracing.otel4s.ToAnyValue
import com.dwolla.tracing.otel4s.mtl.syntax._
import org.typelevel.otel4s.trace.TracerProvider
implicit val tracerProvider: TracerProvider[IO] = TracerProvider.noop[IO]
implicit val userOnRaise: OnRaise[IO, ToAnyValue] = new OnRaise[IO, ToAnyValue] {
  def apply[E](e: E)(implicit ev: ToAnyValue[E]): IO[Unit] = IO.unit
}
implicitly[RaiseRecorder[IO, ToAnyValue]]"""
    )
    assertNoDiff(errors, "")
  }
}
