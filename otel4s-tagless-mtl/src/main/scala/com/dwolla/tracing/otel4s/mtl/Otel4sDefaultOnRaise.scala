package com.dwolla.tracing.otel4s.mtl

import cats.FlatMap
import cats.syntax.all._
import com.dwolla.tagless.mtl.{DefaultOnRaise, OnRaise, RaiseRecorder}
import com.dwolla.tracing.otel4s.ToAnyValue
import org.typelevel.otel4s.{AnyValue, Attribute, Attributes}
import org.typelevel.otel4s.trace.Tracer

/** otel4s's fallback [[com.dwolla.tagless.mtl.DefaultOnRaise]]: records the
  * typed error's runtime class name and its `ToAnyValue` rendering as
  * attributes on the ''current'' span, the direct analogue of natchez's
  * `Trace[F].put`.
  *
  * '''Why `Tracer[F].currentSpanOrNoop` rather than being handed the span.'''
  * `OnRaise` resolves independently of the interpreter and fires
  * inside the method body — inside `fa.codomain.target`, which the
  * interpreter does not wrap — so the hook cannot be handed the `Span` the
  * interpreter is holding. `currentSpanOrNoop` is not a shortcut here; it is
  * the only route.
  *
  * '''Why that finds the right span.''' `TracerWeaveCapturingInputs`/
  * `AndOutputs` build the span with `.use`, and `SpanOps#use` makes the span
  * current for the duration of the body — its lifted implementation is
  * `resource.use { res => res.trace(f(res.span)) }`, and `res.trace` is what
  * otel4s documents as propagating span context.
  *
  * '''Why `.backend`.''' Same reason as
  * `TracerWeaveCapturingInputsAndOutputs`'s own `.backend.addAttributes` call:
  * `Span#addAttributes` is a macro on Scala 2 and inline on Scala 3, and
  * `Span.Backend#addAttributes` is the sealed method underneath it.
  *
  * '''Known limitation.''' A method that raises, rescues internally via
  * `Handle.allow`, and raises again fires this hook twice against the same
  * span: there is no accumulation across two raises within one method call.
  * The second call always overwrites `raise.error.type`, but it overwrites
  * `raise.error.value` only when the second error renders non-empty — the
  * omit-when-empty rule below means a second error rendering to
  * `AnyValue.empty` writes no value key, leaving the ''first'' raise's value
  * standing beside the ''second'' raise's type.
  *
  * The instance requires `Tracer[F]` and `FlatMap[F]`; under `Tracer.noop`,
  * `currentSpanOrNoop` yields a noop span whose `addAttributes` does nothing,
  * so a disabled tracer costs nothing.
  */
trait Otel4sDefaultOnRaise {
  implicit def otel4sDefaultOnRaise[F[_] : FlatMap : Tracer]: DefaultOnRaise[F, ToAnyValue] =
    new DefaultOnRaise[F, ToAnyValue] {
      def onRaise: OnRaise[F, ToAnyValue] = new OnRaise[F, ToAnyValue] {
        def apply[E](e: E)(implicit ev: ToAnyValue[E]): F[Unit] = {
          val value: AnyValue = ev.toAnyValue(e)

          // raise.error.value is omitted when it would encode to
          // AnyValue.empty, matching the omit-when-empty rule this module
          // already applies to parameters and return values.
          val attributes: Attributes =
            Attributes(Attribute(RaiseRecorder.ErrorTypeKey, e.getClass.getName)) ++ (
              if (value == AnyValue.empty) Attributes.empty
              else Attributes(Attribute(RaiseRecorder.ErrorValueKey, value))
            )

          Tracer[F].currentSpanOrNoop.flatMap(_.backend.addAttributes(attributes))
        }
      }
    }
}

object Otel4sDefaultOnRaise extends Otel4sDefaultOnRaise
