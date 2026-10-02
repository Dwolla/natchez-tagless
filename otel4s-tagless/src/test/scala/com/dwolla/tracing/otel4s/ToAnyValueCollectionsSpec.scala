package com.dwolla.tracing.otel4s

import cats.Order
import cats.data._
import io.circe.{Encoder, KeyEncoder}
import munit.FunSuite
import org.typelevel.otel4s.AnyValue

/** A type with a circe `Encoder`, a `KeyEncoder`, and a redacting
  * `ToAnyValue`: inside a collection, and as a map key, the redacting
  * instance must win.
  */
final class Secret(val value: String)

object Secret {
  implicit val secretEncoder: Encoder[Secret] = Encoder[String].contramap(_.value)
  implicit val secretKeyEncoder: KeyEncoder[Secret] = KeyEncoder[String].contramap(_.value)
  implicit val secretOrder: Order[Secret] = Order.by(_.value)
  implicit val secretToAnyValue: ToAnyValue[Secret] = ToAnyValue.instance(_ => AnyValue.string("redacted"))
}

/** A map key with a circe `KeyEncoder` and nothing else: no `ToAnyValue`,
  * `Encoder` or `Show`.
  */
final case class KeyOnly(value: String)

object KeyOnly {
  implicit val keyOnlyKeyEncoder: KeyEncoder[KeyOnly] = KeyEncoder[String].contramap(_.value)
}

class ToAnyValueCollectionsSpec extends FunSuite {
  private val redacted: AnyValue = AnyValue.string("redacted")
  private def secret(value: String): Secret = new Secret(value)

  test("a Set encodes each element through the element's ToAnyValue, not circe") {
    assertEquals(
      ToAnyValue[Set[Secret]].toAnyValue(Set(secret("hunter2"))),
      AnyValue.seq(Seq(redacted)),
    )
  }

  test("a NonEmptyList encodes each element through the element's ToAnyValue, not circe") {
    assertEquals(
      ToAnyValue[NonEmptyList[Secret]].toAnyValue(NonEmptyList.of(secret("a"), secret("b"))),
      AnyValue.seq(Seq(redacted, redacted)),
    )
  }

  test("a NonEmptyList keeps element order") {
    assertEquals(
      ToAnyValue[NonEmptyList[Int]].toAnyValue(NonEmptyList.of(1, 2, 3)),
      AnyValue.seq(Seq(AnyValue.long(1L), AnyValue.long(2L), AnyValue.long(3L))),
    )
  }

  test("a NonEmptyVector encodes each element through the element's ToAnyValue, in order") {
    assertEquals(
      ToAnyValue[NonEmptyVector[Secret]].toAnyValue(NonEmptyVector.of(secret("a"), secret("b"))),
      AnyValue.seq(Seq(redacted, redacted)),
    )
    assertEquals(
      ToAnyValue[NonEmptyVector[Int]].toAnyValue(NonEmptyVector.of(1, 2)),
      AnyValue.seq(Seq(AnyValue.long(1L), AnyValue.long(2L))),
    )
  }

  test("a NonEmptySeq encodes each element through the element's ToAnyValue, in order") {
    assertEquals(
      ToAnyValue[NonEmptySeq[Secret]].toAnyValue(NonEmptySeq.of(secret("a"), secret("b"))),
      AnyValue.seq(Seq(redacted, redacted)),
    )
    assertEquals(
      ToAnyValue[NonEmptySeq[Int]].toAnyValue(NonEmptySeq.of(1, 2)),
      AnyValue.seq(Seq(AnyValue.long(1L), AnyValue.long(2L))),
    )
  }

  test("a Chain encodes each element through the element's ToAnyValue, in order") {
    assertEquals(
      ToAnyValue[Chain[Secret]].toAnyValue(Chain(secret("a"), secret("b"))),
      AnyValue.seq(Seq(redacted, redacted)),
    )
    assertEquals(
      ToAnyValue[Chain[Int]].toAnyValue(Chain(1, 2)),
      AnyValue.seq(Seq(AnyValue.long(1L), AnyValue.long(2L))),
    )
  }

  test("a NonEmptyChain encodes each element through the element's ToAnyValue, in order") {
    assertEquals(
      ToAnyValue[NonEmptyChain[Secret]].toAnyValue(NonEmptyChain(secret("a"), secret("b"))),
      AnyValue.seq(Seq(redacted, redacted)),
    )
    assertEquals(
      ToAnyValue[NonEmptyChain[Int]].toAnyValue(NonEmptyChain(1, 2)),
      AnyValue.seq(Seq(AnyValue.long(1L), AnyValue.long(2L))),
    )
  }

  test("a NonEmptySet encodes each element through the element's ToAnyValue, in sorted order") {
    assertEquals(
      ToAnyValue[NonEmptySet[Secret]].toAnyValue(NonEmptySet.of(secret("a"), secret("b"))),
      AnyValue.seq(Seq(redacted, redacted)),
    )
    assertEquals(
      ToAnyValue[NonEmptySet[Int]].toAnyValue(NonEmptySet.of(2, 1)),
      AnyValue.seq(Seq(AnyValue.long(1L), AnyValue.long(2L))),
    )
  }

  test("an Array encodes each element through the element's ToAnyValue, in order") {
    assertEquals(
      ToAnyValue[Array[Secret]].toAnyValue(Array(secret("a"), secret("b"))),
      AnyValue.seq(Seq(redacted, redacted)),
    )
    assertEquals(
      ToAnyValue[Array[Int]].toAnyValue(Array(1, 2)),
      AnyValue.seq(Seq(AnyValue.long(1L), AnyValue.long(2L))),
    )
  }

