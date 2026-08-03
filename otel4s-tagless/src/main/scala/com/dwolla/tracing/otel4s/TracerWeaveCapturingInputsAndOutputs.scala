package com.dwolla.tracing.otel4s

import cats.FlatMap
import cats.syntax.all._
import cats.tagless.aop.Aspect.Weave
import cats.~>
import com.dwolla.tracing.otel4s.syntax._
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.{AnyValue, Attribute, Attributes}

object TracerWeaveCapturingInputsAndOutputs {
  def apply[F[_]: FlatMap: Tracer]: Weave[F, ToAnyValue, ToAnyValue, *] ~> F =
    new TracerWeaveCapturingInputsAndOutputs[F]
}

/**
 * Use this `FunctionK` when you have an algebra in
 * `Weave[F, ToAnyValue, ToAnyValue, *]` and you want each method call on the
 * algebra to introduce a new child span, using the ambient `Tracer[F]`. Each
 * child span is named using the algebra name and method name captured in the
 * `Weave`, and both the parameters given to the method call and its return
 * value are attached to the span.
 *
 * '''A call records at most two attributes''', and these are their names:
 *
 *  - `<algebraName>.<methodName>.parameters` — an `AnyValue` map keyed by
 *    parameter name, holding every parameter of every parameter list.
 *  - `<algebraName>.<methodName>.returnValue` — the encoded return value.
 *
 * `returnValue` is spelled exactly as `com.dwolla.tracing`'s natchez
 * interpreter spells it, so a query written against a natchez-instrumented
 * service keeps working after a migration to otel4s. One structured
 * `parameters` attribute instead of one attribute per parameter is deliberate:
 * it costs one slot against `SpanLimits.maxNumberOfAttributes` (default 128)
 * where twenty flat attributes would cost twenty, and
 * `maxAttributeValueLength` still recurses into the tree, so nothing escapes
 * truncation by being nested.
 *
 * '''Either attribute is omitted outright when its value would carry
 * nothing''', rather than being recorded empty. A method with no parameters
 * gets no `parameters` attribute, and a `Unit`-returning method — or one whose
 * return type happens to encode to `AnyValue.empty` — gets no `returnValue`
 * attribute; a zero-parameter, `Unit`-returning method therefore produces a
 * span with no attributes at all. `ToAnyValue` is unaffected by this: it stays
 * a total `A => AnyValue`, `ToAnyValue[Unit]` still encodes `()` to
 * `AnyValue.empty`, and a parameter that encodes to nothing is still a kept
 * `name -> AnyValue.empty` ''entry'' inside the map. Only a whole top-level
 * attribute is worth suppressing, because only a whole attribute costs a slot.
 *
 * Both attributes are handed to otel4s as `AnyValue`, but on the
 * `otel4s-oteljava` backend `returnValue` usually does not ''arrive'' as one.
 * The OpenTelemetry Java SDK documents that
 * `AttributesBuilder#put(AttributeKey, Object)` narrows an
 * `AttributeType.VALUE` whose `Value` has a simple equivalent, so a
 * `String`-valued return lands as a plain `STRING` attribute, a `Long`-valued
 * one as `LONG`, and so on. That is worth knowing when writing queries, and it
 * is the better outcome: backends index simple attributes. The `parameters`
 * map has no simple equivalent, so it stays a structured value.
 *
 * There is an asymmetry with `TracerWeaveCapturingInputs` worth knowing about.
 * That interpreter encodes nothing under a noop `Tracer`, because
 * `SpanBuilder#modifyState` returns `this` without applying its function. This
 * one still encodes the ''return value'' under a noop `Tracer`, because
 * `SpanOps#use` does apply its function — `Tracer.noop`'s `build.use(f)` is
 * `f(span)`. The resulting `addAttributes` is a no-op, so nothing is recorded,
 * but `toAnyValue` does run. Keep that in mind if an instance is expensive:
 * disabling tracing does not make it free the way it does for parameters.
 *
 * Also inherited from `TracerInstrumentation`: when a `Throwable` escapes the
 * traced effect, otel4s marks the span as errored on its own.
 * `SpanBuilder`'s default finalization strategy is
 * `SpanFinalizer.Strategy.reportAbnormal`, which records the exception and sets
 * the span status for an error or a cancelation. natchez does no such thing, so
 * a natchez span that fails looks the same as one that succeeded unless
 * something else records the failure. Note that a failed call records no
 * `returnValue` attribute, because there is no return value.
 *
 * The format of the attribute values is controlled by the `ToAnyValue`
 * typeclass. If a parameter or return value is sensitive, one way to keep the
 * sensitive value out of the trace is to give it a newtype and hand-write a
 * redacted `ToAnyValue[Newtype]` instance. For example, using
 * `io.monix::newtypes-core`:
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
 * Note if you have an algebra `Alg[F]` for which an
 * `Aspect[Alg, ToAnyValue, ToAnyValue]` exists, it can be converted to
 * `Alg[Weave[F, ToAnyValue, ToAnyValue, *]]` using
 * `Aspect[Alg, ToAnyValue, ToAnyValue].weave`, and turned back into an `Alg[F]`
 * with `.mapK(TracerWeaveCapturingInputsAndOutputs[F])`. Unlike
 * `TracerWeaveCapturingInputs`, this interpreter reads the codomain's
 * `ToAnyValue` instance, so `Aspect.Domain[Alg, ToAnyValue]` — which pins the
 * codomain to `Trivial` — is not enough; the algebra needs `ToAnyValue`
 * instances for its return types too.
 *
 * `FlatMap[F]` is the constraint, matching the natchez version: the return
 * value has to be sequenced after the call that produced it, but nothing here
 * needs `pure`.
 */
