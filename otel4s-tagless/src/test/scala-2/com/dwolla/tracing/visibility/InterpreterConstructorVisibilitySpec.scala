package com.dwolla.tracing.visibility

import munit.FunSuite

/** The interpreters' constructors are not API: users go through the companion
  * `apply`s, so adding a capability later is not a binary break.
  *
  * Scala 2 only: Scala 3's `compileErrors` does not report `private[otel4s]`
  * access violations (the snippet compiles clean), although the same code in
  * ordinary source is rejected by the Scala 3 compiler. The constructors are
  * declared once for all versions, so the Scala 2 check covers them.
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
  }

  test("TracerWeaveCapturingInputsAndOutputs can only be built through its companion") {
    assertNotEquals(compileErrors("""
      import cats.effect.IO
      import org.typelevel.otel4s.trace.Tracer
      implicit val tracer: Tracer[IO] = Tracer.noop[IO]
      new com.dwolla.tracing.otel4s.TracerWeaveCapturingInputsAndOutputs[IO]
    """), "")
  }
}