  test("a Map with non-String keys encodes each value through the value's ToAnyValue") {
    assertEquals(
      ToAnyValue[Map[Int, Secret]].toAnyValue(Map(1 -> secret("hunter2"))),
      AnyValue.map(Map("1" -> redacted)),
    )
  }

  test("a Map's keys are rendered through the key's ToAnyValue, so a redacted key stays redacted") {
    assertEquals(
      ToAnyValue[Map[Secret, Int]].toAnyValue(Map(secret("hunter2") -> 1)),
      AnyValue.map(Map("redacted" -> AnyValue.long(1L))),
    )
  }

  test("a Map with scalar keys records the same keys circe's KeyEncoder would") {
    assertEquals(
      ToAnyValue[Map[Int, Int]].toAnyValue(Map(1 -> 2)),
      AnyValue.map(Map("1" -> AnyValue.long(2L))),
    )
    assertEquals(
      ToAnyValue[Map[Long, String]].toAnyValue(Map(7L -> "v")),
      AnyValue.map(Map("7" -> AnyValue.string("v"))),
    )
    val uuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")
    assertEquals(
      ToAnyValue[Map[java.util.UUID, Int]].toAnyValue(Map(uuid -> 1)),
      AnyValue.map(Map(uuid.toString -> AnyValue.long(1L))),
    )
  }

  test("a Map whose key has only a KeyEncoder has no instance, rather than leaking its values through circe") {
    val errors = compileErrors("com.dwolla.tracing.otel4s.ToAnyValue[Map[com.dwolla.tracing.otel4s.KeyOnly, com.dwolla.tracing.otel4s.Secret]]")
    assert(errors.contains("ToAnyValue["), errors)
  }

  test("a scala.collection.Seq encodes each element through the element's ToAnyValue, in order") {
    assertEquals(
      ToAnyValue[scala.collection.Seq[Secret]].toAnyValue(scala.collection.Seq(secret("a"), secret("b"))),
      AnyValue.seq(Seq(redacted, redacted)),
    )
  }

  test("an Iterable encodes each element through the element's ToAnyValue, in order") {
    assertEquals(
      ToAnyValue[Iterable[Secret]].toAnyValue(Iterable(secret("a"), secret("b"))),
      AnyValue.seq(Seq(redacted, redacted)),
    )
  }

  test("a scala.collection.Set encodes each element through the element's ToAnyValue") {
    assertEquals(
      ToAnyValue[scala.collection.Set[Secret]].toAnyValue(scala.collection.Set(secret("a"))),
      AnyValue.seq(Seq(redacted)),
    )
  }

  test("a OneAnd encodes its head and then its tail's elements through the element's ToAnyValue") {
    assertEquals(
      ToAnyValue[OneAnd[List, Secret]].toAnyValue(OneAnd(secret("a"), List(secret("b")))),
      AnyValue.seq(Seq(redacted, redacted)),
    )
    assertEquals(
      ToAnyValue[OneAnd[List, Int]].toAnyValue(OneAnd(1, List(2, 3))),
      AnyValue.seq(Seq(AnyValue.long(1L), AnyValue.long(2L), AnyValue.long(3L))),
    )
  }

  test("a NonEmptyMap encodes each key and value through their ToAnyValue instances") {
    assertEquals(
      ToAnyValue[NonEmptyMap[Int, Secret]].toAnyValue(NonEmptyMap.of(1 -> secret("hunter2"))),
      AnyValue.map(Map("1" -> redacted)),
    )
    assertEquals(
      ToAnyValue[NonEmptyMap[Secret, Int]].toAnyValue(NonEmptyMap.of(secret("hunter2") -> 1)),
      AnyValue.map(Map("redacted" -> AnyValue.long(1L))),
    )
  }

  test("a Map[String, A] uses the key as-is and encodes each value through the value's ToAnyValue") {
    assertEquals(
      ToAnyValue[Map[String, Secret]].toAnyValue(Map("k" -> secret("hunter2"))),
      AnyValue.map(Map("k" -> redacted)),
    )
  }

  test("a Tuple1 encodes its element through the element's ToAnyValue") {
    assertEquals(
      ToAnyValue[Tuple1[Secret]].toAnyValue(Tuple1(secret("hunter2"))),
      AnyValue.seq(Seq(redacted)),
    )
  }

  test("a tuple encodes each element through the element's ToAnyValue, in position") {
    assertEquals(
      ToAnyValue[(Secret, Int)].toAnyValue((secret("hunter2"), 1)),
      AnyValue.seq(Seq(redacted, AnyValue.long(1L))),
    )
    assertEquals(
      ToAnyValue[(Int, Secret, String)].toAnyValue((1, secret("hunter2"), "v")),
      AnyValue.seq(Seq(AnyValue.long(1L), redacted, AnyValue.string("v"))),
    )
  }

  test("a 22-tuple encodes each element through the element's ToAnyValue") {
    val s = secret("hunter2")
    assertEquals(
      ToAnyValue[(Secret, Secret, Secret, Secret, Secret, Secret, Secret, Secret, Secret, Secret, Secret,
                  Secret, Secret, Secret, Secret, Secret, Secret, Secret, Secret, Secret, Secret, Int)]
        .toAnyValue((s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, s, 22)),
      AnyValue.seq(Seq.fill(21)(redacted) :+ AnyValue.long(22L)),
    )
  }

  test("a tuple nests a sequence-valued element rather than flattening it, as circe's array encoding does") {
    assertEquals(
      ToAnyValue[(List[Int], String)].toAnyValue((List(1, 2), "v")),
      AnyValue.seq(Seq(AnyValue.seq(Seq(AnyValue.long(1L), AnyValue.long(2L))), AnyValue.string("v"))),
    )
  }
}
