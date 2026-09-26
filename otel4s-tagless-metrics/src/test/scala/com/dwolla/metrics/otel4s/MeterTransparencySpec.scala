package com.dwolla.metrics.otel4s

import cats.effect.{IO, Outcome}
import cats.tagless.aop.Instrumentation
import cats.~>
import munit.CatsEffectSuite
import org.typelevel.otel4s.metrics.Meter

/** What can be checked without an SDK, on every platform.
  *
  * The only coverage the interpreters get on Scala.js: the content suite needs
  * an SDK, and the only backend it runs against today, oteljava, is JVM-only.
  * Under `Meter.noop`, `recordDuration` is `Resource.unit`, so these pin that
  * wrapping a call changes nothing observable about it.
  */
class MeterTransparencySpec extends CatsEffectSuite {
  private implicit val meter: Meter[IO] = Meter.noop[IO]

  private val interpreters: List[(String, IO[Instrumentation[IO, *] ~> IO])] = List(
    "MeterInstrumentation" -> MeterInstrumentation[IO](CallDuration.DefaultBucketBoundaries),
    "RpcMeterInstrumentation" ->
      RpcMeterInstrumentation[IO](RpcRole.Server, RpcSystem("thrift"), RpcService("com.example.FooService")),
  )

  interpreters.foreach { case (label, interpreter) =>
    test(s"$label returns the underlying value and runs the method exactly once") {
      for {
        runs <- IO.ref(0)
        foo <- interpreter.map(Foo.metered(Foo[IO](name => runs.update(_ + 1).as(s"hello $name"), IO.unit), _))
        result <- foo.greet("world")
        count <- runs.get
      } yield {
        assertEquals(result, "hello world")
        assertEquals(count, 1)
      }
    }

    test(s"$label raises the identical error the method raised") {
      val failure = new FooFailure
      interpreter
        .flatMap(Foo.metered(Foo[IO](_ => IO.raiseError(failure), IO.unit), _).greet("world").attempt)
        .map(result => assert(result.left.exists(_ eq failure), s"expected the identical FooFailure back, got $result"))
    }

    test(s"$label propagates cancellation") {
      interpreter
        .flatMap(Foo.metered(Foo[IO](_ => IO.never, IO.canceled), _).ping().start)
        .flatMap(_.join)
        .map(outcome => assertEquals(outcome, Outcome.canceled[IO, Throwable, Unit]))
    }
  }
}
