package com.dwolla.tracing.otel4s

import cats.Show
import cats.syntax.all._
import org.typelevel.otel4s.AnyValue

/** Converts a value of type `A` into an otel4s `AnyValue`, OpenTelemetry's
  * recursive "any" type: a primitive leaf, a sequence, or a key-value map.
  *
  * This is the otel4s counterpart of `natchez.TraceableValue`, and it exists
  * because otel4s ships nothing of the right shape. `Attribute.From` and
  * `Attribute.Make` both take two type parameters. `Attributes.Make` takes one,
  * but it bakes the attribute ''name'' into the instance and upstream ships
  * essentially no instances for it — the Scala 2 `MakeCompanion` is empty.
  *
  * The name does not appear in this signature at all, and that is the design:
  * a method's parameters are recorded as '''one''' attribute,
  * `<algebraName>.<methodName>.parameters`, whose value is an `AnyValue` map
  * keyed by parameter name; the return value is recorded as
  * `<algebraName>.<methodName>.returnValue`. Names come from the `Aspect`'s
  * `Advice`, at the call site, where they belong.
  *
  * '''The result type is `AnyValue`, never one of its subtypes.'''
  * `AnyValue.map(...)` is typed at `AnyValue.MapValue`, and
  * `AttributeKey.KeySelect` is invariant, so `Attribute("k", AnyValue.map(...))`
  * does not compile — and the compiler's message lists only the eight flat
  * types and never mentions `AnyValue`, which makes it actively misleading.
  * Widening anywhere fixes it; declaring this method's result as `AnyValue` is
  * what keeps callers from ever seeing it.
  *
  * '''Requires `opentelemetry-api` 1.59.0 or newer on the `otel4s-oteljava`
  * backend.''' Structured attribute values reach the OpenTelemetry Java SDK
  * through `io.opentelemetry.api.common.AttributeType.VALUE`, which was added
  * in release 1.59.0. otel4s 1.0.1 pulls a newer version transitively, so the
  * default is fine; an application that pins an older SDK will fail to link
  * `AttributeKey.valueKey` inside otel4s's own converter. This module declares
  * no dependency on the Java SDK and cannot enforce the floor for you.
  *
  * Contravariant because `A` occurs only in negative position, matching
  * otel4s's own `Attributes.Make[-A]`; that is what lets a `List[String]` or
  * `Vector[Long]` parameter resolve the generic `Seq` instance.
  *
  * '''What the `otel4s-oteljava` backend does to the encoded value.''' This
  * module always hands otel4s an `AnyValue`, but the OpenTelemetry Java SDK
  * narrows a `VALUE`-typed attribute on the way out — see
  * `TracerWeaveCapturingInputsAndOutputs`'s scaladoc and the module README for
  * the full table and the two consequences that surprise people. In short: a
  * scalar leaf, and a non-empty homogeneous sequence of scalar leaves, arrive
  * as ordinary typed attributes; a map, a byte array, an empty sequence and a
  * heterogeneous one stay structured.
  *
  * '''A type with no instance is a compile error''' at the point the `Aspect`
  * is derived. In practice the `Show` fallback means nearly everything has one,
  * so the likelier failure is silence: a domain type records its `Show`
  * rendering when a structured value was wanted. Write the instance you want
  * and it wins, because the companion's own instances outrank the fallback.
  *
  * {{{
  *   import com.dwolla.tracing.otel4s.ToAnyValue
  *   import org.typelevel.otel4s.AnyValue
  *
  *   // BigDecimal and BigInt ship no instance of their own, so they reach the
  *   // Show fallback and record as strings — where natchez's TraceableValue
  *   // accepted them as numbers. This is the silent case above.
  *   val viaShow: AnyValue = ToAnyValue[BigDecimal].toAnyValue(BigDecimal("1.50"))
  *
  *   // A hand-written instance is how a sensitive or badly-Shown type is kept
  *   // out of the trace, and it outranks the fallback. `instance` exists so
  *   // the result type is AnyValue and never one of its subtypes.
  *   final class Password(val value: String)
  *
  *   implicit val passwordToAnyValue: ToAnyValue[Password] =
  *   ToAnyValue.instance[Password](_ => AnyValue.string("redacted"))
  *
  *   val redacted: AnyValue = ToAnyValue[Password].toAnyValue(new Password("hunter2"))
  *
  *   // Option.empty[String], never a bare `None`. `ToAnyValue` is
  *   // contravariant and `None`'s type is `None.type`, so the search for the
  *   // element instance is a *diverging* implicit expansion rather than a
  *   // clean miss, and the error names `mapToAnyValue` instead of the Option.
  *   // The same goes for `Nil`: write `List.empty[String]`.
  *   val absent: AnyValue = ToAnyValue[Option[String]].toAnyValue(Option.empty[String])
  *
  *   // the generic Seq and Map instances nest to any depth
  *   val nested: AnyValue = ToAnyValue[List[List[Int]]].toAnyValue(List(List(1, 2)))
  *   val keyed: AnyValue = ToAnyValue[Map[String, Int]].toAnyValue(Map("k" -> 1))
  * }}}
  */
