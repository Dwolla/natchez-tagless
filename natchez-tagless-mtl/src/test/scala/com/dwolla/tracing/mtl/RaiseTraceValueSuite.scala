package com.dwolla.tracing.mtl

import cats.effect.IO
import cats.mtl.Handle
import com.dwolla.tagless.mtl.RaiseAspect
import com.dwolla.tracing.mtl.syntax._
import munit.CatsEffectSuite
import natchez.{Trace, TraceableValue}

import BarError._

/** Raise/`Handle.allow`/`rescue` round trip, checked on the actual returned
  * *value* — [[RaiseTraceIntegrationSuite]] checks the span/attribute side of the
  * same scenario, which `InMemory`'s command history can't verify this half of.
  *
  * Uses `Trace.Implicits.noop` rather than `InMemory`: the span history is not the
  * point here, and tracing through a real span changes nothing about whether
  * the domain error survives cats-mtl's `Submarine` encoding.
  */
abstract class RaiseTraceValueSuite extends CatsEffectSuite {
  implicit def barRaiseAspect: RaiseAspect[Bar, TraceableValue, TraceableValue, TraceableValue]
  private implicit val trace: Trace[IO] = Trace.Implicits.noop[IO]

  private val tracedBar: Bar[IO] = Bar[IO].traceWithInputsAndOutputs

  test("a successful call returns the underlying result through the traced wrapper") {
    Handle.allowF[IO, BarError] { implicit h => tracedBar.bar(5) }.attempt.assertEquals(Right("bar:5"))
  }

  test("a raised error propagates through the traced wrapper and is recoverable, domain error intact") {
    Handle
      .allowF[IO, BarError] { implicit h => tracedBar.bar(-1) }
      .rescue { case Negative(i) => IO.pure(s"rescued:$i") }
      .assertEquals("rescued:-1")
  }

}
