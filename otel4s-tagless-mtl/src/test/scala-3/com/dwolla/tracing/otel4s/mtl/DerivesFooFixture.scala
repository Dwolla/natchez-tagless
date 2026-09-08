package com.dwolla.tracing.otel4s.mtl

import cats.Applicative
import cats.mtl.Raise
import cats.syntax.all.*

import scala.annotation.experimental

/** `Foo`'s twin, declared the way `AnyValueRaiseAspect` exists to make possible.
  *
  * Structurally identical to `Foo` (`FooRaiseFixture.scala`) — same parameter,
  * same error type (`FooError`, reused from that shared fixture rather than
  * redeclared), same implementation — so the two can be compared directly and
  * so the difference between a derived and a hand-written instance is the only
  * variable. It cannot simply reuse `Foo`: `Foo` lives in `src/test/scala`,
  * which is compiled on 2.12 and 2.13 as well, where a `derives` clause is a
  * syntax error.
  *
  * `@experimental` is required somewhere — `AnyValueRaiseAspect.derived` is
  * `@experimental` because `DeriveRaise.aspect` is, and on the 3.3.x LTS line
  * there is no `-experimental` flag to opt out with — and this fixture shows
  * the placement `AnyValueRaiseAspect.derived`'s scaladoc recommends: on the
  * '''companion object''', where the `derives` clause's synthesized given
  * actually lands, and not on the trait. Annotating the trait compiles too, but
  * makes the algebra type experimental and so viral to every reference to it.
  * A sibling `@experimental` definition elsewhere in the file is not enough.
  */
trait DerivesFoo[F[_]] derives AnyValueRaiseAspect:
  def foo(i: Int)(using R: Raise[F, FooError]): F[String]

@experimental
object DerivesFoo:
  def apply[F[_]: Applicative]: DerivesFoo[F] = new DerivesFoo[F]:
    def foo(i: Int)(using R: Raise[F, FooError]): F[String] =
      if (i < 0) R.raise(FooError.Negative(i)) else s"foo:$i".pure[F]
