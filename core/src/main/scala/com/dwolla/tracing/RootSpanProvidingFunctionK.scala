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
 *   import cats.data._, cats.effect._, cats.tagless._, cats.tagless.aop._, cats.tagless.syntax.all._
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
