package com.dwolla.tracing.otel4s

import cats.Show
import cats.syntax.all._
import com.dwolla.tracing.otel4s.ImplicitPrioritizationSpec._
import io.circe.Encoder
import munit._
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{Arbitrary, Prop}
import org.typelevel.otel4s.AnyValue

/** Mirrors `com.dwolla.tracing.ImplicitPrioritizationSpec` (the natchez
  * equivalent): proves the `Encoder`-then-`Show` fallback order, rather than
  * just asserting it in prose.
  */
class ImplicitPrioritizationSpec extends FunSuite with ScalaCheckSuite {
  test("ToAnyValue[Foo] uses the Encoder-derived instance") {
    Prop.forAll { (foo: Foo) =>
      assertEquals(ToAnyValue[Foo].toAnyValue(foo), AnyValue.long(foo.foo.toLong))
    }
  }

  test("ToAnyValue[Bar] uses the Show fallback") {
    Prop.forAll { (bar: Bar) =>
      assertEquals(ToAnyValue[Bar].toAnyValue(bar), AnyValue.string(bar.show))
    }
  }
}

object ImplicitPrioritizationSpec {
  case class Foo(foo: Int)
  object Foo {
    implicit val encoder: Encoder[Foo] = Encoder[Int].contramap(_.foo)
    implicit val show: Show[Foo] = Show.fromToString
    implicit val arbFoo: Arbitrary[Foo] = Arbitrary(arbitrary[Int].map(Foo(_)))
  }

  case class Bar(foo: Int)
  object Bar {
    implicit val show: Show[Bar] = Show.fromToString
    implicit val arbBar: Arbitrary[Bar] = Arbitrary(arbitrary[Int].map(Bar(_)))
  }
}
