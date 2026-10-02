package com.dwolla.tracing.otel4s

import cats.*
import cats.data.{Chain, NonEmptyChain, NonEmptyList, NonEmptyMap, NonEmptySeq, NonEmptySet, NonEmptyVector, OneAnd}
import cats.syntax.all.*
import io.circe.{Encoder, Json, JsonNumber, JsonObject}
import java.net.URI
import java.util.UUID
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
  * `com.dwolla.code.function.arguments`, whose value is an `AnyValue` map
  * keyed by parameter name; the return value is recorded as
  * `com.dwolla.code.function.return_value`. Names come from the `Aspect`'s
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
  * in release 1.59.0. otel4s 1.1.0 pulls a newer version transitively, so the
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
  * is derived. There is deliberately no implicit fallback to a circe
  * `Encoder` or a cats `Show`: a value is recorded only through its own
  * `ToAnyValue`, so a redacting instance cannot be bypassed by a container
  * or case class that would otherwise have encoded the whole value with its
  * `Encoder` or `Show`. To record a type through one of those, opt in
  * explicitly with `ToAnyValue.fromEncoder` or `ToAnyValue.fromShow`; the
  * attribute then records whatever that `Encoder` or `Show` reveals.
  *
  * {{{
  *   import com.dwolla.tracing.otel4s.ToAnyValue
  *   import org.typelevel.otel4s.AnyValue
  *
  *   // BigDecimal and BigInt have built-in instances that record numbers.
  *   val number: AnyValue = ToAnyValue[BigDecimal].toAnyValue(BigDecimal("1.50"))
  *
  *   // A type with a circe Encoder records its JSON, folded into a structured
  *   // AnyValue, once you opt in with fromEncoder.
  *   case class Point(x: Int)
  *   implicit val pointEncoder: io.circe.Encoder[Point] = io.circe.Encoder.forProduct1("x")(_.x)
  *   implicit val pointToAnyValue: ToAnyValue[Point] = ToAnyValue.fromEncoder[Point]
  *
  *   val viaEncoder: AnyValue = ToAnyValue[Point].toAnyValue(Point(1))
  *
  *   // Likewise a type with a Show records its rendering with fromShow.
  *   case class Distance(meters: Int)
  *   implicit val distanceShow: cats.Show[Distance] = cats.Show.show(d => d.meters.toString + "m")
  *   implicit val distanceToAnyValue: ToAnyValue[Distance] = ToAnyValue.fromShow[Distance]
  *
  *   val viaShow: AnyValue = ToAnyValue[Distance].toAnyValue(Distance(5))
  *
  *   // A hand-written instance is how a sensitive type is kept out of the
  *   // trace. `instance` exists so the result type is AnyValue and never one
  *   // of its subtypes.
  *   final class Password(val value: String)
  *
  *   implicit val passwordToAnyValue: ToAnyValue[Password] =
  *   ToAnyValue.instance[Password](_ => AnyValue.string("redacted"))
  *
  *   val redacted: AnyValue = ToAnyValue[Password].toAnyValue(new Password("hunter2"))
  *
  *   // Option.empty[String], never a bare `None`. `ToAnyValue` is
  *   // contravariant and `None`'s type is `None.type`, so the element type is
  *   // left undetermined and the search fails confusingly rather than as a
  *   // clean miss: Scala 2 reports a *diverging* implicit expansion that names
  *   // an unrelated instance, and Scala 3 an ambiguity between the primitive
  *   // instances.
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

object ToAnyValue extends ToAnyValueTupleInstances {
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

  /** A map records as a `MapValue`, keys and values both through their own
    * `ToAnyValue`, so a redacting instance is honored on either side.
    *
    * `AnyValue` map keys are strings, so each key's encoding is rendered as
    * one: a string leaf is used as-is, and a long, double or boolean leaf is
    * rendered with `toString`, which for `String`, the integral types,
    * `Double`, `UUID` and `URI` gives the same key circe's `KeyEncoder`
    * would. Any other encoding (a sequence, map, byte array or empty value)
    * is rendered with otel4s's own `Show[AnyValue]`. Keys that render to the
    * same string, as every key does under a redacting instance, collapse to
    * one entry; which entry survives follows the map's iteration order, which
    * is unspecified for an unsorted `Map`.
    */
  implicit def mapToAnyValue[K, A](implicit keys: ToAnyValue[K], values: ToAnyValue[A]): ToAnyValue[Map[K, A]] =
    instance[Map[K, A]](m => AnyValue.map(m.map { case (k, v) => mapKey(keys.toAnyValue(k)) -> values.toAnyValue(v) }))

  private def mapKey(key: AnyValue): String = key match {
    case s: AnyValue.StringValue => s.value
    case l: AnyValue.LongValue => l.value.toString
    case d: AnyValue.DoubleValue => d.value.toString
    case b: AnyValue.BooleanValue => b.value.toString
    case other => other.show
  }

  /** Keyed like `mapToAnyValue`, in the map's sorted order. */
  implicit def nonEmptyMapToAnyValue[K, A](implicit keys: ToAnyValue[K], values: ToAnyValue[A]): ToAnyValue[NonEmptyMap[K, A]] =
    instance[NonEmptyMap[K, A]](m => mapToAnyValue(keys, values).toAnyValue(m.toSortedMap))

  /** Element-wise, like `seqToAnyValue`, so each element's own `ToAnyValue` —
    * including a redacting one — is used.
    *
    * The sequence records the set's iteration order, which is unspecified for
    * an unsorted `Set` and can differ between two equal sets, so the same set
    * of values can record as differently ordered sequences.
    */
  implicit def setToAnyValue[A](implicit ev: ToAnyValue[A]): ToAnyValue[Set[A]] =
    instance[Set[A]](as => AnyValue.seq(as.toSeq.map(ev.toAnyValue)))

  /** Element-wise and in order, like `seqToAnyValue`. */
  implicit def nonEmptyListToAnyValue[A](implicit ev: ToAnyValue[A]): ToAnyValue[NonEmptyList[A]] =
    instance[NonEmptyList[A]](as => AnyValue.seq(as.toList.map(ev.toAnyValue)))

  /** Element-wise and in order, like `seqToAnyValue`. */
  implicit def nonEmptyVectorToAnyValue[A](implicit ev: ToAnyValue[A]): ToAnyValue[NonEmptyVector[A]] =
    instance[NonEmptyVector[A]](as => AnyValue.seq(as.toVector.map(ev.toAnyValue)))

  /** Element-wise and in order, like `seqToAnyValue`. */
  implicit def nonEmptySeqToAnyValue[A](implicit ev: ToAnyValue[A]): ToAnyValue[NonEmptySeq[A]] =
    instance[NonEmptySeq[A]](as => AnyValue.seq(as.toSeq.map(ev.toAnyValue)))

  /** Element-wise and in order, like `seqToAnyValue`. */
  implicit def chainToAnyValue[A](implicit ev: ToAnyValue[A]): ToAnyValue[Chain[A]] =
    instance[Chain[A]](as => AnyValue.seq(as.toVector.map(ev.toAnyValue)))

  /** Element-wise and in order, like `seqToAnyValue`. */
  implicit def nonEmptyChainToAnyValue[A](implicit ev: ToAnyValue[A]): ToAnyValue[NonEmptyChain[A]] =
    instance[NonEmptyChain[A]](as => AnyValue.seq(as.toChain.toVector.map(ev.toAnyValue)))

  /** Element-wise, in the set's sorted order. */
  implicit def nonEmptySetToAnyValue[A](implicit ev: ToAnyValue[A]): ToAnyValue[NonEmptySet[A]] =
    instance[NonEmptySet[A]](as => AnyValue.seq(as.toSortedSet.toSeq.map(ev.toAnyValue)))

  /** Element-wise and in order. `Array` is not a `Seq`, so `seqToAnyValue`
    * does not cover it.
    */
  implicit def arrayToAnyValue[A](implicit ev: ToAnyValue[A]): ToAnyValue[Array[A]] =
    instance[Array[A]](as => AnyValue.seq(as.iterator.map(ev.toAnyValue).toVector))

  /** The head, then the tail's elements, in order. */
  implicit def oneAndToAnyValue[F[_], A](implicit ev: ToAnyValue[A], F: Foldable[F]): ToAnyValue[OneAnd[F, A]] =
    instance[OneAnd[F, A]](oa => AnyValue.seq((oa.head :: F.toList(oa.tail)).map(ev.toAnyValue)))

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
   * The implicit tuple instances in `ToAnyValueTupleInstances` nest instead,
   * so `(ta, tb).tupled` and `ToAnyValue[(A, B)]` differ when a component
   * encodes to a sequence.
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

  /** Records a type's circe JSON, folded into a structured `AnyValue` tree: a
    * `JsonObject` becomes an `AnyValue.map`, a JSON array an `AnyValue.seq`,
    * and so on, never a JSON string.
    *
    * This is an explicit opt-in, never an implicit fallback: the attribute
    * records whatever the `Encoder` reveals, including any field whose own
    * `ToAnyValue` would have redacted it, because the `Encoder` encodes the
    * whole value and never consults `ToAnyValue`.
    */
  def fromEncoder[A: Encoder]: ToAnyValue[A] =
    instance[A](a => Encoder[A].apply(a).foldWith(jsonFolder))

  /** Records a type's cats `Show` rendering as a string.
    *
    * This is an explicit opt-in, never an implicit fallback: the attribute
    * records whatever the `Show` reveals, including any element whose own
    * `ToAnyValue` would have redacted it.
    */
  def fromShow[A: Show]: ToAnyValue[A] =
    instance[A](a => AnyValue.string(a.show))

  implicit val jsonToAnyValue: ToAnyValue[Json] = instance[Json](_.foldWith(jsonFolder))
  implicit val jsonObjectToAnyValue: ToAnyValue[JsonObject] =
    instance[JsonObject](o => Json.fromJsonObject(o).foldWith(jsonFolder))

  /** Records the number exactly: a long when it fits, a double when the
    * double is exact, and otherwise the exact decimal string.
    */
  implicit val bigDecimalToAnyValue: ToAnyValue[BigDecimal] = fromEncoder[BigDecimal]

  /** Records the number exactly, as `bigDecimalToAnyValue` does. */
  implicit val bigIntToAnyValue: ToAnyValue[BigInt] = fromEncoder[BigInt]

  implicit val charToAnyValue: ToAnyValue[Char] = instance[Char](c => AnyValue.string(c.toString))
  implicit val uuidToAnyValue: ToAnyValue[UUID] = instance[UUID](u => AnyValue.string(u.toString))
  implicit val uriToAnyValue: ToAnyValue[URI] = instance[URI](u => AnyValue.string(u.toString))

  private[otel4s] val jsonFolder: Json.Folder[AnyValue] = new Json.Folder[AnyValue] {
    override def onNull: AnyValue = AnyValue.empty
    override def onBoolean(value: Boolean): AnyValue = AnyValue.boolean(value)

    /** otel4s has no arbitrary-precision leaf, so a number outside `Long`
      * range only fits `AnyValue.double` when the round trip through
      * `Double` reproduces the exact original value. When it doesn't — a
      * `BigInt`/`BigDecimal` wider than `Double`'s 53 bits of mantissa,
      * commonly reached via `bigIntToAnyValue` or `bigDecimalToAnyValue` —
      * this falls back to the number's exact decimal string rather than
      * silently rounding it.
      *
      * `toBigDecimal` can return `None` for a number circe itself refuses to
      * expand (a pathological exponent, guarded as a decompression-bomb
      * defense) — in that case skip the round-trip check entirely rather
      * than constructing the `BigDecimal` the guard exists to avoid; `value.
      * toString` is always safe, since it is exactly the source text circe
      * already parsed.
      */
    override def onNumber(value: JsonNumber): AnyValue = value.toLong match {
      case Some(l) => AnyValue.long(l)
      case None =>
        val asDouble = value.toDouble
        val roundTrips = value.toBigDecimal.exists { exact =>
          scala.util.Try(BigDecimal(asDouble.toString)).toOption.contains(exact)
        }
        if (roundTrips) AnyValue.double(asDouble) else AnyValue.string(value.toString)
    }
    override def onString(value: String): AnyValue = AnyValue.string(value)
    override def onArray(value: Vector[Json]): AnyValue = AnyValue.seq(value.map(_.foldWith(this)))
    override def onObject(value: JsonObject): AnyValue =
      AnyValue.map(value.toIterable.map { case (k, v) => k -> v.foldWith(this) }.toMap)
  }
}

/** Ranked below `ToAnyValue`'s companion because `iterableToAnyValue`
  * shares a result shape with the companion's `seqToAnyValue`,
  * `setToAnyValue`, `optionToAnyValue` and `arrayToAnyValue`, all of which
  * also match the types it matches. Specificity ties and the companion's
  * instance wins by being defined in a subclass. A plain
  * `ToAnyValue[Iterable[A]]` would not tie: contravariance makes it the more
  * specific of the two, which cancels the subclass bonus and leaves `List`
  * ambiguous on Scala 2. The type-constructor parameter `C[_]` is what makes
  * it tie.
  *
  * On Scala 2, a type that itself extends `Iterable`, `Seq` or `Set` and has
  * its own instance in its companion is ambiguous with the generic instance;
  * bring its instance into lexical scope with an import instead.
  */
trait GenericCollectionToAnyValueInstances {

  /** Element-wise and in iteration order, for any single-parameter `C[A]`
    * that converts to an `Iterable[A]` and has no instance of its own:
    * `Iterable` itself, `scala.collection.Seq` and `scala.collection.Set`,
    * and the mutable sequences and sets. A map is not `Iterable` in its value
    * type, so `scala.collection.Map` and `mutable.Map` have no instance;
    * convert them with `.toMap`.
    *
    * `asIterable` is declared before `ev` deliberately. Scala 2 also unifies
    * `C[A]` with a non-collection's base type, such as `Comparable[Instant]`;
    * resolving `ev` first would recurse into the very instance being
    * searched for and report a diverging implicit expansion instead of a
    * missing instance.
    */
  implicit def iterableToAnyValue[A, C[_]](implicit asIterable: C[A] => Iterable[A], ev: ToAnyValue[A]): ToAnyValue[C[A]] =
    ToAnyValue.instance[C[A]](as => AnyValue.seq(asIterable(as).iterator.map(ev.toAnyValue).toVector))
}
