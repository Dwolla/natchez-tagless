package com.dwolla.tracing

import cats.Id
import cats.tagless.aop.Aspect
import munit.FunSuite
import natchez.TraceValue.StringValue
import natchez.TraceableValue

/** `TraceableAspect` adds no behaviour: it pins two type parameters so that
  * `derives` has a one-parameter type constructor to work with. These tests say
  * exactly that.
  */
class TraceableAspectSpec extends FunSuite {
  private val wide = HandWrittenLookupAspect.instance
  private val narrow: TraceableAspect[Lookup] = TraceableAspect.fromAspect(wide)

  private val impl: Lookup[Id] = Lookup[Id]

  /** Reduce a woven method to the parts that are comparable — `Aspect.Advice`
    * has reference equality (it overrides `toString` but not `equals`), which
    * is why every law suite in this repo renders rather than compares. */
  private def render(w: Aspect.Weave[Id, TraceableValue, TraceableValue, String])
      : (String, String, List[List[(String, StringValue)]], String) =
    (
      w.algebraName,
      w.codomain.name,
      w.domain.map(_.map(a => a.name -> StringValue(a.instance.toTraceValue(a.target.value).toString))),
      w.codomain.target
    )

  test("weave forwards to the underlying instance") {
    assertEquals(
      render(narrow.weave(impl).get("k")),
      render(wide.weave(impl).get("k"))
    )
  }

  test("mapK forwards to the underlying instance") {
    val fk = new cats.~>[Id, Option] { def apply[A](fa: Id[A]): Option[A] = Some(fa) }
    assertEquals(narrow.mapK(impl)(fk).get("k"), wide.mapK(impl)(fk).get("k"))
    assertEquals(narrow.mapK(impl)(fk).get("k"), Some("v:k"))
  }

  test("instrument is inherited from Aspect's default and works") {
    val i = narrow.instrument(impl).get("k")
    assertEquals(i.algebraName, "Lookup")
    assertEquals(i.methodName, "get")
    assertEquals(i.value, "v:k")
  }

  test("the narrow instance is accepted wherever the wide one is") {
    val asWide: Aspect[Lookup, TraceableValue, TraceableValue] = narrow
    assert(asWide ne null)
  }
}
