package com.dwolla.tracing.otel4s

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

object Foo {
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
