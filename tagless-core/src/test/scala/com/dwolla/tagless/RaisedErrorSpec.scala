package com.dwolla.tagless

import cats.effect.IO
import cats.mtl.Handle
import munit.CatsEffectSuite

/** A domain error that is not a `Throwable`, as raised errors usually aren't. */
final class NotFound(val id: Int)

/** Same simple name and shape as cats-mtl's private class, different package. */
final case class Submarine(e: Any, marker: AnyRef) extends RuntimeException

class RaisedErrorSpec extends CatsEffectSuite {
  test("unwraps the domain error from the exception cats-mtl's Handle.allowF raises") {
    Handle.allowF[IO, NotFound] { h =>
      h.raise[NotFound, Unit](new NotFound(42)).attempt.map {
        case Left(RaisedError(error: NotFound)) => assertEquals(error.id, 42)
        case other => fail(s"expected cats-mtl's Submarine carrying NotFound(42), got $other")
      }
    }.rescue(_ => IO.unit)
  }

  test("leaves ordinary throwables alone") {
    assertEquals(RaisedError.unapply(new RuntimeException("boom")), None)
  }

  test("leaves a look-alike exception in another package alone") {
    assertEquals(RaisedError.unapply(Submarine(new NotFound(1), new AnyRef)), None)
  }
}
