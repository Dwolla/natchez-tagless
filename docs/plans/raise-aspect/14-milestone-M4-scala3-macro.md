# Milestone M4 — Scala 3 derivation macro

## Status

**Complete** (branch `milestone/m4-scala3-macro`, stacked on
`milestone/m3-scala2-macro`). Next: M5 natchez integration + docs.

`DeriveRaise.aspect` / `DeriveRaise.functorK` are implemented in
`raise-aspect-macros/src/main/scala-3`, adapted from the vendored `MacroAspect`,
`MacroFunctorK` and `DeriveMacros` with an Apache-2.0 attribution header. Entry
points are `@experimental`; experimentality is confined to those annotations and
their call sites — no nightly compiler, no raised baseline.

**52 tests pass on 3.3.8** (24 M2 laws through the seam, 5 oracle, 4 L9, 4 `using`,
9 diagnostics, 6 edge) and **46 on each of 2.12.21 / 2.13.18** (M3's 45 plus the new
cross-version agreement spec). `build.sbt` needed no change, as predicted. Frozen
M1/M2 sources and all M3 sources verified untouched: `git diff` against `0e78145`
scoped to `raise-aspect-laws/`, `raise-aspect-core/`, `src/main/scala-2/` and the
existing `src/test/scala-2/` files is empty.

### What Scala 3 made easier than Scala 2

1. **Signature substitution is free.** `newClassOf[T]` derives members from
   `cls.memberType(member)` against the applied parent, so in
   `Alg[Weave[F, Dom, Cod, *]]` a `Raise[F, E]` parameter *arrives already*
   substituted. M3's `substituteCapabilities` has no analogue and was not written.
   Comparing the substituted effect against the carrier type lambda with `=:=`
   works, so classification stayed precise.
2. **`filterNot(c => c.isGiven || c.isImplicit)` drops every implicit/given
   clause**, not just the last — which **fixes the Scala 2 limitation M3
   reported**. Multiple `using` clauses on one method work with no extra code, and
   `MultiUsingAlg` is the fixture that proves it (a shape Scala 2 cannot express).
3. **Abstract `val`s returning `F[A]` work**, because `transformTo` handles
   `transformVal` and `overridableMembers` includes `fieldMembers`.

### The 2.x / 3 asymmetry — M5's docs must state this

Per Brian's decision, each axis keeps parity with *its own* upstream rather than
with the other. The consequence is user-visible:

| Algebra shape | Scala 2.12/2.13 | Scala 3 |
|---|---|---|
| `def m(i: Int)(implicit R: Raise[F, E]): F[A]` | works | works |
| `val v: F[A]` | **fails** — opaque "object creation impossible" | **works** |
| two `using` clauses on one method | not expressible | works |

An algebra using `val v: F[A]` therefore compiles on Scala 3 and fails on 2.13/2.12.
M5's user documentation needs to say so.

### Divergences from upstream `MacroAspect`

1. **Capability transport** — the point of the milestone. `args` transform replaces
   capability arguments with `WeaveArrows.raisePull[F, Dom, Cod](using F).apply(r)`
   (weave) or `arrow.pull(r)` (mapK), and `domain` excludes them.
2. **Classification by exact type symbol, never `<:<`** — `Handle[F, E] extends
   Raise[F, E]` and consumes `F`; the same load-bearing rule as M3.
3. **Validation runs up front against the declared `Alg[F]`**, before class
   synthesis, so the diagnostics name the types the user wrote and arrive before any
   confusing synthesis failure. Upstream instead relies on its `body` transform
   simply not matching.
4. **Context-function returns are rejected** (`Raise[F, E] ?=> F[A]`), detected by
   matching `ContextFunctionN` on the dealiased result's type constructor — chosen
   over `defn.FunctionClass` overloads because it is stable across 3.x.
5. **`Dom`/`Cod` summoned via `Implicits.search`** with our own message naming the
   parameter or method, rather than upstream's `failure.explanation`.
6. **`addToGivenScope` omitted** — see the new future-work section in the overview,
   with a triggering example, per Brian.
7. **`newTypeAlias` retained**, the one remaining dotty-internal reflection. Unlike
   the given-scope hack it is not optional: `overridableMembers` cannot build a class
   for any algebra with a type member without it, M3 supports those, and it fails
   loudly via `report.errorAndAbort` rather than silently.

### Testing-tool finding, and a near-miss

**Scala 3's `compileErrors` needs an explicit type annotation.** Writing
`val errors = compileErrors("…")` makes the macro's inner typecheck trip a
cyclic-reference check, and the captured string is
`"Recursive value errors needs type"` — *not* the macro's diagnostic. All eight
diagnostics assertions failed on that string before `val errors: String = …` fixed
it. The assertions failed honestly rather than vacuously, but only because they
assert on message *content*; a test asserting merely "some error occurred" would
have passed for entirely the wrong reason.

Note this is a **different** trap from M3's: on Scala 2, `compileErrors` cannot
observe errors raised by `c.typecheck` inside the macro at all and returns `""`. Two
distinct failure modes in the same tool across the two versions. Any future
compile-error suite should assert on content, and should be probed once against a
known-bad input to confirm it can actually fail.

