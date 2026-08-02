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
  * `@experimental` is required, and it is required on the algebra itself (or an
  * enclosing scope) rather than merely somewhere in the file:
  * `TraceableRaiseAspect.derived` is `@experimental` because
  * `DeriveRaise.aspect` is, and on the 3.3.x LTS line there is no
  * `-experimental` flag to opt out with. The pre-M13 spelling needed the same
  * annotation on the companion's `implicit val`, so nothing is lost here.
  */
@experimental
trait DerivesBar[F[_]] derives TraceableRaiseAspect:
  def bar(i: Int)(using R: Raise[F, BarError]): F[String]

@experimental
object DerivesBar:
  def apply[F[_]: Applicative]: DerivesBar[F] = new DerivesBar[F]:
    def bar(i: Int)(using R: Raise[F, BarError]): F[String] =
      if (i < 0) R.raise(BarError.Negative(i)) else s"bar:$i".pure[F]
