package com.dwolla.tracing

import cats.*
import cats.syntax.all.*
import com.dwolla.tracing.LowPriorityTraceableValueInstances.*
import io.circe.*
import io.circe.generic.semiauto.*
import io.circe.syntax.*
import munit.*
import natchez.TraceValue.StringValue
import natchez.TraceableValue
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{Arbitrary, Prop}

class ImplicitPrioritizationSpec extends FunSuite with ScalaCheckSuite {
  test("TraceableValue[String] resolves to TraceableValue.stringToTraceValue") {
    assertEquals(implicitly[TraceableValue[String]], TraceableValue.stringToTraceValue)
  }

  test("TraceableValue[Foo] uses JSON encoder") {
    Prop.forAll { (foo: Foo) =>
      assertEquals(TraceableValue[Foo].toTraceValue(foo), StringValue(foo.asJson.noSpaces))
    }
  }

  test("TraceableValue[Bar] uses Show encoder") {
    Prop.forAll { (bar: Bar) =>
      assertEquals(TraceableValue[Bar].toTraceValue(bar), StringValue(bar.show))
    }
  }
}

case class Foo(foo: Int)
object Foo {
  implicit val codec: Codec[Foo] = deriveCodec
  implicit val show: Show[Foo] = Show.fromToString
  implicit val arbFoo: Arbitrary[Foo] = Arbitrary(arbitrary[Int].map(Foo(_)))
}

case class Bar(foo: Int)
object Bar {
  implicit val show: Show[Bar] = Show.fromToString
  implicit val arbFoo: Arbitrary[Bar] = Arbitrary(arbitrary[Int].map(Bar(_)))
}