### Cross-compiler agreement (task 5)

`ExpectedWeaves` in shared test sources holds the golden `RenderedWeave` values for
a fixed set of `TestAlg` calls; a Scala 2 spec and a Scala 3 spec each assert their
derived instance reproduces exactly that list. So the two derivations are compared
directly, not merely transitively through the M1 reference — though both oracles
also assert structural identity to that reference.

### Verification

`+clean` build: 0 errors, 0 new warnings, JVM tests green on all three versions,
Scala.js links clean, MiMa skips the new modules, `+doc` succeeds,
`githubWorkflowCheck` passes. As in M3, the laws passed on the first run, so the
oracle and the golden values are the load-bearing evidence rather than the
discipline rule sets.

---

Read `01-overview-design-and-laws.md` first. Prerequisites: M1–M3 merged.

## Non-negotiable ground rules for this milestone

Identical to M3's: the M2 law suite, M1 reference instance, and M3's
compile-error expectations are the specification. Never modify existing
laws/tests/fixtures to get green; add tests freely; STOP and report if you
believe the spec is wrong. Adapt the vendored upstream Scala 3 macro
(`reference/upstream/.../cats/tagless/macros/MacroAspect.scala` and the
shared helpers it uses, from cats-tagless `v0.16.5`) rather than writing
from memory; keep Apache-2.0 attribution headers on adapted code.

Implementation note: this is a `scala.quoted` / `quotes.reflect` macro that
synthesizes an anonymous class (the `MacroAspect` approach). It is **not** a
`scala.deriving.Mirror` derivation — Mirrors cannot synthesize forwarding
implementations of higher-kinded trait methods. Do not attempt a
Mirror-based design.

Experimental-API note (decided; see overview §2): on the repo's Scala 3 LTS
line, `Symbol.newClass` and related reflect APIs are `@experimental`.
Annotate the Scala 3 `DeriveRaise` entry points `@experimental` (mirroring
upstream cats-tagless), and annotate the Scala 3 test sources that call them
(or use the repo's preferred equivalent, e.g. `-experimental` on 3.4+ test
axes if the build already does that). Do not switch to a nightly compiler,
suppress the requirement with compiler hacks, or raise the Scala 3 baseline.

## Tasks

1. Implement the Scala 3 side of `DeriveRaise.aspect` / `DeriveRaise.functorK`
   in `raise-aspect-macros` `src/main/scala-3`, with the same user-facing
   signatures as M3, generating code per overview §3.4 and the same delta
   from upstream as M3 defines:
   - Capability classification via `TypeRepr.dealias` matched against
     `cats.mtl.Raise` applied to the algebra's effect parameter, with the
     error type not mentioning `F`; implicitness irrelevant to
     classification.
   - Preserve `TermParamClause` shape and `isImplicit`/`isGiven` flags,
     by-name modifiers, and declared parameter types verbatim post-dealias
     when regenerating signatures with the substituted effect.
   - Substitute `WeaveArrows.raisePull[F, Dom, Cod].apply(r)` /
     `arrow.pull(r)` at underlying call sites for capability parameters;
     exclude them from `domain` capture; everything else matches upstream
     `MacroAspect` behavior exactly.
2. Diagnostics via `report.errorAndAbort` with the same messages as M3
   (Handle rejection; F-in-unsupported-position; nested-F return; missing
   Dom/Cod), plus a Scala 3-specific rejection: a context-function return
   type mentioning `Raise` (e.g. `Raise[F, E] ?=> F[A]`) → message suggesting
   a `using` parameter instead.
3. Port/extend the compile-error suite to Scala 3 (munit `compileErrors` or
   the repo's equivalent), including the aliased-capability success case and
   `using`-clause fixtures (a `TestAlg` variant written with `using` instead
   of `implicit` must derive and pass the full suite).
4. Run the entire M2 suite on Scala 3 with derived instances via the M2 seam,
   including the differential-oracle comparison against the M1 reference
   instance.
5. Cross-compiler agreement check: add a test (present in both builds)
   asserting the derived instances produce identical `RenderedWeave` output
   for the shared `TestAlg` samples, so the 2.13 and 3 derivations can be
   compared assertion-for-assertion in CI.

## Known sharp edges (handle explicitly; note each in your report)

- Owner chains when building method bodies under `Symbol.newClass` — follow
  the vendored `MacroAspect` patterns closely.
- By-name parameter types (`ByNameType`) surviving signature regeneration.
- Variance: `Raise[F, -E]` — use the declared `TypeRepr` post-dealias; do not
  rebuild the applied type from parts.
- Given/using resolution for `Dom`/`Cod` instances at derivation site
  (`Implicits.search`), with method+parameter named in the failure message.

## Acceptance criteria

- Full M2 suite green on Scala 3 with derived instances; laws/fixtures/M3
  sources untouched apart from additive files and the cross-build test.
- Compile-error suite green on Scala 3, including the context-function
  rejection and `using`-clause success cases.
- Cross-compiler agreement test green on both builds.
- The Scala 3 build compiles and tests on the repo's stable LTS compiler (no
  nightly), with experimentality confined to `@experimental` annotations on
  the entry points and their call sites.
- Attribution headers present on adapted code.
