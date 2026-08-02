# Milestone M14 — `TraceableAspect`: `derives` ergonomics for cats-tagless's `Aspect`

## Status

**Complete (2026-08-02).** Branch `milestone/m14-traceable-aspect`, stacked on
M13 at `9fce741`. The Decisions section below (D1–D6) was ratified as proposed
and implemented as written; **Q1 was answered "no"** — same ruling as M13's,
recorded in Open questions — so this milestone ships exactly `TraceableAspect`
and no `Trivial`-codomain sibling. The implementation plan is
`27-milestone-M14-implementation-plan.md`; each task's brief, report, and
per-commit review diff live under
`.superpowers/sdd/27-milestone-M14-implementation-plan/`.

A pre-flight commit (`fb61cc6`) corrected the `@experimental` placement rule in
both M14 documents before any brief was written — three sites here, eight in the
plan. The correction was re-measured against cats-tagless 0.16.5's real
`Derive.aspect` rather than carried over from M13 on faith.

M13, M14 and M15 were planned together on 2026-08-02 and are independent of one
another. M14 is M13's idea one level down: the same `derives` ergonomics, for
plain cats-tagless `Aspect` rather than this library's `RaiseAspect`, in `core`
rather than `natchez-tagless-mtl`.

**What landed, task by task:**

- **Task 1** (`1c463fc`) added
  `core/shared/src/main/scala-3/com/dwolla/tracing/TraceableAspect.scala` — the
  trait, the implicit summoner `apply`, and the non-`inline` `fromAspect`
  factory — plus `LookupFixture.scala` (the `Lookup` algebra and its
  hand-written `Aspect` oracle) and the first four `TraceableAspectSpec` tests
  (`weave` forwarding, `mapK` forwarding, inherited `instrument`, and the
  positive subsumption assignment with no cast). coreJVM 3.3.8 **23/23**
  (19 + 4 new), 2.13.18 and 2.12.21 **19/19** each, unchanged from `main`.
- **Task 2** (`89ca776`) added `@experimental inline def derived`, the first
  `derives TraceableAspect` algebra (`DerivesLookup`, with its `@experimental`
  on the *companion object*), the negative `compileErrors` subsumption test, and
  `TraceableAspectSerializationSpec`. coreJVM 3.3.8 **29/29**; 2.12/2.13
  unchanged at 19/19 by construction.
- **Task 3** (`842b297`) added `TraceableAspectTracingSpec`, the end-to-end
  ergonomics gate: `DerivesLookup` traced through the **unedited**
  `com.dwolla.tracing.syntax` against natchez `InMemory` produces the same span
  history as the hand-written `Lookup` oracle, differing only in the algebra
  name (both sides call one `historyFor(alg)` helper, so a hand-copied
  coincidence is structurally impossible). It also folded in two Task 2 review
  minors. coreJVM 3.3.8 **33/33**; 2.12/2.13 unchanged at 19/19.
- **Task 4** (this commit) is documentation. It resolved the four-year-old
  `TODO` in `TraceWeaveCapturingInputsAndOutputs`' scaladoc, scoped a false
  sentence in `TraceableAspectTracingSpec`'s class scaladoc, and wrote this
  status.

**What diverged from the plan, and why:**

- **The serialization spec's path.** Task 2's brief said
  `core/jvm/src/test/scala/`; it landed at `core/jvm/src/test/scala-3/`. The
  brief's path compiles on all three Scala versions, and the file references
  `DerivesLookup`, which exists only under `core/shared/src/test/scala-3` — so
  the brief's path would have been a hard compile failure on 2.12 and 2.13, not
  a warning. sbt composes the platform and Scala-version source axes with no
  build change, so the fix cost nothing. D5's premise ("`core` already has a JVM
  test source directory") was right about the platform axis and silent about the
  version axis.
