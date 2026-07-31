# Milestone M12 — fused derivation: one carrier-preserving `instrument`

## Status

**Planned, not started.** Branch to create: `milestone/m12-fused-derivation`,
stacked on `milestone/m8-capability-aspect` @ `97c5ece` (which is itself stacked
on the unmerged M6/M10/M11/M7 chain). The Decisions section below was proposed
by the planning session on 2026-07-31 and **needs Brian's ratification before
Task 1 starts**.

Prerequisites: M10 and M11 merged into the working chain (they are — `Err[_]`
and `WeaveInterpreter` are both present at `97c5ece`). M8 is **paused** and
stays paused; see "Scope" below.

The implementation plan is `23-milestone-M12-implementation-plan.md`.

### Why this milestone exists, in one paragraph

§3.2's decision to make `Aspect.Weave` the woven algebra's *effect type* forces
the woven methods to take `Raise[Weave[F, Dom, Cod, *], E]`. `cats.mtl.Raise`
declares an abstract `def functor: Functor[F]`, so satisfying that member
requires a `Functor[Weave[F, Dom, Cod, *]]`, which cannot exist lawfully for
the `Cod` this library's headline feature uses. `WeaveArrows.syntheticWeaveFunctor`
supplies one anyway, by substituting a fabricated `Cod[B]` for the real
`Cod[A]`. That instance **fails the functor identity law**, and the failure is
reachable through a public member whose documented purpose is exactly the call
that triggers it. M12 removes the cause rather than the symptom: fuse `weave`
and `mapK` into one carrier-preserving operation, and no capability ever
crosses a carrier boundary, so no `Functor[Weave]` is ever needed and
`Synthetic` deletes outright.

---

## Problem — the synthesized-`Functor` defect, measured

**This section is the durable home for a finding whose current homes are about
to be deleted.** `Synthetic`'s scaladoc
(`raise-aspect-core/src/main/scala/com/dwolla/tagless/mtl/Synthetic.scala:5-76`)
and `ToRaiseTraceWeaveOps`'s
(`natchez-tagless-mtl/src/main/scala/com/dwolla/tracing/mtl/syntax/RaiseTraceWeaveOps.scala:27-56`)
document the defect at length today. Both go away with the code they describe.
The finding must not go with them: it is the entire reason M12 exists, and
without it the milestone reads as a refactor for taste.

### What was measured

`FunctorTests` from cats-laws, run against the functor reachable through the
public API — literally `WeaveArrows.raiseLift[...].apply(rf).functor`, never a
private copy — on Scala 2.13.18 with the versions in `build.sbt` (cats 2.13.0,
cats-mtl 1.7.0, cats-tagless-core 0.16.5, cats-laws 2.13.0, natchez-core
0.3.10), at three equivalences:

| Equivalence | Result |
| --- | --- |
| Structural (`Eq.fromUniversalEquals`) | **All five laws fail**, on the first generated case |
| Structural modulo `Advice` identity (compare `algebraName`, `codomain.name`, rendered domain, `codomain.target` **and** `codomain.instance` extensionally) | **`covariant identity` and `invariant identity` fail**; composition passes |
| The equivalence laws L6a–L6d actually use | All five pass |

Two independent causes, separated:

1. `map` rebuilds the codomain `Advice`, and `Aspect.Advice` is a plain trait
   overriding `toString` but not `equals`/`hashCode`, so it has reference
   equality. This kills all five laws at rung 1, and it is **not our bug** — it
   is a property of upstream cats-tagless, and *any* `Functor[Weave[…]]`
   whatsoever fails rung 1.
2. `map` substitutes `syn[B]` for the real `Cod[A]`. This kills the **identity**
   laws specifically at rung 2, and it *is* our bug.

The composition law is blind to cause 2 by construction: both sides of the
composition go through `map`, so both end up synthetic and the wrongness is
symmetric. The L6a–L6d suite is green only at an equivalence that omits
`codomain.instance` — the one component the implementation gets wrong. That is
the shape of a law fitted to what the implementation could satisfy, and it
produced false confidence for four milestones.

