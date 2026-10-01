package com.dwolla.tracing.visibility

import munit.FunSuite

/** The interpreter classes are `private[otel4s]`, like
  * `MeterInstrumentation`, and the companion `apply` is the fixed public
  * entry point. A private class lets its implementation change freely, but
  * adding a capability to `apply` later would still be breaking, so a new
  * capability needs a new entry point.
  */
class InterpreterConstructorVisibilitySpec extends FunSuite {
  test("TracerInstrumentation can only be built through its companion") {
    assertNotEquals(compileErrors("""
      import cats.effect.IO
      import org.typelevel.otel4s.trace.Tracer
      implicit val tracer: Tracer[IO] = Tracer.noop[IO]
      new com.dwolla.tracing.otel4s.TracerInstrumentation[IO]
    """), "")
    assertEquals(compileErrors("""
      import cats.effect.IO
      import org.typelevel.otel4s.trace.Tracer
      implicit val tracer: Tracer[IO] = Tracer.noop[IO]
      com.dwolla.tracing.otel4s.TracerInstrumentation[IO]
    """), "")
  }

  test("TracerWeaveCapturingInputs can only be built through its companion") {
    assertNotEquals(compileErrors("""
      import cats.effect.IO
      import org.typelevel.otel4s.trace.Tracer
      import com.dwolla.tracing.otel4s.ToAnyValue
      implicit val tracer: Tracer[IO] = Tracer.noop[IO]
      new com.dwolla.tracing.otel4s.TracerWeaveCapturingInputs[IO, ToAnyValue]
    """), "")
    assertEquals(compileErrors("""
      import cats.effect.IO
      import org.typelevel.otel4s.trace.Tracer
      import com.dwolla.tracing.otel4s.ToAnyValue
      implicit val tracer: Tracer[IO] = Tracer.noop[IO]
      com.dwolla.tracing.otel4s.TracerWeaveCapturingInputs[IO, ToAnyValue]
    """), "")
  }

  test("TracerWeaveCapturingInputsAndOutputs can only be built through its companion") {
    assertNotEquals(compileErrors("""
      import cats.effect.IO
      import org.typelevel.otel4s.trace.Tracer
      implicit val tracer: Tracer[IO] = Tracer.noop[IO]
      new com.dwolla.tracing.otel4s.TracerWeaveCapturingInputsAndOutputs[IO]
    """), "")
    assertEquals(compileErrors("""
      import cats.effect.IO
      import org.typelevel.otel4s.trace.Tracer
      implicit val tracer: Tracer[IO] = Tracer.noop[IO]
      com.dwolla.tracing.otel4s.TracerWeaveCapturingInputsAndOutputs[IO]
    """), "")
  }
}
