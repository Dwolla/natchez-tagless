package com.dwolla.tracing

import cats.Id
import cats.tagless.Derive
import cats.tagless.aop.{Aspect, Instrument}
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

  /** `Aspect` has three members, not two: `weave` and the inherited
    * `FunctorK.mapK` are abstract, but `instrument` is concrete ''and''
    * overridable, so a wrapper that forwards only the two abstract ones
    * silently re-derives `instrument` from `Aspect`'s default instead of
    * calling the instance it was built from. The oracle overrides it, which is
    * the only reason this assertion can tell the two apart.
    */
  test("instrument forwards to the underlying instance, including its override") {
    val i = narrow.instrument(impl).get("k")
    val w = wide.instrument(impl).get("k")

    assertEquals(i.algebraName, w.algebraName)
    assertEquals(i.methodName, w.methodName)
    assertEquals(i.value, w.value)

    // the oracle's override, not Aspect's default weave-then-mapK derivation,
    // which would answer ("Lookup", "get")
    assertEquals(i.algebraName, "HandWrittenLookup")
    assertEquals(i.methodName, "lookedUp")
    assertEquals(i.value, "v:k")
  }

  test("instrument still reaches Aspect's default when the underlying instance does not override it") {
    val i = summon[TraceableAspect[DerivesLookup]].instrument(DerivesLookup[Id]).get("k")
    assertEquals(i.algebraName, "DerivesLookup")
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
    // asserting on the diagnostic's content, not merely that it is non-empty:
    // a bare `nonEmpty` stays green for any compile error at all, including an
    // unrelated typo or a rename of the fixture
    val errors: String = compileErrors(
      "summon[TraceableAspect[Lookup]](using HandWrittenLookupAspect.instance)"
    )
    assert(errors.contains("Required:"), errors)
    assert(errors.contains("TraceableAspect[com.dwolla.tracing.Lookup]"), errors)
    assert(errors.contains("Aspect[com.dwolla.tracing.Lookup"), errors)
  }

  /** Not a defect — ordinary Scala specificity — but a silent-shadowing hazard
    * worth latching: an algebra that already has a hand-written wide `Aspect`
    * doing something extra in `weave` (redacting an argument, say) and then
    * gains a `derives TraceableAspect` clause quietly stops using the
    * hand-written one, with no error and no warning. The hazard is more
    * reachable here than for `TraceableRaiseAspect`, because `Aspect` is
    * upstream's widely implemented type class and `Aspect extends Instrument`,
    * so an `Instrument` demand is captured too. The escape hatch is lexical
    * scope, which still outranks implicit scope.
    */
  test("a companion's narrow derived instance silently outranks a wide one beside it, and a local wide instance outranks both") {
    val fromCompanion: Aspect[Coexisting, TraceableValue, TraceableValue] =
      summon[Aspect[Coexisting, TraceableValue, TraceableValue]]

    assert(
      fromCompanion eq summon[TraceableAspect[Coexisting]],
      "a wide demand must resolve to the narrow given the derives clause synthesized"
    )
    assert(
      fromCompanion ne Coexisting.wide,
      "the hand-declared wide instance is the one that loses, silently"
    )
    assert(
      summon[Instrument[Coexisting]] eq summon[TraceableAspect[Coexisting]],
      "an Instrument demand is captured by the narrow given too, since Aspect extends Instrument"
    )

    val localWide: Aspect[Coexisting, TraceableValue, TraceableValue] =
      Derive.aspect[Coexisting, TraceableValue, TraceableValue]

    locally {
      implicit val shadow: Aspect[Coexisting, TraceableValue, TraceableValue] = localWide

      assert(
        summon[Aspect[Coexisting, TraceableValue, TraceableValue]] eq localWide,
        "a lexically scoped wide instance must beat the companion's narrow one"
      )
    }
  }

  test("...and fromAspect is how you get one anyway") {
    val fixed: TraceableAspect[Lookup] = TraceableAspect.fromAspect(HandWrittenLookupAspect.instance)
    assert(fixed ne null)
  }
}
