# Milestone M14 — `TraceableAspect`: `derives` ergonomics for cats-tagless's `Aspect`

## Status

**Planned, not started.** The implementation plan is
`27-milestone-M14-implementation-plan.md`. Ratify the Decisions section below
before Task 1 starts.

M13, M14 and M15 were planned together on 2026-08-02 and are independent of one
another. M14 is M13's idea one level down: the same `derives` ergonomics, for
plain cats-tagless `Aspect` rather than this library's `RaiseAspect`, in `core`
rather than `natchez-tagless-mtl`.

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
@experimental
trait Foo[F[_]] derives TraceableAspect:
  def foo(i: Int): F[String]
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
- **`@experimental` is required on the algebra** (or an enclosing scope). A
  sibling `@experimental` definition in the same compilation unit is not
  enough. The diagnostic is
  `method derived is marked @experimental and therefore may only be used in an
  experimental scope.`, reported at the `derives` clause. This matches the
  requirement already documented for `DeriveRaise.aspect`
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
- [ ] An algebra declared `@experimental trait X[F[_]] derives TraceableAspect`
      traces end to end through the **unedited** `com.dwolla.tracing.syntax`
      `traceWithInputsAndOutputs`, against `InMemory`, with a span history equal
      to the one a hand-written `Aspect` instance produces.
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