### Why it is not merely an API-surface wart

`Raise#functor` is public precisely so external generic code can recover the
algebra bundled with the capability rather than demanding a second implicit.
The documented use is:

```scala
def adapt[F[_], E, A, B](fa: F[A])(f: A => B)(implicit R: Raise[F, E]): F[B] =
  R.functor.map(fa)(f)
```

Hand that a **real** weave — the kind a derived `weave` method produces,
carrying a real `TraceableValue` — together with the `Raise[Weave[F, …], E]`
that `raiseLift` builds, and feed the result through the exact expression at
`core/shared/src/main/scala/com/dwolla/tracing/TraceWeaveCapturingInputsAndOutputs.scala:118`:

```
untouched weave  -> (UserRepo.lookup.returnValue, NumberValue(4242))
after adapt(id)  -> (UserRepo.lookup.returnValue, StringValue(«raised»))
```

No type error, no exception, no log line. A span attribute whose value is the
sentinel string on a call that **succeeded and returned `UserId(4242)`**. Every
other attribute is correct, because `map` preserves them, so the span looks
healthy and one field lies. Severity on its own: **medium** — silent wrong span
metadata, worse than a crash for diagnosis.

### The escalation

`Synthetic` is a public trait with a public companion; third parties are
expected to write instances for their own `Cod`. The scaladoc says an instance
must not reveal anything about the value it stands in for. Nothing enforces it.
With a rendering instance — `a => StringValue(a.toString)`, the obvious first
guess — the same substitution runs backwards through a redaction:

```
untouched (redacting instance) -> (PaymentRepo.fetch.returnValue, StringValue(****1111))
after adapt(id) via leaky syn  -> (PaymentRepo.fetch.returnValue, StringValue(Card(4111111111111111)))
```

A redaction hole reachable through two public extension points and one
documented-as-intended API call, with no unsafe cast and no compiler warning.
**Severity: high, and compliance-class in a payments codebase.**

### Why it cannot be fixed in place

- A lawful `Functor[Weave[F, Dom, Cod, *]]` at the modulo-`Advice` equivalence
  requires exactly a `Functor[Cod]` — verified by building one and watching all
  five rung-2 laws pass. `Invariant[Cod]` and `Contravariant[Cod]` both need a
  `B => A` that `Functor#map` does not have; the shape of `map` is the
  obstruction, not the availability of evidence.
- `cats.tagless.Trivial` has a lawful `Functor` (one cached inhabitant for every
  `A`), so `traceWithInputs[Trivial]` is **already sound today**.
- `natchez.TraceableValue` cannot have one. Its type parameter occurs only in
  negative position and it ships its own `contramap`; the two candidate
  implementations both fail (one throws, one ignores its argument and fails the
  identity law). Requiring `Functor[Cod]` would therefore not fix
  `traceWithInputsAndOutputs` — it would **delete** it.
- Not exposing a `Functor` at all is impossible: `Raise` declares it abstract.
  Throwing from it converts a metadata bug into an outage and breaks the
  legitimate shell-weave case.

That exhausts the in-place options, which is what makes a structural change the
right answer rather than an over-reaction.

### Worth filing upstream (not blocking)

`cats.tagless.aop.Aspect.Advice` should have structural `equals` (or be a case
class). Nothing here depends on it, but it is why rung 1 is unreachable for any
implementation and why every downstream law suite has to invent a
`WeaveRenderer`.

---

## The design: fuse `weave` and `mapK`

Today `RaiseAspect` has two operations and the tracing syntax always composes
them immediately (`WeaveInterpreter.fromRaiseAspect` is
`A.mapK(A.weave(alg))(RaiseArrow(fk, raiseLift(onRaise)))`). Weaving alone is
never a thing a caller wants; it is an intermediate value whose only consumer
is the very next `mapK`. M12 makes that composition the primitive:

```scala
trait RaiseAspect[Alg[_[_]], Dom[_], Cod[_], Err[_]] extends RaiseFunctorK[Alg, Err] {
  def instrument[F[_]](af: Alg[F])(
      fk: Aspect.Weave[F, Dom, Cod, *] ~> F,
      onRaise: OnRaise[F, Err]
  )(implicit F: Apply[F]): Alg[F]
}
```

