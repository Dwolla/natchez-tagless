# Milestone M6 — typed-error span recording at the raise-lift interception point

## Status

**Complete** (branch `milestone/m6-typed-error-span-recording`, stacked on
`milestone/m5-natchez-integration`). The Decisions section below was
ratified by Brian at kickoff (2026-07-29) and implemented as written.

`raise-aspect-core` gained `OnRaise[F]`/`OnRaise.noop` and an `OnRaise`-hook
overload of `WeaveArrows.raiseLift`, additive alongside the existing
no-hook version. `natchez-tagless-mtl` gained `RaiseRecorder` (the same
sealed low-priority-implicit pattern M5 used for `WithInputsAndOutputsTracer`),
wired into both `fromRaiseAspect` tracer methods, recording
`raise.error.type`/`raise.error.message` via `Trace[F].put` by default,
with a user-supplied `implicit OnRaise[F]` taking priority. `git diff`
against `7c4a435` (M5's tip) scoped to `raise-aspect-laws` and
`raise-aspect-macros` is empty. Cross-builds green on 2.12.21/2.13.18/3.3.8,
JVM tests and Scala.js *linking* (not just compilation) both verified,
including the full 2×3 module/version linker matrix run during final
review.

### A real regression, found and fixed mid-milestone: Scala.js linking

Task 1's hand-rolled `Serializable` test (`java.io.ObjectOutputStream`/
`ObjectInputStream`, needed because `raise-aspect-core` deliberately has no
`cats-laws`/`discipline-munit` dependency — that's scoped to the frozen
`raise-aspect-laws` module only) broke Scala.js linking outright: those
classes don't exist in Scala.js's `java.io` emulation, so the linker fails
on the reference regardless of any runtime guard. Task 2 replicated the
same pattern and inherited the break. Fixed (an unplanned "Task 2.5") with
the exact idiom `cats-kernel-laws` itself uses:
`cats.platform.Platform.isJvm` as a `final val` — a compile-time constant
scalac folds away before the Scala.js linker ever sees the eliminated
branch — with per-platform `Platform.scala` sources added via
`.jvmSettings`/`.jsSettings` on `raiseAspectCore`'s existing `crossProject`
(kept scoped to test sources; did not migrate the module off
`CrossType.Pure`). The JVM-side assertion is byte-identical to before, just
wrapped; nothing was weakened.

### Task 4 needed no dedicated commit

Task 3's fix to keep `RaiseTraceIntegrationSuite` green (an "unplanned but
necessary consequence" of wiring `RaiseRecorder` into the default path) already
added the exact raising-path field assertions Task 4 asked for, with concrete
expected values, not just presence checks. Combined with the pre-existing
success-path test's exact-list-equality behavior (which would already fail
on a spurious `raise.*` field), all three of Task 4's acceptance points were
independently and fully satisfied before Task 4 was ever dispatched. Verified
directly rather than assumed; recorded as complete via subsumption with zero
new commits, both in the working ledger and confirmed again by the final
whole-branch reviewer.

### One adjudicated (not fixed) finding: the Serializable-test convention

A per-task reviewer flagged Task 1's hand-rolled Serializable test as not
matching "the repo's existing convention" (`cats-laws`' `SerializableTests`
+ `checkAll`, used in `raise-aspect-laws`). Checked directly:
`raiseAspectCore`'s `build.sbt` entry has no `cats-laws`/`discipline-munit`
dependency at all — that tooling is deliberately scoped to the laws module
only. Adding it to core for one test would be an unrequested new dependency
and a bigger architectural change than this milestone's footprint. Parked,
not fixed: the hand-rolled round-trip is the correct, minimal choice given
the module's existing dependency boundary.

### The one finding from final review: sensitive data in span fields

The final whole-branch reviewer (run on Opus, after independently closing a
JS-linking evidence gap by running the full 2×3 module/version linker
matrix — all green) caught something none of the per-task reviews were
positioned to see: `RaiseRecorder.fromTrace`'s default records the domain
error's raw `e.toString`, bypassing `TraceableValue` entirely — unlike every
other value this library traces, and unmentioned by the redaction guidance
`TraceWeaveCapturingInputs(AndOutputs)` already documents elsewhere. Fixed
in one follow-up commit: a doc paragraph naming the gap and pointing at a
custom `OnRaise[F]` as the redaction mechanism, plus two small doc/test-name
polish items. Scoped re-review: all addressed, no new breakage.

