package com.dwolla.tracing.mtl
package syntax

import com.dwolla.tagless.ErrorTypeName
import com.dwolla.tagless.mtl.{DefaultOnRaise, OnRaise, RaiseRecorder}
import natchez.{Trace, TraceableValue}

/** Natchez's fallback [[com.dwolla.tagless.mtl.DefaultOnRaise]] instance:
  * records the typed error's runtime class name and its `TraceableValue`
  * rendering as span fields, under a `raise.*` key prefix deliberately
  * distinct from the `exception.*` fields a backend derives from
  * `attachError` — those still receive cats-mtl's opaque `Submarine` wrapper
  * on an unhandled raise.
  */
trait NatchezDefaultOnRaise {
  implicit def natchezDefaultOnRaise[F[_]](implicit T: Trace[F]): DefaultOnRaise[F, TraceableValue] =
    new DefaultOnRaise[F, TraceableValue] {
      def onRaise: OnRaise[F, TraceableValue] = new OnRaise[F, TraceableValue] {
        def apply[E](e: E)(implicit ev: TraceableValue[E]): F[Unit] =
          T.put(
            RaiseRecorder.ErrorTypeKey -> ErrorTypeName(e),
            RaiseRecorder.ErrorValueKey -> ev.toTraceValue(e)
          )
      }
    }
}