`Aspect.Weave` is built as **data** and handed straight to `fk`. It is never an
effect type, so no method ever receives a `Raise[Weave[F, Dom, Cod, *], E]`,
so nothing has to synthesize a `Functor` for that carrier, so `Synthetic` has
no job. The capability the underlying implementation receives is the caller's
own `Raise[F, E]`, decorated with the observation hook *at the same carrier*:

```scala
object RaiseAspect {
  def observing[F[_], E, Err[_]](R: Raise[F, E], onRaise: OnRaise[F, Err])(implicit
      F: Apply[F],
      ev: Err[E]
  ): Raise[F, E] =
    new Raise[F, E] {
      val functor: Functor[F] = R.functor

      def raise[E2 <: E, A](e: E2): F[A] =
        onRaise.apply[E](e)(ev) *> R.raise[E2, A](e)
    }
}
```

`functor = R.functor` is the caller's own real `Functor[F]`. Nothing is
synthesized, and law L7 (the pulled capability reports the ambient functor)
stops being a property to check and becomes an identity to assert with `eq`.
The decorator is **sound by construction**: it can only produce what `R`
produces, prefixed by the hook's effect.

`Weave` survives as data passed to the interpreter, so
`TraceWeaveCapturingInputs` and `TraceWeaveCapturingInputsAndOutputs` keep
their exact existing types, and `RaiseTraceWeaveOps`' public signatures do not
change at all.

---

## Decisions (proposed — ratify before starting, then final)

**D1 — Keep the type name `RaiseAspect` and the entry point
`DeriveRaise.aspect`; the *method* becomes `instrument`; the decorator lives at
`RaiseAspect.observing`.**

The macro spike wrote its validated code as a new `RaiseInstrument` type with a
`DeriveRaise.instrument` entry point, because it had to coexist with the real
`RaiseAspect` in the same tree. Its own component-fates table recommends the
opposite for the milestone: "`RaiseAspect`: `weave` replaced by `instrument`;
keeps `extends RaiseFunctorK`. Same file, same name." Keeping the name is the
smaller diff by a wide margin — `WeaveInterpreter.fromRaiseAspect`,
`RaiseAspectLaws`, `RaiseAspectTests`, `RaiseAspectSuite`,
`ReferenceRaiseAspectSpec`, `AspectPriorityFixtures`, `WeaveInterpreterFixtures`,
the natchez priority machinery and every diagnostic string keep their current
spelling — and it keeps the derivation's rejection messages **byte-identical**,
so `DerivationErrorSpec` needs no edit on either axis. Renaming is a cheap
reversal if Brian prefers `RaiseInstrument`; the plan quotes the spike's code
with exactly two identifiers substituted, and says so at each site.

The earlier spike flagged that `instrument` collides conceptually with
`cats.tagless.aop.Instrument#instrument`, which returns `Alg[Instrumentation[F, *]]`
— a different thing — and suggested `weaveWith`/`intercept`/`interpret`. The
method name `instrument` is what compiled and passed on all three Scala
versions, the collision is nominal rather than structural (there is no
`Instrument` instance anywhere in this repo and no import can make the two
ambiguous), and `RaiseAspect#instrument`'s scaladoc will name the difference.
Flagged so the ratification is informed, not to reopen it.

**D2 — Keep the hierarchy: `RaiseAspect extends RaiseFunctorK`, one derivation
emits both `instrument` and `mapK`.**

This is the higher-risk of the two shapes and it was chosen for the spike
*because* it was the higher-risk one — if `instantiate`/`newClassOf` could only
fill one generated method per derivation, that would be a real finding. It
works on both axes with no extra machinery: on Scala 2 it is
`instantiate[…](raiseInstrument(…), raiseMapK(Err))`, the same two-`MethodDef`
call `aspect` makes today; on Scala 3 it is one more member in the quoted class
body. It costs nothing, preserves the existing one-call ergonomics, and keeps
`RaiseFunctorKTests` composable into `RaiseAspectTests` as a parent rule set,
which the discipline suites depend on.

