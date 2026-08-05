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

  private def expected(json: Json): AnyValue =
    json.fold(
      jsonNull = AnyValue.empty,
      jsonBoolean = AnyValue.boolean,
      jsonNumber = n =>
        n.toLong match {
          case Some(l) => AnyValue.long(l)
          case None => AnyValue.double(n.toDouble)
        },
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
}