class TracerWeaveCapturingInputsAndOutputs[F[_]: FlatMap: Tracer]
  extends (Weave[F, ToAnyValue, ToAnyValue, *] ~> F) {

  override def apply[A](fa: Weave[F, ToAnyValue, ToAnyValue, A]): F[A] = {
    val name = s"${fa.algebraName}.${fa.codomain.name}"

    Tracer[F]
      .spanBuilder(name)
      // asAttributes stays *inside* this lambda. Tracer.noop's modifyState
      // never applies the function, so a disabled tracer pays nothing for
      // encoding the parameters — and by-name parameters are never forced.
      .modifyState(_.addAttributes(fa.asAttributes))
      .build
      // `use`, not `surround`: otel4s has no ambient `Tracer[F].put`, so the
      // only way to attach an attribute after the call is to hold the Span.
      // `flatTap` so the underlying target is read exactly once.
      .use { span =>
        fa.codomain.target.flatTap { out =>
          // Typed AnyValue on purpose: see ToAnyValue's scaladoc. It is also
          // what makes `Attribute(name, returnValue)` resolve KeySelect, which
          // is invariant and whose @implicitNotFound message never mentions
          // AnyValue.
          val returnValue: AnyValue = fa.codomain.instance.toAnyValue(out)

          // An empty return value (Unit, or any type that happens to encode to
          // AnyValue.empty) omits the attribute entirely rather than recording
          // EmptyValue. ToAnyValue is unchanged — this check is local to the
          // interpreter, exactly as asAttributes' zero-parameter check is.
          val attributes: Attributes =
            if (returnValue == AnyValue.empty) Attributes.empty
            else Attributes(Attribute(s"$name.returnValue", returnValue))

          // `.backend` deliberately: Span#addAttributes is a macro on Scala 2
          // and inline on Scala 3, and Span.Backend#addAttributes is the sealed
          // method underneath it. Attributes is already an
          // immutable.Iterable[Attribute[_]], so it passes straight through,
          // and Attributes.empty is a no-op iterable when there is nothing to
          // add.
          span.backend.addAttributes(attributes)
        }
      }
  }
}
