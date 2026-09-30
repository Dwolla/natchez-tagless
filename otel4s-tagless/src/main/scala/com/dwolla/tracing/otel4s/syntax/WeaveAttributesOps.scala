package com.dwolla.tracing.otel4s
package syntax

import cats.tagless.aop.Aspect.Weave
import com.dwolla.tagless.WeaveNaming._
import org.typelevel.otel4s.{AnyValue, Attribute, Attributes}

trait ToWeaveAttributesOps {
  implicit def toWeaveAttributesOps[F[_], Cod[_], A](fa: Weave[F, ToAnyValue, Cod, A]): WeaveAttributesOps[F, Cod, A] =
    new WeaveAttributesOps(fa)
}

class WeaveAttributesOps[F[_], Cod[_], A](val fa: Weave[F, ToAnyValue, Cod, A]) extends AnyVal {
  /** All of the call's parameters, from every parameter list, as exactly one
    * attribute named `algebraName.methodName.parameters` whose value is an
    * `AnyValue` map keyed by parameter name — '''or no attribute at all''' if
    * the method takes no parameters. `ToAnyValue` itself stays a total
    * `A => AnyValue`: a parameter that encodes to nothing is still a kept
    * `name -> AnyValue.empty` entry, never a missing one, so a map with at
    * least one parameter is always recorded, empty-valued entries and all.
    * Only a map with '''zero''' entries — a method with no parameters at all —
    * is omitted, because the interpreter can see, locally, that there is
    * nothing worth an attribute slot.
    *
    * One attribute rather than one per parameter is deliberate: a structured
    * map counts once against `SpanLimits.maxNumberOfAttributes` (default 128),
    * so a twenty-parameter method costs one slot instead of twenty, and
    * `maxAttributeValueLength` still recurses into the tree, so nothing escapes
    * truncation by being nested.
    *
    * Flattening the parameter lists cannot lose a parameter: Scala rejects
    * duplicate parameter names within a method signature, including across
    * parameter lists.
    *
    * The `parameters` local is typed `AnyValue` on purpose — `AnyValue.map`
    * returns the precise subtype `AnyValue.MapValue`, and `KeySelect` is
    * invariant, so `Attribute(name, AnyValue.map(...))` does not compile. The
    * `@implicitNotFound` message you get instead lists only the eight flat
    * types and never mentions `AnyValue`, so the fix is an ascription, not a
    * new `KeySelect`.
    */
  def asAttributes: Attributes = {
    val entries: Map[String, AnyValue] =
      fa.domain.flatten.map { advice =>
        // Verbose keys, but the OpenTelemetry attribute-naming spec says to
        // namespace everything:
        // https://opentelemetry.io/docs/specs/semconv/general/attribute-naming/
        advice.name -> advice.instance.toAnyValue(advice.target.value)
      }.toMap

    if (entries.isEmpty) Attributes.empty
    else {
      val parameters: AnyValue = AnyValue.map(entries)
      Attributes(Attribute(s"${fa.qualifiedMethodName}.parameters", parameters))
    }
  }
}
