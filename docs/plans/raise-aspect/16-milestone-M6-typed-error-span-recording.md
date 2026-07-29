# Milestone M6 — typed-error span recording at the raise-lift interception point

## Status

**Not started.** Prerequisites: M5 merged. Independent of M7 — the two can
run in either order. The Decisions section below was proposed by the planning
session on 2026-07-29 and has not yet been ratified by Brian; confirm it
before starting, then treat it as final.

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
