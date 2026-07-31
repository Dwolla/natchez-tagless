# Milestone M12 — fused derivation: one carrier-preserving `intercept`

## Status

**Complete (2026-07-31).** Branch `milestone/m8-capability-aspect` (M12
continued on it; stacked on the unmerged M6/M10/M11/M7 chain). The Decisions
section below was ratified as proposed; all eight tasks landed as written,
with two corrections found and made along the way (below).

The implementation plan is `23-milestone-M12-implementation-plan.md`; each
task's brief, report, and per-commit review diff live under
`.superpowers/sdd/23-milestone-M12-implementation-plan/`.

**What landed, task by task:**

- **Task 1** (`raise-aspect-core`, `ea84f13`) fused `weave`+`mapK` into
  `RaiseAspect#intercept`, with `RaiseAspect.observing` decorating the
  caller's own `Raise[F, E]` in place, at the same carrier. 43/43 on
  2.12.21/2.13.18/3.3.8, JVM and JS linker, zero warnings.
- **Task 2** (`raise-aspect-core`, `1a52c13`) deleted `Synthetic` and
  `WeaveArrows.raisePull`/`raiseLift`/`eraseWeave`, reducing `WeaveArrows` to
  `codomainTarget` alone. `grep -rn "new Functor" raise-aspect-*/src/main` is
  empty. Zero review findings at any severity.
- **Task 3** (`raise-aspect-laws`, `28f75e1`..`8bf341c`) turned L3 into L3′,
  deleted L5 and L6a–d with the machinery they were laws about, turned L7 into
  an `eq` assertion, and re-anchored L8/L9 on a recording interpreter. One
  review round (2 findings, both addressed) restored a falsifiable `Err =
  Trivial` instantiation for L4 that had degenerated to a tautology. 21/21.
- **Task 4** (Scala 2 macro, `39ad9a3`) renamed `raiseWeave` to
  `raiseInstrument`; every rejection diagnostic is unchanged;
  `ExpectedWeaves.expected` is byte-identical. 58/58, zero warnings.
- **Task 5** (Scala 3 macro, `1603ec9`) renamed `deriveWeave` to
  `deriveInstrument`; cross-axis agreement was verified structurally (the args
  and body handlers build in the same order on both axes), not just by test
  count. 58/58 → 66/66, the new test being `UsingAlgSpec`'s proof that a
  two-capability method decorates both parameters independently.
- **Task 6** (differential-oracle audit, `1603ec9`..`9937804`) is a
  code-free, assertion-by-assertion audit of the pre-M12 oracle, verified by
  injecting seven defects into the hand-written reference and confirming each
  is caught — not by reading. **Headline finding:** the harness was blind to a
  dropped hook. With `OnRaise.noop` in play, the events comparison degenerated
  to the weave-order check `renderedWeaves` already covered, and
  `drop-hook-e-r1` survived the *entire* Scala 2 macro suite and core. Closed
  by `LawsInstances.observed`, whose hook writes into the recorder's log, so
  derived and reference must now agree on hook/weave interleaving and which
  `Err` instance rendered each raise.
- **Task 7** (`natchez-tagless-mtl`, `a74c335`) dropped
  `Synthetic[TraceableValue]` and its 30-line caveat from
  `RaiseTraceWeaveOps`; `traceWithInputs`/`traceWithInputsAndOutputs`
  signatures are byte-identical (confirmed by diff, not assumed), and span
  histories are unmoved (grepping the diff for
  `CreateSpan|Put\(|AttachError|ReleaseSpan` returns nothing).
  `natchezTaglessMtlJVM` 18/18 on all three versions.
- **Task 8** (this task) is this Status section, the overview's §3.2–§3.4/§4,
  and a consequence note on `18-milestone-M8-capability-aspect.md`, plus the
  four carried-forward stale-comment fixes and the `tlFatalWarnings`
  correction below.

**What diverged from the plan, and why:**

- The plan's claim that `substituteCapabilities` and
  `Method#transformedParamLists` become dead code once the fused generator
  lands was **false**: `raiseMapK`, kept unchanged by decision D2, still calls
  `substituteCapabilities` to retype `Raise[F, E]` for its genuine carrier
  change. Found during Task 4, corrected in four places (`5298756`) — both
  acceptance-criteria lists and this document — before Task 5 or the
  whole-branch review could be measured against the false criterion.
- L1/L2's replacement non-identity arrow (`eraseWeave` is deleted) needed new
  fixture work, `CarrierArrows.resultToLazily`
  (`Either[TestError, *] → EitherT[Eval, TestError, *]`), which the plan
  flagged correctly as task-level but did not pre-build; an early version of
  L4's `Err = Trivial` instantiation over this arrow was a tautology (it
  reduced to the same expression on both sides at `RaiseArrow.id`) until Task
  3's review round caught it and moved it onto the non-identity arrow.
