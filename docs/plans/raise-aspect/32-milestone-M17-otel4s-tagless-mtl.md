# Milestone M17 — `otel4s-tagless-mtl`, and the shared `RaiseRecorder`

## Status

**Complete (2026-08-03).** Branch `milestone/m17-otel4s-tagless-mtl`, stacked on
M16's `685dcce`. The implementation plan is
`33-milestone-M17-implementation-plan.md`; each task's brief and report live
under `.superpowers/sdd/33-milestone-M17-implementation-plan/`, and
`progress.md` there is the authoritative per-task ledger. D1–D8 shipped as
written. **D9 is retracted** — see below and the decision itself.

Requested by Brian: "add an `otel4s-tagless-mtl` module next, similar to
`natchez-tagless-mtl`, but for otel4s code. We should extract any shared code
from `natchez-tagless-mtl` into a common place."

### What landed, task by task

- **Task 1** (`a34d4eb` "refactor: share the RaiseRecorder priority mechanism
  via raise-aspect-core", `5850867`, `913757a`) moved `RaiseRecorder`,
  `DefaultOnRaise` and the `raise.error.*` key constants into
  `com.dwolla.tagless.mtl` in `raise-aspect-core`, deleted
  `RaiseRecorder.IsTraceableValue`, and left `natchez-tagless-mtl` supplying
  only a `NatchezDefaultOnRaise`.
- **Task 2** (`856d257` "build: add the otel4s-tagless-mtl module") added the
  `otel4sTaglessMtl` cross-project and proved the 2.12 containment against an
  empty module, before any content existed.
- **Task 3** (`4d8ef3c` "feat(m17): record typed raised errors as otel4s span
  attributes") added `Otel4sDefaultOnRaise`, `syntax/package.scala` and
  `RaiseRecorderPrioritySpec`.
- **Task 4** (`cb027b9` "feat(m17): add RaiseAspect-aware otel4s tracing
  syntax", `f240cdd`) added `RaiseTracerWeaveOps`, the `Foo` fixture and
  `RaiseTracerTransparencySpec` — and retracted D9.
- **Task 5** (`bef48d9` "feat(m17): add AnyValueRaiseAspect for Scala 3 derives
  clauses", `8f2642d`) added `AnyValueRaiseAspect`, `Scala3UsageNote`, and the
  module's first `src/test/scala-3` sources.
- **Task 6** (this commit) added the JVM span-content proof, the module
  scaladoc, the README section, and this reconciliation.

### What diverged from the plan, and why

- **D9 was factually wrong and is retracted in place** (Task 4). It claimed the
  mtl `traceWithInputs` must demand an `Apply[F]` its non-mtl counterpart does
  not, resting on a misconception about Scala implicit resolution. The
  implementer added the parameter per the decision, hit a `-Wunused:params`
  warning on it — a **true** positive — and suppressed it with `@nowarn` rather
  than questioning the decision. The code review settled it with a compile
  probe on 2.13.18 and 3.3.8: the parameter contributes nothing, and its
  presence made the mtl signature strictly *stronger* than the non-mtl one.
  Parameter and suppression both removed. This is the milestone's most
  substantive lesson: a wrong claim about language semantics in a design
  decision propagated into a signature, a suppression and scaladoc before
  anything caught it.
- **Six defects were found in the plan's own prose, five of them in
  uncompiled code sketches.** A task-ordering error (Task 3's test importing a
  package object Task 4 created); a doctest whose stated proof of D2 did not
  actually exercise D2; `cats.effect.unsafe.implicits.global` colliding with
  `CatsEffectSuite`'s own `IORuntime`; `.unsafeRunSync()`, which does not exist
  on Scala.js's `IO`, in a cross-platform test; a `new TracerWeaveCapturingInputs`
  sketch whose real constructor takes two type parameters; and D9. Plan text
  written against uncompiled APIs kept producing this class of error.
- **`build.sbt` changed more than planned, twice.** Task 3 moved
  `munit-cats-effect` from `.jvmSettings` (`%%`) to the shared block (`%%%`)
  because its test is cross-platform — which invalidated Task 2's containment
  result, so the full containment check was re-run rather than assumed. Task 6
  added a `src/test/scala-3-jvm` test source directory, gated on
  `scalaBinaryVersion == "3"` inside `.jvmSettings`, because the
  `derives`-clause span-content test needs the Scala 3 axis *and* the JVM-only
  testkit and no existing directory is the intersection of the two.
- **Task 5's Scala 3 coverage used a different oracle than planned.** The brief
  said to mirror `DerivesBarTracingSpec`'s fixed span history; that depends on
  natchez's cross-platform `InMemory`, and otel4s has no cross-platform
  equivalent (span content is JVM-testkit-only, the asymmetry M16 ratified). It
  mirrored `TraceableRaiseAspectSpec`'s differential-oracle pattern instead.
  That left the derived instance untested through a real `Tracer`, which Task 5
  flagged and Task 6 closed with `DerivesFooSpanContentSpec`.
- **`@nowarn` filter strings are not portable** (Task 1). `@nowarn("cat=unused")`
  is Scala 2 syntax; Scala 3 rejects the category and then *also* emits the
  original warning, so the axis got worse. A bare `@nowarn` works on 2.12.21,
  2.13.18 and 3.3.8.
- **A false statement in `otel4s-tagless`'s README was found and corrected**
  (Task 6). It said a cats-mtl `Raise` error is invisible to otel4s's
  `reportAbnormal`. That holds only when `Raise` lives in the effect's
  *success* channel; under `Handle.allowF` over a `MonadThrow` `F` — the shape
  both mtl modules' own examples use — cats-mtl's submarine encoding makes the
  raise a real `Throwable`, and the span is marked `ERROR`.

### Verification

Whole-branch, at Task 6, with `SBT_OPTS="-Xmx6G -XX:MaxMetaspaceSize=1G"`
(`+test` OOMs at the default heap on `raiseAspectMacros`' Scala 3 test
compile — environment, not code).

`sbt +test` exit 0. `++2.12.21 natchez-tagless-rootJVM/test` and `rootJS/test`
exit 0. `+otel4sTaglessMtlJVM/doc`, `+natchezTaglessMtlJVM/doc`,
`+raiseAspectCoreJVM/doc` exit 0. `githubWorkflowCheck` clean with no
regeneration needed. **No `[warn]` line anywhere in the sweep names either
otel4s module**; the warnings that remain are pre-existing `core` unused
imports, a `natchez-tagless-mtl` doctest outer-reference warning, and
sbt's "multiple main classes" notice.

Per-module, per-version, per-platform (JVM/JS):

| module | 2.12.21 | 2.13.18 | 3.3.8 |
|---|---|---|---|
| `core` | 15/15 · 15/15 | 15/15 · 15/15 | 31/31 · 29/29 |
| `natchezTaglessMtl` | 20/20 · 20/20 | 20/20 · 20/20 | 33/33 · 33/33 |
| `raiseAspectCore` | 36/36 · 36/36 | 36/36 · 36/36 | 36/36 · 36/36 |
| `otel4sTagless` | — | 29/29 · 21/21 | 29/29 · 21/21 |
| `otel4sTaglessMtl` | — | 12/12 · 7/7 | 22/22 · 15/15 |

Against the branch point, two modules moved and both were meant to:
`raiseAspectCore` (Task 1's new `RaiseRecorderSpec`, 36 on every axis, no prior
suite) and the new module. `natchezTaglessMtl` moved from its pre-M17 baseline
of 18/18/31 to 20/20/33 — exactly the two `DefaultOnRaiseReachabilitySpec`
tests Task 1's fix round added to give D2 an executable proof; nothing else in
it changed. `core` and `otel4sTagless` are untouched in every compiled source
(M17's only edit to `otel4s-tagless` is its README).

The 2.12 dashes are the containment working: both otel4s modules compile
nothing, run no test task, and resolve no otel4s coordinate on 2.12, while both
2.12 root runs stay green.

### Anything a later milestone needs

- **`mimaPreviousArtifacts := Set.empty` is now on seven modules**, and M17 did
  not address it. `tlVersionIntroduced := Map(...)` in place of `Set.empty` is
  the fix, and it is one decision covering all seven — needed before the next
  publish. The comments in `build.sbt` are internally inconsistent about the
  count (the six older copies still say "six"), which the fix should tidy.
- **D5's semconv gap is still open.** Nothing in this family sets OTel's
  registered `error.type`, and doing it properly means setting it at span *end*
  from how the span actually finished, which is a different insertion point
  from this hook.
- **Risk 4 (the circe divergence) is still unruled.** `ToTraceValue` ranks a
  circe `Encoder` above `Show`; `ToAnyValue` has only `Show`. M16 documented it
  for parameters and return values; M17 extended the documentation to error
  values. Whether a structural `Json => AnyValue` fallback should ship is Q3 in
  `30-milestone-M16-otel4s-module.md`.
- **`raise.error.*` on a second raise within one method call overwrites** (D4).
  Documented, not designed around. If it ever needs fixing, a span event is the
  alternative that was considered and rejected.
- **Span content stays JVM-only for otel4s.** Revisit when
  `otel4s-sdk-trace-testkit` reaches 1.0.x; today it stops at 0.19.0 and
  `otel4s-oteljava-trace-testkit` publishes no `_sjs1_` artifact.
- **Source comments in `raise-aspect-core` still cite SDD task numbers** (one,
  in `ObservingCapabilitySpec.scala`). M17 scrubbed the ones in
  `otel4s-tagless-mtl`; that one was out of footprint.

## Context

M16 shipped `otel4s-tagless`: otel4s counterparts of `core`'s three tracing
interpreters, wired to plain `cats.tagless.aop.Aspect`. It deliberately did not
touch the `RaiseAspect` path, so an algebra whose methods take
`cats.mtl.Raise[F, E]` capability parameters can be traced through natchez and
not through otel4s. M17 closes that gap.

**Most of the extraction Brian asked for already happened, in M11.**
`raise-aspect-core` holds `RaiseAspect`, `OnRaise` and `WeaveInterpreter`, and
`WeaveInterpreter`'s scaladoc already states the intent verbatim: "Nothing here
is specific to tracing, or to any particular backend. […] a natchez module and
an otel4s module — which share no rendering type class — use the same instance
resolution." That claim has never been exercised by a second backend. M17 is its
first real test, and if it turns out to be false, that is a finding worth more
than the module.

What is left in `natchez-tagless-mtl` is a thin wiring layer — roughly 130 lines
of code under ~200 lines of scaladoc:

| file | lines | backend-agnostic? |
|---|---|---|
| `syntax/RaiseRecorder.scala` | 85 | **shape yes, default no** |
| `syntax/RaiseTraceWeaveOps.scala` | 49 | no — every type is natchez |
| `mtl/package.scala` | 197 | no — documentation |
| `syntax/package.scala` | 3 | no |

`RaiseTraceWeaveOps` shares nothing real: `Trace`, `TraceableValue`,
`TraceWeaveCapturingInputs` and `TraceWeaveCapturingInputsAndOutputs` are all
natchez. `RaiseRecorder` is the only genuine extraction candidate — its
priority mechanism is backend-agnostic and only its low-priority default is
natchez-specific.

## Decisions

### D1 — extract the `RaiseRecorder` mechanism into `raise-aspect-core`

`RaiseRecorder` moves from `com.dwolla.tracing.mtl.syntax` to
`com.dwolla.tagless.mtl`, joining `OnRaise` and `WeaveInterpreter`. The
low-priority instance stops naming natchez and instead takes a new
backend-supplied tag:

```scala
trait DefaultOnRaise[F[_], Err[_]] extends Serializable {
  def onRaise: OnRaise[F, Err]
}

sealed trait RaiseRecorder[F[_], Err[_]] {
  def onRaise: OnRaise[F, Err]
}

object RaiseRecorder extends LowPriorityRaiseRecorder {
  implicit def fromOnRaise[F[_], Err[_]](implicit or: OnRaise[F, Err]): RaiseRecorder[F, Err]
}

trait LowPriorityRaiseRecorder {
  implicit def fromDefault[F[_], Err[_]](implicit d: DefaultOnRaise[F, Err]): RaiseRecorder[F, Err]
}
```

Each backend then supplies only a `DefaultOnRaise` instance.

**`RaiseRecorder.IsTraceableValue` deletes.** That 22-line witness, plus its
12-line scaladoc, exists solely to give the two instances a matching
`[F[_], Err[_]]` shape, because Scala 2.13 — and only 2.13 — refuses to order
them by the object-extends-trait rule when the low-priority one fixes `Err`
directly. Under D1 both instances are naturally `[F[_], Err[_]]`-shaped and the
witness has nothing to do.

Deleting it rather than duplicating it is the whole point. A second copy in
`otel4s-tagless-mtl` would be a second copy of a *compiler-quirk workaround*,
with its own scaladoc re-explaining the same quirk — the kind of duplication
that rots, because a later cleaner fix gets applied to one copy and not the
other.

#### The evidence

This was verified before the decision was taken, not asserted. A four-case ×
three-version matrix, each case printing which instance actually won rather than
merely compiling:

| case | 2.12.21 | 2.13.18 | 3.3.8 |
|---|---|---|---|
| control, default only | `fromTrace` | `fromTrace` | `fromTrace` |
| control, user hook present | `fromOnRaise` | **AMBIGUOUS** | `fromOnRaise` |
| proposal, default only | `fromDefault` | `fromDefault` | `fromDefault` |
| proposal, user hook present | `fromOnRaise` | `fromOnRaise` | `fromOnRaise` |

The control is the current shape with the witness removed. It reproduces the
ambiguity on exactly 2.13, exactly where `IsTraceableValue`'s own scaladoc says
it lives:

```
ambiguous implicit values:
 both method fromTrace in trait LowPriorityRaiseRecorder of type
   [F[_]](implicit T: natchez.Trace[F]): spike.RaiseRecorder[F,natchez.TraceableValue]
 and method fromOnRaise in object RaiseRecorder of type
   [F[_], Err[_]](implicit or: spike.OnRaise[F,Err]): spike.RaiseRecorder[F,Err]
```

Two things the matrix shows that a naive spike would have missed:

- **The ambiguity only bites when a user hook is in scope.** With only the
  default available, `fromOnRaise` is not applicable and priority never has to
  fire, so 2.13 passes. The witness is therefore not protecting an edge case —
  it protects the entire override path, which is the feature `RaiseRecorder`
  exists for. A spike testing only the default path returns all-green and
  supports the wrong conclusion.
- **The proposal is green in all twelve cells**, and the load-bearing cell is
  `proposal, user hook present` on 2.13: same shape on both instances, priority
  orders them, no witness.

**Scope of that evidence.** The spike used a stand-in `OnRaise` and a hand-rolled
mechanism at a concrete `F = IO` via `implicitly`, not the real
`raise-aspect-core` types through the real `RaiseTraceWeaveOps` call sites,
which summon at abstract `F` with more implicits in flight. Task 1 must confirm
resolution against the real module rather than treat the spike as settling it.

The script and full per-case compiler output are not committed — they were
scratch. The matrix and the error above are the durable record.

### D2 — the cost of D1: the natchez default moves to lexical scope

Today `fromTrace` sits in `RaiseRecorder`'s companion hierarchy, so it is in the
*implicit scope* of `RaiseRecorder[F, TraceableValue]` and is found with no
import at all. After D1 the companion lives in `raise-aspect-core`, which cannot
name natchez, so the default must live in `natchez-tagless-mtl` — and implicit
scope for `DefaultOnRaise[F, TraceableValue]` reaches the companions of
`DefaultOnRaise` (core, cannot), `F` (unknown), and `TraceableValue` (natchez's
own, not ours). None are available, so the instance is reached lexically: by
import.

This is a real regression and is accepted knowingly:

- **The normal path is unaffected.** Calling `traceWithInputsAndOutputs` already
  requires `import com.dwolla.tracing.mtl.syntax._`, and that import carries the
  default.
- **What breaks is a bare summon** — `implicitly[RaiseRecorder[F, TraceableValue]]`
  with no syntax import. `mtl/package.scala`'s second doctest does exactly this
  and must gain the import.
- **It does not collide with a user hook.** Both are lexically scoped, but they
  are different types (`OnRaise` vs `DefaultOnRaise`), and priority is still
  decided between the two `RaiseRecorder` instances in the companion hierarchy.
  The spike's `proposal, user hook present` case covers precisely this.

`natchez-tagless-mtl` and `raise-aspect-core` both carry
`mimaPreviousArtifacts := Set.empty` and have never been published, so the move
costs no binary compatibility.

### D3 — the error keys live in core, shared by both backends

`ErrorTypeKey = "raise.error.type"` and `ErrorValueKey = "raise.error.value"`
move to `raise-aspect-core` alongside the mechanism, rather than being declared
separately in each backend module.

Both backends record under identical keys (D5), and putting the constants in one
place makes that structural rather than a convention two modules happen to
agree on and can drift from. It also means a consumer switching backends can
compare against one name.

### D4 — otel4s records the raise as span attributes, not a span event

`DefaultOnRaise[F, ToAnyValue]` adds attributes to the current span, the direct
analogue of natchez's `Trace[F].put`. `Span.Backend#addEvent(name, attributes)`
exists in otel4s 1.0.1 and was the alternative.

**For attributes:** parity with the natchez module — the family's whole premise
is that swapping the import changes the backend, not the shape of your data —
and query ergonomics, since filtering and aggregating on span attributes is
straightforward in every backend, whereas events get flattened into pseudo-spans
(Honeycomb) or rendered as logs (Jaeger, Tempo) and are awkward to aggregate.

**Against attributes, honestly:** the tempting argument that a collision is
*impossible* — one span per method, one raise per method — is wrong. The hook is
attached to the `Raise[F, E]` handed into the method, so a method that raises,
rescues internally via `Handle.allow`, and raises again fires the hook twice
against the same span, and the second `addAttributes` overwrites the first. An
event would record both with timestamps. This is unusual, and it is a real
lossy case, and it is documented rather than designed around.

**The argument for events that was discounted:** "it is more idiomatic OTel."
OTel's convention for an exceptional occurrence is the `exception` event, which
this cannot use — a raise is not a `Throwable`, and backends special-case that
name and expect stacktrace fields. A custom `raise` event that nothing
special-cases makes the idiomatic benefit largely aesthetic while the parity and
queryability costs stay concrete.

### D5 — the keys are `raise.error.type` / `raise.error.value`, not semconv `error.type`

Byte-identical to what natchez already records.

The obvious alternative is OTel's registered `error.type` attribute, which would
light up backend error-rate tooling for free. **The cardinality objection to it
is not valid** and should not be repeated: semconv explicitly endorses a
canonical class name there ("its canonical class name identifying the type
within the artifact SHOULD be used"), and a domain error ADT is a small closed
set of case classes.

The objection that does hold is about *meaning*. Semconv `error.type` describes
how **the operation ended**. This hook fires at raise time, not at span-end
time, so a raise that the method rescues internally leaves a span that completed
successfully but carries `error.type` — telling every backend that reads that
key that an operation failed when it did not. A false signal in a field with
defined semantics is worse than a private key nobody interprets.

Participating in semconv properly would mean setting `error.type` at span *end*,
from how the span actually finished. That is a separate piece of work and is not
in M17.

### D6 — the hook reaches the span through `Tracer[F].currentSpanOrNoop`

`OnRaise` resolves independently of the interpreter and fires inside the method
body — inside `fa.codomain.target`, which the interpreter does not wrap. So the
hook cannot be handed the `Span` the interpreter is holding.
`currentSpanOrNoop` is not a shortcut here; it is the only route.

This works because `TracerWeaveCapturingInputs`/`AndOutputs` build the span with
`.use`, and `SpanOps#use` makes the span current for the duration of the body —
its lifted implementation is `resource.use { res => res.trace(f(res.span)) }`,
and `res.trace` is what otel4s documents as propagating span context. M16's own
span-nesting test in `SpanContentSpec` corroborates it empirically: parent and
child ids only line up if `use` establishes currency.

**This claim is the single most likely thing in M17 to be wrong**, so it gets a
dedicated JVM test asserting the `raise.error.*` attributes land on the method's
own span and not on the parent.

**Confirmed empirically in Task 6** (`RaiseSpanContentSpec`, against the
oteljava testkit), and the test was proven discriminating rather than trusted
green: two mutations of `TracerWeaveCapturingInputsAndOutputs` were run and
reverted. Re-establishing the parent's context inside `use` made the hook's
writes disappear (4 of 5 tests fail); removing the child span altogether put
`raise.error.type` and `raise.error.value` literally on the caller's span (all
5 fail). Prior to this the claim had been corroborated only by reading otel4s's
sources.

The instance requires `Tracer[F]` and `FlatMap[F]` — the natchez counterpart
needs only `Trace[F]`. Under `Tracer.noop`, `currentSpanOrNoop` yields a noop
span whose `addAttributes` does nothing, so a disabled tracer costs nothing.

`.backend.addAttributes` deliberately, for the reason M16 already documents:
`Span#addAttributes` is a macro on Scala 2 and inline on Scala 3, and
`Span.Backend#addAttributes` is the sealed method underneath.

### D7 — omit `raise.error.value` when it encodes to `AnyValue.empty`

`raise.error.type` is always recorded. `raise.error.value` is omitted when
`ToAnyValue` renders the error to `AnyValue.empty`, matching the omit-when-empty
rule the module already applies to parameters and return values.

This preserves M16's division of labour: **totality lives in the type class,
absence lives in the interpreter.** `ToAnyValue` stays a total `A => AnyValue`;
only the recording site decides an empty value is not worth an attribute slot.

### D8 — module shape

Artifact and directory `otel4s-tagless-mtl`, sbt cross-project `otel4sTaglessMtl`,
package `com.dwolla.tracing.otel4s.mtl`, syntax package
`com.dwolla.tracing.otel4s.mtl.syntax`. Cross JVM/JS, `CrossType.Pure`,
`.dependsOn(otel4sTagless, raiseAspectCore, raiseAspectMacros)`.

2.13 and 3 only, using the identical "Option B" containment `otel4sTagless`
carries — otel4s dependency gated on `isOtel4sScalaVersion`, both
`Compile` and `Test` `unmanagedSourceDirectories` emptied on 2.12,
`publish / skip`, and — separately — the `.jvmSettings` test-source-directory
addition gated too. That last gate leaked twice during M16 because `.jvmSettings`
are appended *after* the shared `:=` that empties the list; the plan names it
explicitly for that reason.

Contents, mirroring `natchez-tagless-mtl` file for file:

| `natchez-tagless-mtl` | `otel4s-tagless-mtl` |
|---|---|
| `syntax/RaiseRecorder.scala` | — (moved to core by D1) |
| `syntax/RaiseTraceWeaveOps.scala` | `syntax/RaiseTracerWeaveOps.scala` |
| `syntax/package.scala` | `syntax/package.scala` |
| `mtl/package.scala` | `mtl/package.scala` |
| `scala-3/TraceableRaiseAspect.scala` | `scala-3/AnyValueRaiseAspect.scala` |
| `scala-3/Scala3UsageNote.scala` | `scala-3/Scala3UsageNote.scala` |

`AnyValueRaiseAspect` — the M13 parallel, pinning `Dom`, `Cod` and `Err` to
`ToAnyValue` so Scala 3 users can write `derives AnyValueRaiseAspect` — is
included because `natchez-tagless-mtl` has its counterpart and Brian asked for
similarity. It is the most severable piece of M17 if scope needs cutting.

M13's hard-won `@experimental` placement rule carries over unchanged: the
annotation belongs on the **companion object**, not the algebra trait.
Annotating the trait makes the algebra *type* experimental, which forces
`@experimental` onto every reference to it, including untraced call sites.

### D9 — RETRACTED: `traceWithInputs` does *not* need an `Apply[F]` the non-mtl otel4s version lacks

**Originally believed:** `WeaveInterpreter.fromRaiseAspect` requires `Apply[F]`
to sequence the hook, and the mtl `traceWithInputs` cannot know in advance
which `WeaveInterpreter` instance will resolve, so it must demand `Apply[F]`
up front regardless — an `Apply[F]` that `com.dwolla.tracing.otel4s.syntax
.traceWithInputs` does not need, since M16 dropped it deliberately (parameters
go onto the `SpanBuilder` before the span exists, so there is nothing to
sequence). The stated conclusion was that moving a call site from
`otel4s.syntax` to `otel4s.mtl.syntax` adds this one constraint, documented
rather than designed around because every realistic `F` has `Apply` anyway.

**This is wrong.** The reasoning rested on a misconception about Scala
implicit resolution: that an earlier implicit parameter in a method's own
parameter list is available as a candidate when the compiler resolves a later
parameter in that *same* list — i.e., that `traceWithInputs`'s own `F:
Apply[F]` parameter could be what lets its own `ev: WeaveInterpreter[...]`
parameter resolve down the `fromRaiseAspect` path. It is not. A method's
implicit parameter list resolves as a whole from the *caller's* scope; the
method's own parameters are not implicit candidates for resolving each other.
`WeaveInterpreter#apply` itself declares no effect constraint at all. Whatever
satisfies `Apply[F]` at a call site was already there, in that caller's scope,
and equally available to `RaiseAspect#intercept` (via `fromRaiseAspect`)
directly — `traceWithInputs`'s own `F: Apply[F]` parameter contributed nothing
to that resolution.

Task 4's implementer had added the parameter per this decision, hit a
`-Wunused:params` warning on it (a true positive, not a false one — the
compiler was right that the parameter had no use), and suppressed the warning
with `@nowarn` instead of questioning the decision that produced it. Task 4's
code review caught the error and settled it empirically: with a compile probe
on both 2.13.18 and 3.3.8, dropping the parameter from `traceWithInputs`
leaves the `RaiseAspect` path resolving exactly as before.

**Consequence:** `traceWithInputs`'s signature needs no `Apply[F]`, and is
therefore identical to the non-mtl otel4s `traceWithInputs` after all —
restoring the property this design doc wanted throughout, that switching a
call site between the two syntax packages changes nothing about what the
caller must provide. There is no divergence to document, and the `@nowarn`
that had suppressed the correct warning was removed along with the parameter.

Left in place, retracted rather than deleted, so the next person who wonders
about `Apply[F]` on this method finds the wrong reasoning, why it was wrong,
and how it was settled — instead of silently finding nothing.

## Non-goals

- **No otel4s `Handle` support.** `Handle[F, E]` remains rejected at derivation
  time for the reason `raise-aspect-core` already documents: it *consumes* `F`,
  so it is not transportable across the woven boundary.
- **No semconv `error.type` participation** (D5) — separate work.
- **No `instrumentAndTrace` in the mtl module.** `natchez-tagless-mtl` has no
  counterpart; `Instrument` is a plain-`Aspect` notion.
- **No move of `RaiseTraceWeaveOps`** into anything shared. Every type in it is
  natchez; there is nothing to share.
- **The `tlVersionIntroduced` release blocker is not addressed here.** M17 adds a
  seventh `mimaPreviousArtifacts := Set.empty` module. One decision covering all
  of them is still needed before the next publish.

## Verification

- `+natchezTaglessMtlJVM/test` and `+raiseAspectCoreJVM/test` green on all three
  versions, with **counts identical to before Task 1** — the extraction is a
  refactor and must not change behaviour.
- `otel4sTaglessMtlJVM/test` and `otel4sTaglessMtlJS/test` green on 2.13.18 and
  3.3.8; both compile and run **zero** tests on 2.12 by construction.
- Both 2.12 root runs green (`natchez-tagless-rootJVM` and `rootJS`), which is
  what the containment exists to protect.
- `doc` succeeds on every touched module; `githubWorkflowCheck` clean.
- The JS linker produces a non-trivial artifact for the new module — M16's
  sibling bug (`%%` instead of `%%%` silently linking zero tests) is a
  known failure mode in this build, so JS test counts are asserted, not assumed.

## Risks

1. **`use`-makes-the-span-current could be wrong** (D6). Mitigated by a
   dedicated parent-vs-child assertion, not just "an attribute was recorded".
2. **`WeaveInterpreter`'s backend-agnostic claim could be false.** First real
   second-backend test. If it needs changing, that is a `raise-aspect-core`
   change and affects natchez too — stop and report rather than patching around
   it.
3. **The 2.12 containment leaks a third way.** Two leaks in M16, both found late.
   Task 2 verifies containment before any content exists, so a leak is found
   against an empty module.
4. **Circe divergence, inherited.** `ToTraceValue` ranks an `Encoder` fallback
   above `Show`; `ToAnyValue` has only `Show`. An error ADT with both traces
   structurally under natchez and, after an import swap, compiles unchanged and
   records its `Show` rendering. M16 documented this for parameters and return
   values; M17 extends it to error values. Still unruled as a design question.
