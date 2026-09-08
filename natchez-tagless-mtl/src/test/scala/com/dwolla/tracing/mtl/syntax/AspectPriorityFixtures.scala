package com.dwolla.tracing.mtl
package syntax

import cats.effect.IO
import cats.tagless.aop.Aspect
import cats.tagless.aop.Aspect.Weave
import cats.{FlatMap, ~>}
import com.dwolla.tagless.mtl.{OnRaise, RaiseArrow, RaiseAspect}
import natchez.TraceableValue

/** An algebra with both an `Aspect` and a `RaiseAspect` instance in scope,
  * deliberately divergent so that using the wrong one is immediately
  * observable — the `RaiseAspect` instance throws if it is ever invoked, rather than
  * silently producing plausible-looking wrong output.
  */
trait Foo[F[_]] {
  def foo(i: Int): F[String]
}

object Foo {
  def io: Foo[IO] = new Foo[IO] {
    def foo(i: Int): IO[String] = IO.pure(s"foo:$i")
  }

  /** The correct, hand-written `Aspect` instance — the one priority must select. */
  implicit val fooAspect: Aspect[Foo, TraceableValue, TraceableValue] =
    new Aspect[Foo, TraceableValue, TraceableValue] {
      def weave[F[_]](af: Foo[F]): Foo[Weave[F, TraceableValue, TraceableValue, *]] =
        new Foo[Weave[F, TraceableValue, TraceableValue, *]] {
          def foo(i: Int): Weave[F, TraceableValue, TraceableValue, String] =
            Weave("Foo", List(List(Aspect.Advice.byValue("i", i))), Aspect.Advice("foo", af.foo(i)))
        }

      def mapK[F[_], G[_]](af: Foo[F])(fk: F ~> G): Foo[G] =
        new Foo[G] {
          def foo(i: Int): G[String] = fk(af.foo(i))
        }
    }

  /** The poison instance: throws immediately if `intercept` or `mapK` is ever invoked,
    * so priority resolving to this instance fails the test loudly rather than producing
    * a subtly wrong span name.
    */
  implicit val fooRaiseAspectPoison: RaiseAspect[Foo, TraceableValue, TraceableValue, TraceableValue] =
    new RaiseAspect[Foo, TraceableValue, TraceableValue, TraceableValue] {
      def intercept[F[_]](af: Foo[F])(
          fk: Weave[F, TraceableValue, TraceableValue, *] ~> F,
          onRaise: OnRaise[F, TraceableValue]
      )(implicit F: FlatMap[F]): Foo[F] =
        throw new AssertionError("priority resolved to RaiseAspect instead of Aspect")

      def mapK[F[_], G[_]](af: Foo[F])(arrow: RaiseArrow[F, G, TraceableValue]): Foo[G] =
        throw new AssertionError("priority resolved to RaiseAspect instead of Aspect")
    }
}
