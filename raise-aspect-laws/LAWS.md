# raise-aspect-laws — law index

The executable specification for `RaiseAspect`. Laws are numbered L1–L10,
matching the table below. L3 is styled `L3′` in code and here: M12 fused the
original two-step `weave`/`mapK` law into one `intercept`-based property, so
what's tested today is `intercept`'s own analogue of the original statement,
not a re-verification of it.

**Don't weaken, delete, or modify a law below because a derivation is hard to
satisfy.** A derived instance that fails one of these is a finding about the
derivation, not a reason to edit the law. If you believe a law is genuinely
wrong, stop and raise it for a human decision — don't resolve the tension by
loosening the test.

## Where each law lives

| Law | What it says | Implemented in | Exercised by |
|---|---|---|---|
| L1 | `mapK(af)(RaiseArrow.id) <-> af` | `laws/RaiseFunctorKLaws.scala` — `mapKIdentity` | `discipline/RaiseFunctorKTests` → `RaiseAspectSuite` checkAll, at `Result` (bundled with L3′), and again over a genuine carrier change `Result → SyncIO` |
| L2 | `mapK(mapK(af)(f))(g) <-> mapK(af)(f andThen g)` | `laws/RaiseFunctorKLaws.scala` — `mapKComposition` | same as L1 |
| L3′ | `intercept(af)(codomainTarget, OnRaise.noop) <-> af` | `laws/RaiseAspectLaws.scala` — `interceptErasure` | `discipline/RaiseAspectTests` → `RaiseAspectSuite` checkAll, at `Result` |
| L4 | `arrow.fk(arrow.pull(rg).raise(e)) <-> rg.raise(e)` | `laws/RaiseArrowLaws.scala` — `arrowCoherence` | `RaiseAspectSuite`, three properties at `Err = Render`: the identity arrow, the carrier-change arrow (`CarrierArrows.resultToSyncIO`), and that arrow `andThen` the identity arrow; plus the carrier-change arrow again at `Err = Trivial` |
| L5 | `raisePull(raiseLift(r)).raise(e) <-> r.raise(e)` | retired at M12 — no value-level content; see below | none — retired |
| L6 | synthesized functor maps the target and preserves `algebraName`/`domain`/`codomain.name` | retired at M12 — no value-level content; see below | none — retired |
| L7 | the capability decorator reports the ambient `Functor[F]` | true by construction in `RaiseAspect.observing` (`functor = R.functor`) since M12, not a checked property | `RaiseAspectSuite`, one unit test: `"L7 the decorated capability reports the caller's own Functor instance"` |
| L8 | weave structure fidelity and capability erasure | concrete tests, not a law trait | `RaiseAspectSuite`, four `"L8 …"` tests; two compare rendered output via `WeaveRenderer`, two compare behavior directly |
| L9 | conservative extension vs. `cats.tagless.Derive.aspect` | `ConservativeExtensionSuite.scala` | `scala-2/ConservativeExtensionSpec` and `scala-3/ConservativeExtensionSpec` (hand-written reference vs. upstream); `raise-aspect-macros`'s `DerivedConservativeExtensionSpec` additionally checks the macro-derived instance against both upstream and the hand-written reference |
| L10 | laziness parity — weaving runs no effects, and running the intercepted path runs the same number of effects as the plain one | concrete tests, not a law trait | `RaiseAspectSuite`, two `"L10 …"` tests, over `SyncIO` with a `Ref`-based effect counter |
| — | `Serializable` for the typeclass instances | `cats.kernel.laws.discipline.SerializableTests` | `RaiseAspectSuite`, two checkAlls: `RaisePull.id` and `RaiseArrow.id` |

