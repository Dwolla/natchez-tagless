package com.dwolla.tracing

import cats.Id
import cats.tagless.aop.Aspect
import munit.FunSuite
import natchez.TraceValue.StringValue
import natchez.TraceableValue

import scala.annotation.experimental

/** `TraceableAspect` adds no behaviour: it pins two type parameters so that
  * `derives` has a one-parameter type constructor to work with. These tests say
  * exactly that.
  *
  * `@experimental` on the class because it summons the given `derives
  * TraceableAspect` synthesized into `DerivesLookup`'s `@experimental`
  * companion.
  */
@experimental
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

  test("the derives clause produces an instance, and it is the narrow type") {
    val derived: TraceableAspect[DerivesLookup] = summon[TraceableAspect[DerivesLookup]]
    assert(derived ne null)
  }

  test("the derived instance agrees with the hand-written one, modulo the algebra name") {
    val derivedImpl: DerivesLookup[Id] = DerivesLookup[Id]
    val d = summon[TraceableAspect[DerivesLookup]].weave(derivedImpl).get("k")
    val h = wide.weave(impl).get("k")

    assertEquals(d.algebraName, "DerivesLookup")
    assertEquals(h.algebraName, "Lookup")
    assertEquals(d.codomain.name, h.codomain.name)
    assertEquals(d.codomain.target, h.codomain.target)
    assertEquals(
      d.domain.map(_.map(a => a.name -> a.instance.toTraceValue(a.target.value))),
      h.domain.map(_.map(a => a.name -> a.instance.toTraceValue(a.target.value)))
    )
  }

  test("a wide Aspect does not satisfy a demand for the narrow type") {
    assert(
      compileErrors(
        "summon[TraceableAspect[Lookup]](using HandWrittenLookupAspect.instance)"
      ).nonEmpty,
      "Aspect[Lookup, TraceableValue, TraceableValue] must not be a TraceableAspect[Lookup]"
    )
  }

  test("...and fromAspect is how you get one anyway") {
    val fixed: TraceableAspect[Lookup] = TraceableAspect.fromAspect(HandWrittenLookupAspect.instance)
    assert(fixed ne null)
  }
}
