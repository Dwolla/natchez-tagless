package com.dwolla.tracing.mtl
package syntax

import cats.effect.IO
import com.dwolla.tagless.mtl.OnRaise
import natchez.TraceableValue

/** A poison `OnRaise[F, Err]` instance, deliberately divergent from
  * [[NatchezDefaultOnRaise.natchezDefaultOnRaise]]'s default so that priority
  * resolving to the wrong instance is immediately observable — it throws if it is
  * ever invoked, rather than silently recording plausible-looking wrong span fields.
  */
object RaiseRecorderPriorityFixtures {
  implicit val poisonOnRaise: OnRaise[IO, TraceableValue] =
    new OnRaise[IO, TraceableValue] {
      def apply[E](e: E)(implicit ev: TraceableValue[E]): IO[Unit] =
        throw new AssertionError("priority resolved to the Trace-derived default instead of the user-supplied OnRaise")
    }

  /** A companion-placement counter-example. `OnRaise[IO, TraceableValue]`
    * never mentions `CompanionPoisonError`, so implicit search for that type — the
    * companions of `OnRaise`, `IO`, and `TraceableValue` only — never looks inside
    * this companion object, no matter what error value is later raised. The poison
    * instance below is compiled and on the classpath, but deliberately never
    * imported anywhere, to prove it is unreachable rather than merely unused.
    */
  final case class CompanionPoisonError(message: String)

  object CompanionPoisonError {
    // This one *is* found — TraceableValue[CompanionPoisonError] mentions
    // CompanionPoisonError, so its companion is part of implicit scope for it.
    // Needed so `.onRaise(CompanionPoisonError(...))` typechecks at all; its
    // presence is exactly the contrast the poisonOnRaise below is meant to show.
    implicit val traceableValue: TraceableValue[CompanionPoisonError] =
      e => natchez.TraceValue.StringValue(e.message)

    implicit val poisonOnRaise: OnRaise[IO, TraceableValue] =
      new OnRaise[IO, TraceableValue] {
        def apply[E](e: E)(implicit ev: TraceableValue[E]): IO[Unit] =
          throw new AssertionError("priority resolved to an OnRaise reachable only via an error ADT's companion object")
      }
  }
}
