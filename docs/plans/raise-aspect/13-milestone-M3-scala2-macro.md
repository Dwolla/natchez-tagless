# Milestone M3 — Scala 2 derivation macro

## Status

**Complete** (branch `milestone/m3-scala2-macro`, stacked on `milestone/m2-laws`).
Next: M4 Scala 3 macro.

`DeriveRaise.aspect` and `DeriveRaise.functorK` are implemented in
`raise-aspect-macros/src/main/scala-2`, adapted from the vendored upstream
`DeriveMacros` with an Apache-2.0 attribution header naming the repo, tag,
commit, upstream file, and the fact of modification.

**45 tests pass on both 2.12.21 and 2.13.18** — the full M2 law suite (24) through
the seam, plus 4 differential-oracle, 4 L9, 8 diagnostics and 5 edge-case tests.
No version guards were needed: the reflect API usage is identical on both axes.
Frozen sources verified untouched — `git diff` against `1c84af7` scoped to
`raise-aspect-laws/` and `raise-aspect-core/` is empty; M3 modifies only
`build.sbt` and adds new files.

### Divergences from upstream's macro, and why

1. **Capability transport** — the point of the milestone. Where upstream aborts on
   `occursInSignature(f)`, we classify parameters whose type dealiases to exactly
   `Raise[F, e]`, substitute the effect inside that type in the generated
   signature, and pass `WeaveArrows.raisePull[F, Dom, Cod].apply(r)` (weave) or
   `arrow.pull(r)` (mapK) at the underlying call site.
2. **Classification is by exact type symbol, never a subtype test.** `Handle[F, E]
   extends Raise[F, E]`, and `Handle` consumes `F`. A `<:<` test would silently
   classify `Handle` parameters as transportable and generate unsound code. This is
   the single most important line in the file.
3. **Stricter nested-return rejection.** Upstream's weave guard is
   `returnType.typeSymbol == f`, which *accepts* `F[F[A]]` and then fails later
   with a confusing missing-`Cod[F[A]]` error. `returnsEffectDirectly` additionally
   requires the return type's arguments not to mention `F`, so `F[F[A]]` gets a
   clear diagnostic. Stricter than upstream, and matches overview rule 3.
4. **`Dom`/`Cod` instances are pre-summoned** with `c.inferImplicitValue` and passed
   explicitly to `Advice.byValue`/`byName`, so a missing instance aborts naming the
   type, parameter and method (task 3). Upstream emits `byValue[Dom, pt](…)` and
   lets the implicit search fail generically.
5. **Domain capture** follows upstream — drop the trailing implicit clause wholesale
   — *plus* filtering capability parameters out of the remaining clauses, which is
   needed because a `Raise` parameter need not be implicit. Per Brian's decision;
   this is what keeps L9 unconditionally true.

### Upstream behaviours the overview's rules don't cover — these feed M4

1. **Abstract `val`s returning `F[A]` are unsupported in both derivations.**
   `delegateMethods` filters `!member.asMethod.isAccessor`, so the member is never
   implemented and compilation fails with the compiler's own *"object creation
   impossible. Missing implementation for member … val v"*. Overview rule 6 asks
   for parity with upstream, and parity holds — but the failure mode is opaque.
   M4 should decide whether to detect accessors and emit a real diagnostic.
   Verified manually; see the next point for why it is not a test.
2. **`compileErrors` cannot observe macro-internal typecheck errors.** Errors
   reported by `c.typecheck` *inside* the macro (which is how the abstract-val
   failure surfaces) escape munit's capture, and `compileErrors` returns `""`. Only
   `c.abort` messages are capturable. An earlier version of the diagnostics suite
   asserted on this and was silently vacuous until the behaviour was probed
   directly. M4's compile-error suite needs the same caveat.
3. **`hasImplicits` inspects only the last parameter list.** Sound on Scala 2, which
   permits at most one implicit clause. **Scala 3 allows multiple `using` clauses**,
   so M4 cannot copy this shape — it must drop every `using` clause, or the domain
   will capture capabilities from all but the last.
4. **Overloads work** on Scala 2: each overload is a distinct member symbol, so
   `delegateMethods` handles them independently. Confirmed by test.
5. **A by-name capability parameter (`implicit R: => Raise[F, E]`) is not
   classified as a capability.** `transformedParamLists` sees the `<byname>`
   wrapper, so it falls through to the F-in-unsupported-position rejection rather
   than being transported. Safe (it rejects rather than miscompiles) and bizarre to
   write, but it is a gap rather than a decision.
6. **Varargs keep upstream's limitation.** The `q"…: _*"` splice construction is
   preserved verbatim, so a parameter clause mixing a vararg with other parameters
   generates invalid code — upstream has the same bug. Out of scope per overview,
   and no fixture exercises it.

### Finding about M2

`ConservativeExtensionSuite` hardcodes `private val ours = PlainAlgReference…` and
exposes only `upstream` as a seam, so it cannot be reused to check a *derived*
instance against upstream. Per Brian's decision the frozen module was left alone
and `DerivedConservativeExtensionSpec` restates the three comparisons additively.
Worth making `ours` a seam whenever the freeze is next lifted.

### Verification

