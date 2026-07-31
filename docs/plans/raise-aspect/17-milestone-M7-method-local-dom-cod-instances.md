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

### Spike outcome (Task 2, 2026-07-30) — GO on both axes, via a technique the
### document did not anticipate

Independently verified by a second agent that reimplemented the technique from
scratch in a throwaway clone rather than trusting the spike's report.

**The document's own hypothesis for Scala 3 failed.** Emitting
`scala.compiletime.summonInline[T]` in the generated body is a NO-GO for all
three instance kinds. The cause is *not* the `Symbol.newClass`-built def — a
quote-only control macro with no `newClass` fails identically, while the same
shape in ordinary source compiles. An inline call inside a macro expansion is
reduced against the **macro call site's** implicit scope.

Worse than inert: with a conforming `given` at the call site, the woven code
*used the call-site instance and ignored the one the method was handed*. It is
a silent-wrong-instance hazard, not a no-op. `scala.compiletime.summonFrom`
cannot be emitted at all ("can only be used in an inline method"). **Do not
revisit either.**

**What works — "technique B".** Emit a direct reference to the generated
method's own implicit/given parameter: `Ref(paramSymbol)` on Scala 3,
`Ident(name)` on Scala 2. GO on both axes, all three instance kinds, on
2.12.21, 2.13.18 and 3.3.8. Runtime-verified for `Dom` and `Cod` (the woven
advice really carries the method-local instance) and at the emitted-tree level
for `Err`. A strict extension: the full suite keeps passing except Task 1's
four red tests, which flip, and all three existing missing-instance
diagnostics stay intact.

**The precise rule it implements** — this, not "in-body summon", is what gets
built:

> An instance resolves iff the declared type of one of the generated method's
> own implicit/given parameters is a **subtype** (`<:<`) of the needed
> `Dom[T]` / `Cod[T]` / `Err[E]`. No implicit search of any kind is performed:
> no derivation, no companion scope, no chaining.

Verified to resolve: exact match; a candidate that is a *subtype* of the needed
type; the needed type behind a type alias; contravariant widening (handed
`Show[Widget]`, needs `Show[SubWidget]` for `Show[-A]` — sound, and why the
test is `<:<` and not `=:=`); several method-local implicits with exactly one
conforming; polymorphic and context-bound methods (`def poly[A: Render](a: A)`)
— which could *never* resolve at the derivation site, so this is scope M7 did
not promise; a conforming parameter in a non-final `using` clause on Scala 3;
and the `mapK` / `RaiseFunctorK` path.

Verified *not* to resolve: derivation from a method-local instance (needs
`Render[List[Widget]]`, handed `Render[Widget]`); a needed type that is a
subtype of the handed one under an invariant type class; an instance reachable
only *through* a parameter (`B.unbox`); and an instance handed as an ordinary
**non-implicit** parameter.

**Brian's ruling, 2026-07-30: the derivation case is out of scope. Build the
hybrid on technique B.** Note what makes that cheap to accept — Scala 2's
`implicitly`-splice alternative only derives when the derivation rule is in
lexical scope *at the derivation site*, because the generated body's lexical
scope **is** the macro call site. "Full implicit search in the body" was never
going to deliver what the phrase suggests, on either axis, short of the
permanently-rejected option (a).

**Scala 2's `implicitly` splice is rejected even though it works**: it
regresses all three existing missing-instance diagnostics into raw
`TypecheckException` stack traces (7 suite failures versus technique B's 4),
and it would give Scala 2 a capability Scala 3 cannot match.

**A correction Task 3 must carry.** Task 1 reported, and the spike then
"explained", that the combined `WidgetAlg` fails via `Dom` first on Scala 3 and
`Err` first on Scala 2. Both accounts are wrong. On Scala 3 the first-fired
diagnostic for a multi-method algebra is **unstable across compilation
arrangements** — the same derivation yielded `Dom`, `Cod`, and `Err` depending
on build state. Consequences: no test may assert *which* diagnostic fires for a
multi-method algebra, and no debugging may reason "it failed via `Err`, so
`Dom` must have worked."

**Path taken, per acceptance criterion.** Path (b) — via technique B, the
direct parameter reference described above — was taken on **both** axes, for
all three instance kinds (`Dom`, `Cod`, `Err`). Task 4 (the Decision-3
rejection-diagnostic path) was therefore skipped, since it applies only when
(b) is infeasible on either axis, and the spike found it feasible on both. The
`WidgetAlg` fixtures (Task 1) derive and pass their structural checks on
2.12.21, 2.13.18, and 3.3.8, exactly as Task 3 implemented and the full macro
suite confirms.


---

Read `01-overview-design-and-laws.md` first — especially the appendix
"Method-local `Dom`/`Cod`/`Err` instances (found in M4, resolved in M7)", which
defines this milestone's problem and enumerates the options (a)/(b)/(c). This
milestone resolves that limitation on **both** macro axes; the overview
explicitly requires whatever is chosen to apply to both.

## Decisions (ratified 2026-07-30 — final)

> **Decision 1 amended 2026-07-30**, after the Task 2 spike and Brian's ruling.
> Its mechanism — "emit an in-body summon" — is not buildable: `summonInline`
> is a NO-GO on Scala 3 and actively binds the wrong instance. What gets built
> instead is technique B, a direct reference to the method's own conforming
> implicit/given parameter, described precisely in the Status section above.
> The hybrid shape is unchanged: derivation-site resolution first, method-local
> fallback only when that fails. Decision 1's *goal* stands; only its mechanism
> and its reach change, and the narrowing (no derivation from a method-local
> instance) was ruled out of scope.

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
