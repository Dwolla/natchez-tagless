# Milestone M3 — Scala 2 derivation macro

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