`+clean` build: 0 errors, 0 new warnings, JVM tests green on 2.12/2.13/3, Scala.js
links clean, MiMa skips the new modules, `+doc` succeeds, `githubWorkflowCheck`
passes. The derived instance passed all ten laws on the first run — which M2 taught
us to distrust — so the differential oracle is the load-bearing evidence here: it
asserts the derived weave is *structurally identical* to M1's reference for every
method across every sample, not merely behaviourally equivalent.

---

Read `01-overview-design-and-laws.md` first. Prerequisites: M1 and M2 merged.

## Non-negotiable ground rules for this milestone

- The M2 law suite and the M1 reference instance are the specification. You
  must NOT modify, weaken, skip, tag-as-ignored, or delete any existing law,
  test, fixture, or the reference instance to make a build pass. You MAY add
  new test files (the compile-error suite below is additive). If you believe
  an existing law/test/fixture is wrong, STOP, write up why, and end the
  session with that report.
- Your task is to **adapt the vendored upstream macro** in
  `reference/upstream/` (cats-tagless `v0.16.5`, Scala 2 tree — the file
  containing the `aspect` derivation and its utilities), not to write a macro
  from memory. Copy what you need into `raise-aspect-macros`
  `src/main/scala-2` under our namespace, keep the upstream Apache-2.0
  attribution in a header comment (source repo, tag, file), and modify per
  the rules below.

## Tasks

1. Entry points in `raise-aspect-macros` (shared `DeriveRaise` object with
   version-specific implementations):

   ```scala
   object DeriveRaise {
     def aspect[Alg[_[_]], Dom[_], Cod[_]]: RaiseAspect[Alg, Dom, Cod] = macro ...
     def functorK[Alg[_[_]]]: RaiseFunctorK[Alg] = macro ...
   }
   ```

   Instances are not implicitly derived; users declare them in companions
   (cats-tagless convention).

2. Implement generation per overview §3.4's expansion spec and rules 1–7.
   The delta from upstream `aspect` derivation is:
   - Parameter classification: a parameter whose type **dealiases** to
     `cats.mtl.Raise[F, e]` (with `F` = the algebra's effect parameter, `e`
     not mentioning `F`) is a capability parameter — regardless of
     implicitness. It is excluded from `domain` capture; at the underlying
     call site substitute `WeaveArrows.raisePull[F, Dom, Cod].apply(r)`
     (weave) or `arrow.pull(r)` (mapK).
   - Generated method signatures substitute the effect type inside the
     capability parameter's type (`Raise[F, e]` → `Raise[Weave[F, Dom, Cod, *], e]`
     or `Raise[G, e]`) while preserving parameter-list shape, implicit flags,
     by-name modifiers, and the declared type verbatim post-dealias
     (contravariance in `E`: do not reconstruct the type).
   - `weave` gains the `Functor[F]` parameter per the typeclass signature.
   - Everything else (domain capture, Eval.now/always, Dom/Cod summoning via
     `c.inferImplicitValue`, algebra/advice naming) matches upstream behavior
     exactly — L8/L9 will check this.

3. Diagnostics via `c.abort`, with tests:
   - `Handle[F, e]` parameter: "method <m> takes cats.mtl.Handle[F, <e>];
     Handle consumes F and cannot be woven. Take Raise[F, <e>] in algebra
     methods and introduce Handle at the boundary (Handle.allow / rescue)."
   - Other `F`-mentioning parameter: "parameter <p> of method <m> mentions
     the effect type F in an unsupported position; RaiseAspect supports F
     only as the top-level return type and in Raise[F, E] parameters."
   - `F` nested in the return type: analogous message.
   - Missing `Dom`/`Cod` instance: name the type, parameter, and method.

4. Compile-error test suite (new files, 2.13 only for now) covering: the
   three rejections above; `F[F[A]]` and `Either[E, F[A]]` returns; and a
   **success** case for an aliased capability
   (`type BarRaise[F[_]] = Raise[F, BarError]`) proving dealiasing works.

5. Wire the derived instances into the M2 suite via the seam M2 built:
   every law suite runs against `DeriveRaise.aspect[TestAlg, Render, Render]`
   and `DeriveRaise.aspect[PlainAlg, Render, Render]` on 2.13.

6. Differential oracle check: assert the derived instance's `RenderedWeave`
   output for every `TestAlg` method/argument sample equals the M1 reference
   instance's, and that `mapK` results are `Eq`-equal.

7. Edge coverage to confirm (add fixtures if M1's don't cover them):
   inherited members from a parent trait; an abstract val / nullary def
   returning `F[A]`; overloads if upstream supports them (match upstream —
   if upstream rejects, we reject identically).

## Acceptance criteria

- Full M2 suite green on 2.13 with derived instances substituted; reference
  instance untouched (`git diff` on M1/M2 sources is empty apart from the
  additive seam usage and new test files).
- Compile-error tests pass with the specified messages.
- Differential-oracle assertions pass.
- Attribution headers present on adapted code.

## Reporting

List every place your adaptation diverged from the upstream macro's behavior
and why, plus any upstream behaviors you discovered that the overview's rules
don't cover (these feed M4 and future upstreaming).
