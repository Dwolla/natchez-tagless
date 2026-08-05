package com.dwolla.tracing.otel4s

import cats.laws.discipline.*
import cats.laws.discipline.arbitrary.*
import cats.laws.discipline.eq.*
import cats.{Eq, Show}
import com.dwolla.tracing.otel4s.ToAnyValueSpec.*
import munit.DisciplineSuite
import org.scalacheck.{Arbitrary, Cogen, Gen}
import org.typelevel.otel4s.AnyValue

object ToAnyValueSpec {
  final case class Money(cents: Long)
  object Money {
    implicit val moneyShow: Show[Money] = Show.show(m => s"$$${m.cents}")
  }
}

class ToAnyValueSpec extends DisciplineSuite {
  private def enc[A](a: A)(implicit ev: ToAnyValue[A]): AnyValue = ev.toAnyValue(a)

  private implicit val eqAnyValue: Eq[AnyValue] = Eq.fromUniversalEquals

  private val maxAnyValueDepth: Int = 3
  private val maxAnyValueCollectionSize: Int = 5

  private def genAnyValue(depth: Int): Gen[AnyValue] = {
    val leaf = Gen.oneOf(
      Gen.alphaNumStr.map(AnyValue.string),
      Arbitrary.arbitrary[Boolean].map(AnyValue.boolean),
      Arbitrary.arbitrary[Long].map(AnyValue.long),
      Arbitrary.arbitrary[Double].map(AnyValue.double),
      Gen.const(AnyValue.empty),
      Gen.listOf(Arbitrary.arbitrary[Byte]).map(bytes => AnyValue.bytes(bytes.toArray))
    )

    if (depth >= maxAnyValueDepth) leaf
    else {
      val child = genAnyValue(depth + 1)

      def sizedListOf[A](g: Gen[A]): Gen[List[A]] =
        Gen.choose(0, maxAnyValueCollectionSize).flatMap(n => Gen.listOfN(n, g))

      Gen.oneOf(
        leaf,
        sizedListOf(child).map(AnyValue.seq),
        sizedListOf(for {
          k <- Gen.alphaNumStr
          v <- child
        } yield k -> v).map(kvs => AnyValue.map(kvs.toMap))
      )
    }
  }

  private implicit val arbitraryAnyValue: Arbitrary[AnyValue] = Arbitrary(Gen.choose(0, maxAnyValueDepth).flatMap(genAnyValue))

  private implicit def arbitraryToAnyValue[A: Cogen]: Arbitrary[ToAnyValue[A]] =
    Arbitrary(Arbitrary.arbitrary[A => AnyValue].map(ToAnyValue.instance))

  private implicit def eqToAnyValue[A](implicit ev: ExhaustiveCheck[A]): Eq[ToAnyValue[A]] =
    Eq.by(ta => (a: A) => ta.toAnyValue(a))

  test("String, Boolean, Long and Double encode to their native leaves") {
    assertEquals(enc("v"), AnyValue.string("v"))
    assertEquals(enc(true), AnyValue.boolean(true))
    assertEquals(enc(7L), AnyValue.long(7L))
    assertEquals(enc(1.5d), AnyValue.double(1.5d))
  }

  test("Int, Short and Byte widen to LongValue — Long is otel4s's only integral leaf") {
    assertEquals(enc(7), AnyValue.long(7L))
    assertEquals(enc(7.toShort), AnyValue.long(7L))
    assertEquals(enc(7.toByte), AnyValue.long(7L))
  }

  test("Float widens to the exact Double it denotes") {
    assertEquals(enc(0.1f), AnyValue.double(0.1f.toDouble))
    assertEquals(enc(0.1f).toString, "DoubleValue(0.10000000149011612)")
    assert(enc(0.1f) != AnyValue.double(0.1d))
  }

  test("Unit and None encode to EmptyValue, not to strings") {
    assertEquals(enc(()), AnyValue.empty)
    assertEquals(enc(Option.empty[String]), AnyValue.empty)
    assertEquals(enc(Option("v")), AnyValue.string("v"))
  }

  test("the generic Seq instance composes, to any depth") {
    assertEquals(enc(Seq("a", "b")), AnyValue.seq(Seq(AnyValue.string("a"), AnyValue.string("b"))))
    assertEquals(enc(Seq(1, 2)), AnyValue.seq(Seq(AnyValue.long(1L), AnyValue.long(2L))))
    assertEquals(enc(List(List(1))), AnyValue.seq(Seq(AnyValue.seq(Seq(AnyValue.long(1L))))))
  }

  test("a Map[String, A] encodes as a MapValue") {
    assertEquals(enc(Map("k" -> 1)), AnyValue.map(Map("k" -> AnyValue.long(1L))))
  }

  test("a type with only a Show instance falls back to its rendering") {
    assertEquals(enc(Money(500)), AnyValue.string("$500"))
    assertEquals(enc(Seq(Money(1))), AnyValue.seq(Seq(AnyValue.string("$1"))))
  }

  test("BigDecimal and BigInt resolve via the Encoder fallback, not the Show fallback") {
    // "2.00" is a mathematically whole number despite its trailing zeros, so
    // it folds to AnyValue.long, not AnyValue.double — the fold decides on
    // value, not on scale or textual form.
    assertEquals(enc(BigDecimal("2.00")), AnyValue.long(2L))
    assertEquals(enc(BigDecimal("1.50")), AnyValue.double(1.5))
    assertEquals(enc(BigInt("123456789012345678901234567890")), AnyValue.double(BigDecimal("123456789012345678901234567890").toDouble))
  }

  checkAll("SemigroupK[ToAnyValue]", SemigroupKTests[ToAnyValue].semigroupK[MiniInt])
  checkAll("ContravariantSemigroupal[ToAnyValue]", ContravariantSemigroupalTests[ToAnyValue].contravariantSemigroupal[MiniInt, MiniInt, Boolean])
}
