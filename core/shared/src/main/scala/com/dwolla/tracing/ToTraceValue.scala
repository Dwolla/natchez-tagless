package com.dwolla.tracing

import cats.*
import cats.data.*
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Encoder, Json, JsonNumber}
import natchez.TraceValue.{BooleanValue, NumberValue, StringValue}
import natchez.{TraceValue, TraceableValue}

object LowPriorityTraceableValueInstances extends LowPriorityTraceableValueInstances

/** `TraceableValue` instances for `Unit`, `Option`, common collections, maps,
  * and tuples, plus explicit constructors for types with a circe `Encoder` or
  * a cats `Show`.
  *
  * Every container instance is element-wise: each element, map key, map value,
  * and tuple component is recorded through its own `TraceableValue`, so a
  * redacting instance is honored wherever the type appears. A type with no
  * `TraceableValue` of its own is a compile error, never a silent fallback to
  * its `Encoder` or `Show`, because those encode the whole value without
  * consulting any `TraceableValue` and so would reveal anything a redacting
  * instance hides.
  *
  * natchez's `TraceValue` has no list or map case, so a container records a
  * `StringValue` holding compact JSON: a sequence, set, or tuple becomes a
  * JSON array, and a map a JSON object. An element recorded as a
  * `StringValue`, `NumberValue`, or `BooleanValue` becomes a JSON string,
  * number, or boolean, so containers of primitives record exactly what their
  * circe `Encoder` would. A nested container, or a type opted in with
  * `fromEncoder`, nests as JSON rather than as a JSON string.
  *
  * A map key is recorded as the string form of its `TraceValue`. Keys that
  * record the same string, as every key does under a redacting instance,
  * collapse to one entry holding the last such value in iteration order.
  */
trait LowPriorityTraceableValueInstances extends LowestPriorityTraceableValueInstances {
  implicit val unitTraceableValue: TraceableValue[Unit] =
    TraceableValue.stringToTraceValue.contramap(_ => "()")

  implicit def optionalTraceValue[A: TraceableValue]: TraceableValue[Option[A]] = {
    case Some(a) => TraceableValue[A].toTraceValue(a)
    case None => TraceValue.StringValue("None")
  }

  /** Records a type as its circe JSON, rendered compactly.
    *
    * This is an explicit opt-in, never an implicit fallback: the attribute
    * records whatever the `Encoder` reveals, including any field whose own
    * `TraceableValue` would have redacted it, because the `Encoder` encodes
    * the whole value and never consults `TraceableValue`.
    */
  def fromEncoder[A: Encoder]: TraceableValue[A] =
    new JsonTraceableValue[A](_.asJson)

  /** Records a type as its cats `Show` rendering.
    *
    * This is an explicit opt-in, never an implicit fallback: the attribute
    * records whatever the `Show` reveals, including any field whose own
    * `TraceableValue` would have redacted it.
    */
  def fromShow[A: Show]: TraceableValue[A] =
    TraceableValue.stringToTraceValue.contramap(_.show)

  implicit def listTraceableValue[A: TraceableValue]: TraceableValue[List[A]] =
    JsonTraceableValue.array(_.iterator)

  implicit def seqTraceableValue[A: TraceableValue]: TraceableValue[Seq[A]] =
    JsonTraceableValue.array(_.iterator)

  implicit def vectorTraceableValue[A: TraceableValue]: TraceableValue[Vector[A]] =
    JsonTraceableValue.array(_.iterator)

  /** Records the set's iteration order, which is unspecified for an unsorted
    * `Set`, exactly as its circe `Encoder` would.
    */
  implicit def setTraceableValue[A: TraceableValue]: TraceableValue[Set[A]] =
    JsonTraceableValue.array(_.iterator)

  implicit def chainTraceableValue[A: TraceableValue]: TraceableValue[Chain[A]] =
    JsonTraceableValue.array(_.iterator)

  implicit def nonEmptyListTraceableValue[A: TraceableValue]: TraceableValue[NonEmptyList[A]] =
    JsonTraceableValue.array(_.iterator)

  implicit def nonEmptyVectorTraceableValue[A: TraceableValue]: TraceableValue[NonEmptyVector[A]] =
    JsonTraceableValue.array(_.iterator)

  /** Records the set's sorted order. */
  implicit def nonEmptySetTraceableValue[A: TraceableValue]: TraceableValue[NonEmptySet[A]] =
    JsonTraceableValue.array(_.toSortedSet.iterator)

  implicit def nonEmptyChainTraceableValue[A: TraceableValue]: TraceableValue[NonEmptyChain[A]] =
    JsonTraceableValue.array(_.iterator)

  /** Records a JSON object in the map's iteration order. See the trait's
    * documentation for how keys are rendered and when they collapse.
    */
  implicit def mapTraceableValue[K: TraceableValue, V: TraceableValue]: TraceableValue[Map[K, V]] =
    new JsonTraceableValue[Map[K, V]](m =>
      Json.fromFields(m.iterator.map { case (k, v) => JsonTraceableValue.key(k) -> JsonTraceableValue.json(v) }.toList)
    )

