# Milestone M7 — method-local `Dom`/`Cod` instances in derivation

## Status

**In progress.** Brian set the order on 2026-07-30: M7, then M8, and ratified
the Decisions below the same day.

Prerequisites: M5 merged (the macros as they landed in M3/M4). Independent of
M6 — the two can run in either order. The Decisions section below was proposed
by the planning session on 2026-07-29 and **was ratified by Brian on
2026-07-30**. Treat the Decisions section as final.

### What changed under this milestone since it was written

M10 and M11 landed on `milestone/m10-evidence-carrying-transport` (unmerged, on
top of the equally unmerged M6). Two consequences for this milestone:

1. **There is now a third instance to resolve, not two.** M10 gave the
   derivation an `Err[_]` evidence parameter, summoned per `Raise[F, E]`
   capability parameter at the derivation site — `inferErrOrAbort` on the
   Scala 2 axis, `summonErrOrAbort` on the Scala 3 axis. A method supplying
   its own `TraceableValue[ErrA]`/`Render[ErrA]` through its own
   `implicit`/`using` clause has exactly the problem this milestone exists to
   solve, so **whatever is decided for `Dom`/`Cod` must cover `Err` too**, on
   both axes. Recorded in `03-evidence-carrying-transport-design.md` §A.6.
   Task 1's `WidgetAlg` fixture should gain a capability parameter whose `Err`
   instance is method-local, alongside the `Dom`/`Cod` case it already
   specifies.
2. **The derivation entry points gained a type parameter.** They are now
   `DeriveRaise.aspect[Alg, Dom, Cod, Err]` and
   `DeriveRaise.functorK[Alg, Err]` on both axes. Any snippet in this document
   showing a three-argument `aspect` call is stale.

Nothing in M10/M11 invalidates this milestone's Decisions section; option (b)
and the (a)/(c) rulings stand as written.

---

Read `01-overview-design-and-laws.md` first — especially the appendix
"Method-local `Dom`/`Cod` instances are not resolved (found in M4)", which
defines this milestone's problem and enumerates the options (a)/(b)/(c).
This milestone resolves that limitation on **both** macro axes; the overview
explicitly requires whatever is chosen to apply to both.

## Decisions (ratified 2026-07-30 — final)

1. **Pursue option (b)** — resolve `Dom`/`Cod` instances inside the
   generated method body, where the method's own `implicit`/`using`
   parameters are genuinely in scope — **as a hybrid**:
   - Resolution is attempted at the derivation site first, exactly as
     today. When it succeeds, nothing changes — including the current
     high-quality custom diagnostics for genuinely missing instances, which
     `DerivationErrorSpec` pins on both axes.
   - Only when derivation-site resolution fails does the macro emit an
     in-body summon for that instance, deferring resolution to where the
     method's own givens are visible.
   - Consequence: a truly missing instance still errors at the
     `DeriveRaise.aspect` call site (the expanded tree is typechecked
     there), just with the compiler's generic missing-implicit message
     instead of ours for the method-local shapes. The existing custom
     messages keep covering the common case.
2. **Option (a) stays rejected** — upstream's `addToGivenScope` reflection
   into `dotty.tools.dotc` internals, for the reasons recorded in the
   overview appendix. Do not revisit.
3. **Option (c) is the decided fallback**, not a competing choice: if the
   spike (Task 2) shows (b) is infeasible on *either* axis, implement (c) on
   *both* axes — an explicit rejection diagnostic — rather than shipping an
   asymmetric feature. The (c) diagnostic: when derivation-site resolution
   fails *and* the method's own implicit/using parameters contain an
   instance of the needed type, fail with a message naming the parameter and
   stating that method-local `Dom`/`Cod` instances are not supported and the
   instance must be available at the derivation site. Otherwise the existing
   missing-instance error stands.

## Why (b) is plausible — hypotheses to verify, not assumptions

- **Scala 2:** splice an untyped `q"_root_.scala.Predef.implicitly[$tpe]"`
  into the generated method body. The typer resolves it when it typechecks
  the expansion, with the method's implicit parameters in scope. Verify
  against the actual macro structure in
  `raise-aspect-macros/src/main/scala-2/` (how bodies are built determines
  whether an untyped splice is possible at that point).
- **Scala 3:** emit a call to `scala.compiletime.summonInline[T]` in the
  generated method body. The hypothesis is that the post-macro inlining
  phase expands it in the body's scope, where the method's `using`
  parameters are visible. This is the load-bearing uncertainty: resolution
  may instead happen in the expansion context, or inlining may misbehave
  inside `Symbol.newClass`-built defs on the 3.3 LTS line. That is what the
  spike answers.
- Do not recall compiler behavior — demonstrate it. The vendored upstream
  sources in `reference/upstream/` show how `MacroAspect` builds method
  bodies; our macros adapted that structure in M3/M4.

## Tasks

1. **Fixtures first (red).** On both axes, add the `WidgetAlg` shape from
   the overview appendix to the macro test fixtures: a method whose
   `Render[Widget]` (the test `Dom`/`Cod` typeclass) is supplied only by the
   method's own `implicit`/`using` clause. Write the test asserting
   `DeriveRaise.aspect[WidgetAlg, Render, Render]` compiles and the derived
   instance passes the relevant structural checks (an `ExpectedWeaves`-style
   rendering of a `show` call, matching the existing macro-test
   conventions). It must fail today with the "Not found / could not find"
   error the overview records.
2. **Spike, scoped to a report.** On each axis, attempt the minimal in-body
   summon for exactly the `WidgetAlg` fixture, without restructuring the
   rest of the derivation. Outcome is a written GO/NO-GO per axis in the
   Status section, with the actual compiler behavior observed. If the
   Scala 3 behavior is surprising in a way that suggests a compiler
   limitation rather than a fixable structuring problem, STOP and report
   before investing further.
3. **If GO on both axes:** implement the hybrid from Decision 1 in both
   macros. The entire existing macro test suite — differential oracle
   against the M1 reference instance, the shared law suite via the M2 seam,
   edge cases, cross-version agreement, and both `DerivationErrorSpec`s —
   must remain green unmodified. New tests: the Task 1 fixtures pass; plus a
   `compileErrors` test proving a *genuinely* missing instance (present
   neither at the derivation site nor on the method) still fails with an
   actionable message naming the missing type.
4. **If NO-GO on either axis:** implement Decision 3's rejection diagnostic
   on both axes, with `compileErrors` tests asserting the message names the
   offending parameter and points at the derivation site. The Task 1
   success-fixtures are then converted into these rejection tests (the
   compiling variant is kept only on an axis where (b) landed — which, per
   Decision 3, means it is kept nowhere).
5. **Docs:** update the overview appendix to record the outcome (it is a
   design-history appendix, not a law — updating it is in scope), and the
   user-facing docs in `natchez-tagless-mtl` only if the limitation's
   user-visible behavior changed.

## Acceptance criteria

- The `WidgetAlg` fixture either derives and passes its structural test on
  2.12, 2.13, and 3 (path b), or is rejected with the decided diagnostic on
  all three (path c) — never a mix, never the old opaque error.
- All existing macro suites green and unmodified; `git diff` scoped to
  `raise-aspect-laws` and the M1 fixture sources is empty.
- The milestone report records which path was taken, per axis, and why,
  including the spike's observed compiler behavior.

## Ground rules reminder

The frozen laws are untouched by every branch of this milestone. If the
spike suggests the *design* is wrong (e.g. in-body resolution changes
observable weave structure), STOP — that is an overview-level question for
Brian, not a milestone-level fix.
