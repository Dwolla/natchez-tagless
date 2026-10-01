package com.dwolla.tracing.otel4s

import cats.Show
import cats.syntax.all._
import com.dwolla.tracing.otel4s.ExplicitOptInSpec._
import io.circe.Encoder
import munit._
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{Arbitrary, Prop}
import org.typelevel.otel4s.AnyValue

/** A circe `Encoder` or a cats `Show` never supplies a `ToAnyValue`
  * implicitly; a type records through one only after opting in with
  * `fromEncoder` or `fromShow`.
  */
class ExplicitOptInSpec extends FunSuite with ScalaCheckSuite {
  private def assertNoInstance(errors: String)(implicit loc: Location): Unit =
    assert(errors.contains("ToAnyValue["), errors)

  test("a type with only an Encoder has no implicit ToAnyValue") {
    assertNoInstance(compileErrors("com.dwolla.tracing.otel4s.ToAnyValue[com.dwolla.tracing.otel4s.ExplicitOptInSpec.EncoderOnly]"))
  }

  test("a type with only a Show has no implicit ToAnyValue") {
    assertNoInstance(compileErrors("com.dwolla.tracing.otel4s.ToAnyValue[com.dwolla.tracing.otel4s.ExplicitOptInSpec.ShowOnly]"))
  }

  test("a container of a type with only an Encoder or a Show has no implicit ToAnyValue either") {
    assertNoInstance(compileErrors("com.dwolla.tracing.otel4s.ToAnyValue[List[com.dwolla.tracing.otel4s.ExplicitOptInSpec.EncoderOnly]]"))
    assertNoInstance(compileErrors("com.dwolla.tracing.otel4s.ToAnyValue[Option[com.dwolla.tracing.otel4s.ExplicitOptInSpec.ShowOnly]]"))
  }

  test("a Comparable type with no instance is reported as missing, not as a diverging expansion") {
    // Scala 2 unifies the generic Iterable instance's C[A] with Comparable[Instant];
    // resolving the element instance before the Iterable evidence would recurse
    // into ToAnyValue[Instant] and report divergence instead of a missing instance.
    val bare = compileErrors("com.dwolla.tracing.otel4s.ToAnyValue[java.time.Instant]")
    val inList = compileErrors("com.dwolla.tracing.otel4s.ToAnyValue[List[java.time.Instant]]")
    assertNoInstance(bare)
    assertNoInstance(inList)
    assert(!bare.contains("diverging"), bare)
    assert(!inList.contains("diverging"), inList)
  }

  test("fromEncoder records the Encoder's JSON") {
    Prop.forAll { (foo: Foo) =>
      assertEquals(ToAnyValue[Foo].toAnyValue(foo), AnyValue.long(foo.foo.toLong))
    }
  }

  test("fromShow records the Show rendering") {
    Prop.forAll { (bar: Bar) =>
      assertEquals(ToAnyValue[Bar].toAnyValue(bar), AnyValue.string(bar.show))
    }
  }
}

object ExplicitOptInSpec {
  final case class EncoderOnly(value: Int)
  object EncoderOnly {
    implicit val encoder: Encoder[EncoderOnly] = Encoder[Int].contramap(_.value)
  }

  final case class ShowOnly(value: Int)
  object ShowOnly {
    implicit val show: Show[ShowOnly] = Show.fromToString
  }

  final case class Foo(foo: Int)
  object Foo {
    implicit val encoder: Encoder[Foo] = Encoder[Int].contramap(_.foo)
    implicit val toAnyValue: ToAnyValue[Foo] = ToAnyValue.fromEncoder[Foo]
    implicit val arbFoo: Arbitrary[Foo] = Arbitrary(arbitrary[Int].map(Foo(_)))
  }

  final case class Bar(foo: Int)
  object Bar {
    implicit val show: Show[Bar] = Show.fromToString
    implicit val toAnyValue: ToAnyValue[Bar] = ToAnyValue.fromShow[Bar]
    implicit val arbBar: Arbitrary[Bar] = Arbitrary(arbitrary[Int].map(Bar(_)))
  }
}
