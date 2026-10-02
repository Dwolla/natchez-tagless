package com.dwolla.tracing

import cats.*
import cats.data.*
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

import scala.collection.immutable.SortedSet

class ImplicitPrioritizationSpec extends FunSuite with ScalaCheckSuite {
  test("TraceableValue[String] resolves to TraceableValue.stringToTraceValue") {
    assertEquals(implicitly[TraceableValue[String]], TraceableValue.stringToTraceValue)
  }

  test("a type with a circe Encoder and a cats Show has no implicit TraceableValue") {
    val errors = compileErrors("TraceableValue[Foo]")
    assert(errors.contains("TraceableValue"), errors)
  }

  test("fromEncoder renders the type's JSON") {
    Prop.forAll { (foo: Foo) =>
      assertEquals(fromEncoder[Foo].toTraceValue(foo), StringValue(foo.asJson.noSpaces))
    }
  }

  test("fromShow renders the type's Show") {
    Prop.forAll { (bar: Bar) =>
      assertEquals(fromShow[Bar].toTraceValue(bar), StringValue(bar.show))
    }
  }

  test("a container of a fromEncoder type nests each element's JSON, as the Encoder fallback did") {
    implicit val fooTraceableValue: TraceableValue[Foo] = fromEncoder[Foo]

    Prop.forAll { (foos: List[Foo], byName: Map[String, Foo]) =>
      assertMatchesJson(foos)
      assertMatchesJson(byName)
    }
  }

  test("sequences of primitives render as the Encoder fallback did") {
    Prop.forAll { (ints: List[Int], strings: Seq[String], longs: Vector[Long], booleans: Set[Boolean], doubles: List[Double], floats: List[Float]) =>
      assertMatchesJson(ints)
      assertMatchesJson(strings)
      assertMatchesJson(longs)
      assertMatchesJson(booleans)
      assertMatchesJson(doubles)
      assertMatchesJson(floats)
    }
  }

  test("cats data containers of primitives render as the Encoder fallback did") {
    Prop.forAll { (head: Int, tail: List[Int], strings: List[String]) =>
      assertMatchesJson(Chain.fromSeq(tail))
      assertMatchesJson(NonEmptyList(head, tail))
      assertMatchesJson(NonEmptyVector(head, tail.toVector))
      assertMatchesJson(NonEmptySet(head, SortedSet.empty[Int] ++ tail))
      assertMatchesJson(NonEmptyChain.fromNonEmptyList(NonEmptyList(head, tail)))
      assertMatchesJson(Chain.fromSeq(strings))
    }
  }

  test("maps of primitives render as the Encoder fallback did") {
    Prop.forAll { (byString: Map[String, Int], byInt: Map[Int, String], byLong: Map[Long, Boolean], byDouble: Map[Double, Long]) =>
      assertMatchesJson(byString)
      assertMatchesJson(byInt)
      assertMatchesJson(byLong)
      assertMatchesJson(byDouble)
    }
  }

  test("tuples of primitives render as the Encoder fallback did") {
    Prop.forAll { (i: Int, s: String, l: Long, b: Boolean, d: Double) =>
      assertMatchesJson((i, s))
      assertMatchesJson((i, s, l))
      assertMatchesJson((i, s, l, b))
      assertMatchesJson((i, s, l, b, d))
    }
  }

  test("nested containers of primitives render as the Encoder fallback did") {
    Prop.forAll { (nested: List[List[Int]], byName: Map[String, Vector[String]], pairs: List[(Int, Set[String])]) =>
      assertMatchesJson(nested)
      assertMatchesJson(byName)
      assertMatchesJson(pairs)
    }
  }

  private def assertMatchesJson[A: TraceableValue : Encoder](a: A): Unit =
    assertEquals(TraceableValue[A].toTraceValue(a), StringValue(a.asJson.noSpaces))
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