Consequence, and it is a *reason* for D2 rather than a cost of it: `validate`'s
rejection of `F` in a parameter position **stays**, unchanged and re-tested,
because the shared derivation still emits `mapK`, which genuinely cannot handle
it.

**D3 — `Raise`-only recognition is retained. M8 stays paused.**

The fused form makes other cats-mtl capabilities admissible: a prior spike
demonstrated `Handle`, `Local`, `Listen`, `Censor` and `Stateful` all passing
through and working, over an `RWST` base, with the implementation genuinely
invoking `local`, `listen`, `handleWith`, `modify`/`get` — and requiring only
`Apply[F]`, with no `Applicative[F]` tax for `Ask` and no `Monad[F]` tax for
`Stateful`. The reason is one sentence: the fused expansion never *transports*
a capability, so it never needs the capability's evidence member on a foreign
carrier, so the producing-vs-consuming distinction has nothing to bite on.

**None of that is in scope here.** `capabilityError` keeps its exact-symbol
comparison against `cats.mtl.Raise`; `isHandle`/`handleError` keep producing the
"Handle consumes F" diagnostic; `validate`/`validateParams` keep rejecting every
other mention of `F` in a parameter; and every one of those rejections is
re-asserted against the fused entry point. Whether to admit more is M8's
decision and Brian has not made it. Two hazards recorded there stay live and
would have to be answered first: decorating a `Handle` with `observing`
silently downcasts it to `Raise` and drops `handleWith`, and the hook would
fire for raises that never escape.

M8's four-of-nine transportability classification does **not** expire — it
relocates, from "which capabilities can be traced" to "which capabilities can
be `mapK`'d". `mapK` keeps real transport and the classification still governs
it exactly as written.

**D4 — The law surface shrinks with the machinery it describes.** See the Laws
section. L5 and L6a–d vanish because the functions they are laws *about* cease
to exist; L7 becomes an `eq` assertion; L3 becomes L3′.

**D5 — No compatibility shims, deprecated overloads, or dual code paths.** The
two-operation form is deleted, not deprecated. This follows M10's decision D7
and the same justification applies unchanged: every one of these types is
unpublished (`mimaPreviousArtifacts := Set.empty` on all four modules) and the
break is deliberate rather than incidental.

**D6 — `raise-aspect-laws` is not frozen for this milestone, but the *meaning*
of every surviving law is.** M10's decision D2 set the precedent: mechanically
retyping a frozen file, forced by a ratified design, is not a weakening. M12
follows it, and says so out loud. The test is the same one M10 used: if
implementing a law change requires editing either operand of a `<->`, stop and
report — that is an overview-level question, not a milestone-level fix. Laws
that are *deleted* are a different matter and are enumerated explicitly below,
each with the function whose disappearance justifies it.

**D7 — The differential-oracle rewrite gets its own review gate.** It is the
largest single chunk of remaining work and the easiest place to quietly lose
coverage. Task 6 of the plan is an assertion-by-assertion audit with no
production code in it.

---

## What changes

### Component fates

| Component | Fate |
| --- | --- |
| `RaiseAspect` | `weave` replaced by `instrument`; keeps `extends RaiseFunctorK`. Same file, same name (D1). Gains a companion holding `observing`. |
| `RaiseFunctorK` | **untouched.** |
| `RaiseArrow` | **untouched.** Still what `mapK` travels along. |
| `RaisePull` | **untouched.** Still `RaiseArrow`'s backward half. |
| `OnRaise` | **untouched.** |
| `WeaveArrows` | reduced to `codomainTarget`. `raisePull`, both `raiseLift` overloads, `eraseWeave` and the private `syntheticWeaveFunctor` delete; the file goes from 134 lines to ~25. |
| `Synthetic` | **deleted outright**, along with the defect that motivated it. |
| `WeaveInterpreter` | **shape unchanged** — its `apply` is already `instrument`'s signature. `fromAspect` unchanged. `fromRaiseAspect` becomes a one-liner and drops its `implicit syn: Synthetic[Cod]`. The low/high-priority resolution mechanism survives whole. |
| `WeaveKnot` (in `core`) | untouched — already fused-shaped (`Alg[F] => Alg[F]`). |
| natchez syntax | `traceWithInputs`/`traceWithInputsAndOutputs` signatures **unchanged**. `syntheticTraceableValue` and its 30-line caveat delete. `RaiseRecorder` untouched. |
| Scala 2 macro | `raiseWeave` → `raiseInstrument`; `substituteCapabilities` and `Method#transformedParamLists` become provably dead and delete. |
| Scala 3 macro | `deriveWeave` → `deriveInstrument`. |
| Every rejection diagnostic | **unchanged**, on both axes, re-asserted against the fused entry point. |

