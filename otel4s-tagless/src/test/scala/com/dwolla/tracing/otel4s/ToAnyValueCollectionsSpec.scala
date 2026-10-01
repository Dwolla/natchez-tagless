package com.dwolla.tracing.otel4s

import cats.data.NonEmptyList
import io.circe.Encoder
import munit.FunSuite
import org.typelevel.otel4s.AnyValue

/** A type with both a circe `Encoder` and a redacting `ToAnyValue`: inside a
  * collection, the redacting instance must win.
  */
final class Secret(val value: String)

object Secret {
  implicit val secretEncoder: Encoder[Secret] = Encoder[String].contramap(_.value)
  implicit val secretToAnyValue: ToAnyValue[Secret] = ToAnyValue.instance(_ => AnyValue.string("redacted"))
}

class ToAnyValueCollectionsSpec extends FunSuite {
  test("a Set encodes each element through the element's ToAnyValue, not circe") {
    assertEquals(
      ToAnyValue[Set[Secret]].toAnyValue(Set(new Secret("hunter2"))),
      AnyValue.seq(Seq(AnyValue.string("redacted"))),
    )
  }

  test("a NonEmptyList encodes each element through the element's ToAnyValue, not circe") {
    assertEquals(
      ToAnyValue[NonEmptyList[Secret]].toAnyValue(NonEmptyList.of(new Secret("a"), new Secret("b"))),
      AnyValue.seq(Seq(AnyValue.string("redacted"), AnyValue.string("redacted"))),
    )
  }

  test("a NonEmptyList keeps element order") {
    assertEquals(
      ToAnyValue[NonEmptyList[Int]].toAnyValue(NonEmptyList.of(1, 2, 3)),
      AnyValue.seq(Seq(AnyValue.long(1L), AnyValue.long(2L), AnyValue.long(3L))),
    )
  }
}
