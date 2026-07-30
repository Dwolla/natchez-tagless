package com.dwolla.tracing.mtl
package syntax

import cats.effect.IO
import com.dwolla.tagless.mtl.OnRaise

/** Task 3's fixture: a poison `OnRaise[F]` instance, deliberately divergent from
  * [[RaiseRecorder.fromTrace]]'s default so that priority resolving to the wrong
  * instance is immediately observable — it throws if it is ever invoked, rather
  * than silently recording plausible-looking wrong span fields.
  */
object RaiseRecorderPriorityFixtures {
  implicit val poisonOnRaise: OnRaise[IO] =
    new OnRaise[IO] {
      def apply[E](e: E): IO[Unit] =
        throw new AssertionError("priority resolved to the Trace-derived default instead of the user-supplied OnRaise")
    }
}