### Laws

**Survive unchanged in meaning:** L1/L2 (`RaiseFunctorK` identity and
composition), L4 (arrow coherence, on the retained `RaiseArrow`), L9
(conservative extension), L10 (laziness parity).

**Change:**

- **L3 → L3′.** `mapK(weave(af))(eraseWeave) <-> af` becomes
  `instrument(af)(codomainTarget, OnRaise.noop) <-> af`. Demonstrated passing
  against both derived instances on all three Scala versions. L3′ is weaker in
  one sense — there is no second operation for the first to be inverse to — but
  the content L3 actually carried (a raise comes back as the identical value
  through the woven path) is exactly what L3′ states. Its effect constraint
  strengthens from `Functor[A]` to `Applicative[A]`, because `OnRaise.noop`
  needs `Applicative` and `instrument` needs `Apply`.
- **L8 re-anchors** from inspecting `Alg[Weave[…]]` values to inspecting what a
  recording `fk` receives. Strictly stronger: it additionally pins the *order*
  in which weaves reach the interpreter, which value inspection cannot see.
- **L1/L2's non-identity instantiation needs a new arrow.** Today the second
  `checkAll` in `RaiseAspectSuite` exercises `mapK` over `eraseWeave`, an arrow
  between `Woven` and `Result`. Both endpoints disappear. The replacement is a
  genuine carrier change, `Either[TestError, *] → EitherT[Eval, TestError, *]`,
  whose forward half is `EitherT.fromEither` and whose backward pull transports
  along `.value.value`. The derivation spike demonstrated `mapK` across exactly
  this pair; the specific `RaiseArrow` value is new fixture work and is a
  task-level check, not an assumption. **Do not** let L1/L2 end up tested only
  at the identity arrow — that is a real coverage loss disguised as a deletion.

**Vanish, with the machinery they describe:**

- **L5** (section/retraction on `raiseLift`/`raisePull`) — both functions cease
  to exist.
- **L6a–L6d** (synthesized-functor coherence) — they are laws *about*
  `syntheticWeaveFunctor`, which ceases to exist. This is a reduction in law
  surface matching a reduction in machinery, not lost coverage; and per the
  Problem section, three of the four were only ever green at an equivalence
  chosen to avoid the broken field.
- **L7** does not so much vanish as become trivially true: `observing` sets
  `functor = R.functor`, so the suite asserts that identity directly with `eq`
  rather than extensionally.
- The L8 sub-test "the synthesized `Cod` instance never appears in a woven
  method's domain" goes with `Synthetic`: after M12 there is no synthesized
  instance for it to look for.

### Tests

**Survive unchanged:** `ExpectedWeaves.expected` (**byte for byte** —
demonstrated), `TestFixtures`, `OnRaiseSpec`, `RenderErrorInstancesSpec`,
`DerivationErrorSpec` on both axes, all of `natchez-tagless-mtl`'s integration
suites (`RaiseTraceIntegrationSuite`, `RaiseTraceValueSuite`, both priority
specs) — demonstrated indirectly by the natchez spike reproducing their exact
command histories through the real `InMemory` backend with no `Synthetic` in
scope.