  implicit def tuple2TraceableValue[A: TraceableValue, B: TraceableValue]: TraceableValue[(A, B)] =
    JsonTraceableValue.tuple[(A, B)] { case (a, b) =>
      List(JsonTraceableValue.json(a), JsonTraceableValue.json(b))
    }

  implicit def tuple3TraceableValue[A: TraceableValue, B: TraceableValue, C: TraceableValue]: TraceableValue[(A, B, C)] =
    JsonTraceableValue.tuple[(A, B, C)] { case (a, b, c) =>
      List(JsonTraceableValue.json(a), JsonTraceableValue.json(b), JsonTraceableValue.json(c))
    }

  implicit def tuple4TraceableValue[A: TraceableValue, B: TraceableValue, C: TraceableValue, D: TraceableValue]: TraceableValue[(A, B, C, D)] =
    JsonTraceableValue.tuple[(A, B, C, D)] { case (a, b, c, d) =>
      List(JsonTraceableValue.json(a), JsonTraceableValue.json(b), JsonTraceableValue.json(c), JsonTraceableValue.json(d))
    }

  implicit def tuple5TraceableValue[A: TraceableValue, B: TraceableValue, C: TraceableValue, D: TraceableValue, E: TraceableValue]: TraceableValue[(A, B, C, D, E)] =
    JsonTraceableValue.tuple[(A, B, C, D, E)] { case (a, b, c, d, e) =>
      List(JsonTraceableValue.json(a), JsonTraceableValue.json(b), JsonTraceableValue.json(c), JsonTraceableValue.json(d), JsonTraceableValue.json(e))
    }

  /** Not implicit: as a fallback it bypassed redacting `TraceableValue`
    * instances. Kept, package-private, for binary compatibility with 0.2.6 and earlier.
    */
  @deprecated("use fromEncoder to opt in explicitly", "0.2.7")
  private[tracing] def traceValueViaJson[A: Encoder]: TraceableValue[A] =
    TraceableValue.stringToTraceValue.contramap(_.asJson.noSpaces)
}

trait LowestPriorityTraceableValueInstances {
  /** Not implicit: as a fallback it bypassed redacting `TraceableValue`
    * instances. Kept, package-private, for binary compatibility with 0.2.6 and earlier.
    */
  @deprecated("use fromShow to opt in explicitly", "0.2.7")
  private[tracing] def traceValueViaShow[A: Show]: TraceableValue[A] =
    TraceableValue.stringToTraceValue.contramap(_.show)
}

/** A `TraceableValue` that records compact JSON and exposes that JSON, so a
  * container holding it nests the JSON itself instead of a JSON string.
  */
private[tracing] final class JsonTraceableValue[A](val toJson: A => Json) extends TraceableValue[A] {
  override def toTraceValue(a: A): TraceValue = StringValue(toJson(a).noSpaces)
}

private[tracing] object JsonTraceableValue {
  def array[C, A: TraceableValue](elements: C => Iterator[A]): TraceableValue[C] =
    new JsonTraceableValue[C](c => Json.fromValues(elements(c).map(json(_)).toList))

  def tuple[T](components: T => List[Json]): TraceableValue[T] =
    new JsonTraceableValue[T](t => Json.fromValues(components(t)))

  def json[A](a: A)(implicit A: TraceableValue[A]): Json =
    A match {
      case nested: JsonTraceableValue[A] => nested.toJson(a)
      case _ => traceValueJson(A.toTraceValue(a))
    }

  def key[A](a: A)(implicit A: TraceableValue[A]): String =
    A.toTraceValue(a) match {
      case StringValue(s) => s
      case other => other.value.toString
    }

  private def traceValueJson(value: TraceValue): Json =
    value match {
      case StringValue(s) => Json.fromString(s)
      case BooleanValue(b) => Json.fromBoolean(b)
      case NumberValue(n) => numberJson(n)
    }

  /** Mirrors circe's `Encoder` for each boxed number type, so a `NumberValue`
    * records the same JSON number its type's `Encoder` would.
    */
  private def numberJson(n: Number): Json =
    n match {
      case i: java.lang.Integer => Json.fromInt(i.intValue)
      case l: java.lang.Long => Json.fromLong(l.longValue)
      case f: java.lang.Float => Json.fromFloatOrNull(f.floatValue)
      case d: java.lang.Double => Json.fromDoubleOrNull(d.doubleValue)
      case s: java.lang.Short => Json.fromInt(s.intValue)
      case b: java.lang.Byte => Json.fromInt(b.intValue)
      case bi: BigInt => Json.fromBigInt(bi)
      case bd: BigDecimal => Json.fromBigDecimal(bd)
      case bi: java.math.BigInteger => Json.fromBigInt(BigInt(bi))
      case bd: java.math.BigDecimal => Json.fromBigDecimal(BigDecimal(bd))
      case other => JsonNumber.fromString(other.toString).fold(Json.fromString(other.toString))(Json.fromJsonNumber)
    }
}
