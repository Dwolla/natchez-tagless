# Milestone M2 — Laws and discipline test kit

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