### Verification

`sbt "+raiseAspectCoreJVM/test"` (34/34 × 3 versions),
`natchezTaglessMtlJVM/test` (13/13 × 3 versions),
`raiseAspectCoreJS/Test/scalaJSLinkerResult` and
`natchezTaglessMtlJS/Test/scalaJSLinkerResult` both green on all three
Scala versions (verified independently during final review, not just
compile-checked), `natchezTaglessMtlJVM/doc` succeeds. Zero diff in
`raise-aspect-laws`/`raise-aspect-macros`. Scala.js test *execution*
remains uncovered locally (no Node), consistent with every prior milestone.

---

The rest of this document is the original task brief, preserved as written
before implementation began.

---

Read `01-overview-design-and-laws.md` first.

## Problem

M5 documented (and tested) the Submarine caveat: a raise that crosses the
traced wrapper before being rescued surfaces in the span's `Throwable`
channel only as cats-mtl's opaque `Submarine` exception (cats-mtl issue
#648). The typed domain error is invisible in the trace.

But `WeaveArrows.raiseLift` holds the typed error `e: E2` at the exact
moment it crosses the tracing boundary — the shell `Weave` it builds wraps
`rf.raise[E2, A](e)`. That is the interception point the overview reserved
for this milestone: run a recording effect there, before the raise, so the
span carries the domain error even though the `Throwable` channel stays
opaque.

## Decisions (proposed — ratify before starting, then final)

1. **The hook is universally quantified in the error type.** `RaisePull`'s
   `apply[E]` method takes no per-`E` implicit evidence, so a per-error-type
   typeclass (e.g. `TraceableValue[E]`) *cannot* be threaded to the
   interception point — this is structural, not a style choice. Anything
   error-specific happens inside a user-supplied hook by matching on the
   value.

2. **Core hook type**, in `com.dwolla.tagless.mtl` (`raise-aspect-core`; no
   new dependencies):

   ```scala
   trait OnRaise[F[_]] extends Serializable {
     def apply[E](e: E): F[Unit]
   }
   object OnRaise {
     def noop[F[_]](implicit F: Applicative[F]): OnRaise[F] = ...
   }
   ```

3. **Core: an overload of `raiseLift` taking the hook.** Same shape as the
   existing method, with the shell target becoming
   `onRaise(e) *> rf.raise[E2, A](e)`:

   ```scala
   def raiseLift[F[_], Dom[_], Cod[_]](onRaise: OnRaise[F])(implicit
       F: Apply[F],
       syn: Synthetic[Cod]
   ): RaisePull[F, Aspect.Weave[F, Dom, Cod, *]]
   ```

   `Apply[F]` (upgraded from `Functor[F]`) is what the sequencing needs; the
   existing no-hook `raiseLift` keeps its `Functor` constraint and its exact
   behavior. The synthesized `functor` member is identical in both overloads
   (share the private `syntheticWeaveFunctor`).

4. **Default recording in `natchez-tagless-mtl`**: two fields via
   `Trace[F].put` (the same API `TraceWeaveCapturingInputsAndOutputs`
   already uses):

   - `raise.error.type` → `e.getClass.getName`
   - `raise.error.message` → `e.toString`

   Key names are constants on the companion of the resolver (decision 5).
   The `raise.*` prefix is deliberate: it cannot collide with the
   `exception.*`/error fields a backend derives from `attachError`, which
   still receives the `Submarine` (unchanged — `attachError` requires a
   `Throwable` and `E` is not one; do not try to call it).

