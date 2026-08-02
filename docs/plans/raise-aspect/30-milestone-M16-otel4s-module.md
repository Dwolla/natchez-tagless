# Milestone M16 — an otel4s module mirroring the plain-`Aspect` interpreters

## Status

**Planned, not started.** The implementation plan is
`31-milestone-M16-implementation-plan.md`. Ratify the Decisions section below —
and answer **Q1 (the artifact name)**, **Q2 (the test-scope testkit)** and
**Q3 (the circe fallback)** — before Task 1 starts.

**M16 stacks on M15.** `com.dwolla.tagless.WeaveKnot` must already live in the
`tagless-core` module before this module can depend on it without dragging in
natchez. M15 (`28-milestone-M15-tagless-core-module.md`) exists for exactly this
reason; do not start M16 until it has landed.

The research this milestone is built on is
`.superpowers/sdd/m16-otel4s-research.md`, a source-verified pass against
otel4s **v1.0.1** (commit `f34c324851e079d0d6fb2c47064c6c9e04cf2c01`), whose
cited sources are vendored under `reference/upstream/otel4s/`. Every `file:line`
citation in this document refers to that tree. Do not re-derive its findings
from memory; read the vendored file.

### Why this milestone exists, in one paragraph

This repository's headline feature is a set of `FunctionK`s that turn a woven
cats-tagless algebra into traced calls: `TraceInstrumentation`,
`TraceWeaveCapturingInputs` and `TraceWeaveCapturingInputsAndOutputs`, plus the
syntax that makes them one method call on an algebra. All three are written
against `natchez.Trace`. otel4s is the Typelevel OpenTelemetry API and is where
new Typelevel tracing is going. M16 ports **those three interpreters and their
syntax, and nothing else**, to otel4s, in a new module that depends on
`otel4s-core-trace` and does not depend on natchez.

---

## Scope

**In scope.** Mirrors of, and only of:

| natchez, in `core` | otel4s, in the new module |
| --- | --- |
| `TraceInstrumentation` | `TracerInstrumentation` |
| `TraceWeaveCapturingInputs` | `TracerWeaveCapturingInputs` |
| `TraceWeaveCapturingInputsAndOutputs` | `TracerWeaveCapturingInputsAndOutputs` |
| `ToTraceValue` / `natchez.TraceableValue` | `ToAttributes` (project-owned — see below) |
| `syntax.TraceWeaveOps` (`traceWithInputs`, `traceWithInputsAndOutputs`) | `syntax.TracerWeaveOps`, same method names |
| `syntax.TraceParamsOps` (`asTraceParams`) | `syntax.WeaveAttributesOps` (`asAttributes`) |
| `syntax.InstrumentableAndTraceableOps` (`instrumentAndTrace`) | same name, `Tracer`-typed |

**Out of scope, deliberately.**

- **`RaiseAspect` and the whole `raise-aspect-*` family.** There is no otel4s
  analogue of `natchez-tagless-mtl` in this milestone. If a task starts reaching
  for `OnRaise`, `RaiseAspect`, `RaiseRecorder` or an otel4s `RaiseTraceWeaveOps`,
  **stop** — that is a later milestone nobody has asked for.
