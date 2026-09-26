package com.dwolla.metrics.otel4s

import cats.tagless.aop.{Instrument, Instrumentation}
import cats.~>

/** The algebra every suite in this module is written against. Each method is
  * backed by an effect the test supplies, so a test controls how long a call
  * takes and how it ends.
  */
trait Foo[F[_]] {
  def greet(name: String): F[String]
  def ping(): F[Unit]
}

/** A top-level (not method-local) error type, so its `getClass.getName` —
  * what `error.type` records — is a stable, predictable string.
  */
final class FooFailure extends RuntimeException("boom")

object Foo {
  def apply[F[_]](greetWith: String => F[String], pingWith: F[Unit]): Foo[F] =
    new Foo[F] {
      override def greet(name: String): F[String] = greetWith(name)
      override def ping(): F[Unit] = pingWith
    }

  /** `foo` with every call routed through `fk` — what the syntax does, without
    * depending on it, so the interpreter suites don't need the syntax to exist.
    */
  def metered[F[_]](foo: Foo[F], fk: Instrumentation[F, *] ~> F): Foo[F] =
    fooInstrument.mapK(fooInstrument.instrument(foo))(fk)

  // Hand-written rather than derived, so this fixture needs no
  // cats-tagless-macros dependency on Scala 2 and is identical on both axes.
  implicit val fooInstrument: Instrument[Foo] =
    new Instrument[Foo] {
      override def instrument[F[_]](af: Foo[F]): Foo[Instrumentation[F, *]] =
        new Foo[Instrumentation[F, *]] {
          override def greet(name: String): Instrumentation[F, String] =
            Instrumentation(af.greet(name), "Foo", "greet")
          override def ping(): Instrumentation[F, Unit] =
            Instrumentation(af.ping(), "Foo", "ping")
        }

      override def mapK[F[_], G[_]](af: Foo[F])(fk: F ~> G): Foo[G] =
        new Foo[G] {
          override def greet(name: String): G[String] = fk(af.greet(name))
          override def ping(): G[Unit] = fk(af.ping())
        }
    }
}