**Need transposing** (mechanical; technique demonstrated on the hardest
sample): `TestAlgReference` and its two specs, `EvidenceThreadingSpec`,
`WeaveInterpreterSpec`, `RaiseAspectSuite`'s L8/L10 blocks,
`ConservativeExtensionSuite`, `ExpectedWeaves.rendered`, and on each macro axis
`DifferentialOracleSpec`, `CrossVersionAgreementSpec`, `EdgeCaseDerivationSpec`,
`MethodLocalInstanceSpec`, `DerivedRaiseAspectSpec`,
`DerivedConservativeExtensionSpec`, plus Scala 3's `UsingAlgSpec`.

**Become meaningless and delete:** `SyntheticTraceableValueSpec`, most of
`WeaveArrowsSpec` and `WeaveArrowsOnRaiseSpec`, `RaiseAspectSuite`'s L5/L6/L7
blocks and their `Err = Trivial` twins. They test functions that will not
exist. **The hook coverage inside `WeaveArrowsOnRaiseSpec` — fires exactly once
per raise, sequenced before the raise, never on a success path — must be
rescued into a spec against `observing` *before* the deletion**, not after.
The plan orders it that way deliberately.

### Two corrections to the spike's inventory, found while planning

The macro spike's §4.4 lists these as surviving unchanged. They do not, and
both call functions that M12 deletes:

1. **`EvidenceThreadingSpec`** (`raise-aspect-core`) is built on
   `WeaveArrows.raiseLift(hook)`. It transposes onto `RaiseAspect.observing`,
   keeping its name and its intent (the `Err[E]` evidence reaches the hook).
2. **`ConservativeExtensionSuite`** (laws) and
   **`DerivedConservativeExtensionSpec`** (both macro axes) call
   `ours.weave(impl)` in two of their three L9 comparisons. L9's *content*
   survives — our derivation still agrees with upstream `Derive.aspect` on a
   capability-free algebra — but the comparison has to run through a recording
   `fk` on our side against upstream's `weave` on theirs.

Neither is difficult; both are unbudgeted in the spike's line counts.

### Sizing

≈ 900 lines deleted (≈ 450 source, ≈ 450 docs), ≈ 700 new or changed. The macro
merge, which is the part that gated the whole idea, is **~130 lines net across
both axes** and is already written and compiled. What remains is deletion plus
mechanical test transposition. One milestone.

---

## Evidence: demonstrated versus argued

Carried from the three spikes, all of which reverted their code. **The spike
reports live in `.superpowers/sdd/` and are untracked**, so the load-bearing
claims are restated here rather than cited.

**Demonstrated** — compiled and run on 2.12.21, 2.13.18 and 3.3.8, JVM tests
and JS `Test/compile`, with the full existing suite passing alongside:

- Both macros generate the fused form for all five `TestAlg` methods. It
  compiled and passed **on the first attempt on all three axes, including
  2.12**, which has been the risky axis throughout. The reason is visible in
  the generator: the fused Scala 2 path *removes* the two things that have
  caused 2.12 grief — carrier substitution in parameter types, and constructing
  a retyped `implement(appliedType(algebra, WeaveF))` — and never builds a type
  the compiler has not already seen in the algebra's own declaration.
- The generated code is behaviourally identical to today's `weave`+`mapK`
  composition, over the exhaustive input domains and all three `eOutcome`s,
  including an identical **ordered** log of what a recording `fk` saw,
  interleaved with hook firings.
- All of M3's edge cases and all of M7's method-local `Dom`/`Cod`/`Err`
  fixtures derive and pass. Method-local `Err` is now *directly* observable,
  since the hook receives the resolved `Err[E]`.
- Every rejection diagnostic is unchanged, re-asserted against the fused entry
  point: `Handle`, effectful parameter, nested `F[F[A]]` return, buried `F`
  return, capability without an `F[?]` return, Scala 3's context-function
  return, missing `Dom`/`Cod`/`Err`, the non-implicit-conforming-parameter
  hint, the method-local ambiguity, and the alias-dealiasing success case.
- L3′ holds. The hook fires exactly once per raise, never on a success path,
  and renders through `Err[E]` (proved by a prefixed rendering, not `toString`).
  Two different error types on one method resolve two different instances.
