# Milestone M2 — Laws and discipline test kit

## Status

**Complete** (branch `milestone/m2-laws`, stacked on `milestone/m1-runtime-core`).
Next: M3 Scala 2 macro. **This module is now frozen** — see `LAWS.md`.

All ten laws L1–L10 plus Serializable are implemented and pass against M1's
reference instance on **2.12.21, 2.13.18, and 3.3.8**: 27 law tests per version,
alongside 24 raise-aspect-core and 19 pre-existing core tests. `LAWS.md` maps
every law to its location.

### The suite was proven non-vacuous

All ten laws passing on the first full run is exactly the outcome to distrust,
so the suite was checked against four deliberately broken instances. Each was
caught: wrong `algebraName` and dropped `domain` by L8's structural tests, a
by-name argument forced during weaving by L8's laziness test, and an off-by-one
codomain target by **L3 weave erasure** plus L8. The mutants were throwaway and
are not committed; the results are recorded in `LAWS.md`.

Worth knowing for M3/M4: L1–L3 compare *behaviour*, so they are blind to
metadata-only defects. L8 is the only thing pinning weave structure. A macro
that gets `algebraName` or advice names wrong will sail through the discipline
rule sets.

### Two traps found while implementing — both documented in LAWS.md

1. **`Aspect.Advice` has no `equals`** (carried over from M1's report). Every
   structural comparison goes through `WeaveRenderer.render`, and
   `LawsInstances.eqWoven` pairs that with an `Eq[F[A]]` on the codomain target.
   A derived `Eq` or `Eq.fromUniversalEquals` would compare references and make
   the weave laws meaningless.
2. **`implicit val raiseResult: Raise[Result, TestError] = Raise[Result, TestError]`
   resolves to itself** and initializes to `null`, surfacing much later as an
   NPE inside `raiseLift`. This actually happened here. `LawsInstances` names
   `Raise.raiseEither[TestError]` explicitly. M1's specs avoided it only by
   luck — their equivalent val is not `implicit`.

### Design decisions worth reviewing

- **L3 runs at the base effect, L1/L2 additionally over `eraseWeave`.** A single
  `raiseAspect[A, B, C]` instantiation cannot do both: L3's `weave` needs a
  `Functor` for the effect being woven, and weaving an already-woven algebra is
  not meaningful. So the suite runs `raiseAspect[Result, Result, Result]` (L1–L3,
  identity arrows) *and* `raiseFunctorK[Woven, Result, Result]` (L1/L2 over the
  genuinely non-trivial erasure arrow). Without the second, L2 would only ever
  compose identities.
- **L6's domain-preservation compares advice names, not rendered targets.** The
  law trait has no `Renderable[Dom]` to work with; full structural domain
  comparison is L8's job via `WeaveRenderer`. Called out in the law's scaladoc.
- **L9 needs version-specific call sites.** `cats.tagless.Derive` is annotated
  `@experimental` on Scala 3, and the repo is on 3.3.x LTS where the
  `-experimental` flag does not exist, so the annotation is the only option. The
  suite is shared; only the two-line call site is duplicated under
  `src/test/scala-2` and `src/test/scala-3`. Verified that the `@experimental`
  class is still discovered and run by munit on Scala 3 — all three L9 tests
  execute there. **M4's own `DeriveRaise` will inherit this requirement**, and it
  lands on end users' algebra companions.
- **A hand-written `RaiseAspect[PlainAlg, …]` was added here** (`PlainAlgReference`),
  because L9 needs something to compare upstream against and M1 deliberately
  left it out. It follows the same §3.4 expansion and is a permanent fixture.
- **`cats-tagless-macros` is a Scala-2-only Test dependency** of
  `raise-aspect-laws`, for L9's upstream derivation. On Scala 3 `Derive` lives in
  cats-tagless-core, which the module already has.

### Layering

Law traits and discipline `Tests` live in `raise-aspect-laws` **main**; the
fixture-specific suites live in its **test** config, which reaches M1's fixtures
via `raiseAspectCore % "compile->compile;test->test"`. This mirrors upstream,
where cats-tagless-laws holds generic laws and the separate `tests` module holds
fixtures and concrete suites. M3/M4 reach the shared suite the same way.

### The substitution seam (acceptance criterion)

`RaiseAspectSuite` is abstract in exactly one member —
`def instance: RaiseAspect[TestAlg, Render, Render]`. `ReferenceRaiseAspectSpec`
supplies M1's reference instance in four lines; M3/M4 add sibling classes
supplying derived instances and inherit all ten laws unchanged. `ConservativeExtensionSuite.upstream`
is the same shape for L9. The seam is demonstrated, not just claimed: the four
mutant specs each substituted a different instance and re-ran the whole suite.

### Verification

Clean `+clean` build: JVM tests pass on all three Scala versions, Scala.js links
clean, MiMa reports all four new modules skipped, `+doc` succeeds, and
`githubWorkflowCheck` passes. Zero errors and zero new warnings. Scala.js test
*execution* remains uncovered locally (no node), per the agreed bar.

---

Read `01-overview-design-and-laws.md` first. Prerequisite: M1 merged.

You are writing the executable specification (overview §4, laws L1–L10) and
validating it against M1's hand-written reference instance. After this
milestone merges, this module is **frozen**: later milestones may add tests
but never modify these.

## Tasks

1. In `raise-aspect-laws`, implement law traits and discipline `Tests`
   classes modeled on the vendored cats-tagless-laws sources
   (`reference/upstream/`):

   ```scala
   trait RaiseFunctorKLaws[Alg[_[_]]]                 // L1, L2
   trait RaiseAspectLaws[Alg[_[_]], Dom[_], Cod[_]]   // extends the above; L3
   object RaiseArrowLaws                              // L4, L5, L6, L7 (value-level)
   trait RaiseFunctorKTests[Alg[_[_]]]                // discipline RuleSets
   trait RaiseAspectTests[Alg[_[_]], Dom[_], Cod[_]]
   ```

   L8 (weave structure fidelity), L9 (conservative extension vs.
   `cats.tagless.Derive.aspect`), and L10 (laziness parity) ship as concrete
   suites against the fixture algebras rather than parameterized law traits.
   Add `SerializableTests` for the typeclass instances per cats convention.

2. Effect choices:
   - Primary `F = Either[TestError, *]` (cats-mtl materializes `Raise` from
     `ApplicativeError`; contravariance supplies `Raise[F, ErrA]`/
     `Raise[F, ErrB]`). Inputs must include values that trigger raises so
     L3/L4/L5 exercise the error path — a raise must round-trip as the
     identical `Left`.
   - Secondary `EitherT[Eval, TestError, *]` for laziness coverage; L10 uses
     a `Writer[List[String], *]` or counting-`State` effect and asserts
     identical effect counts through woven and unwoven paths.

3. `Eq[TestAlg[F]]` / `Eq[PlainAlg[F]]` by sampling: equal iff all methods
   agree on all inputs drawn from `ExhaustiveCheck` instances over small
   domains (include raise-triggering and succeeding inputs). Mirror how
   cats-tagless tests its own instances.

4. Structural `Weave` comparison for L6/L8/L9: a test-kit renderer

   ```scala
   final case class RenderedWeave(
     algebraName: String,
     methodName: String,
     domain: List[List[(String, Rendered)]],  // via Dom
     codomain: Rendered                       // via Cod; F[A] compared via Eq separately
   )
   ```

   using the test `Render` typeclass from M1 as `Dom`/`Cod` (no natchez
   dependency in these modules). Include the throwing-thunk test proving
   by-name parameters are not forced by weaving.

5. Arrows under test for L4: `RaiseArrow.id`, `WeaveArrows.eraseWeave`, and
   compositions of both via `andThen` (checking L1/L2 interplay).

6. Run the complete suite against the **M1 reference instance** on both
   Scala versions. L9 additionally derives `cats.tagless.Derive.aspect` for
   `PlainAlg` and compares.

7. Structure suites so M3/M4 can re-run them wholesale with a derived
   instance substituted for the reference instance (e.g., abstract the
   instance provider behind a small trait or constructor parameter in the
   shared suite).

## Acceptance criteria

- Every law L1–L10 has a corresponding, passing check against the reference
  instance on Scala 2.13 and 3; Serializable tests pass.
- The suite is demonstrably re-runnable with a substituted instance (show the
  seam).
- A short LAWS.md in the module maps each law number to its test location.

## Ground rules reminder

If, while implementing, you conclude a law from overview §4 is unsatisfiable
or wrongly stated, STOP and report — do not adjust the law to fit the M1
implementation, and do not adjust the M1 implementation without flagging it
(an M1 bug found here is a valid and valuable outcome, but it is a reviewed
change, not a silent fix).
