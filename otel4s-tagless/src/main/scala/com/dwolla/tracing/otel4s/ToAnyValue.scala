package com.dwolla.tracing.otel4s

import cats.*
import cats.syntax.all.*
import io.circe.{Encoder, Json, JsonNumber, JsonObject}
import org.typelevel.otel4s.AnyValue
import org.typelevel.scalaccompat.annotation.nowarn213

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
  * is derived. In practice the `Encoder`-then-`Show` fallback chain means
  * nearly everything has one, so the likelier failure is silence: a domain
  * type records its JSON, or failing that its `Show` rendering, when a
  * hand-written encoding was wanted. Write the instance you want and it wins,
  * because the companion's own instances outrank both fallbacks.
  *
  * {{{
  *   import com.dwolla.tracing.otel4s.ToAnyValue
  *   import org.typelevel.otel4s.AnyValue
  *
  *   // BigDecimal and BigInt have a circe Encoder, so they resolve through
  *   // encodableToAnyValue and record as numbers, not through the Show
  *   // fallback below.
  *   val viaEncoder: AnyValue = ToAnyValue[BigDecimal].toAnyValue(BigDecimal("1.50"))
  *
  *   // A type with only a Show instance still falls back to its rendering.
  *   case class Distance(meters: Int)
  *   implicit val distanceShow: cats.Show[Distance] = cats.Show.show(d => d.meters.toString + "m")
  *
  *   val viaShow: AnyValue = ToAnyValue[Distance].toAnyValue(Distance(5))
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
    */
  implicit val unitToAnyValue: ToAnyValue[Unit] = instance[Unit](_ => AnyValue.empty)

  /** An absent value records as an empty value rather than being omitted: this
    * type class is a total `A => AnyValue` with no channel for "nothing", and
    * giving it one would force every instance — and every nesting site — to
    * answer what a sequence does with an absent element. As a map entry the
    * cost is a null-valued key, not a whole attribute slot.
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

  /** Concatenates rather than nests, which is what makes this lawful.
   * `ContravariantSemigroupal`'s associativity law demands that
   * `product(fa, product(fb, fc))` and `product(product(fa, fb), fc)`
   * describe the same `(A, B, C)` once both sides are reassociated via
   * `contramap`. Nesting each pair inside a fresh two-element `SeqValue`
   * would put `fc`'s encoding at a different depth on each side — not
   * equal. Flattening one level of `SeqValue` before re-wrapping makes
   * the combination associative regardless of how the tupling nested,
   * at a real cost: a component whose own `ToAnyValue` already produces
   * a `SeqValue` (the generic `seqToAnyValue` instance, for example) is
   * absorbed into the pair's sequence rather than appearing as a nested
   * element one level down. `product` is therefore not safe to reach for
   * across a component you need to keep intact as its own nested `Seq`.
   */
  @nowarn213("msg=Calls to parameterless method compose will be easy to mistake for calls to overloads which have a single implicit parameter list.")
  implicit val toAnyValueContravariantSemigroupalK: ContravariantSemigroupal[ToAnyValue] & SemigroupK[ToAnyValue] = new ContravariantSemigroupal[ToAnyValue] with SemigroupK[ToAnyValue] {
    private def flattened(v: AnyValue): Seq[AnyValue] = v match {
      case s: AnyValue.SeqValue => s.value
      case other => Seq(other)
    }

    override def combineK[A](x: ToAnyValue[A], y: ToAnyValue[A]): ToAnyValue[A] =
      instance[A] { a =>
        AnyValue.seq(flattened(x.toAnyValue(a)) ++ flattened(y.toAnyValue(a)))
      }

    override def contramap[A, B](fa: ToAnyValue[A])(f: B => A): ToAnyValue[B] = b => fa.toAnyValue(f(b))

    override def product[A, B](fa: ToAnyValue[A], fb: ToAnyValue[B]): ToAnyValue[(A, B)] =
      instance[(A, B)] { case (a, b) =>

        AnyValue.seq(flattened(fa.toAnyValue(a)) ++ flattened(fb.toAnyValue(b)))
      }
  }

  private[otel4s] val jsonToAnyValue: Json.Folder[AnyValue] = new Json.Folder[AnyValue] {
    override def onNull: AnyValue = AnyValue.empty
    override def onBoolean(value: Boolean): AnyValue = AnyValue.boolean(value)
    override def onNumber(value: JsonNumber): AnyValue = value.toLong match {
      case Some(l) => AnyValue.long(l)
      case None => AnyValue.double(value.toDouble)
    }
    override def onString(value: String): AnyValue = AnyValue.string(value)
    override def onArray(value: Vector[Json]): AnyValue = AnyValue.seq(value.map(_.foldWith(this)))
    override def onObject(value: JsonObject): AnyValue =
      AnyValue.map(value.toIterable.map { case (k, v) => k -> v.foldWith(this) }.toMap)
  }
}

/** Neither fallback below needs the `NotGiven` ambiguity guards
  * `com.dwolla.tracing.ToTraceValue` carries: those exist because natchez's
  * primitive instances live in an upstream companion at the same priority as
  * its fallbacks, while every instance here — primitives, `Contravariant`,
  * and both fallbacks — lives in a companion this module owns, ranked
  * unambiguously by how many `extends` hops separate it from `object
  * ToAnyValue`.
  */
trait LowPriorityToAnyValueInstances extends LowestPriorityToAnyValueInstances {

  /** Derives a `ToAnyValue[A]` from a circe `Encoder[A]`, ranked above the
    * `Show` fallback in `LowestPriorityToAnyValueInstances`: a type with
    * both records its JSON, matching `natchez.TraceableValue`'s own priority
    * between the two fallbacks (see ARCHAEOLOGY.md).
    */
  implicit def encodableToAnyValue[A: Encoder]: ToAnyValue[A] =
    ToAnyValue.instance[A](a => Encoder[A].apply(a).foldWith(ToAnyValue.jsonToAnyValue))
}

trait LowestPriorityToAnyValueInstances {
  implicit def showToAnyValue[A: Show]: ToAnyValue[A] =
    ToAnyValue.instance[A](a => AnyValue.string(a.show))
}
