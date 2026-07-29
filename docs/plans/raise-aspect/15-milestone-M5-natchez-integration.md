# Milestone M5 — natchez-tagless integration and docs

Read `01-overview-design-and-laws.md` first. Prerequisites: M1–M4 merged.

## Tasks

1. In `natchez-tagless-mtl`, provide
   `implicit val syntheticTraceableValue: Synthetic[TraceableValue]`
   returning a constant instance rendering as `"«raised»"`. Per the laws it
   is never observable; the greppable sentinel exists purely so a future bug
   would be diagnosable. Document this on the definition.
2. Extension syntax mirroring the existing natchez-tagless syntax: for an
   algebra with `RaiseAspect[Alg, TraceableValue, TraceableValue]` in scope,
   `alg.traceWithInputsAndOutputs` (and the inputs-only variant if the
   existing library offers one — mirror what exists) producing `Alg[F]` by:

   ```scala
   raiseAspect.mapK(raiseAspect.weave(alg))(
     RaiseArrow(<existing Weave ~> F trace interpreter>, WeaveArrows.raiseLift)
   )
   ```

   Reuse the repo's existing `Weave[F, TraceableValue, TraceableValue, *] ~> F`
   interpreter unchanged; constraints are that interpreter's existing
   constraints plus `Functor[F]` (already implied by them). Recall the
   decided fixed shell name `"raise"` inside `raiseLift` — no algebra-name
   threading.
3. Implicit priority: when an algebra has both an `Aspect` and a
   `RaiseAspect` instance, `alg.traceWithInputsAndOutputs` must resolve
   unambiguously (existing `Aspect`-based enrichment wins). Use standard
   low-priority implicit layering and add a compilation test with both
   instances in scope.
4. End-to-end integration test with natchez's in-memory/test entry point
   (`InMemory` or whatever the repo's tests already use): a `TestAlg`-like
   algebra with method-level `Raise`, run at `F = Kleisli[IO, Span[IO], *]`
   or the repo's established pattern, asserting:
   - span names follow the existing `<Algebra>.<method>` convention;
   - input attributes captured for non-capability parameters only;
   - output attributes captured on success;
   - a raised error propagates through the traced wrapper and is recoverable
     via `Handle.allow`/`rescue` (cats-mtl ≥ 1.4.0, test scope) with the
     domain error intact.
5. Documentation (mdoc, matching the repo's README tooling):
   - A worked example: algebra with method-level `Raise`, companion with
     `DeriveRaise.aspect`, tracing at the boundary with `allow`/`rescue`.
   - The design constraints in user-facing terms: `Functor[F]` on `weave`;
     `Handle` parameters unsupported and why; where `Raise` may appear.
   - Scala 3 usage requirement: declaring a derived instance
     (`DeriveRaise.aspect`) requires an `@experimental` annotation at the
     call site or `-experimental` (Scala 3.4+), same as upstream
     cats-tagless derivation, because the underlying macro APIs are
     experimental on the LTS line. Show the annotation in the worked
     example's Scala 3 variant. (Scala 2 is unaffected.)
   - The Submarine caveat: a raise that crosses the traced wrapper before
     being rescued surfaces in the `Throwable` channel as cats-mtl's opaque
     `Submarine` exception (cats-mtl issue #648), so span error annotations
     for in-flight domain errors may be uninformative. Note that a
     typed-error recording hook at the `raiseLift` interception point is
     planned future work — do not implement it in this milestone.

## Acceptance criteria

- Integration test green on both Scala versions.
- Ambiguity compilation test green (both instances in scope, resolves to the
  `Aspect` path; `RaiseAspect` path used when only it exists).
- Docs build passes (mdoc compiles the examples).
- No changes to `raise-aspect-core`, `raise-aspect-laws`, or the macro
  sources; if integration reveals a defect in them, STOP and report rather
  than patching in this milestone's PR.
