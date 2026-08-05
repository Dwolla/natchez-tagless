package com.dwolla.tagless.mtl

import cats.tagless.aop.Aspect
import cats.~>

import scala.collection.mutable.ListBuffer

/** One `Aspect.Weave` as the interpreter received it.
  *
  * The result type is held as a type member rather than a wildcard so this
  * file compiles identically on 2.12, 2.13 and 3 — `Weave` is invariant in its
  * last parameter, so a `List[Weave[F, Dom, Cod, _]]` would need either an
  * existential or a cast.
  */
sealed trait RecordedWeave[F[_], Dom[_], Cod[_]] {
  type A
  def weave: Aspect.Weave[F, Dom, Cod, A]
}

object RecordedWeave {
  def apply[F[_], Dom[_], Cod[_], A0](w: Aspect.Weave[F, Dom, Cod, A0]): RecordedWeave[F, Dom, Cod] =
    new RecordedWeave[F, Dom, Cod] {
      type A = A0
      val weave: Aspect.Weave[F, Dom, Cod, A0] = w
    }
}

/** A `Weave ~> F` that records what it is handed and then behaves exactly like
  * `WeaveArrows.codomainTarget`.
  *
  * `record` lets a test's `OnRaise` hook append to the same log, so one buffer
  * holds both kinds of event in the order they happened.
  */
final class RecordingFk[F[_], Dom[_], Cod[_]] {
  private val recorded = ListBuffer.empty[RecordedWeave[F, Dom, Cod]]
  private val log = ListBuffer.empty[String]

  val fk: Aspect.Weave[F, Dom, Cod, *] ~> F =
    new (Aspect.Weave[F, Dom, Cod, *] ~> F) {
      // Bare appends rather than the `val _ =` idiom: 2.12 treats `_` as a real
      // value name, so only one `val _` may appear per block. These are method
      // calls, not pure expressions, so they warn under neither axis.
      def apply[A](w: Aspect.Weave[F, Dom, Cod, A]): F[A] = {
        recorded += RecordedWeave(w)
        log += s"weave:${w.algebraName}.${w.codomain.name}"
        w.codomain.target
      }
    }

  def record(event: String): Unit = {
    val _ = log += event
  }

  def weaves: List[RecordedWeave[F, Dom, Cod]] = recorded.toList

  def events: List[String] = log.toList
}