5. **Resolution uses M5's sealed-typeclass low-priority pattern** (the same
   mechanism as `WithInputsAndOutputsTracer`, for the same cross-module
   reason). In `com.dwolla.tracing.mtl.syntax`:

   ```scala
   sealed trait RaiseRecorder[F[_]] { def onRaise: OnRaise[F] }
   object RaiseRecorder extends LowPriorityRaiseRecorder {
     val ErrorTypeKey: String = "raise.error.type"
     val ErrorMessageKey: String = "raise.error.message"
     /** Higher priority: a user-supplied OnRaise[F] wins. */
     implicit def fromOnRaise[F[_]](implicit or: OnRaise[F]): RaiseRecorder[F]
   }
   trait LowPriorityRaiseRecorder {
     /** Lower priority: default fields via Trace[F].put. */
     implicit def fromTrace[F[_]](implicit T: Trace[F]): RaiseRecorder[F]
   }
   ```

   `WithInputsAndOutputsTracer.fromRaiseAspect` and
   `WithInputsTracer.fromRaiseAspect` each gain an
   `R: RaiseRecorder[F]` implicit parameter and switch from
   `WeaveArrows.raiseLift[F, ...]` to `WeaveArrows.raiseLift(R.onRaise)`.
   Their existing constraints already include what the new overload needs
   (`FlatMap`/`Apply` and `Trace`), so `RaiseRecorder` always resolves — the
   `Aspect`-based (higher-priority) tracer instances are untouched.

6. **The recording lands on the method's span.** The shell target is part of
   the effect the trace interpreter runs inside `Trace[F].span(...)`, so no
   span plumbing is needed — but the integration test must prove it rather
   than assume it.

## Tasks

1. `raise-aspect-core`: add `OnRaise` and `OnRaise.noop` (TDD; `noop`
   produces `().pure[F]` and does nothing else).
2. `raise-aspect-core`: add the `raiseLift(onRaise)` overload. Property
   tests (in core's test config, using the existing M1 fixtures — the laws
   module is frozen and needs no changes):
   - **Noop equivalence:** at `F = Either[TestError, *]`,
     `raiseLift(OnRaise.noop)` agrees extensionally with the existing
     `raiseLift` on the L4/L5 shapes (arrow coherence and
     section/retraction), for both raising and non-raising flows.
   - **Exactly-once, raise-only:** with a counting effect (the L10 pattern —
     e.g. `EitherT[Writer[Chain[String], *], TestError, *]`; copy the
     approach, not the frozen files), the hook runs exactly once per raise,
     its effect is sequenced before the raised value, and it never runs on a
     success path.
   - Serializable test for `OnRaise.noop`, matching the repo's convention
     for typeclass instances.
3. `natchez-tagless-mtl`: add `RaiseRecorder` per decision 5 and wire both
   `fromRaiseAspect` instances through `WeaveArrows.raiseLift(R.onRaise)`.
   Priority is proven the M5 way: a poison `OnRaise` that throws if invoked
   confirms a user instance wins over the `Trace`-derived default; removing
   it confirms the default path is reachable (interactive check, not
   committed, recorded in the report).
4. Integration test (extend the existing `InMemory`-backed raise-path suite
   in `natchez-tagless-mtl`): on `bar(-1)`,
   - the method's span history contains a `put` with `raise.error.type` and
     `raise.error.message` carrying the domain error's class name and
     rendered value;
   - the raise still propagates and is still rescuable via
     `Handle.allow`/`rescue` with the domain error intact (existing
     assertions stay);
   - the success path produces no `raise.*` fields.
5. Docs: update the worked example scaladoc on the `com.dwolla.tracing.mtl`
   package object — the Submarine caveat paragraph now says the typed error
   is recorded as span fields at the moment of the raise, names the two
   keys, and shows a two-line custom `OnRaise` example (pattern-match the
   domain ADT to richer fields). Verified by the repo's doctest tooling like
   the rest of that example.

## Acceptance criteria

- New property tests and integration assertions green on 2.12, 2.13, and 3,
  JVM and JS linking, matching the repo's established verification bar.
- `git diff` scoped to `raise-aspect-laws` and `raise-aspect-macros` is
  empty; core changes are additive (existing `raiseLift` behavior untouched,
  demonstrated by the untouched existing suites).
- Priority test proves a user `OnRaise[F]` wins and the default is
  reachable.
- Docs build passes.

## Ground rules reminder

If implementing this reveals a defect in the frozen laws or in
`raise-aspect-core`'s existing behavior, STOP and report — this milestone's
changes are additive only.
