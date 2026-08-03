package com.dwolla.tracing.otel4s

import cats.{Eval, Id}
import cats.tagless.aop.Aspect
import com.dwolla.tracing.otel4s.syntax._
import munit.FunSuite
import org.typelevel.otel4s.{AnyValue, Attribute, Attributes}

class WeaveAttributesOpsSpec extends FunSuite {
  private def weaveOf(domain: List[List[Aspect.Advice[Eval, ToAnyValue]]]): Aspect.Weave[Id, ToAnyValue, ToAnyValue, String] =
    Aspect.Weave[Id, ToAnyValue, ToAnyValue, String](
      "Foo",
      domain,
      Aspect.Advice[Id, ToAnyValue, String]("greet", "hi")
    )

  // The explicit [AnyValue] is the D1 rule in test form: AnyValue.map(...) is
  // typed at AnyValue.MapValue and KeySelect is invariant, so without a
  // widening somewhere this line does not compile.
  private def expected(entries: (String, AnyValue)*): Attributes =
    Attributes(Attribute[AnyValue]("Foo.greet.parameters", AnyValue.map(entries.toMap)))

  test("every parameter of every parameter list lands in one `parameters` map") {
    val weave = weaveOf(List(
      List(Aspect.Advice.byValue[ToAnyValue, String]("name", "world")),
      List(Aspect.Advice.byValue[ToAnyValue, Int]("times", 2)),
    ))

    assertEquals(
      weave.asAttributes,
      expected("name" -> AnyValue.string("world"), "times" -> AnyValue.long(2L))
    )
  }

  test("exactly one attribute is produced, whatever the parameter count") {
    val weave = weaveOf(List(List(
      Aspect.Advice.byValue[ToAnyValue, Int]("a", 1),
      Aspect.Advice.byValue[ToAnyValue, Int]("b", 2),
      Aspect.Advice.byValue[ToAnyValue, Int]("c", 3),
    )))

    assertEquals(weave.asAttributes.size, 1)
  }

  // D3: within a non-empty map, absence is a kept EmptyValue entry, not a
  // missing one. This map has two entries (both empty-valued), so it is not
  // itself empty, and the whole attribute is still recorded — contrast with
  // the next test, where the map has *zero* entries.
  test("a parameter that encodes to nothing is a kept EmptyValue entry") {
    val weave = weaveOf(List(List(
      Aspect.Advice.byValue[ToAnyValue, Option[String]]("note", None),
      Aspect.Advice.byValue[ToAnyValue, Unit]("nothing", ()),
    )))

    assertEquals(weave.asAttributes, expected("note" -> AnyValue.empty, "nothing" -> AnyValue.empty))
  }

  // D3, corrected 2026-08-02: a map with *zero* entries is not recorded at
  // all — the whole `parameters` attribute is omitted, not emitted holding
  // `MapValue({})`. ToAnyValue itself is unchanged; this is the interpreter
  // choosing not to spend an attribute slot saying nothing.
  test("a method with no parameters records no `parameters` attribute at all") {
    assertEquals(weaveOf(List(List.empty)).asAttributes, Attributes.empty)
    assertEquals(weaveOf(List.empty).asAttributes, Attributes.empty)
  }

  test("a by-name parameter is forced exactly once, when asAttributes is called") {
    var forced = 0
    val weave = weaveOf(List(List(
      Aspect.Advice.byName[ToAnyValue, String]("lazyParam", { forced += 1; "x" })
    )))

    assertEquals(forced, 0)
    assertEquals(weave.asAttributes, expected("lazyParam" -> AnyValue.string("x")))
    assertEquals(forced, 1)
  }
}