- **`roundTrips` → `roundTrip`.** Task 2's serialization helper never called
  `readObject`, and its `bytes.size() > 0` assertion was tautological
  (`ObjectOutputStream`'s constructor writes a four-byte header). It did gate
  the real risk — `writeObject` throws `NotSerializableException` if
  `fromAspect`'s wrapper captures something unserializable — but the plural name
  asserted a round *trip* that did not happen, and that false claim had already
  propagated into `89ca776`'s commit message. Task 3 renamed it, made it
  actually deserialize through `ObjectInputStream`, and dropped the tautology.
- No divergence from the design (D1–D6). The trait, the one-way subtype
  relationship, the Scala-3-only placement, the non-`inline` factory, the
  `TODO`-resolved-not-deleted treatment of the doctest, and the
  differential-plus-end-to-end test shape all landed exactly as proposed.
- The **arity correction** is carried in the shipped code, not only here:
  `TraceableAspect`'s class scaladoc has a "(Two, not three: …)" paragraph
  naming `com.dwolla.tracing.mtl.TraceableRaiseAspect` as the type the third
  parameter belongs to. It is a backtick reference rather than a `[[…]]` link
  on purpose — `core` does not depend on `natchez-tagless-mtl`, so a scaladoc
  link would break `doc`.

**Verification actually run** (final state, at Task 4):

- `+coreJVM/test`: 2.12.21 **19/19**, 2.13.18 **19/19** — both identical to
  `main` — and 3.3.8 **33/33** (19 pre-existing + 14 new: 4 from Task 1, 6 from
  Task 2, 4 from Task 3). Zero failures, zero errors on every axis.
- `ImplicitPrioritizationSpec` run deliberately rather than incidentally: 3/3 on
  all three versions.
- `+coreJS/Test/scalaJSLinkerResult`: green on all three Scala versions. **This
  means the linker succeeded, not that any test ran** — see the carry-forward
  below.
- `+coreJVM/doc`: "Main Scala API documentation successful" on all three
  versions, including the `{{{ }}}` doctest on `derived`, which contains both
  `derives` and `@experimental` (argued-not-demonstrated in the plan, now
  demonstrated in-tree).
- **MiMa, via `mimaFindBinaryIssues`** — deliberately *not*
  `mimaReportBinaryIssues`, which prints nothing on success and so cannot
  distinguish "clean" from "compared against nothing". 34 comparisons, every one
  `(List(), List())`: `coreJVM` and `coreJS` each against 7 previous artifacts
  on 2.12.21, 7 on 2.13.18 and 3 on 3.3.8 (`tlVersionIntroduced` limits the
  Scala 3 axis to 0.2.4–0.2.6). **No `mimaBinaryIssueFilters` entry was added at
  any point in this milestone.**
- **The inline-duplication invariant, re-checked on a clean 3.3.8 rebuild**
  (`coreJVM/clean` then `coreJVM/Test/compile`, so the artifacts provably
  postdate the last source edit): exactly **one**
  `com/dwolla/tracing/TraceableAspect$$anon$1.class` in
  `core/jvm/target/scala-3.3.8/classes/` and **zero** in `test-classes/`,
  despite two inline sites (`DerivesLookup`'s `derives` clause and the doctest's
  `Greeter`). Zero occurrences of "duplicated at each inline site" anywhere in
  the build log. The non-`inline` `fromAspect` factory is doing its job.
- **Warnings:** exactly the four pre-existing Scala 3 unused-import warnings in
  `core/shared/src/main/scala/com/dwolla/tracing/syntax/`
  (`InstrumentableAndTraceableOps.scala:4`,
  `ResourceInitializationSpanOps.scala:5`,
  `InstrumentableAndTraceableInKleisliOps.scala:5`, `TraceParamsOps.scala:4`)
  and no others. `-Xfatal-warnings` cannot be forced on this module for that
  reason; the specific regression it would have guarded against — the inline
  duplication warning — was grepped for directly instead. Per M12's precedent
  the four are flagged, not fixed here.
- `git status` at Task 4 shows exactly two modified files
  (`TraceWeaveCapturingInputsAndOutputs.scala`,
  `TraceableAspectTracingSpec.scala`) and `git diff --name-only fb61cc6..HEAD`
  over `core/shared/src/main/scala/` shows the *only* pre-existing main source
  touched in the whole milestone is that one scaladoc.
  `TraceWeaveOps.scala`, `WeaveInterpreter.scala`, `WeaveKnot`, `build.sbt` and
  every expected span history are untouched — confirmed directly, not assumed.

**How the `TODO` was resolved** (D6, as executed). The scaladoc example stays
verbatim; only its comment changed, from
`// TODO reintroduce derived instance when cats-tagless-macros supports Scala 3`
to a two-line note saying the instance is hand-written so the example compiles
on 2.12 and 2.13, and pointing at a paragraph **outside** the `{{{ }}}` block.
That placement is load-bearing and was verified rather than assumed: everything
inside the block is extracted by sbt-doctest and compiled on all three Scala
versions, where `derives` is a syntax error on two of them. Proof: the
regenerated
`core/jvm/target/scala-{2.12,2.13,3.3.8}/src_managed/test/com/dwolla/tracing/TraceWeaveCapturingInputsAndOutputsDoctest.scala`
differ from their pre-edit selves **only in those comment lines** — code
byte-identical after comment stripping, same SHA-256 on all three — and the new
paragraph appears in none of them.

**Anything found along the way a later milestone needs:**

- **`coreJS` runs zero munit tests, and reports success anyway.** Found during
  Task 1; **pre-existing on `main`, not introduced by M14**, and raised with
  Brian. `build.sbt:65-67` declares `munit-cats-effect`, `scalacheck-effect` and
  `scalacheck-effect-munit` with `%%` instead of `%%%` inside `core`'s **shared**
  `crossProject` `.settings()` block, where every neighbouring dependency
  correctly uses `%%%`. (Line 73's `%%` is fine — it is inside `.jvmSettings`.)
  `coreJS/Test/definedTests` lists only the four scalacheck doctests on all
  three Scala versions. **Consequence for M15 and M16: a green `coreJS/test`
  means nothing.** Every "JS green" claim about `core` anywhere in this stack —
  M14's included — means the linker succeeded, never that tests ran. Not fixed
  here: the M14 plan forbids `build.sbt` changes, and the three-word fix will
  likely surface previously-unrun JS tests, which is its own task. The exact
  mechanism (JVM artifacts on a Scala.js classpath registering no test
  framework, while linking still succeeds) is *not* fully confirmed; the
  zero-test observation and the `%%`/`%%%` discrepancy both are.
- **`derives TraceableAspect` links fine under Scala.js.** The restriction is
  **Scala 3 versus Scala 2, not JVM versus JS.** Do not describe these types as
  JVM-only.
- **`@experimental` is needed only where the given is *summoned*.** Refining
  what the Evidence section below records: the sbt-doctest-generated wrapper
  around `derived`'s example is unannotated and compiles, so a scope enclosing a
  `derives` clause needs the annotation only when it actually summons the
  synthesized given, not merely because the clause is lexically inside it.
- **The `@experimental`-on-the-companion placement is not a clean wash.**
  `derives` has no way to annotate the synthesized given alone, so
  `@experimental object Alg` makes *every* companion member experimental, where
  the hand-written spelling annotates one `implicit val`. Both leave the algebra
  *type* clean, which is the property that matters; an algebra with no declared
  companion should declare an empty `@experimental object Alg` rather than
  annotate the trait.
- **`cats.tagless.aop.Aspect` 0.16.5 has exactly two abstract members**:
  `weave` (`Aspect.scala:39`) and inherited `FunctorK.mapK` (`FunctorK.scala:29`).
  `instrument` (`Aspect.scala:40-41`) is **concrete** and deliberately left
  inherited — verified against the published sources jar, not assumed.
- **Three more copies of the same `TODO` survive in `core`, and M14's answer
  does not fit any of them.** They are at
  `TraceInstrumentation.scala:30`, `RootSpanProvidingFunctionK.scala:31` (both
  `implicit val fooInstrument: Instrument[Foo]`) and
  `TraceWeaveCapturingInputs.scala:72`
  (`implicit val fooTracingAspect: Aspect.Domain[Foo, TraceableValue]`, i.e.
  `Aspect[Foo, TraceableValue, Trivial]`). The two `Instrument` ones are
  answered by **upstream's** `derives Instrument`
  (`object Instrument extends DerivedInstrument`, `Instrument.scala:37`), not by
  `TraceableAspect`; the `Aspect.Domain` one is the `Cod = Trivial` shape that
  **Q1 deliberately declined to build**, so it has no answer today. Left
  untouched on purpose — pointing any of them at `TraceableAspect` would be
  false. Each needs its own, different note; that is a follow-up task, not
  M14's.
- **The one-anon-class invariant is guarded only by prose and a manual `ls`.**
  CI has no fatal warnings — the four pre-existing unused imports would fail
  every build if it did — so a future regression would warn and be ignored. Keep
  the manual check in the checklist of any task that touches `derived`.
- **Forcing `-Xfatal-warnings` on a `crossProject`:** `set` cannot resolve
  `crossProject`-synthesized IDs as Scala expressions. `project coreJVM` first,
  then a project-relative `set Test / scalacOptions += "-Xfatal-warnings"`. (M13
  recorded this for `natchezTaglessMtl`; it applies verbatim to `core`, where it
  is nonetheless unusable because of the four pre-existing warnings.)

### Correction to the request: three type parameters, not four

The request asked for a trait extending `Aspect` with four type parameters.
**`cats.tagless.aop.Aspect` takes three**, and this is not a detail that can be
papered over — the missing fourth is `Err`, which M10 introduced and which only
`RaiseAspect` has:

```scala
trait Aspect[Alg[_[_]], Dom[_], Cod[_]] extends Instrument[Alg] {
  def weave[F[_]](af: Alg[F]): Alg[Aspect.Weave[F, Dom, Cod, *]]
  def instrument[F[_]](af: Alg[F]): Alg[Instrumentation[F, *]] =
    mapK(weave(af))(Aspect.Weave.instrumentationK)
}
```

— cats-tagless-core 0.16.5, `cats/tagless/aop/Aspect.scala:38-42`, mirrored in
this repo at `reference/upstream/core/src/main/scala/cats/tagless/aop/Aspect.scala:38-42`.

`Err` is the per-error-type evidence type class that a raise hook renders a
raised error through. A plain `Aspect` algebra has no `Raise` capability
parameters, so it has nothing to raise and no error to render — `Err` would have
no inhabitant to be about. `WeaveInterpreter.fromAspect` says the same thing
from the other side: it takes an `Err[_]` parameter and *ignores* the
`OnRaise[F, Err]` hook, with a scaladoc explaining that "an algebra with an
`Aspect` instance has no `Raise` capability parameters, so the hook's domain is
empty and it can never fire"
(`raise-aspect-core/src/main/scala/com/dwolla/tagless/mtl/WeaveInterpreter.scala:33-45`).

So `TraceableAspect[Alg]` pins **two** parameters, not three:

```scala
trait TraceableAspect[Alg[_[_]]] extends Aspect[Alg, TraceableValue, TraceableValue]
```

Its abstract members are `weave` and `mapK`; `instrument` has a default and is
inherited for free.

### Why this milestone exists, in one paragraph

The obstruction is the same one M13 documents: `derives X` desugars to
`given X[Alg] = X.derived`, so `X` must be a **one-parameter** type constructor
whose companion carries `derived`. `Aspect` takes three, which is why upstream
cats-tagless has `derives Instrument` but no `derives Aspect` —
`object Instrument extends DerivedInstrument`
(`cats/tagless/aop/Instrument.scala:37`), while `object Aspect` extends nothing
of the kind. Pinning `Dom` and `Cod` to `TraceableValue` in a trait produces the
one-parameter constructor `derives` needs, for the shape this library's own
tracing syntax is built around
(`core/shared/src/main/scala/com/dwolla/tracing/syntax/TraceWeaveOps.scala:21-25`
demands exactly `Aspect[Alg, TraceableValue, TraceableValue]`).

**The codebase has been asking for this since before the raise-aspect work
started.** `TraceWeaveCapturingInputsAndOutputs`' scaladoc carries a
twenty-line hand-written `Aspect` instance with the comment

```
// TODO reintroduce derived instance when cats-tagless-macros supports Scala 3
```

at `core/shared/src/main/scala/com/dwolla/tracing/TraceWeaveCapturingInputsAndOutputs.scala:66-67`.
cats-tagless does support Scala 3 now, via `Derive.aspect`; M14 is what lets
that example collapse to one word.

---

## The design

New file, `core/shared/src/main/scala-3/com/dwolla/tracing/TraceableAspect.scala`:

```scala
package com.dwolla.tracing

import cats.tagless.Derive
import cats.tagless.aop.Aspect
import cats.~>
import natchez.TraceableValue

import scala.annotation.experimental

trait TraceableAspect[Alg[_[_]]] extends Aspect[Alg, TraceableValue, TraceableValue]

object TraceableAspect:
  def apply[Alg[_[_]]](implicit ev: TraceableAspect[Alg]): TraceableAspect[Alg] = ev

  def fromAspect[Alg[_[_]]](
      underlying: Aspect[Alg, TraceableValue, TraceableValue]
  ): TraceableAspect[Alg] =
    new TraceableAspect[Alg]:
      def weave[F[_]](af: Alg[F]): Alg[Aspect.Weave[F, TraceableValue, TraceableValue, *]] =
        underlying.weave(af)
      def mapK[F[_], G[_]](af: Alg[F])(fk: F ~> G): Alg[G] =
        underlying.mapK(af)(fk)

  @experimental
  inline def derived[Alg[_[_]]]: TraceableAspect[Alg] =
    fromAspect(Derive.aspect[Alg, TraceableValue, TraceableValue])
```

Used as:

```scala
// no @experimental here: the algebra type stays usable from ordinary code
trait Foo[F[_]] derives TraceableAspect:
  def foo(i: Int): F[String]

// ...it goes here instead, where the synthesized given lands
@experimental
object Foo
```

**This exact code was compiled and run.** See Evidence — unlike M13, whose
measurement is on a structural analogue, M14's spike used the real types
(`Aspect`, `Derive.aspect`, `natchez.TraceableValue`) from the published
artifacts this repo depends on, so it *is* the milestone's design rather than a
model of it.

**Why `fromAspect` is a separate, non-`inline` method.** `derived` must be
`inline` because `Derive.aspect` is an inline macro entry point. Three
formulations were measured on 3.3.8:

| Formulation | Result |
| --- | --- |
| anonymous class directly inside `inline def derived` | compiles, but warns `New anonymous class definition will be duplicated at each inline site` |
| `private final class FromAspect` in the companion | **does not compile** — the inline body is spliced at the call site, which cannot see a private member (`private class FromAspect can only be accessed from object TraceableAspect`) |
| non-`inline` `def fromAspect` holding the anonymous class | **clean** — zero warnings under `-Wunused:all -Wvalue-discard -Ykind-projector` |

`fromAspect` is public rather than package-private because it is independently
useful: it narrows any existing `Aspect` at the natchez shape, which is the only
way a hand-written or Scala 2-derived instance can reach the narrow type.

---

## Decisions (proposed — ratify before starting, then final)

**D1 — Two pinned parameters, and the arity correction above is recorded as a
correction rather than silently applied.** `Err` belongs to `RaiseAspect`
(M10) and has no meaning for `Aspect`.

**D2 — A `trait`, not a type alias**, for the same two reasons as M13: a type
alias has no companion to carry `derived`, and the owner specified "extends".
The relationship is one-way and the tests pin both directions:

- `TraceableAspect[Foo]` **is** an `Aspect[Foo, TraceableValue, TraceableValue]`,
  so it satisfies `TraceWeaveOps#traceWithInputsAndOutputs`,
  `WeaveInterpreter.fromAspect`, `WeaveKnot.weave`, and — via the inherited
  default `instrument` — everything phrased in terms of `Instrument`.
  Verified, including for a search whose `Cod` is still a free type parameter
  (`traceWithInputs[Cod]`'s shape).
- The converse is false. A user with a hand-written
  `Aspect[Foo, TraceableValue, TraceableValue]` who writes
  `summon[TraceableAspect[Foo]]` gets a missing-implicit error;
  `TraceableAspect.fromAspect` is the fix.

One consequence specific to M14, because `TraceWeaveOps#traceWithInputs[Cod]`
leaves `Cod` free: a `TraceableAspect[Foo]` serves `traceWithInputs[TraceableValue]`
and `traceWithInputsAndOutputs`, but **not** `traceWithInputs[Trivial]`. That is
exactly the same limitation a hand-written `Aspect[Foo, TraceableValue,
TraceableValue]` has today — M14 neither creates nor removes it. See Open
questions.

**D3 — Scala 3 only, under `core/shared/src/main/scala-3`.** Same reasoning as
M13's D2, and the directory already exists as a source root: under 3.3.8,
`coreJVM/Compile/unmanagedSourceDirectories` lists
`core/shared/src/main/scala-3` (verified 2026-08-02). `core` is `CrossType.Full`
and cross-builds for JS, so `shared` — not `jvm` — is where it goes.

**D4 — `core` has MiMa enabled, and this milestone verifies it rather than
assuming.** Unlike the four newer modules, `core` does **not** set
`mimaPreviousArtifacts := Set.empty`; it compares against seven real published
versions (0.2.0 through 0.2.6), on both the JVM and JS axes — verified by
`show coreJVM/mimaPreviousArtifacts`. Adding a Scala-3-only trait is purely
additive and should be clean, but "should be" is not a check. The plan runs
`+coreJVM/mimaReportBinaryIssues` and `+coreJS/mimaReportBinaryIssues` as the
first thing after the trait exists, and again at the end.

There is one subtlety worth naming so nobody is surprised: `tlVersionIntroduced`
is `Map("3" -> "0.2.4")` at `ThisBuild` scope (`build.sbt:15`), so on the Scala
3 axis MiMa compares against 0.2.4–0.2.6 only. New Scala-3-only code is
additive against those too.

**D5 — Serializability is asserted, not merely argued.**
`Aspect extends Instrument extends FunctorK extends InvariantK extends Serializable`
(cats-tagless-core 0.16.5, `cats/tagless/InvariantK.scala:27`), so
`TraceableAspect` inherits a declared `Serializable` contract, and `fromAspect`'s
anonymous class captures exactly one field, which is itself a `Serializable` by
that same declaration. Unlike M13's module, `core` already has a JVM test source
directory (`core/jvm/src/test/scala/com/dwolla/tracing/`), so a round-trip
through `ObjectOutputStream` costs one small JVM-only file. Take it.

**D6 — The `TODO` in `TraceWeaveCapturingInputsAndOutputs`' scaladoc is
resolved, not deleted.** That scaladoc is a doctest: its twenty-line
hand-written instance is compiled and run on every build, on all three Scala
versions. It cannot simply become `derives TraceableAspect`, because the doctest
also runs on 2.12 and 2.13. The example stays; a short paragraph and a
cross-reference are added pointing Scala 3 readers at the one-word form, and the
`TODO` — which is now answerable — is replaced by that pointer.

---

## What changes

| Component | Fate |
| --- | --- |
| `TraceableAspect` (new) | `core/shared/src/main/scala-3/com/dwolla/tracing/TraceableAspect.scala`. Trait + companion with `apply`, `fromAspect`, `derived`. |
| `cats.tagless.aop.Aspect`, `Derive` | **untouched** (upstream). |
| `TraceWeaveOps`, `TraceWeaveCapturingInputs(AndOutputs)` | **signatures untouched.** One scaladoc paragraph replaces a `TODO`. |
| `WeaveInterpreter.fromAspect` | **untouched.** The subtype satisfies its existing `Aspect` demand. |
| `WeaveKnot` | **untouched** — but note M15 moves it to another module. If M15 lands first, `WeaveKnot.weave`'s `Aspect[Alg, Dom, Cod]` demand is unchanged wherever it lives, and M14's tests should not reference it. |
| `build.sbt` | **untouched.** |
| MiMa | verified clean for `coreJVM` and `coreJS`, additively — see D4. |

---

## Evidence: demonstrated versus argued

**Demonstrated.** Compiled and run on **Scala 3.3.8** with `-Ykind-projector`
and `-Wunused:all -Wvalue-discard`, against **cats-tagless-core 0.16.5 and
natchez-core 0.3.10** — the exact published artifacts this repo depends on
(`build.sbt:27,57`). The spike is the design above verbatim, plus a `Lookup`
algebra and a handful of `summon` checks; it reverted its code.

- `trait Lookup[F[_]] derives TraceableAspect` compiles and produces a working
  instance. Runtime check through the inherited default `instrument`:
  `Lookup.get = v:k` — correct algebra name, method name and value.
- **`@experimental` is required, and where it goes matters.** A `derives`
  clause invokes `derived` from a given the compiler synthesizes into the
  algebra's companion object, so `@experimental` on the **companion object** is
  the right placement — the trait stays unannotated. All three placements were
  compiled against the real `TraceableAspect` on 3.3.8:

  | placement | result |
  | --- | --- |
  | nowhere | fails at the `derives` clause: `method derived is marked @experimental and therefore may only be used in an experimental scope.` |
  | **companion object only** | **compiles**; the given resolves at both the narrow and the wide type, and non-experimental code uses the algebra type freely |
  | the trait | compiles, but the annotation is viral: an unrelated, untraced `def use[F[_]](v: Alg[F])` fails with `trait Alg is marked @experimental and therefore may only be used in an experimental scope` |

  A sibling `@experimental` definition elsewhere in the same compilation unit is
  *not* enough — a companion object is not "a scope enclosing" the trait, which
  is why the trait placement (this document's own earlier claim) was wrong
  rather than merely incomplete. The companion placement is **very nearly** not
  a regression: `derives` cannot annotate the synthesized given alone, so it
  takes the whole companion — every companion member becomes experimental, not
  just the instance — where a hand-written instance needs only a single
  `implicit val` annotated. Both leave the algebra *type* clean. An algebra with
  no declared companion at all has nowhere else to put the annotation — declare
  an empty `@experimental object Alg` rather than annotating the trait. This
  matches the requirement already documented for `DeriveRaise.aspect`
  (`raise-aspect-macros/src/main/scala-3/com/dwolla/tagless/mtl/DeriveRaise.scala`
  scaladoc) and is a property of upstream: the whole of `object Derive` is
  `@experimental` (`cats/tagless/Derive.scala:28-29`).
- The macro genuinely runs rather than the clause being ignored — proven by the
  failure mode: an algebra method returning `F[Option[Long]]` reported
  `Not found: given natchez.TraceableValue[scala.Option[scala.Long]]` at the
  `derives` clause.
- The three `inline`/anonymous-class formulations behave as tabulated above.
- Subsumption: `summon[TraceableAspect[Lookup]]` typechecks as
  `Aspect[Lookup, TraceableValue, TraceableValue]`; a search phrased at the
  wider type resolves to it; and a search with the extra parameters still free
  (`def f[Alg[_[_]], Cod[_]](using Aspect[Alg, TraceableValue, Cod])`) also
  resolves to it.

**Argued from source, not compiled:**

- That MiMa reports nothing for `coreJVM`/`coreJS`. Strongly supported — the
  change is a new class in a Scala-3-only source directory, and MiMa only
  reports on classes present in the *previous* artifact — but `core`'s MiMa is
  live, so this is a **task-level check**, run twice.
- That the doctest harness copes with `derives` and `@experimental` in a
  `{{{ }}}` block. `Scala3UsageNote.scala` establishes `@experimental` works;
  `derives` in a doctest is new. Task-level check, shared with M13.
- That nothing in `core`'s existing implicit resolution changes. Nothing asks
  for `TraceableAspect`, and `ImplicitPrioritizationSpec`
  (`core/shared/src/test/scala/com/dwolla/tracing/ImplicitPrioritizationSpec.scala`)
  is the suite that would notice if it did — the plan runs it deliberately
  rather than incidentally.

---

## Open questions for Brian

**Q1 — Is a `Trivial`-codomain sibling wanted?** — **Answered 2026-08-02: no.**
Same ruling as M13's Q1; see `24-milestone-M13-traceable-raise-aspect.md` for
the reasoning. It applies *more* strongly here, since this document already
records that the plain `Aspect` shape has no in-repo `Trivial` usage at all,
not even a fixture.

*(Original question, preserved:)* Identical in substance to
M13's Q1, and it should get one answer covering both milestones rather than two.
`traceWithInputs[Trivial]` — trace the inputs, do not render the return value —
cannot be served by a `derives` clause under this design, because an algebra can
carry only one `derives` clause per type class and the two shapes are different
type classes. M14 as planned ships only `TraceableAspect`, on YAGNI grounds.
The naming problem is the blocker, not the code: the second trait needs a name
that reads well beside the first, and `TraceableInputsAspect` is the best I have
and it is not good.

Note the evidence differs slightly between the two milestones: the `Trivial`
codomain is demonstrably used in this repo for the **`RaiseAspect`** shape
(`RaiseTraceIntegrationSpec.scala:14-15` declares
`barRaiseAspectTrivialCod`), whereas for the plain `Aspect` shape I found no
in-repo use. So if the answer is "one of them, not both", M13 has the stronger
case.

---

## Acceptance criteria

- [ ] `trait TraceableAspect[Alg[_[_]]] extends Aspect[Alg, TraceableValue, TraceableValue]`
      exists in `com.dwolla.tracing`, under `core/shared/src/main/scala-3`, with
      `apply`, `fromAspect` and `@experimental inline derived` on its companion.
- [ ] An algebra declared `trait X[F[_]] derives TraceableAspect` with a
      separate `@experimental object X` traces end to end through the
      **unedited** `com.dwolla.tracing.syntax` `traceWithInputsAndOutputs`,
      against `InMemory`, with a span history equal to the one a hand-written
      `Aspect` instance produces.
- [ ] The derived and hand-written instances agree on `weave` (algebra name,
      method name, domain names and rendered values, codomain target) and on
      `mapK`; and `instrument`, which neither implements, works on both.
- [ ] `compileErrors` confirms a wide `Aspect[…]` is not a `TraceableAspect[…]`,
      and `fromAspect` converts it.
- [ ] `+coreJVM/mimaReportBinaryIssues` and `+coreJS/mimaReportBinaryIssues`
      report **no** problems, with no `mimaBinaryIssueFilters` entry added.
- [ ] The derived instance survives a JVM `ObjectOutputStream` round trip.
- [ ] `+coreJVM/test` green on 2.12.21, 2.13.18 and 3.3.8, with the 2.12 and
      2.13 counts **identical to `main`**; `+coreJS/Test/scalaJSLinkerResult`
      green; `+coreJVM/doc` succeeds.
- [ ] `ImplicitPrioritizationSpec` passes unedited.
- [ ] The `TODO` at `TraceWeaveCapturingInputsAndOutputs.scala:67` is gone,
      replaced by a pointer rather than by deletion of the example.
- [ ] Zero new warnings with `-Xfatal-warnings` forced — in particular no
      "anonymous class definition will be duplicated at each inline site".

## Ground rules reminder

- **Additive only.** No existing main source signature changes. If a task
  appears to require editing `TraceWeaveOps`, `WeaveInterpreter` or
  `Aspect`-facing code, STOP and report.
- No expected span history may be edited.
- If MiMa reports anything, **do not add a filter to make it quiet** — report
  it. An additive Scala-3-only class producing a MiMa problem would mean the
  change is not what this document says it is.
- Do not do M13's or M15's work here, and do not touch M16 (otel4s).
- Never use `--no-verify` or any other hook-bypass flag.
