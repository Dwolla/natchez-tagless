# raise-aspect-laws — law index

The executable specification for `RaiseAspect`. Laws are numbered L1–L10.
The numbers are stable identifiers that test names refer to, so a law removed
along with the API it specified leaves a gap rather than a renumbering. L3 is
styled `L3′` in code and here: it is `intercept`'s own analogue of an original
two-step `weave`/`mapK` law, not a re-verification of that law (see
`ARCHAEOLOGY.md`, "`RaiseAspect#intercept` fuses `weave` and `mapK`").

Existing laws must not be weakened or modified. A law is removed only together
with the API it specifies, and only with the maintainer's explicit approval.
A derived instance that fails one of these is a finding about the derivation,
not a reason to edit the law. If you believe a law is genuinely wrong, stop and
raise it for a human decision — don't resolve the tension by loosening the
test.

## Where each law lives

| Law | What it says | Implemented in | Exercised by |
|---|---|---|---|
| L3′ | `intercept(af)(codomainTarget, OnRaise.noop) <-> af` | `laws/RaiseAspectLaws.scala` — `interceptErasure` | `discipline/RaiseAspectTests` → `RaiseAspectSuite` checkAll, at `Result` |
| L7 | the capability decorator reports the ambient `Functor[F]` | true by construction in `RaiseAspect.observing` (`functor = R.functor`), not a checked property | `RaiseAspectSuite`, one unit test: `"L7 the decorated capability reports the caller's own Functor instance"` |
| L8 | weave structure fidelity and capability erasure | concrete tests, not a law trait | `RaiseAspectSuite`, four `"L8 …"` tests; two compare rendered output via `WeaveRenderer`, two compare behavior directly |
| L9 | conservative extension vs. `cats.tagless.Derive.aspect` | `ConservativeExtensionSuite.scala` | `scala-2/ConservativeExtensionSpec` and `scala-3/ConservativeExtensionSpec` (hand-written reference vs. upstream); `scala-2/DerivedConservativeExtensionSpec` and `scala-3/DerivedConservativeExtensionSpec` additionally check the macro-derived instance against both upstream and the hand-written reference |
| L10 | laziness parity — weaving runs no effects, and running the intercepted path runs the same number of effects as the plain one | concrete tests, not a law trait | `RaiseAspectSuite`, two `"L10 …"` tests, over `SyncIO` with a `Ref`-based effect counter |
| — | `RaiseAspect` instances are `Serializable` | `cats.kernel.laws.discipline.SerializableTests` | `RaiseAspectSuite`, the `"RaiseAspect.serializable"` checkAll |

L1, L2, L4, L5, and L6 specified APIs that no longer exist. See
`ARCHAEOLOGY.md`, "Laws removed with their APIs".

## Two things to know before extending this

**`Aspect.Advice` has no `equals`.** It is a trait that overrides `toString`
only, so it compares by reference — and `Weave`, though a case class,
inherits that through its `domain` and `codomain` fields. Two
identically-constructed weaves are never `==`. That's why L8's structural
assertions go through `WeaveRenderer.render` rather than comparing `Weave`
values with `==`. It's also why `TestAlg` equality (`LawsInstances.eqTestAlg`)
doesn't attempt to compare weaves at all: `intercept` returns no `Weave` to
compare, so `eqTestAlg` samples each method over the exhaustive
domains and compares the resulting `F[A]` values extensionally instead.

**Do not summon `Raise[Result, TestError]` into an implicit val.** Writing
`implicit val raiseResult: Raise[Result, TestError] = Raise[Result, TestError]`
resolves the summon on the right to the val being defined and initializes it
to `null`, which surfaces much later as an NPE the first time something calls
`.raise` on it (e.g., inside `RaiseAspect.observing`).
`LawsInstances.raiseResult` is therefore a plain `val`: `Raise`'s companion
supplies the instance, so no local implicit is needed at all — the val only
names the instance the L7 test uses.

## The substitution seam

`RaiseAspectSuite` is abstract in exactly one member:

```scala
def instance: RaiseAspect[TestAlg, Render, Render, Render]
```

`ReferenceRaiseAspectSpec` supplies the hand-written reference instance.
`DerivedRaiseAspectSpec` (one per Scala major version, in
this module's tests) supplies the macro-derived instance and inherits every
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

Note that L3′ compares *behaviour*, so it is blind to metadata-only
defects; L8 is what pins structure. Keep that division in mind when adding
laws.
