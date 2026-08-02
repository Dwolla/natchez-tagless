package com.dwolla.tracing

import cats.data.Kleisli
import cats.effect.MonadCancelThrow
import cats.tagless.aop._
import cats.~>
import natchez.{EntryPoint, Span}

object RootSpanProvidingFunctionK {
  def apply[F[_] : MonadCancelThrow](entryPoint: EntryPoint[F]): Instrumentation[Kleisli[F, Span[F], *], *] ~> F =
    new RootSpanProvidingFunctionK(entryPoint)
}

/**
 * Use this FunctionK when you have an algebra in `Instrumentation[Kleisli[F, Span[F], *], *]` and you want
 * each method call on the algebra to create a new root span using the given `EntryPoint[F]`.
 *
 * Note that if you have an algebra `Alg[F]` for which an `Instrument[Alg]` exists, you can convert it
 * to `Alg[Instrumentation[Kleisli[F, Span[F], *], *]]` in two steps, using `Kleisli.liftK` and
 * `Instrument[Alg].instrument`:
 *
 * {{{
 *   import cats.data._, cats.effect._, cats.tagless.aop._, cats.tagless.syntax.all._, cats._
 *
 *   trait Foo[F[_]] {
 *     def foo: F[Unit]
 *   }
 *
 *   object Foo {
 *     implicit val fooInstrument: Instrument[Foo] = {
 *       // hand-written so this example compiles on 2.12 and 2.13 too, where
 *       // `derives` does not exist and `Derive` lives in cats-tagless-macros,
 *       // which `core` does not depend on. On Scala 3 the whole instance
 *       // collapses to `trait Foo[F[_]] derives Instrument` — that is
 *       // upstream's own derivation, supplied by
 *       // `object Instrument extends DerivedInstrument`, and needs nothing
 *       // from this library.
 *       new Instrument[Foo] {
 *         override def instrument[F[_]](af: Foo[F]): Foo[Instrumentation[F, *]] =
 *           new Foo[Instrumentation[F, *]] {
 *             override def foo: Instrumentation[F, Unit] =
 *               Instrumentation(af.foo, "Foo", "foo")
 *           }
 *
 *         override def mapK[F[_], G[_]](af: Foo[F])(fk: F ~> G): Foo[G] =
 *           new Foo[G] {
 *             override def foo: G[Unit] = fk(af.foo)
 *           }
 *       }
 *     }
 *   }
 *
 *   def myFoo: Foo[IO] = new Foo[IO] {
 *     def foo = IO.println("foo!")
 *   }
 *
 *   val instrumentedFoo: Foo[Instrumentation[Kleisli[IO, natchez.Span[IO], *], *]] =
 *     myFoo.mapK(Kleisli.liftK[IO, natchez.Span[IO]]).instrument
 * }}}
 *
 * @param entryPoint the Natchez `EntryPoint[F]` that will construct the new root spans
 */
class RootSpanProvidingFunctionK[F[_] : MonadCancelThrow](entryPoint: EntryPoint[F]) extends (Instrumentation[Kleisli[F, Span[F], *], *] ~> F) {
  override def apply[A](fa: Instrumentation[Kleisli[F, Span[F], *], A]): F[A] =
    entryPoint.root(s"${fa.algebraName}.${fa.methodName}").use(fa.value.run(_))
}
