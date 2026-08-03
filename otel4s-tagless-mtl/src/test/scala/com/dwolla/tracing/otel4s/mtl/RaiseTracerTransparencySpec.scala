package com.dwolla.tracing.otel4s.mtl

import cats.effect.IO
import cats.mtl.Handle
import com.dwolla.tracing.otel4s.ToAnyValue
import com.dwolla.tracing.otel4s.mtl.FooError.Negative
import com.dwolla.tracing.otel4s.mtl.syntax._
import munit.CatsEffectSuite
import org.typelevel.otel4s.trace.Tracer

/** Task 4 — proves `RaiseTracerWeaveOps`'s two methods are reachable via
  * `com.dwolla.tracing.otel4s.mtl.syntax._`, and that weaving `Foo` with
  * either one is transparent under `Tracer.noop`: the traced algebra returns
  * exactly what the untraced algebra returns, both for a call that succeeds
  * and for one that raises and is rescued.
  *
  * Both `traceWithInputs[ToAnyValue]` and `traceWithInputsAndOutputs` are
  * exercised, and both the success and the raise path are asserted for each —
  * a suite that only covered success would pass even against a `RaiseRecorder`
  * whose `onRaise` hook throws, which would defeat the point of testing
  * transparency at all.
  *
  * Compares the traced and untraced results directly, rather than against a
  * hand-computed expected value, so the assertion stays meaningful even if
  * `Foo`'s own implementation changes.
  */
class RaiseTracerTransparencySpec extends CatsEffectSuite {
  private implicit val tracer: Tracer[IO] = Tracer.noop[IO]

  private val untraced: Foo[IO] = Foo[IO]
  private val tracedInputs: Foo[IO] = Foo[IO].traceWithInputs[ToAnyValue]
  private val tracedInputsAndOutputs: Foo[IO] = Foo[IO].traceWithInputsAndOutputs

  /** Runs `alg.foo(i)` under a fresh `Handle[IO, FooError]`, recovering any
    * raise with `recover` — mirrors `RaiseTraceValueSuite`'s
    * `Handle.allowF(...).rescue(...)` shape.
    */
  private def viaHandle(alg: Foo[IO], i: Int)(recover: FooError => IO[String]): IO[String] =
    Handle.allowF[IO, FooError] { implicit h => alg.foo(i) }.rescue(recover)

  private def assertTransparent(traced: Foo[IO], i: Int)(recover: FooError => IO[String]): IO[Unit] =
    for {
      tracedResult <- viaHandle(traced, i)(recover)
      untracedResult <- viaHandle(untraced, i)(recover)
    } yield assertEquals(tracedResult, untracedResult)

  private val failIfRaised: FooError => IO[String] =
    e => IO.raiseError(new AssertionError(s"unexpected raise: $e"))

  private val rescueNegative: FooError => IO[String] = { case Negative(i) => IO.pure(s"rescued:$i") }

  test("traceWithInputsAndOutputs is transparent on the success path") {
    assertTransparent(tracedInputsAndOutputs, 5)(failIfRaised)
  }

  test("traceWithInputsAndOutputs is transparent on the raise path") {
    assertTransparent(tracedInputsAndOutputs, -1)(rescueNegative)
  }

  test("traceWithInputs[ToAnyValue] is transparent on the success path") {
    assertTransparent(tracedInputs, 5)(failIfRaised)
  }

  test("traceWithInputs[ToAnyValue] is transparent on the raise path") {
    assertTransparent(tracedInputs, -1)(rescueNegative)
  }
}
