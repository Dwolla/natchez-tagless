package com.dwolla.tracing

import cats.tagless.aop._
import cats.~>
import natchez.Trace

object TraceInstrumentation {
  def apply[F[_] : Trace]: Instrumentation[F, *] ~> F = new TraceInstrumentation[F]
}

/**
 * Use this `FunctionK` when you have an algebra in `Instrumentation[F, *]` and you
 * want each method call on the algebra to introduce a new child span, using the
 * ambient `Trace[F]`. Each child span will be named using the algebra name and
 * method name as captured in the `Instrumentation[F, A]`.
 *
 * Note if you have an algebra `Alg[F]` for which an `Instrument[Alg]` exists,
 * it can be converted to `Alg[Instrumentation[F, *]]`
 * using `Instrument[Alg].instrument`:
 *
 * {{{
 *   import cats.effect._, cats.tagless._, cats.tagless.aop._, cats.tagless.syntax.all._
 *
 *   trait Foo[F[_]] {
 *     def foo: F[Unit]
 *   }
 *
 *   object Foo {
 *     implicit val fooInstrument: Instrument[Foo] = Derive.instrument
 *   }
 *
 *   def myFoo: Foo[IO] = new Foo[IO] {
 *     def foo = IO.println("foo!")
 *   }
 *
 *   val instrumentedFoo: Foo[Instrumentation[IO, *]] =
 *     myFoo.instrument
 * }}}
 */
class TraceInstrumentation[F[_] : Trace] extends (Instrumentation[F, *] ~> F) {
  override def apply[A](fa: Instrumentation[F, A]): F[A] =
    Trace[F].span(s"${fa.algebraName}.${fa.methodName}")(fa.value)
}
