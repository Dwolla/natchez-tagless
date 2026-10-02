package com.dwolla.tracing.visibility

import munit.FunSuite

/** The interpreter classes are `private[otel4s]`, like
  * `MeterInstrumentation`, and the companion `apply` is the fixed public
  * entry point. A private class lets its implementation change freely, but
  * adding a capability to `apply` later would still be breaking, so a new
  * capability needs a new entry point.
  */
class InterpreterConstructorVisibilitySpec extends FunSuite {
  /** Any compile error would make a snippet fail, including a typo in it, so
    * this checks that the error is the access violation, which Scala 2 and
    * Scala 3 both word as "cannot be accessed as a member of".
    */
  private def assertAccessViolation(errors: String)(implicit loc: munit.Location): Unit =
    assert(errors.contains("cannot be accessed as a member of"), errors)

  test("TracerInstrumentation can only be built through its companion") {
    assertAccessViolation(compileErrors("""
      import cats.effect.IO
      import org.typelevel.otel4s.trace.Tracer
      implicit val tracer: Tracer[IO] = Tracer.noop[IO]
      new com.dwolla.tracing.otel4s.TracerInstrumentation[IO]
    """))
    assertEquals(compileErrors("""
      import cats.effect.IO
      import org.typelevel.otel4s.trace.Tracer
      implicit val tracer: Tracer[IO] = Tracer.noop[IO]
      com.dwolla.tracing.otel4s.TracerInstrumentation[IO]
    """), "")
  }

  test("TracerWeaveCapturingInputs can only be built through its companion") {
    assertAccessViolation(compileErrors("""
      import cats.effect.IO
      import org.typelevel.otel4s.trace.Tracer
      import com.dwolla.tracing.otel4s.ToAnyValue
      implicit val tracer: Tracer[IO] = Tracer.noop[IO]
      new com.dwolla.tracing.otel4s.TracerWeaveCapturingInputs[IO, ToAnyValue]
    """))
    assertEquals(compileErrors("""
      import cats.effect.IO
      import org.typelevel.otel4s.trace.Tracer
      import com.dwolla.tracing.otel4s.ToAnyValue
      implicit val tracer: Tracer[IO] = Tracer.noop[IO]
      com.dwolla.tracing.otel4s.TracerWeaveCapturingInputs[IO, ToAnyValue]
    """), "")
  }

  test("TracerWeaveCapturingInputsAndOutputs can only be built through its companion") {
    assertAccessViolation(compileErrors("""
      import cats.effect.IO
      import org.typelevel.otel4s.trace.Tracer
      implicit val tracer: Tracer[IO] = Tracer.noop[IO]
      new com.dwolla.tracing.otel4s.TracerWeaveCapturingInputsAndOutputs[IO]
    """))
    assertEquals(compileErrors("""
      import cats.effect.IO
      import org.typelevel.otel4s.trace.Tracer
      implicit val tracer: Tracer[IO] = Tracer.noop[IO]
      com.dwolla.tracing.otel4s.TracerWeaveCapturingInputsAndOutputs[IO]
    """), "")
  }
}
