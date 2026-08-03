package com.dwolla.tracing.otel4s

import cats.Show
import com.dwolla.tracing.otel4s.ToAnyValueSpec._
import munit.FunSuite
import org.typelevel.otel4s.AnyValue

object ToAnyValueSpec {
  final case class Money(cents: Long)
  object Money {
    implicit val moneyShow: Show[Money] = Show.show(m => s"$$${m.cents}")
  }
}

class ToAnyValueSpec extends FunSuite {
  private def enc[A](a: A)(implicit ev: ToAnyValue[A]): AnyValue = ev.toAnyValue(a)

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

  // D3. otel4s has no Float leaf, so the widening is to the exact Double the
  // Float denotes. It prints with extra digits, and that is deliberate: the
  // alternative (_.toString.toDouble) prints prettily by changing the value.
  test("Float widens to the exact Double it denotes") {
    assertEquals(enc(0.1f), AnyValue.double(0.1f.toDouble))
    assertEquals(enc(0.1f).toString, "DoubleValue(0.10000000149011612)")
    assert(enc(0.1f) != AnyValue.double(0.1d))
  }

  // D3. natchez records the strings "()" and "None". A typed empty value beats
  // a string that looks like data, and EmptyValue is OTLP's own encoding of
  // absence — it reaches the wire as {}.
  test("Unit and None encode to EmptyValue, not to strings") {
    assertEquals(enc(()), AnyValue.empty)
    assertEquals(enc(Option.empty[String]), AnyValue.empty)
    assertEquals(enc(Option("v")), AnyValue.string("v"))
  }

  // D4. The flat design could not offer this instance, because Attributes
  // deduplicates by key and per-element attributes under one name would have
  // kept only the last. AnyValue.seq makes a sequence one value.
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
}
