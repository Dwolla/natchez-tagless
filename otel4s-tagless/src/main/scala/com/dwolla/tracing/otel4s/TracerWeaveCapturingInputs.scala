package com.dwolla.tracing.otel4s

import cats.tagless.aop.Aspect.Weave
import cats.~>
import com.dwolla.tracing.otel4s.syntax._
import org.typelevel.otel4s.trace.Tracer

object TracerWeaveCapturingInputs {
  def apply[F[_]: Tracer, Cod[_]]: Weave[F, ToAnyValue, Cod, *] ~> F =
    new TracerWeaveCapturingInputs[F, Cod]
}

/**
 * Use this `FunctionK` when you have an algebra in
 * `Weave[F, ToAnyValue, Cod, *]` and you want each method call on the algebra
 * to introduce a new child span, using the ambient `Tracer[F]`. Each child span
 * is named using the algebra name and method name captured in the `Weave`, and
 * the parameters given to the method call are attached to the span.
 *
 * This is the otel4s counterpart of
 * `com.dwolla.tracing.TraceWeaveCapturingInputs`, and it differs from it in
 * four ways that are easy to assume away:
 *
 *  1. '''No `Apply[F]`.''' The natchez version needs one to sequence
 *     `Trace[F].put(…) *> target`. Here the attributes go onto the
 *     `SpanBuilder`, so there is nothing to sequence, and `Tracer[F]` is the
 *     only constraint.
 *  1. '''The input attributes exist at span start''', not merely after it, so a
 *     sampler can see them. otel4s samples at span start and warns that
 *     renaming a span afterwards has implementation-defined sampling effects.
 *  1. '''One structured attribute, not one per parameter.''' Every parameter of
 *     every parameter list lands in a single `AnyValue` map recorded as
 *     `algebraName.methodName.parameters`, which costs one slot against
 *     `SpanLimits.maxNumberOfAttributes` (default 128) where twenty flat
 *     attributes would cost twenty. `maxAttributeValueLength` still recurses
 *     into the tree, so nothing escapes truncation by being nested.
 *  1. '''No attribute at all when there are no parameters.''' A zero-parameter
 *     method records no `parameters` attribute, rather than one holding an
 *     empty map. `ToAnyValue` stays a total encoder — an absent parameter is a
 *     kept `name -> AnyValue.empty` entry — but a map with zero entries is not
 *     worth an attribute slot, so `asAttributes` omits the whole attribute.
 *
 * `asAttributes` is called ''inside'' the `modifyState` lambda deliberately.
 * `Tracer.noop`'s `SpanBuilder#modifyState` returns `this` without applying the
 * function, so an application with tracing disabled never encodes a parameter
 * at all, and by-name parameters are never forced. Hoisting the call out to a
 * `val` would quietly give that up.
 *
 * The format of the attribute values is controlled by the `ToAnyValue`
 * typeclass. If a parameter is sensitive, one way to keep the sensitive value
 * out of the trace is to give the parameter a newtype and hand-write a redacted
 * `ToAnyValue[Newtype]` instance. For example, using `io.monix::newtypes-core`:
 *
 * {{{
 *   import monix.newtypes._
 *   import org.typelevel.otel4s.AnyValue
 *   import com.dwolla.tracing.otel4s.ToAnyValue
 *
 *   type Password = Password.Type
 *
 *   object Password extends NewtypeWrapped[String] {
 *     implicit val PasswordToAnyValue: ToAnyValue[Password] = new ToAnyValue[Password] {
 *       override def toAnyValue(a: Password): AnyValue = AnyValue.string("redacted password value")
 *     }
 *   }
 * }}}
 *
 * With that instance the span records `"redacted password value"` and never the
 * actual value. Similar functionality can be achieved with the newtype library
 * of your choice.
 *
 * `TracerWeaveCapturingInputs` ignores the codomain (i.e. output) type, so your
 * algebra could have an `Aspect.Domain[Alg, ToAnyValue]` (equivalent to
 * `Aspect[Alg, ToAnyValue, Trivial]`), `Aspect[Alg, ToAnyValue, ToAnyValue]`,
 * or really any other typeclass in the `Cod[_]` position, and still work with
 * this transformation.
 *
 * Note if you have an algebra `Alg[F]` for which an
 * `Aspect.Domain[Alg, ToAnyValue]` exists, it can be converted to
 * `Alg[Weave.Domain[F, ToAnyValue, *]]` using
 * `Aspect.Domain[Alg, ToAnyValue].weave`, and turned back into an `Alg[F]` with
 * `.mapK(TracerWeaveCapturingInputs[F, Cod])`.
 */
class TracerWeaveCapturingInputs[F[_]: Tracer, Cod[_]] extends (Weave[F, ToAnyValue, Cod, *] ~> F) {
  override def apply[A](fa: Weave[F, ToAnyValue, Cod, A]): F[A] =
    Tracer[F]
      .spanBuilder(s"${fa.algebraName}.${fa.codomain.name}")
      // asAttributes stays *inside* this lambda. Tracer.noop's modifyState
      // never applies the function, so a disabled tracer pays nothing for
      // encoding — and by-name parameters are never forced.
      .modifyState(_.addAttributes(fa.asAttributes))
      .build
      .surround(fa.codomain.target)
}
