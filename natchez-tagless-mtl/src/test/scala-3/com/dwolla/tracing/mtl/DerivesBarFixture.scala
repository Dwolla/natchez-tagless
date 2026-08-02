package com.dwolla.tracing.mtl

import cats.Applicative
import cats.mtl.Raise
import cats.syntax.all.*

import scala.annotation.experimental

/** `Bar`'s twin, declared the way M13 exists to make possible.
  *
  * Structurally identical to `Bar` (`BarFixture.scala`) — same parameter, same
  * error type, same implementation — so the two can be compared directly and so
  * the expected span history differs from the existing suites' only in the
  * algebra name. It cannot simply reuse `Bar`: `Bar` lives in
  * `src/test/scala`, which is compiled on 2.12 and 2.13 as well, where a
  * `derives` clause is a syntax error.
  *
  * `@experimental` is required somewhere — `TraceableRaiseAspect.derived` is
  * `@experimental` because `DeriveRaise.aspect` is, and on the 3.3.x LTS line
  * there is no `-experimental` flag to opt out with — and this fixture shows
  * the placement `TraceableRaiseAspect.derived`'s scaladoc recommends: on the
  * '''companion object''', where the `derives` clause's synthesized given
  * actually lands, and not on the trait. Annotating the trait compiles too, but
  * makes the algebra type experimental and so viral to every reference to it.
  * A sibling `@experimental` definition elsewhere in the file is not enough.
  */
trait DerivesBar[F[_]] derives TraceableRaiseAspect:
  def bar(i: Int)(using R: Raise[F, BarError]): F[String]

@experimental
object DerivesBar:
  def apply[F[_]: Applicative]: DerivesBar[F] = new DerivesBar[F]:
    def bar(i: Int)(using R: Raise[F, BarError]): F[String] =
      if (i < 0) R.raise(BarError.Negative(i)) else s"bar:$i".pure[F]