- **A claim that `sbt-typelevel` enables `tlFatalWarnings` in CI is false for
  this repo, and had been repeated uncorrected across several M12 task
  dispatches** (it originated in M11's final review). `sbt-typelevel-settings`
  0.8.6 defaults `tlFatalWarnings := false`
  (`TypelevelSettingsPlugin.scala:47`), and nothing in `build.sbt`,
  `project/`, or `.github/` overrides it — `ci.yml`'s env block holds only
  `GITHUB_TOKEN`. Proof: four Scala 3 unused-import warnings exist in `core`
  on `main` today; if fatal warnings were enforced there, `main` would
  already be red. Consequence: tasks that forced `-Xfatal-warnings` locally
  were *stricter* than CI requires, not catching up to a real gate — their
  zero-warning claims stand, just not for the reason originally given.
  Corrected here and in `20-milestone-M10-evidence-carrying-transport.md`,
  `21-milestone-M11-weave-interpreter.md`, and
  `23-milestone-M12-implementation-plan.md`, the only other places the claim
  appeared.

**Verification actually run, with counts:**

- JVM tests green on 2.12.21, 2.13.18, and 3.3.8 (measured directly by Task 8,
  after this document's own edits) — `coreJVM` 19/19, `raiseAspectCoreJVM`
  34/34, `raiseAspectLawsJVM` 21/21, and `natchezTaglessMtlJVM` 18/18, each ×3
  Scala versions unchanged; `raiseAspectMacrosJVM` 59/59 on 2.12.21, 59/59 on
  2.13.18, and 67/67 on 3.3.8 — Scala 3 carries eight more tests than Scala 2
  because `UsingAlgSpec` (`using`-clause fixtures) has no Scala 2 analogue.
  Zero new warnings on any version; the pre-existing four Scala 3 unused-import
  warnings in `core` and the pre-existing doctest outer-reference warning on
  2.12/2.13 are both unchanged from `main`. (`sbt +test` unqualified also
  attempts the JS test-execution projects, which abort for lack of a local
  Node install — the same pre-existing environment gap every prior milestone
  hit; the JVM-scoped run above is what actually exercises the suites.)
- All four JS linkers (`raiseAspectCoreJS`, `raiseAspectLawsJS`,
  `raiseAspectMacrosJS`, `natchezTaglessMtlJS`
  `Test/scalaJSLinkerResult`): green. Test *execution* on JS remains
  uncovered locally (no Node), consistent with every prior milestone.
- `natchezTaglessMtlJVM/doc`: succeeds — the `package.scala` doctests compile
  and run against `intercept`/`RaiseAspect.observing`.
- Both `DerivationErrorSpec`s and every rejection diagnostic: unedited,
  passing on all three versions.
- `git diff --stat` on `ExpectedWeaves.scala`: touches only `rendered` and
  the additive `expectedOrder`; `val expected` is untouched.

**Found along the way, for later milestones:**

- The unlawful-`Functor` finding's only surviving home is this document's
  "Problem" section above; the scaladoc that used to carry it (`Synthetic`,
  `RaiseTraceWeaveOps`) is deleted with the code.
- M8, if resumed, is now deciding a different question than when it paused:
  nothing crosses a carrier boundary post-fusion, so the producing/consuming
  classification governs `mapK` only, not method-parameter admissibility.
  See `18-milestone-M8-capability-aspect.md`'s status section for the note
  recorded there.
- Any future milestone document that states "CI enforces fatal warnings" is
  wrong under this repo's current `sbt-typelevel-settings` version and
  should be corrected the same way this one was.

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
  def intercept[F[_]](af: Alg[F])(
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
`DeriveRaise.aspect`; the *method* becomes `intercept`; the decorator lives at
`RaiseAspect.observing`.**

The macro spike wrote its validated code as a new `RaiseInstrument` type with a
`DeriveRaise.intercept` entry point, because it had to coexist with the real
`RaiseAspect` in the same tree. Its own component-fates table recommends the
opposite for the milestone: "`RaiseAspect`: `weave` replaced by `intercept`;
keeps `extends RaiseFunctorK`. Same file, same name." Keeping the name is the
smaller diff by a wide margin — `WeaveInterpreter.fromRaiseAspect`,
`RaiseAspectLaws`, `RaiseAspectTests`, `RaiseAspectSuite`,
`ReferenceRaiseAspectSpec`, `AspectPriorityFixtures`, `WeaveInterpreterFixtures`,
the natchez priority machinery and every diagnostic string keep their current
spelling — and it keeps the derivation's rejection messages **byte-identical**,
so `DerivationErrorSpec` needs no edit on either axis. Renaming is a cheap
reversal if Brian prefers `RaiseInstrument`; the plan quotes the spike's code
with exactly two identifiers substituted, and says so at each site.

**The method is named `intercept`, ruled by Brian on 2026-07-31.** The spike's
sources called it `intercept`, which collided conceptually with
`cats.tagless.aop.Instrument#intercept` — a different operation, returning
`Alg[Instrumentation[F, *]]`. That collision was nominal rather than
structural, since no `Instrument` instance exists in this repo, but M9 may
one day upstream this work into cats-tagless itself, where both names would
live in one library. `intercept` was among the alternatives the spike raised
and it removes the problem rather than documenting it. No scaladoc anywhere
should explain how this method differs from `Instrument#intercept`; with the
new name there is nothing to distinguish.

**D2 — Keep the hierarchy: `RaiseAspect extends RaiseFunctorK`, one derivation
emits both `intercept` and `mapK`.**

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
| `RaiseAspect` | `weave` replaced by `intercept`; keeps `extends RaiseFunctorK`. Same file, same name (D1). Gains a companion holding `observing`. |
| `RaiseFunctorK` | **untouched.** |
| `RaiseArrow` | **untouched.** Still what `mapK` travels along. |
| `RaisePull` | **untouched.** Still `RaiseArrow`'s backward half. |
| `OnRaise` | **untouched.** |
| `WeaveArrows` | reduced to `codomainTarget`. `raisePull`, both `raiseLift` overloads, `eraseWeave` and the private `syntheticWeaveFunctor` delete; the file goes from 134 lines to ~25. |
| `Synthetic` | **deleted outright**, along with the defect that motivated it. |
| `WeaveInterpreter` | **shape unchanged** — its `apply` is already `intercept`'s signature. `fromAspect` unchanged. `fromRaiseAspect` becomes a one-liner and drops its `implicit syn: Synthetic[Cod]`. The low/high-priority resolution mechanism survives whole. |
| `WeaveKnot` (in `core`) | untouched — already fused-shaped (`Alg[F] => Alg[F]`). |
| natchez syntax | `traceWithInputs`/`traceWithInputsAndOutputs` signatures **unchanged**. `syntheticTraceableValue` and its 30-line caveat delete. `RaiseRecorder` untouched. |
| Scala 2 macro | `raiseWeave` → the fused generator. `substituteCapabilities` and `Method#transformedParamLists` **survive** — `raiseMapK` is their sole caller (the spike's "provably dead" claim held only for the `intercept` path). |
| Scala 3 macro | `deriveWeave` → `deriveInstrument`. |
| Every rejection diagnostic | **unchanged**, on both axes, re-asserted against the fused entry point. |

### Laws

**Survive unchanged in meaning:** L1/L2 (`RaiseFunctorK` identity and
composition), L4 (arrow coherence, on the retained `RaiseArrow`), L9
(conservative extension), L10 (laziness parity).

**Change:**

- **L3 → L3′.** `mapK(weave(af))(eraseWeave) <-> af` becomes
  `intercept(af)(codomainTarget, OnRaise.noop) <-> af`. Demonstrated passing
  against both derived instances on all three Scala versions. L3′ is weaker in
  one sense — there is no second operation for the first to be inverse to — but
  the content L3 actually carried (a raise comes back as the identical value
  through the woven path) is exactly what L3′ states. Its effect constraint
  strengthens from `Functor[A]` to `Applicative[A]`, because `OnRaise.noop`
  needs `Applicative` and `intercept` needs `Apply`.
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
- One derivation emits both `intercept` and `mapK` on both axes.
- ~~`substituteCapabilities` and `Method#transformedParamLists` become
  unused.~~ **False — corrected during Task 4.** The fused generator does call
  `method.copy(body = …)` and nothing else, but `raiseMapK` still calls
  `substituteCapabilities` to retype `Raise[F, E]` → `Raise[G, E]` for its
  genuine carrier change, and decision D2 keeps `mapK` in the same derivation.
  Both helpers survive with exactly one caller. The spike's claim held only
  for the `intercept` path.
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
  signature is *already* `intercept`'s, and the natchez spike called the same
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

- [ ] `RaiseAspect` has exactly one weaving operation, `intercept`, with the
      signature above; `weave` does not exist anywhere in the tree.
- [ ] `Synthetic` does not exist; `WeaveArrows` contains `codomainTarget` and
      nothing else. `substituteCapabilities` and `transformedParamLists` **survive**, with
      `raiseMapK` as their sole caller — see the correction note below.
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
