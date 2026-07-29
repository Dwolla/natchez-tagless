# Milestone M9 — upstreaming to cats-tagless

## Status

**Not started.** Prerequisites: M5 merged. Tasks 1–2 (research and proposal
draft) can run any time after M5 and are cheap; everything after them is
gated.

**Gates:**

1. **Brian's call to propose it at all**, and the community engagement —
   posting the proposal, discussing with maintainers — is Brian's to do
   personally, not a session's.
2. **Maintainer buy-in before any porting.** No fork work until upstream has
   said the contribution is wanted and in what shape.
3. **The shape question.** Whether upstream would rather receive
   `RaiseAspect` as-is or a general `CapabilityAspect` (M8) should be raised
   *in the proposal* — its answer sequences M8 versus the port, so opening
   the discussion early is cheap and informative, while porting early risks
   porting a shape upstream doesn't want.

---

Read `01-overview-design-and-laws.md` first. The overview's §2 decision —
modules live in the natchez-tagless build under `com.dwolla` coordinates,
upstreaming deferred — is what this milestone revisits, deliberately and
with Brian's sign-off.

## Tasks

1. **Drift survey (research only; produces a committed report).** Our
   derivation targets cats-tagless `v0.16.5`. Diff, between `v0.16.5` and
   upstream's current default branch:
   - `cats/tagless/aop/Aspect.scala` (`Weave`/`Advice` shapes),
   - the Scala 3 `cats.tagless.macros.MacroAspect` and whatever shared
     derivation machinery it touches,
   - the Scala 2 derivation source M3 adapted.

   Catalog every API or behavioral change a port must absorb, note whether
   upstream has changed its published Scala axes, and record the findings in
   `docs/plans/raise-aspect/91-upstream-drift-report.md` plus a note in
   `reference/upstream/README.md` (the vendored copies stay pinned at
   `v0.16.5` — record the surveyed upstream SHA rather than re-vendoring).

2. **Proposal draft for Brian to post as himself.** Write
   `docs/plans/raise-aspect/90-upstream-proposal-draft.md` containing
   issue/discussion-ready text: the problem (`FunctorK` is uninhabited for
   algebras with capability parameters), the design in upstream's
   vocabulary (overview §2–§3), the law suite (§4), what already exists
   (four modules, cross-built 2.12/2.13/3, discipline laws, differential
   oracle against a hand-written reference, both derivation macros), the
   open `Raise`-specific-versus-general question (M8), the known
   method-local-instance limitation and its M7 status, and an explicit offer
   to contribute the code. Link targets and claimed test counts must be
   verified against the repo at drafting time, not recalled from these plan
   docs.

3. **[Gate 2] The port** — happens in a cats-tagless fork, not in this
   repository; this repo contributes the report and the code being ported.
   Adapt `raise-aspect-core`/`-laws`/`-macros` into upstream's module,
   package, and build layout, with names and placement per maintainer
   guidance; rebase the derivation onto upstream HEAD using the Task 1
   report; adapt the law suites to upstream's discipline conventions; both
   projects are Apache-2.0 and the adapted macro sources already carry
   attribution headers — verify they survive the move, with attribution now
   flowing the other way (code originating here, landing there). Open the
   upstream PR and drive it to green under upstream CI.

4. **[Gate: an upstream release containing the port exists] Migrate this
   repository.** `natchez-tagless-mtl` (the `Trace`-specific integration:
   `Synthetic[TraceableValue]`, the tracing syntax, `RaiseRecorder` if M6
   landed) stays here permanently; the question is the fate of
   `raise-aspect-core`/`-laws`/`-macros`:
   - **Option 1: clean break** — a major/minor version drops the three
     modules and `natchez-tagless-mtl` depends on the upstream artifacts.
   - **Option 2: deprecated re-export shims** for a transition period —
     this is backward compatibility by CLAUDE.md's definition and requires
     Brian's explicit approval; do not build it on default.

   Either way: swap dependencies, delete or shim per Brian's decision,
   verify the whole build against the upstream artifacts, and record the
   decision in this document's Status section.

## Acceptance criteria

- Task 1: drift report committed; every claimed upstream change cites the
  file and upstream SHA it was observed at.
- Task 2: draft committed; Brian has what he needs to post without editing
  for factual accuracy.
- Task 3: upstream PR open and green under upstream's CI, with laws ported
  and passing there.
- Task 4: this repo builds and tests green against the upstream artifacts
  on all published axes; the shim decision is recorded and, if Option 2 was
  taken, it was explicitly approved.

## Ground rules reminder

Nothing in Tasks 1–2 modifies code. If the drift survey reveals upstream
changes that invalidate parts of the overview's design (e.g. `Weave`
constructor changes), that is input to the proposal and to M8's design doc —
report it; do not start adapting our modules to upstream HEAD inside this
repo.
