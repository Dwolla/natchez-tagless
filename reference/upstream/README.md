# Upstream reference material — cats-tagless

Read-only reference copies of selected [cats-tagless][repo] sources, kept here so the
implementation milestones can be checked against the real upstream code instead of
against recollection.

**Nothing in this directory is compiled.** It is not a source directory of any sbt
project: `build.sbt` declares projects rooted at `core/`, `scalacache/`, and
`buildInfoForTests/`, and nothing adds `reference/` to `unmanagedSourceDirectories`.
The nested `src/main/scala-*` paths below mirror the upstream layout only — they are
inert here. Do not wire them into the build; if you need this code, adapt it into our
own namespace (see *License* below).

## Provenance

| | |
| --- | --- |
| Source repo | <https://github.com/typelevel/cats-tagless> |
| Tag | `v0.16.5` |
| Commit SHA | `2f0c9317c09a51784f4f16abb52ee5ae63274e1b` |
| Commit date | 2026-03-03 |
| License | Apache License 2.0 (see [`LICENSE`](LICENSE)) |

Reproduce with:

```
git clone --depth 1 --branch v0.16.5 https://github.com/typelevel/cats-tagless
```

Every file here is a byte-for-byte copy of its upstream counterpart, at the same
repo-relative path, with the Apache-2.0 license header intact. Do not edit them — if
they drift from upstream they stop being useful as a reference. To refresh, re-clone at
a new tag and update this table.

## Contents

### 1. Scala 3 macro reference

`core/src/main/scala-3/cats/tagless/macros/`

- `MacroAspect.scala` — the `Aspect` derivation: `derive`/`aspect` entry points and
  `deriveWeave`, which builds the `Weave`/`Advice` tree per method.
- `MacroFunctorK.scala` — `deriveMapK`, called directly by `MacroAspect.aspect` to
  implement the `Aspect#mapK` half.
- `DeriveMacros.scala` — the shared derivation machinery. Supplies the extension
  methods `MacroAspect` relies on (`transformTo`, `widenParam`, `isByName`,
  `isRepeated`, `summonLambda`, …). Self-contained: imports only `scala.quoted`.

Those three are the complete transitive closure of what `MacroAspect` touches inside
the `cats.tagless.macros` package.

> **Note on location.** In 0.16.x the Scala 3 macros live in the **core** module's
> `scala-3` tree, not the `macros` module — the `macros` module is Scala 2 only. The
> upstream path is preserved above.

### 2. Scala 2 macro reference

`macros/src/main/scala-2/cats/tagless/`

- `DeriveMacros.scala` — the whole Scala 2 blackbox-macro bundle; the `aspect`
  derivation is at line 855. Depends on nothing else in the package (it imports only
  `cats.*` and `scala.reflect.macros.blackbox`), so it is copied whole and alone.
- `Derive.scala` — included for context: the public entry point that binds
  `Derive.aspect` to `DeriveMacros.aspect`, showing the call shape. Not strictly a
  dependency of the macro.

`MacroUtils.scala` is deliberately **not** copied: it serves the `auto*` annotation
macros, not `DeriveMacros`.

### 3. API reference

`core/src/main/scala/cats/tagless/aop/`

- `Aspect.scala` — `Aspect`, `Aspect.Weave`, `Aspect.Advice`. **Verify constructor and
  factory signatures against this file rather than guessing them** (`Advice.byValue`,
  `Advice.byName`, `Weave.apply`, `Weave.instrumentationK`).
- `Instrument.scala` — `Aspect extends Instrument`, and `Instrumentation` appears in
  the aspect laws; copied so `Aspect.scala` reads standalone.

### 4. Laws style reference

`laws/src/main/scala/cats/tagless/laws/`

Law traits: `AspectLaws.scala`, `InstrumentLaws.scala`, `FunctorKLaws.scala`,
`InvariantKLaws.scala`.

Discipline tests: `discipline/AspectTests.scala`, `discipline/InstrumentTests.scala`,
`discipline/FunctorKTests.scala`, `discipline/InvariantKTests.scala`.

Both full inheritance chains are present — `AspectLaws → InstrumentLaws → FunctorKLaws
→ InvariantKLaws` and the parallel `…Tests` chain — so the `FunctorK` and `Aspect`
conventions M2 should match can be read end to end without jumping to GitHub.

## License

cats-tagless is licensed under the Apache License 2.0, copyright the cats-tagless
maintainers. The full license text is in [`LICENSE`](LICENSE); the per-file headers are
retained verbatim.

This repository (natchez-tagless) is MIT-licensed. Apache-2.0 permits redistribution
and derivative works provided the license, copyright notice, and a statement of changes
are carried along.

**Obligation for adapted code:** when macro code from here is adapted into our own
namespace (M3/M4), the adapted file must carry an attribution header naming
cats-tagless, the Apache-2.0 license, the upstream file, and the fact that it was
modified. The milestone docs already instruct this — **confirm it in review** before
merging any adapted file. Verbatim copies that stay in this directory need no change
beyond the header they already have.

[repo]: https://github.com/typelevel/cats-tagless
