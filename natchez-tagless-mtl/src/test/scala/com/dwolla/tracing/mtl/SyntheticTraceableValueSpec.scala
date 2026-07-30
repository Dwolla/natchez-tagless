package com.dwolla.tracing.mtl

import com.dwolla.tagless.mtl.Synthetic
import com.dwolla.tracing.mtl.syntax._
import munit.FunSuite
import natchez.{TraceValue, TraceableValue}

class SyntheticTraceableValueSpec extends FunSuite {
  test("the synthesized TraceableValue renders a greppable sentinel, for any type") {
    assertEquals(Synthetic[TraceableValue].apply[Int].toTraceValue(42), TraceValue.StringValue("«raised»"))
    assertEquals(Synthetic[TraceableValue].apply[String].toTraceValue("anything"), TraceValue.StringValue("«raised»"))
  }

  test("it ignores the value entirely — per the laws it is never observed") {
    assertEquals(
      Synthetic[TraceableValue].apply[Int].toTraceValue(1),
      Synthetic[TraceableValue].apply[Int].toTraceValue(999)
    )
  }
}
