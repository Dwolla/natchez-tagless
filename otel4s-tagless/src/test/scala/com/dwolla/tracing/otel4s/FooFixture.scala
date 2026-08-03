package com.dwolla.tracing.otel4s

import cats.Functor
import cats.syntax.all._
import cats.tagless.aop.Aspect
import cats.~>

/** The algebra every suite in this module is written against.
  *
  * `greet` covers the ordinary case — several parameters, a non-`Unit` return
  * — and `ping()` covers the two edges: an empty parameter list and a `Unit`
  * return value.
  */
trait Foo[F[_]] {
  def greet(name: String, times: Int): F[String]
  def ping(): F[Unit]
}

/** How many times each method of a `Foo.counting` algebra has run.
  *
  * Value equality cannot see an interpreter that runs the underlying call
  * twice: both runs produce the same answer, so `assertEquals` is satisfied
  * either way. An interpreter that needs `Monad` — which every interpreter
  * after `TracerInstrumentation` does — can very easily use
  * `fa.codomain.target` more than once, so the count is the only assertion
  * that catches it.
  */
final class FooCallCounts {
  private var greetRuns = 0
  private var pingRuns = 0

  def greet: Int = greetRuns
  def ping: Int = pingRuns

  private[otel4s] def recordGreet(): Unit = greetRuns += 1
  private[otel4s] def recordPing(): Unit = pingRuns += 1
}

object Foo {
  /** A `Foo[F]` that counts each of its own runs into `counts`.
    *
    * `suspend` says how the counting side effect is deferred into `F`: pass
    * `f => IO(f())` for an effectful `F`, or `f => f()` for `Id`, which has
    * nothing to defer into. Taking it as a parameter is what lets one fixture
    * serve both suites without a `Sync[F]` that `Id` cannot satisfy.
    *
    * Deferring matters: with an eager `IO.pure(…)` body the count would record
    * how many times the effect was ''built'', which is once no matter how many
    * times an interpreter then runs it — exactly the bug the count exists to
    * catch.
    */
  def counting[F[_]: Functor](counts: FooCallCounts)(suspend: (() => Unit) => F[Unit]): Foo[F] =
    new Foo[F] {
      override def greet(name: String, times: Int): F[String] =
        suspend(() => counts.recordGreet()).as(s"hello $name" * times)

      override def ping(): F[Unit] =
        suspend(() => counts.recordPing())
    }


  // Hand-written rather than derived, so this fixture needs no
  // cats-tagless-macros dependency on Scala 2 and is identical on both axes.
  //
  // Only the Aspect is implicit. Aspect extends Instrument, so `Instrument[Foo]`
  // resolves to this by subtyping; declaring a second `implicit val
  // fooInstrument: Instrument[Foo] = fooAspect` would make every
  // `Instrument[Foo]` summon ambiguous.
  implicit val fooAspect: Aspect[Foo, ToAnyValue, ToAnyValue] =
    new Aspect[Foo, ToAnyValue, ToAnyValue] {
      override def weave[F[_]](af: Foo[F]): Foo[Aspect.Weave[F, ToAnyValue, ToAnyValue, *]] =
        new Foo[Aspect.Weave[F, ToAnyValue, ToAnyValue, *]] {
          override def greet(name: String, times: Int): Aspect.Weave[F, ToAnyValue, ToAnyValue, String] =
            Aspect.Weave[F, ToAnyValue, ToAnyValue, String](
              "Foo",
              List(List(
                Aspect.Advice.byValue[ToAnyValue, String]("name", name),
                Aspect.Advice.byValue[ToAnyValue, Int]("times", times),
              )),
              Aspect.Advice[F, ToAnyValue, String]("greet", af.greet(name, times))
            )

          override def ping(): Aspect.Weave[F, ToAnyValue, ToAnyValue, Unit] =
            Aspect.Weave[F, ToAnyValue, ToAnyValue, Unit](
              "Foo",
              List(List.empty),
              Aspect.Advice[F, ToAnyValue, Unit]("ping", af.ping())
            )
        }

      override def mapK[F[_], G[_]](af: Foo[F])(fk: F ~> G): Foo[G] =
        new Foo[G] {
          override def greet(name: String, times: Int): G[String] = fk(af.greet(name, times))
          override def ping(): G[Unit] = fk(af.ping())
        }
    }
}