- `ExpectedWeaves.expected` reproduces **byte for byte**.
- One derivation emits both `instrument` and `mapK` on both axes.
- `substituteCapabilities` and `Method#transformedParamLists` become unused —
  the fused generator calls `method.copy(body = …)` and nothing else, and
  `transformedParamLists` has no other caller.
- End to end through the real natchez `InMemory` backend with
  `TraceWeaveCapturingInputsAndOutputs[F]` at its exact existing type and
  `RaiseRecorder[F, TraceableValue].onRaise`, reproducing the command histories
  `RaiseTraceIntegrationSuite` asserts today on both the success and the
  raise/`Handle.rescue` paths, on both the `Kleisli` and `IOLocal` wirings —
  in a file that deliberately does not import `com.dwolla.tracing.mtl.syntax._`,
  so no `Synthetic[TraceableValue]` is in scope.
- Scala 3's `val constant: F[String]` member fuses. Its `fk(Weave(…))` runs when
  the instrumented algebra is *constructed* rather than on access — but today's
  path is eager in the same way, so nothing changes. Worth knowing, not a
  regression.

**Argued from source, not compiled** — each of these is a **task-level check**
in the plan, not an assumption:

- `WeaveInterpreter.fromRaiseAspect`'s five-line rewrite and its dropped
  `Synthetic[Cod]` parameter. Strongly supported: `WeaveInterpreter#apply`'s
  signature is *already* `instrument`'s, and the natchez spike called the same
  `fk`/`onRaise` pair by hand.
- The `ToRaiseTraceWeaveOps` deletion, and that `RaiseTraceWeaveOps`' public
  signatures are unaffected.
- Every line count above that refers to a file no spike edited.
- That no other cats-mtl capability becomes admissible *by accident*. The
  rejections were re-tested; no attempt was made to construct an algebra that
  slips past them.
- The replacement L1/L2 arrow (see Laws above).

---

## Acceptance criteria

- [ ] `RaiseAspect` has exactly one weaving operation, `instrument`, with the
      signature above; `weave` does not exist anywhere in the tree.
- [ ] `Synthetic` does not exist; `WeaveArrows` contains `codomainTarget` and
      nothing else; `substituteCapabilities` and `transformedParamLists` are
      gone from the Scala 2 macro.
- [ ] No `Functor` is constructed anywhere in the library except by reading
      `R.functor` off a capability the caller supplied. `grep -rn "new Functor"`
      over `raise-aspect-*` main sources returns nothing.
- [ ] `git diff --stat` on
      `raise-aspect-macros/src/test/scala/com/dwolla/tagless/mtl/laws/ExpectedWeaves.scala`
      shows the `rendered` helper changed and `expected` **unchanged**.
- [ ] Every rejection diagnostic in both `DerivationErrorSpec`s passes with its
      current wording, unedited, on 2.12, 2.13 and 3.
- [ ] The natchez integration suites pass with their current expected command
      histories, unedited.
- [ ] The laws suite passes at both `Err = Trivial` and `Err = Render`; L1/L2
      are exercised over a genuine non-identity arrow, not only at identity.
- [ ] `sbt +test` green on 2.12.21, 2.13.18 and 3.3.8; both JS linkers green;
      `natchezTaglessMtlJVM/doc` succeeds; zero new warnings under
      `-Xfatal-warnings`.
- [ ] The unlawful-`Functor` finding survives the deletion of `Synthetic`'s
      scaladoc — this document is where it lives, and the overview points here.

## Ground rules reminder

- `raise-aspect-laws` may change (D6), but no surviving law's `<->` operands
  may. If a law change needs one edited, STOP and report.
- `ExpectedWeaves.expected` must not be edited. Fusion changes which code
  compiles and who holds the weave, not what a woven call produces. If a task
  appears to require editing it, stop and report — something is wrong.
- Do not admit any capability other than `Raise` (D3), and do not remove a
  rejection diagnostic. If a task starts drifting toward M8, stop.
- Never use `--no-verify` or any other hook-bypass flag.