- **Everything `Resource`-shaped**: `TraceResourceAcquisition`,
  `syntax.ResourceInitializationSpanOps`, `syntax.TraceResourceLifecycleOps`.
  These are built on `natchez.Trace#spanR: Resource[F, F ~> F]`. otel4s's
  `SpanOps.resource: Resource[F, SpanOps.Res[F]]` is close in shape but
  explicitly does **not** propagate span context into the resource's `use`
  block — you must route through `res.trace: F ~> F` (`SpanOps.scala:191`,
  `:290`, documented at `:90-110`, tracked upstream as
  [otel4s#194](https://github.com/typelevel/otel4s/issues/194)). That is a
  different ergonomic problem and a distinct chunk of work.
- **`EntryPointRootScope`, `RootSpanProvidingFunctionK`,
  `syntax.InstrumentableAndTraceableInKleisliOps`.** otel4s has no `EntryPoint`
  and no `natchez.Span`-in-`Kleisli` idiom; the nearest pieces are
  `TracerProvider.get` and `Tracer[F].rootScope` (`Tracer.scala:178`). Nothing
  to mirror one-to-one.
- **Any change to `core`, `scalacache`, `natchez-tagless-mtl` or the three
  `raise-aspect-*` modules.** M16 is additive.

---

## The 2.12 exclusion — read this first

**Scala 2.12 is excluded from this module, and only this module. Ruled by
Brian, 2026-08-02.**

CLAUDE.md requires that dropping 2.12 support be called out. This is that
call-out, and the honest framing is: **the library does not drop 2.12; one new
module never gains it.** `core`, `scalacache`, `raise-aspect-core`,
`raise-aspect-laws`, `raise-aspect-macros`, `natchez-tagless-mtl` and
`tagless-core` all keep publishing `_2.12` artifacts, unchanged. The otel4s
module's published artifact list is a strict subset of the rest of the build's.

The reason is not preference, it is availability:

- otel4s's own build says so. `otel4s/build.sbt:35` reads
  `ThisBuild / crossScalaVersions := Seq(Scala213, "3.3.8")` where
  `Scala213 = "2.13.18"` (`build.sbt:34`). A `val Scala212 = "2.12.21"` exists
  at `build.sbt:33` and **is referenced nowhere else** in `build.sbt` or
  `project/`; it is dead.
- Maven Central agrees, at every version, not just 1.0.1:
  `https://repo1.maven.org/maven2/org/typelevel/otel4s-core-trace_2.12/maven-metadata.xml`
  → **HTTP 404**, and likewise `otel4s-core-trace_sjs1_2.12`. There is no
  2.12 artifact to depend on. It was never published, not dropped.

So the choice is between a 2.13/3 module and no module. Narrowing
`crossScalaVersions` on the otel4s project alone is what sbt supports and what
the plan does.

**A consequence worth writing down for whoever reads this next:** the
`val _ = …` double-assignment hazard that bit this repo on 2.12 does not apply
here. There is no 2.12 axis in this module. Do not copy 2.12 warning-shape
workarounds into it, and do not assume a warning seen on 2.12 elsewhere in the
build has a counterpart here.

**Scala.js is in scope.** otel4s publishes `_sjs1_2.13` and `_sjs1_3` at 1.0.1
(HTTP 200 on each jar), and `core-trace`/`core-common` are
`crossProject(JVMPlatform, JSPlatform, NativePlatform)` upstream
(`otel4s/build.sbt:150`, `:204`). The module cross-builds JVM and JS like the
rest of this repo.

---

## The load-bearing design decision: what plays `Dom`/`Cod`

`Aspect.Weave[F, Dom, Cod, A]` needs `Dom` and `Cod` at kind `* -> *`. natchez
hands us `natchez.TraceableValue[A]` for free. **otel4s ships nothing of that
shape.** The research established this by reading every candidate:

| candidate | kind | why it does not work |
| --- | --- | --- |
| `Attribute.From[-Value, Key]` (`Attribute.scala:82`) | `(*, *) -> *` | two parameters; wrong kind |
| `Attribute.Make[A, Key]` (`Attribute.scala:131`) | `(*, *) -> *` | two parameters, **and** both `const` overloads bake the attribute *name* into the instance (`:164`, `:193`) |
| `Attributes.Make[-A]` (`Attributes.scala:141`) | `* -> *` ✓ | name still baked in, and on **Scala 2 there are zero instances** — `core/common/src/main/scala-2/.../AttributesScalaVersionCompanion.scala` defines an empty `MakeCompanion` (verified: the file's `MakeCompanion` body is empty) |
| `AttributeKey.KeySelect[A]` (`AttributeKey.scala:118`) | `* -> *` ✓ | not a conversion at all — it enumerates the nine legal primitive types and nothing more |

The name is the crux. natchez keeps the attribute name at the *call site* —
`Trace[F].put(fields: (String, TraceValue)*)` — which is what lets
`TraceParamsOps.asTraceParams` build `algebraName.methodName.paramName` from the
`Advice`. Every otel4s conversion type class puts the name in the *instance*,
which would force every algebra parameter type to hard-code the attribute name
it will be recorded under. That is not a type class, it is a per-call-site
constant wearing one.

### The decision

**D1 — the module defines its own type class:**

```scala
trait ToAttributes[-A] {
  def toAttributes(name: String, value: A): Attributes
}
```

Kind `* -> *`; name supplied at call time from the `Advice`; results compose
across parameter lists through `Attributes`' `Monoid`
(`Attributes.scala:204-212`). This is the research's recommended shape, adopted,
with three refinements it did not make.

**Why `Attributes` and not `Attribute[_]`.** The research offered
`ToAttribute[A] { def toAttribute(name: String, value: A): Attribute[_] }` as
the alternative. `Attributes` wins on three counts, and the third is decisive:

1. The existential `Attribute[_]` is awkward on Scala 2 in a way `Attributes`
   is not, even though `Attributes` is *itself* `immutable.Iterable[Attribute[_]]`
   (`Attributes.scala:36-38`) — the existential stays inside upstream's own
   types rather than appearing in ours.
2. `Attributes` has a `Monoid`, so `asAttributes` is a two-level `foldMap` over
   the domain rather than a hand-rolled `List` build.
3. It is the only one of the two that can produce **zero** attributes or
   **several**. Zero is what `Unit` and `None` should produce (see D3); several
   is what a user's case class should produce, and it is otel4s's own idiom —
   upstream's `Attributes.Make` scaladoc example is literally
   `user => Attributes(Attribute("user.id", user.id), Attribute("user.group", user.group))`.

`Attributes` passes straight into both places the module needs it, with no
adaptation: `SpanBuilder.State.addAttributes(attributes: immutable.Iterable[Attribute[_]]): State`
(`SpanBuilder.scala:140`) and
`Span.Backend.addAttributes(attributes: immutable.Iterable[Attribute[_]]): F[Unit]`
(`Span.scala:166`).

**Why contravariant, where `TraceableValue` is invariant.** `A` occurs only in
negative position, so `ToAttributes[-A]` is well-formed, and upstream's own
`Attributes.Make[-A]` sets the precedent. It buys something concrete: an
algebra method taking `List[String]` resolves the `ToAttributes[Seq[String]]`
instance, which an invariant type class could not do. `TraceableValue`'s
invariance is an upstream constraint we are not obliged to inherit. **If
contravariance turns out to make implicit resolution ambiguous in practice, the
fallback is invariance plus explicit `List`/`Vector` instances** — that is a
task-level check in the plan, not an assumption here.

**D2 — no `NotGiven` ambiguity guards, and therefore no
`scalac-compat-features` dependency.** `ToTraceValue.scala:25-32` and `:40-47`
carry six `NotGiven[A =:= …]` guards each. Those exist for one reason: the
`Show`/circe fallbacks live in *our* traits while the primitive instances live
in *upstream's* `TraceableValue` companion, so the two are at the same implicit
priority and collide. We own the whole of `ToAttributes`, so the primitives go
in the companion object's body and the fallback goes in a `LowPriority` parent
trait, and ordinary implicit-priority-by-inheritance resolves it. The research
said the guards "will need the same treatment"; they do not, and the plan
proves it by compiling a call that would be ambiguous if they did.

### What ships, and what happens with no instance

Instances, in the companion body (high priority):

| instance | via |
| --- | --- |
| `String`, `Boolean`, `Long`, `Double` | `AttributeKey.KeySelect` directly (`AttributeKey.scala:125-143`) |
| `Int`, `Short`, `Byte` | widened to `Long` — upstream has `From[Int, Long]` etc. at `Attribute.scala:91-93`, and `.toLong` is the same thing without the indirection |
| `Float` | widened to `Double` — see D3 |
| `Unit` | `Attributes.empty` — see D3 |
| `Option[A: ToAttributes]` | `Some` delegates; `None` is `Attributes.empty` — see D3 |
| `Seq[String]`, `Seq[Boolean]`, `Seq[Long]`, `Seq[Double]` | the four native sequence key types (`AttributeKey.scala:131-142`) |
| `Seq[Int]`, `Seq[Short]`, `Seq[Byte]` | mapped to `Seq[Long]` |

In a `LowPriorityToAttributesInstances` parent trait:

| instance | via |
| --- | --- |
| any `A: Show` | `_.show`, as a single string attribute |

**A type with no instance is a compile error at the point the `Aspect` is
derived**, exactly as on the natchez side, with the same iterate-until-it-builds
experience. In practice the `Show` fallback means almost everything has one, so
the failure mode is the same as natchez's: silence rather than an error, and the
`Show` rendering rather than the intended one. The scaladoc says so.

**One hazard that must be in the scaladoc, because it is not obvious:
`Attributes` contains only unique keys** (`Attributes.scala:30-31`; `Monoid`'s
`combine` is `x ++ y`, so `y` wins). A generic `ToAttributes[Seq[A]]` that
combined per-element `Attributes` under one name would therefore silently record
**only the last element**. That is why the sequence instances above are the
specific native ones and there is deliberately no generic
`Seq[A: ToAttributes]`.

### Two type classes for one domain type

A user with both modules on the classpath needs a `TraceableValue[Money]` *and*
a `ToAttributes[Money]`. That is unavoidable — the two libraries have
incompatible attribute models — and it is worth saying out loud rather than
discovering. A `TraceableValue[A] => ToAttributes[A]` bridge is conceivable but
is **not** in M16: it would have to map `TraceValue.NumberValue(java.lang.Number)`
onto `Long` or `Double`, which is lossy for `BigDecimal`/`BigInt`, and it would
give the otel4s module a natchez dependency — the precise thing M15 was done to
avoid. If it is ever wanted it belongs in a third, interop module.

---

## The three mismatches, resolved

### 1. There is no `Trace[F].span(name)(fa)`

natchez (`natchez/Trace.scala:39`):
`def span[A](name: String, options: Span.Options = …)(k: F[A]): F[A]`.

otel4s splits this into builder → `SpanOps` → run. `Tracer[F]` is **sealed**
(`Tracer.scala:45`) and exposes `spanBuilder(name): SpanBuilder[F]` (`:83`);
`SpanBuilder#build: SpanOps[F]` (`SpanBuilder.scala:52`); and `SpanOps` gives
`use[A](f: Span[F] => F[A])` (`SpanOps.scala:219`) and
`final def surround[A](fa: F[A]): F[A] = use(_ => fa)` (`:247`). There is also
no ambient `Tracer[F].put` — post-hoc attributes need a `Span[F]` handle, from
`use` or from `Tracer[F].currentSpanOrNoop` (`Tracer.scala:58`).

**Resolution: all three interpreters use the builder path, and the shape change
is an improvement in two of the three.**

- `TracerInstrumentation`:
  `Tracer[F].spanBuilder(name).build.surround(fa.value)`. Constraint profile
  unchanged from natchez — `Tracer[F]` alone, no `Functor`. `surround`/`use`
  impose no constraint on `F` at the call site.
- `TracerWeaveCapturingInputs`: inputs are attached to the **builder**
  (`modifyState(_.addAttributes(…))`, `SpanBuilder.scala:43`, `:140`) instead of
  being `put` inside the span. Two consequences: the `Apply[F]` constraint the
  natchez version needs in order to sequence `put *> target`
  (`TraceWeaveCapturingInputs.scala:117`) **is not needed**, and the input
  attributes exist at span *start*, where a sampler can see them. Strictly
  better on both counts.
- `TracerWeaveCapturingInputsAndOutputs`: the return value genuinely has to be
  attached after the call, so this one uses
  `.build.use { span => target.flatTap(out => span.backend.addAttributes(…)) }`.
  `FlatMap[F]` — the same constraint the natchez version carries.

`.backend` rather than the `Span` itself is deliberate, and is mismatch 2.

### 2. `Tracer.span`, `Span.addAttributes` and `recordException` are macros

On Scala 2 they are blackbox macros
(`scala-2/.../{TracerMacro,SpanMacro,SpanBuilderMacro}.scala`); on Scala 3 they
are `inline` with union-typed varargs
(`AttributeOrIterableOnce = Attribute[?] | IterableOnce[Attribute[?]]`,
`scala-3/.../AttributesScalaVersionCompanion.scala:22`) that has no Scala 2
counterpart. The list is long: `Tracer.span`, `Span.addAttribute(s)`,
`Span.recordException`, `Span.setStatus`, `SpanBuilder.addAttribute(s)`,
`withFinalizationStrategy`, `withSpanKind`, `withStartTimestamp`, `withParent`.

**Resolution: the module never calls any of them, in main sources *or* in
scaladoc.** The sealed, non-macro path is
`spanBuilder → modifyState → build → surround`/`use`, plus
`span.backend.addAttributes` — every one of which is an ordinary method on a
sealed trait taking `immutable.Iterable[Attribute[_]]`.

The scaladoc half of that is not decoration. `build.sbt:40-49` sets
`doctestOnlyCodeBlocksMode := true` and applies `doctestSettings` to every
module that has scaladoc examples, so **every `{{{ }}}` block in this module is
compiled and run on both 2.13 and 3**. A doctest that showed
`Tracer[F].span("x").surround(fa)` would be exercising precisely the macro
asymmetry the module is avoiding. The plan makes this a grep-able acceptance
criterion rather than a habit.

### 3. otel4s records exceptions and sets `StatusCode.Error` automatically

`SpanBuilder.State`'s default finalization strategy is
`SpanFinalizer.Strategy.reportAbnormal` (`SpanBuilder.scala:192`), which does
`recordException(e) |+| setStatus(StatusCode.Error)` on
`Resource.ExitCase.Errored` and `setStatus(StatusCode.Error, "canceled")` on
cancelation (`SpanFinalizer.scala:47-56`). It applies to `use`, `surround` and
`resource` (`SpanOps.scala:113`, `:205`).

**Resolution: M16 adds no error recording of its own, and leaves the default
strategy alone.** This is not a compromise, it is the scope reading:

- **Nothing double-records, because there is nothing to double.** The three
  natchez interpreters being mirrored do not record errors either — none of them
  calls `Trace[F].attachError`. Error recording in this repo is the M6
  `OnRaise`/`RaiseRecorder` work, and that lives in `natchez-tagless-mtl`, which
  M16 does not mirror. Adding exception recording to these three interpreters
  would *create* the double-recording, not avoid it.
- **The behaviour difference is real and favours otel4s, and must be
  documented.** With natchez, a `Throwable` escaping a traced method leaves the
  span with no error marking from these interpreters. With otel4s, the same
  escape is recorded as an exception event and the span status is set to `Error`
  — for free, by the library. Users migrating will see spans they did not see
  before. Say so in the scaladoc.
- **cats-mtl `Raise` errors remain invisible, by a different mechanism than on
  the natchez side.** `reportAbnormal` keys off `Resource.ExitCase`, which only
  observes `MonadCancel` failures. An error carried in a `Handle`/`Raise`
  channel completes *successfully* as far as `F` is concerned, so the span is
  marked OK. M6's docs record the same gap for natchez but the mechanism there
  is "we never called `attachError`"; here it is "the automatic strategy cannot
  see it". **The wording must be rewritten, not copied.** It goes in the module
  README rather than in an interpreter's scaladoc, since closing the gap is a
  future mtl-module milestone's job, not these interpreters'.
- If a user does want to suppress or change the automatic behaviour, the sealed
  escape hatch is `SpanBuilder.State.withFinalizationStrategy`
  (`SpanBuilder.scala:160`) reached through `modifyState` — note the
  `SpanBuilder#withFinalizationStrategy` of the same name is a macro
  (`scala-2/.../SpanBuilderMacro.scala:100`) and is not what to document.
  M16 does not expose a knob for this; a user who needs it writes their own
  `Weave ~> F`, which is four lines.

---

## The module

### Shape

```scala
lazy val otel4sTagless = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("otel4s-tagless"))
  .settings(
    name := "otel4s-tagless",
    // otel4s has never published a 2.12 artifact — see M16's milestone doc.
    crossScalaVersions := Seq(Scala213, "3.3.8"),
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "otel4s-core-trace" % otel4sVersion,
      "org.typelevel" %%% "cats-core" % catsVersion,
      "org.typelevel" %%% "cats-tagless-core" % catsTaglessVersion,
      "org.scalameta" %%% "munit" % munitVersion % Test,
    ),
    mimaPreviousArtifacts := Set.empty,
  )
  .settings(doctestSettings *)
  .dependsOn(taglessCore)
```

`CrossType.Pure`, matching every module added since `core`. `doctestSettings`
because the module has scaladoc examples and they must be compiled.
`mimaPreviousArtifacts := Set.empty` following the precedent of the five other
unpublished modules — and carrying the same unresolved note M15 recorded, that
`tlVersionIntroduced` is the mechanism that would keep MiMa live from first
release onward and that all of the new modules need that decision before they
are first published.

### Dependencies, verified against the published POMs

`otel4s-core-trace_2.13:1.0.1`'s POM declares, at **compile** scope:
`otel4s-core-common` 1.0.1, `cats-effect-kernel` 3.7.0, `scodec-bits` 1.2.5.
`otel4s-core-common_2.13:1.0.1` declares, at compile scope: `cats-core` 2.13.0,
**`cats-effect` 3.7.0** (the full artifact, not just kernel), `cats-mtl` 1.7.0,
`scodec-bits` 1.2.5, `vault` 3.7.0. `scalac-compat-annotation` is **`provided`**
in `core-trace` and therefore does not come through.

| dependency | otel4s 1.0.1 wants | this repo | verdict |
| --- | --- | --- | --- |
| cats-core | 2.13.0 | 2.13.0 | match |
| cats-mtl | 1.7.0 | 1.7.0 | match |
| cats-effect | 3.7.0 | not pinned in this repo | arrives transitively; the module declares none |
| scodec-bits | 1.2.5 | — | **new to the build**, transitively |
| vault | 3.7.0 | — | **new to the build**, transitively |

`cats-core` and `cats-tagless-core` are declared explicitly because the module
uses them directly (`~>`, `Show`, `syntax.all`, `Aspect`, `Instrument`,
`Instrumentation`), not because they are missing. **The only new direct
dependency in the whole build is `otel4s-core-trace`.** `vault` is a transitive
the research did not list; it is `org.typelevel`, cross-published, and harmless.

### Package

`com.dwolla.tracing.otel4s`, with syntax at `com.dwolla.tracing.otel4s.syntax` —
exactly the shape `natchez-tagless-mtl` already uses (`com.dwolla.tracing.mtl`
and `com.dwolla.tracing.mtl.syntax`). It must not be `com.dwolla.tracing`
itself: a downstream application may depend on both modules, and the two
`syntax` package objects would then mix conflicting implicits into one scope.

**One footgun to know about.** Inside `package com.dwolla.tracing.otel4s`, the
bare identifier `otel4s` resolves to *this* package, not to
`org.typelevel.otel4s`. Import upstream types by their full path
(`import org.typelevel.otel4s.trace.Tracer`) and never write a partially
qualified `otel4s.Something`. On 2.13 this is additionally safe because
`-Xsource:3` — which `TypelevelSettingsPlugin` supplies build-wide, confirmed
for `core` on 2.13 during M15 — makes imports absolute.

### The syntax-method name collision, and why the names still match

`com.dwolla.tracing.syntax` and `com.dwolla.tracing.otel4s.syntax` will both
offer `traceWithInputs`, `traceWithInputsAndOutputs` and `instrumentAndTrace` on
`Alg[F]`, through differently-named implicit conversions. A single file that
wildcard-imports **both** will get an ambiguous-implicit-conversion error at the
call site.

That is the right trade. Matching names mean a migrating file changes exactly
one import line and no call sites; the alternative — renaming the otel4s
methods — imposes churn on every user in exchange for supporting a
same-file-two-backends case that is rare and that has an obvious workaround
(import one selectively). The scaladoc says so, in one sentence, on the syntax
package object.

The *class* names do diverge (`TracerInstrumentation`, not
`TraceInstrumentation`), because those are used by direct construction —
`alg.weave.mapK(new TracerWeaveCapturingInputs)` — where two same-named imports
would be a genuine nuisance, and because `Tracer` is what the otel4s type is
actually called.

### The `WeaveKnot` dependency

`.dependsOn(taglessCore)`. None of the three interpreters *calls* `WeaveKnot`;
neither does `core`, which merely ships it. The dependency exists so that a user
of `otel4s-tagless` has the same knot-tying facility a user of
`natchez-tagless` has, without taking on natchez — which is verbatim the reason
M15 was done. Task 6's doctest exercises it, so the dependency is used rather
than merely declared. If Brian prefers strict minimality it is one line to drop,
but then M15's stated purpose goes unmet.

---

## Testing: the one place the research left a hole

`Tracer[F]`, `Span[F]`, `SpanOps[F]`, `SpanBuilder[F]` and `Span.Backend[F]` are
**all sealed**, and their `private[otel4s] trait Unsealed` escape hatches
(`Tracer.scala:223`, `SpanOps.scala:261`, `SpanBuilder.scala:66`,
`Span.scala:202`) are package-private to otel4s. **You cannot hand-roll a
recording `Tracer[F]`** the way this repo hand-rolls a `Trace[F]` in
`InMemorySuite`. Asserting on span names and attributes requires an otel4s
testkit.

Checked against Maven Central on 2026-08-02 — this is new information, not in
the research:

| testkit | latest published | JS? |
| --- | --- | --- |
| `otel4s-oteljava-trace-testkit` | **1.0.1** | **no** — no `_sjs1_` artifact exists |
| `otel4s-sdk-trace-testkit` | **0.19.0** | yes (`_sjs1_2.13`, `_sjs1_3`) |
| `otel4s-testkit` | 0.4.0 | — |

The cross-platform SDK family has **not been released at 1.0.x** —
`otel4s-sdk-trace` and `otel4s-sdk-trace-testkit` both stop at 0.19.0, and
`https://repo1.maven.org/maven2/org/typelevel/otel4s-sdk-trace-testkit_sjs1_2.13/1.0.1/`
is a 404. So at otel4s 1.0.1 **there is no testkit that works on Scala.js.**

**Proposed resolution (Q2 — Brian's call).** Split the tests:

- **Cross-platform, no testkit.** `ToAttributes` instances and `asAttributes`
  are pure functions over `Attributes`; they need no `Tracer` at all. The
  interpreters get a *transparency* suite over `Tracer.noop` (`Tracer.scala:239`,
  in `core-trace`): an instrumented call returns exactly what the underlying
  call returns, by-name arguments are not forced, and the constraint sets
  resolve. That is real coverage of the parts that are ours, and it runs on JVM
  and JS.
- **JVM only, with a testkit.** Span names, span nesting and recorded
  attributes are asserted with `otel4s-oteljava-trace-testkit % Test` in
  `.jvmSettings`, in a JVM-only test source directory. Both patterns already
  exist in this build: `core` has a JVM-only test dependency on
  `dwolla-otel-natchez` and a JVM-only test source
  (`core/jvm/src/test/.../TraceInitializationExample.scala`), and
  `raise-aspect-core` has the `Test / unmanagedSourceDirectories += … "scala-jvm"`
  split.

This means the module's headline behaviour — *the right attributes end up on
the right span* — is verified on the JVM and **not** on Scala.js. That is a
genuine coverage asymmetry and the reason this is a question rather than a
decision. The alternatives are worse: mixing `otel4s-core-trace` 1.0.1 with
`otel4s-sdk-trace-testkit` 0.19.0 invites an eviction across a major version
boundary, and pinning the whole module to 0.19.0 to get a JS testkit would ship
against a pre-1.0 API.

---

## Decisions (proposed — ratify before starting, then final)

**D1 — `ToAttributes[-A]` with `toAttributes(name, value): Attributes` is the
module's `Dom` and `Cod`.** Rationale above. This is the decision everything
else in the module is shaped by; if it changes, the plan changes.

**D2 — no `NotGiven` guards; instance priority is by trait inheritance.** We own
the type class, so the collision `ToTraceValue` works around does not arise.
Saves a dependency on `scalac-compat-features` and about twelve lines of noise.

**D3 — three deliberate divergences from `ToTraceValue`'s semantics, each
because otel4s's model can express something natchez's cannot:**

- **`Unit` → `Attributes.empty`**, not the string `"()"`. A `Unit`-returning
  method records no `returnValue` attribute at all. The span's existence already
  says the method ran.
- **`None` → `Attributes.empty`**, not the string `"None"`. An absent value
  becomes an absent attribute, which is what `AttributeKey.maybe`
  (`AttributeKey.scala:67`) does upstream and what queries over span data
  expect.
- **`Float` → `Double` via `.toDouble`.** otel4s has no `Float` key type and no
  `From` instance for one (`AttributeType.scala:25-38`,
  `Attribute.scala:89-101`); `Long` and `Double` are the only numeric keys. The
  widening is exact in the IEEE-754 sense and *inexact-looking* in print —
  `0.1f` records as `0.10000000149011612`. The scaladoc says so and points at
  writing your own instance if a different rendering is wanted. The alternative
  (`_.toString.toDouble`) prints prettily by silently changing the value, which
  is worse in a library.

Also record, for `BigDecimal`/`BigInt`: no instance ships, so they fall to the
`Show` fallback and record as strings. natchez accepted them as `NumberValue`.
This is a real behaviour difference, not a naming one.

**D4 — no generic `ToAttributes[Seq[A]]`.** `Attributes` deduplicates by key, so
a generic sequence instance would record only the last element. Only the four
native `Seq` primitives plus the three widened ones ship.

**D5 — `Show` fallback, no circe fallback** (subject to Q3). Keeps the module's
dependency list at `otel4s-core-trace` + cats + cats-tagless. A user who wants
JSON-encoded attributes writes a three-line instance.

**D6 — the module never calls an otel4s macro or `inline` method, including in
doctests.** Grep-checked in the plan.

**D7 — no error recording, and the default finalization strategy is left
alone.** Mismatch 3 above.

**D8 — 2.12 is excluded for this module only, by narrowing
`crossScalaVersions` on this project.** Ruled by Brian, 2026-08-02.

**D9 — no compatibility shims and no changes to any existing module.** M16 is
purely additive. Nothing in `core` is deprecated, aliased or re-pointed.

---

## Evidence: demonstrated versus argued

**Demonstrated (2026-08-02).** Nothing in this milestone has been compiled — no
sbt was run, by instruction — so "demonstrated" here means *read from a verified
source* or *observed over the network*, not *built*.

- Every API signature quoted above was read from `reference/upstream/otel4s/`,
  which is a byte-for-byte vendored subset of otel4s `v1.0.1` (see that
  directory's `README.md` for the provenance table).
- `SpanBuilder.scala:192` sets `finalizationStrategy = SpanFinalizer.Strategy.reportAbnormal`
  as the default; `SpanFinalizer.scala:47-56` is the strategy's body. Read
  directly, not inferred from the scaladoc that mentions it.
- The Scala 2 `AttributesScalaVersionCompanion` really does define an empty
  `MakeCompanion` — the whole file is a license header plus two empty traits.
- All five sealed types have `private[otel4s] trait Unsealed` and no public
  escape hatch (grepped across the vendored `trace` package).
- Maven Central, queried directly: `otel4s-core-trace_2.12` and
  `otel4s-core-trace_sjs1_2.12` are 404. `otel4s-oteljava-trace-testkit_2.13`
  is at 1.0.1 with no `_sjs1_` variant published at any version.
  `otel4s-sdk-trace-testkit` (all four platform/version combinations) stops at
  0.19.0, and its `1.0.1/` directory is a 404.
- The published POMs for `otel4s-core-trace_2.13:1.0.1` and
  `otel4s-core-common_2.13:1.0.1`, parsed for scope: the compile-scope
  dependency set in the table above, with `scalac-compat-annotation` at
  `provided`.
- `Tracer.noop`'s span path really is transparent, read rather than assumed:
  `Tracer.noop` delegates to `SpanBuilder.noop` (`Tracer.scala:239-245`), whose
  `modifyState(f) = this` and whose `build.use(f) = f(span)`
  (`SpanBuilder.scala:236-253`). So
  `spanBuilder(n).modifyState(g).build.surround(fa)` reduces to `fa`, which is
  exactly what the cross-platform transparency suite asserts.
- `otel4s-oteljava-trace-testkit_2.13:1.0.1`'s **sources jar** was downloaded and
  read: `TracesTestkit.inMemory[F: Async: LocalContextProvider](customize = identity): Resource[F, TracesTestkit[F]]`,
  with `def tracerProvider: TracerProvider[F]` and
  `def finishedSpans: F[List[io.opentelemetry.sdk.trace.data.SpanData]]` on the
  sealed result. Assertions therefore read the **OpenTelemetry Java** `SpanData`
  model (`getName`, `getAttributes`), or the testkit's own
  `SpanExpectation`/`TraceExpectations` DSL.
- `.github/workflows/ci.yml:58` runs `sbt githubWorkflowCheck`, and `:78`/`:82`
  enumerate per-project target directories — so **adding a module requires
  regenerating and committing the workflow** or CI fails on a check that has
  nothing to do with the code. (Worth flagging: M15's plan adds a module and
  never regenerates the workflow. That is a gap in `29-…-implementation-plan.md`,
  not in this one.)

**Argued from source, not compiled — each of these is a task-level check in the
plan, not an assumption:**

- That `ToAttributes[-A]`'s contravariance does not make implicit resolution
  ambiguous, in particular between `Seq[String]` and the `Show` fallback.
- That the inheritance priority ladder suffices without `NotGiven` (D2). The
  reasoning is standard implicit-priority-by-subclassing, but the natchez file
  next door does it the other way and the plan should not take that on faith.
- **Where `LocalContextProvider[IO]` comes from.** `TracesTestkit.inMemory`
  needs one, and `LocalContextProvider[F]` is a type alias for
  `org.typelevel.otel4s.context.LocalProvider[F, Context]`. otel4s's own
  examples call `TracesTestkit.inMemory[IO]()` with no extra wiring, which
  implies an implicit instance for `IO`, but the instance was **not** located in
  the 1.0.1 sources. Task 2 must read it, not guess it.
- That `sbt`'s `++ 2.12` skips a project whose `crossScalaVersions` lacks it,
  rather than failing the aggregate build. This is documented sbt ≥ 1.4
  behaviour and the build is on 1.12.13, but it is the mechanism the whole 2.12
  exclusion rests on and CI runs `++ 2.12` over the root aggregate
  (`ci.yml:66`).
- That `otel4s-oteljava-trace-testkit` runs on JDK 8, which `tlJdkRelease :=
  Some(8)` and the CI matrix require. `core` already runs the OpenTelemetry Java
  SDK under the same constraint via `dwolla-otel-natchez`, so the precedent is
  good, but it has not been checked for this artifact.
- Every line of scaladoc: the doctests compile only when the module does.

---

## Open questions for Brian

**Q1 — what should the module and artifact be called?** The permanent-on-publish
question, same as M15's Q1.

The repo has two naming families: natchez-coupled modules are prefixed
(`natchez-tagless`, `natchez-tagless-scalacache`, `natchez-tagless-mtl`), and
natchez-free modules are named for what they contain (`raise-aspect-core`,
`tagless-core`). This module is otel4s-coupled, so it wants the first family's
shape with a different prefix.

| candidate | for | against |
| --- | --- | --- |
| **`otel4s-tagless`** (recommended) | exact mirror of `natchez-tagless`, which is this repo's own flagship artifact; reads correctly as a coordinate; directory = artifact | puts an `org.typelevel` product name at the head of a `com.dwolla` artifact — but so does `natchez-tagless` |
| `tagless-otel4s` | keeps `tagless-core`'s leading word | inconsistent with `natchez-tagless`, which is the closer sibling |
| `natchez-tagless-otel4s` | fits the existing prefix family mechanically | asserts a natchez dependency the module deliberately does not have |

**The plan is written for `otel4s-tagless`**, directory `otel4s-tagless/`, sbt
project `otel4sTagless`. Substituting another name is a ten-minute edit before
the first publish and a breaking change after.

**Q2 — is a JVM-only test-scope dependency on `otel4s-oteljava-trace-testkit`
acceptable, given it leaves span-content assertions unrun on Scala.js?** See
*Testing* above. This exceeds the "`otel4s-core-trace` and nothing else"
constraint, in test scope, on one platform — and the alternative is no
span-content assertions anywhere, because the sealed types make a hand-rolled
recording `Tracer` impossible and the cross-platform SDK testkit is two minor
versions behind the API we are building against.

Sub-question, if the answer is yes: `otel4s-oteljava-trace-testkit` brings the
OpenTelemetry Java SDK into the JVM test classpath. `core` already does this via
`dwolla-otel-natchez % Test`, so it is not new to the build, but it is new to a
module that would otherwise have a very small test footprint.

**Q3 — should `ToAttributes` ship a circe-`Encoder` fallback, mirroring
`ToTraceValue.nonPrimitiveTraceValueViaJson`?** It would add `circe-core` as a
direct dependency of the module. My recommendation is **no** — `Show` covers
the same "I did not write an instance" case, circe's presence in `core` is
historical, and a user who wants JSON attributes writes
`ToAttributes[String].contramap(_.asJson.noSpaces)` in three lines. But it is a
real asymmetry with the natchez module and it is a dependency decision, so it is
yours.

---

## Acceptance criteria

- [ ] A `crossProject(JVMPlatform, JSPlatform)` / `CrossType.Pure` module exists
      at `otel4s-tagless/` (or the ratified name) with
      `crossScalaVersions := Seq("2.13.18", "3.3.8")`, one new direct dependency
      (`otel4s-core-trace`), and `mimaPreviousArtifacts := Set.empty`.
- [ ] `show otel4sTaglessJVM/crossScalaVersions` does **not** contain 2.12, and
      `show coreJVM/crossScalaVersions` (and every other module's) still does.
      `sbt "++ 2.12 natchez-tagless-rootJVM/test"` is green, having skipped the
      new module rather than failed on it.
- [ ] `com.dwolla.tracing.otel4s` contains `ToAttributes`,
      `TracerInstrumentation`, `TracerWeaveCapturingInputs` and
      `TracerWeaveCapturingInputsAndOutputs`, and
      `com.dwolla.tracing.otel4s.syntax` contains `traceWithInputs`,
      `traceWithInputsAndOutputs`, `instrumentAndTrace` and `asAttributes`.
- [ ] `grep -rnE "\.span\(|\.addAttribute\(|\.recordException\(|\.setStatus\(|\.withFinalizationStrategy\(|\.withSpanKind\(|\.withStartTimestamp\(|\.withParent\(" otel4s-tagless/src`
      returns nothing except `.backend.addAttributes(` and
      `State#addAttributes` inside `modifyState` — no otel4s macro is called
      anywhere, main sources or scaladoc.
- [ ] The transparency suite (over `Tracer.noop`) and the `ToAttributes` suite
      run and pass on **both** JVM and JS; the testkit suite runs and passes on
      the JVM. Every JS linker in the build is green.
- [ ] `ToAttributes` resolves without ambiguity for `String`, `Int`, `Float`,
      `Unit`, `Option[String]`, `List[String]` and a `Show`-only type, and
      **fails to compile** for a type with neither an instance nor a `Show`.
      The `NotGiven`-free priority ladder is proved by a compiling call, not by
      argument (D2).
- [ ] Doctests compile **and run** on 2.13.18 and 3.3.8;
      `otel4sTaglessJVM/doc` succeeds.
- [ ] Zero new compiler warnings on 2.13.18 and 3.3.8, verified locally with
      `-Xfatal-warnings` forced. CI does not enforce this
      (`sbt-typelevel-settings` 0.8.6 defaults `tlFatalWarnings := false`,
      unoverridden).
- [ ] `.github/workflows/ci.yml` is regenerated by `sbt githubWorkflowGenerate`
      and committed; `sbt githubWorkflowCheck` passes.
- [ ] No file outside `otel4s-tagless/`, `build.sbt`,
      `.github/workflows/ci.yml` and `docs/` is modified.
- [ ] `01-overview-design-and-laws.md` §3.1 names the new module and records the
      2.12 exclusion; M15's status section is updated to say its purpose was
      met.

## Ground rules reminder

- **`RaiseAspect` and `raise-aspect-*` are out of scope.** No `OnRaise`, no
  `RaiseAspect`, no otel4s `RaiseTraceWeaveOps`. If a task drifts there, stop.
- **Do not call an otel4s macro or `inline` method**, in main sources or in a
  doctest.
- **Do not modify `core`** or any other existing module. If M16 appears to
  require it, stop and report — that is a design problem, not a task.
- **Do not add a second otel4s compile dependency.** `otel4s-core-trace` brings
  the attribute model with it; `otel4s-core`, `oteljava-*` and `sdk-*` are
  backends and belong in applications.
- Do not re-derive the research's findings from memory. Read
  `reference/upstream/otel4s/`.
- Never use `--no-verify` or any other hook-bypass flag.