trait ToAnyValue[-A] {
  def toAnyValue(a: A): AnyValue
}

object ToAnyValue extends LowPriorityToAnyValueInstances {
  def apply[A](implicit ev: ToAnyValue[A]): ToAnyValue[A] = ev

  def instance[A](f: A => AnyValue): ToAnyValue[A] =
    new ToAnyValue[A] {
      override def toAnyValue(a: A): AnyValue = f(a)
    }

  implicit val stringToAnyValue: ToAnyValue[String] = instance[String](AnyValue.string)
  implicit val booleanToAnyValue: ToAnyValue[Boolean] = instance[Boolean](AnyValue.boolean)
  implicit val longToAnyValue: ToAnyValue[Long] = instance[Long](AnyValue.long)
  implicit val doubleToAnyValue: ToAnyValue[Double] = instance[Double](AnyValue.double)

  // Long is otel4s's only integral leaf; Int, Short and Byte widen exactly.
  implicit val intToAnyValue: ToAnyValue[Int] = instance[Int](i => AnyValue.long(i.toLong))
  implicit val shortToAnyValue: ToAnyValue[Short] = instance[Short](s => AnyValue.long(s.toLong))
  implicit val byteToAnyValue: ToAnyValue[Byte] = instance[Byte](b => AnyValue.long(b.toLong))

  /** otel4s has no `Float` leaf, so a `Float` records as the exact `Double` it
    * denotes — `0.1f` becomes `0.10000000149011612`. Shadow this instance if
    * you would rather record the shorter rendering; doing so changes the value,
    * which is why it is not the default.
    */
  implicit val floatToAnyValue: ToAnyValue[Float] = instance[Float](f => AnyValue.double(f.toDouble))

  /** A `Unit` return value records as an empty value, which is OTLP's own
    * encoding of "no value" and reaches the wire as `{}`.
    * (`natchez.TraceableValue` records the string `"()"`.)
    */
  implicit val unitToAnyValue: ToAnyValue[Unit] = instance[Unit](_ => AnyValue.empty)

  /** An absent value records as an empty value rather than being omitted: this
    * type class is a total `A => AnyValue` with no channel for "nothing", and
    * giving it one would force every instance — and every nesting site — to
    * answer what a sequence does with an absent element. As a map entry the
    * cost is a null-valued key, not a whole attribute slot.
    * (`natchez.TraceableValue` records the string `"None"`.)
    */
  implicit def optionToAnyValue[A](implicit ev: ToAnyValue[A]): ToAnyValue[Option[A]] =
    instance[Option[A]] {
      case Some(a) => ev.toAnyValue(a)
      case None => AnyValue.empty
    }

  /** A sequence is '''one''' `AnyValue`, so unlike a flat attribute model this
    * generic instance is safe: there is no per-element key to collide.
    * It nests to any depth.
    */
  implicit def seqToAnyValue[A](implicit ev: ToAnyValue[A]): ToAnyValue[Seq[A]] =
    instance[Seq[A]](as => AnyValue.seq(as.map(ev.toAnyValue)))

  implicit def mapToAnyValue[A](implicit ev: ToAnyValue[A]): ToAnyValue[Map[String, A]] =
    instance[Map[String, A]](m => AnyValue.map(m.map { case (k, v) => k -> ev.toAnyValue(v) }))
}

/** The fallback, at lower implicit priority than everything in the companion's
  * own body — which is why this type class needs none of the `NotGiven`
  * ambiguity guards `com.dwolla.tracing.ToTraceValue` carries. Those exist
  * because natchez's primitive instances live in an upstream companion at the
  * same priority as the fallback; ours live one rung up, in a companion we own.
  */
trait LowPriorityToAnyValueInstances {
  implicit def showToAnyValue[A: Show]: ToAnyValue[A] =
    ToAnyValue.instance[A](a => AnyValue.string(a.show))
}
