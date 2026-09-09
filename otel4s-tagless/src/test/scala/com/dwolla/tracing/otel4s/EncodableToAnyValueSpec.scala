package com.dwolla.tracing.otel4s

import io.circe.{Json, JsonObject}
import io.circe.testing.ArbitraryInstances
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import org.typelevel.otel4s.AnyValue

/** Property tests for `ToAnyValue.encodableToAnyValue`, which folds a circe
  * `Json` tree into `AnyValue`. `expected` below is a second, independent
  * recursive walk of the same tree — via `Json#fold` and `JsonObject#toMap`
  * rather than `Json.Folder` and `JsonObject#toIterable` — so the property
  * isn't just the implementation checking itself.
  */
class EncodableToAnyValueSpec extends ScalaCheckSuite with ArbitraryInstances {

  /** Independent of `jsonToAnyValue.onNumber`'s own round-trip check, but
    * must agree with it: a `Double` that doesn't reproduce the original
    * number exactly loses precision silently, so both sides fall back to
    * the number's exact string form instead.
    */
  private def expectedNumber(n: io.circe.JsonNumber): AnyValue =
    n.toLong match {
      case Some(l) => AnyValue.long(l)
      case None =>
        val asDouble = n.toDouble
        val roundTrips = n.toBigDecimal.exists(exact => scala.util.Try(BigDecimal(asDouble.toString)).toOption.contains(exact))
        if (roundTrips) AnyValue.double(asDouble) else AnyValue.string(n.toString)
    }

  private def expected(json: Json): AnyValue =
    json.fold(
      jsonNull = AnyValue.empty,
      jsonBoolean = AnyValue.boolean,
      jsonNumber = expectedNumber,
      jsonString = AnyValue.string,
      jsonArray = arr => AnyValue.seq(arr.map(expected)),
      jsonObject = obj => AnyValue.map(obj.toMap.view.mapValues(expected).toMap)
    )

  property("an arbitrary Json tree folds the same way as an independent recursive walk") {
    forAll { (json: Json) =>
      ToAnyValue[Json].toAnyValue(json) == expected(json)
    }
  }

  property("a JSON object always becomes a structured AnyValue.map") {
    forAll { (obj: JsonObject) =>
      ToAnyValue[Json].toAnyValue(Json.fromJsonObject(obj)).isInstanceOf[AnyValue.MapValue]
    }
  }

  property("a JSON array always becomes an AnyValue.seq, in order") {
    forAll { (elements: Vector[Json]) =>
      ToAnyValue[Json].toAnyValue(Json.fromValues(elements)) == AnyValue.seq(elements.map(expected))
    }
  }

  property("a whole-number JsonNumber folds to AnyValue.long, never AnyValue.double") {
    forAll { (l: Long) =>
      ToAnyValue[Json].toAnyValue(Json.fromLong(l)) == AnyValue.long(l)
    }
  }

  test("Json.Null folds to AnyValue.empty") {
    assertEquals(ToAnyValue[Json].toAnyValue(Json.Null), AnyValue.empty)
  }

  test("a whole number too big for a Long folds to AnyValue.string, preserving every digit") {
    val exact = BigInt("123456789012345678901234567890")
    val json = Json.fromBigInt(exact)

    ToAnyValue[Json].toAnyValue(json) match {
      case sv: AnyValue.StringValue => assertEquals(BigInt(sv.value), exact)
      case other => fail(s"expected a StringValue preserving $exact exactly, got $other")
    }
  }

  test("a decimal with more significant digits than a Double can hold folds to AnyValue.string, not a lossy Double") {
    val exact = BigDecimal("0.123456789012345678901234567890")
    val json = Json.fromBigDecimal(exact)

    ToAnyValue[Json].toAnyValue(json) match {
      case sv: AnyValue.StringValue => assertEquals(BigDecimal(sv.value), exact)
      case other => fail(s"expected a StringValue preserving $exact exactly, got $other")
    }
  }

  test("a decimal that Double represents exactly still folds to AnyValue.double") {
    assertEquals(ToAnyValue[Json].toAnyValue(Json.fromDoubleOrNull(1.5)), AnyValue.double(1.5))
  }
}
