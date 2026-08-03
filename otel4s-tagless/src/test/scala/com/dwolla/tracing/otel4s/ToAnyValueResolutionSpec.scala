package com.dwolla.tracing.otel4s

import munit.FunSuite
import org.typelevel.otel4s.AnyValue

/** D1 and D2, made falsifiable.
  *
  * Each `implicitly` here is a compile-time assertion: if the priority ladder
  * did not work, or if contravariance did not do what the milestone document
  * claims, the module would not build and this file is where the error lands.
  */
class ToAnyValueResolutionSpec extends FunSuite {
  test("a primitive resolves to its own instance, not to the Show fallback") {
    // Show[String] exists, so without the priority ladder this is ambiguous.
    assertEquals(implicitly[ToAnyValue[String]].toAnyValue("v"), AnyValue.string("v"))
  }

  test("Show[Int] and Show[Boolean] do not shadow the primitive instances either") {
    assertEquals(implicitly[ToAnyValue[Int]].toAnyValue(3), AnyValue.long(3L))
    assertEquals(implicitly[ToAnyValue[Boolean]].toAnyValue(true), AnyValue.boolean(true))
  }

  test("contravariance lets List and Vector use the generic Seq instance") {
    assertEquals(implicitly[ToAnyValue[List[String]]].toAnyValue(List("a")), AnyValue.seq(Seq(AnyValue.string("a"))))
    assertEquals(implicitly[ToAnyValue[Vector[Long]]].toAnyValue(Vector(1L)), AnyValue.seq(Seq(AnyValue.long(1L))))
  }

  test("Show[List[String]] does not shadow the generic Seq instance") {
    // cats has Show[List[A]]; if the fallback outranked the companion, this
    // would be StringValue("List(a)") instead.
    assertEquals(implicitly[ToAnyValue[List[String]]].toAnyValue(List("a")).toString, "SeqValue([StringValue(a)])")
  }
}
