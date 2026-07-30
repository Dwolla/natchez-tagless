# raise-aspect-laws — law index

The executable specification for `RaiseAspect`. Laws are numbered as in
`docs/plans/raise-aspect/01-overview-design-and-laws.md` §4.

**This module is frozen.** Later milestones may add new test files, but must not
weaken, delete, or modify the laws below. A derived instance that fails one of
these is a finding about the derivation, not a reason to edit the law. If you
believe a law is wrong, stop and raise it for a human decision.

## Where each law lives

| Law | What it says | Implemented in | Exercised by |
|---|---|---|---|
| L1 | `mapK(af)(RaiseArrow.id) <-> af` | `laws/RaiseFunctorKLaws.scala` — `mapKIdentity` | `discipline/RaiseFunctorKTests` → `RaiseAspectSuite` checkAll, at `Result` and at `Woven` |
| L2 | `mapK(mapK(af)(f))(g) <-> mapK(af)(f andThen g)` | `laws/RaiseFunctorKLaws.scala` — `mapKComposition` | same as L1 |
| L3 | `mapK(weave(af))(eraseWeave) <-> af` | `laws/RaiseAspectLaws.scala` — `weaveErasure` | `discipline/RaiseAspectTests` → `RaiseAspectSuite` checkAll, at `Result` |
| L4 | `arrow.fk(arrow.pull(rg).raise(e)) <-> rg.raise(e)` | `laws/RaiseArrowLaws.scala` — `arrowCoherence` | `RaiseAspectSuite`, three properties at `Err = Render`: `id`, `eraseWeave`, and `eraseWeave andThen id`; plus `eraseWeave` again at `Err = Trivial` |
| L5 | `raisePull(raiseLift(r)).raise(e) <-> r.raise(e)` | `laws/RaiseArrowLaws.scala` — `sectionRetraction` | `RaiseAspectSuite`, "L5 raisePull is a retraction of raiseLift" at `Err = Render`, plus "L5 section/retraction, at Err = Trivial" |
| L6 | synthesized functor maps the target and preserves `algebraName`/`domain`/`codomain.name` | `laws/RaiseArrowLaws.scala` — `liftedFunctorMapsTarget`, `…PreservesAlgebraName`, `…PreservesCodomainName`, `…PreservesDomain` | `RaiseAspectSuite`, "L6a …" and "L6b/c/d …" at `Err = Render`, plus both again at `Err = Trivial` |
| L7 | the pulled `Raise` reports the ambient `Functor[F]` | `laws/RaiseArrowLaws.scala` — `pulledFunctorIsAmbient` | `RaiseAspectSuite`, one property (extensional) plus one `eq` unit test (identity) at `Err = Render`, plus the property again at `Err = Trivial` |
| L8 | weave structure fidelity and capability erasure | concrete tests, not a law trait | `RaiseAspectSuite`, five "L8 …" tests, via `WeaveRenderer` |
| L9 | conservative extension vs. `cats.tagless.Derive.aspect` | `ConservativeExtensionSuite.scala` | `scala-2/ConservativeExtensionSpec` and `scala-3/ConservativeExtensionSpec` |
| L10 | laziness parity — weaving runs no effects | concrete tests, not a law trait | `RaiseAspectSuite`, two "L10 …" tests, over `EitherT[Eval, TestError, *]` |
| — | `Serializable` for the typeclass instances | `cats.kernel.laws.discipline.SerializableTests` | `RaiseAspectSuite`, four checkAlls |

**Why L4/L5/L6/L7 each carry an `Err = Trivial` copy.** M10 added an
`implicit ev: Err[E]` parameter to these value-level laws, which narrows each
from "for all `E`" to "for all `E` for which `Err[E]` exists" — a strictly
weaker statement than what M1–M9 established, since `Render` doesn't cover
every `E`. `cats.tagless.Trivial` has exactly one instance, universal in `E`,
so instantiating each law again at `Err = Trivial` restores the original
`∀E` quantifier. The human partner ruled that all seven value-level law
properties (L4's three, L5's one, L6's two, L7's one) must carry that
restoration, not just a representative subset — do not "consolidate" them
away as redundant with the `Render` instantiations; they are the only thing
proving the laws still hold at their pre-M10 strength.

## Two things to know before extending this

**`Aspect.Advice` has no `equals`.** It is a trait that overrides `toString`
only, so it compares by reference — and `Weave`, though a case class, inherits
that through its `domain` and `codomain` fields. Two identically-constructed
weaves are never `==`. Every structural comparison therefore goes through
`WeaveRenderer.render`, and `LawsInstances.eqWoven` combines that with an
`Eq[F[A]]` on the codomain target. Do not "simplify" it to `Eq.fromUniversalEquals`.

**Do not summon `Raise[Result, TestError]` into an implicit val.** Writing
`implicit val raiseResult: Raise[Result, TestError] = Raise[Result, TestError]`
resolves the summon to the val being defined and initializes it to `null`, which
surfaces much later as an NPE inside `raiseLift`. `LawsInstances.raiseResult` is
therefore a plain `val`: `Raise`'s companion supplies the instance, so no local
implicit is needed at all — the val only names one instance for the value-level
laws, which take the capability as an explicit parameter. `raiseWoven` *is* an
`implicit val`, because cats-mtl has no instance for the woven carrier and
`eqTestAlg[Woven]` is summoned by `checkAll`; it is safe because its right-hand
side names `raiseResult` rather than summoning a `Raise[Woven, TestError]`.

## The substitution seam

`RaiseAspectSuite` is abstract in exactly one member:

```scala
def instance: RaiseAspect[TestAlg, Render, Render, Render]
```

`ReferenceRaiseAspectSpec` supplies M1's hand-written reference instance. M3 and
M4 add sibling classes supplying macro-derived instances, and inherit every law
above unchanged:

```scala
class DerivedRaiseAspectSpec extends RaiseAspectSuite {
  def instance: RaiseAspect[TestAlg, Render, Render, Render] = DeriveRaise.aspect[TestAlg, Render, Render, Render]
}
```

L9's seam is the same shape, on `ConservativeExtensionSuite.upstream`.

## Non-vacuity

The suite was checked against four deliberately broken instances during M2 to
confirm it can fail. Each was caught:

| Injected defect | Caught by |
|---|---|
| wrong `algebraName` | L8 algebra/method names |
| `domain` dropped | L8 domain shape |
| by-name argument forced while weaving | L8 laziness |
| off-by-one codomain target | L3 weave erasure, and L8 codomain |

Note that L1–L3 compare *behaviour*, so they are blind to metadata-only defects;
L8 is what pins structure. Keep that division in mind when adding laws.