**L5, L6, and part of L7 are retired, not merely undocumented.** Before M12,
`RaiseAspect` was modeled as a separate `weave` producing a woven algebra and
a separate `mapK` interpreting it, which required lifting a capability across
the woven carrier (`raiseLift`/`raisePull`) and, for methods with `Raise`
parameters, synthesizing a `Functor` cats-tagless could not derive honestly —
a real defect (see `ARCHAEOLOGY.md`, "`RaiseAspect#intercept` fuses `weave`
and `mapK`"). M12 fused the two into `intercept`, so a `Weave` is now handed
straight to the interpreter as data and no method ever receives a
`Raise[Aspect.Weave[F, Dom, Cod, *], E]`. L5 (retraction through that lift)
and L6 (functor-preservation on that lifted capability) no longer have a
subject to quantify over — `RaiseArrowLaws.scala`'s own comment says as much.
L7's "ambient functor" half survives, but only because `observing` sets
`functor = R.functor` directly rather than synthesizing one — true by
construction, so it's asserted with one identity check instead of a
scalacheck property, and carries no `Err = Trivial` restoration since it
never depended on `Err` in the first place.

**Why L4 carries an `Err = Trivial` copy.** `Render`, the `Err` used
elsewhere in this suite, doesn't cover every `E`, so a law parameterized by
`implicit ev: Err[E]` only holds "for all `E` for which `Err[E]` exists" —
weaker than the `∀E` the law is meant to state. `cats.tagless.Trivial` has
exactly one instance, universal in `E`, so instantiating the law again at
`Err = Trivial` restores the `∀E` quantifier. Before M12 this applied to
L4–L7; today only L4 has value-level content left to restate this way.

## Two things to know before extending this

**`Aspect.Advice` has no `equals`.** It is a trait that overrides `toString`
only, so it compares by reference — and `Weave`, though a case class,
inherits that through its `domain` and `codomain` fields. Two
identically-constructed weaves are never `==`. That's why L8's structural
assertions go through `WeaveRenderer.render` rather than comparing `Weave`
values with `==`. It's also why `TestAlg` equality (`LawsInstances.eqTestAlg`)
doesn't attempt to compare weaves at all: since M12 there's no returned
`Weave` to compare, so `eqTestAlg` samples each method over the exhaustive
domains and compares the resulting `F[A]` values extensionally instead.

**Do not summon `Raise[Result, TestError]` into an implicit val.** Writing
`implicit val raiseResult: Raise[Result, TestError] = Raise[Result, TestError]`
resolves the summon on the right to the val being defined and initializes it
to `null`, which surfaces much later as an NPE the first time something calls
`.raise` on it (e.g., inside `arrowCoherence` or `RaiseAspect.observing`).
`LawsInstances.raiseResult` is therefore a plain `val`: `Raise`'s companion
supplies the instance, so no local implicit is needed at all — the val only
names one instance for the value-level laws, which take the capability as an
explicit parameter.

## The substitution seam

`RaiseAspectSuite` is abstract in exactly one member:

```scala
def instance: RaiseAspect[TestAlg, Render, Render, Render]
```

`ReferenceRaiseAspectSpec` supplies the hand-written reference instance.
`DerivedRaiseAspectSpec` (one per Scala major version, in
`raise-aspect-macros`) supplies the macro-derived instance and inherits every
law above unchanged:

```scala
class DerivedRaiseAspectSpec extends RaiseAspectSuite {
  def instance: RaiseAspect[TestAlg, Render, Render, Render] = DeriveRaise.aspect[TestAlg, Render, Render, Render]
}
```

L9's seam is the same shape, on `ConservativeExtensionSuite.upstream`.

## Non-vacuity

The suite was checked against four deliberately broken instances to confirm
it can fail. Each was caught:

| Injected defect | Caught by |
|---|---|
| wrong `algebraName` | L8 algebra/method names |
| `domain` dropped | L8 domain shape |
| by-name argument forced while weaving | L8 laziness |
| off-by-one codomain target | L3′ intercept erasure, and L8 codomain |

Note that L1–L3′ compare *behaviour*, so they are blind to metadata-only
defects; L8 is what pins structure. Keep that division in mind when adding
laws.
