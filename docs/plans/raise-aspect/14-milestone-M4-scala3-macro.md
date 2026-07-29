# Milestone M4 — Scala 3 derivation macro

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
