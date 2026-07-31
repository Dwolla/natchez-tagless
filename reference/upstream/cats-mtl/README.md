# Upstream reference material — cats-mtl

Read-only reference copies of selected [cats-mtl][repo] sources, kept here so the M8
capability-generalization milestone can be checked against the real upstream code
instead of against recollection. This is a **separate upstream** from the
`cats-tagless` material that lives alongside it in `reference/upstream/` — the two are
kept in distinct subtrees (`reference/upstream/cats-mtl/` vs. `reference/upstream/core`
`/laws`/`macros`) precisely so they aren't confused with one another. Package paths
also don't collide: this tree is entirely `cats.mtl.*`, the sibling tree is entirely
`cats.tagless.*`.

**Nothing in this directory is compiled.** It is not a source directory of any sbt
project: `build.sbt` declares projects rooted at `core/`, `scalacache/`,
`raise-aspect-core/`, `raise-aspect-laws/`, `raise-aspect-macros/`, and
`natchez-tagless-mtl/`, and nothing adds `reference/` to `unmanagedSourceDirectories`.
The nested `src/main/scala` path below mirrors the upstream layout only — it is inert
here. Do not wire it into the build; if you need this code, adapt it into our own
namespace.

## Provenance

| | |
| --- | --- |
| Source repo | <https://github.com/typelevel/cats-mtl> |
| Tag | `v1.7.0` |
| Commit SHA | `931556e44938a47eaa91af4d907b61a4a0bb4cab` |
| Commit date | 2026-06-13 |
| `build.sbt` version confirmation | `ThisBuild / tlBaseVersion := "1.7"` at that commit, and the tag is reachable from `main` |

Reproduce with:

```
git clone --depth 1 --branch v1.7.0 https://github.com/typelevel/cats-mtl
```

This is the same version this project already depends on: `catsMtlVersion = "1.7.0"`
in `build.sbt`. The verification for M8 did **not** trust the pre-existing checkout at
`~/Developer/github/cats-mtl` — that working copy is on a branch (`lift-raise`) with
uncommitted local edits to `Handle.scala`/`Raise.scala` and sits at a commit
(`69c2d19f`) with no `1.7.0` tag. Instead, a throwaway clone was made in a scratch
directory and `v1.7.0` was checked out there; nothing in this repo's vendored tree came
from the modified local checkout.

Every file here is a byte-for-byte copy of its upstream counterpart, at the same
repo-relative path, with the license header intact (verified with `diff` against the
throwaway `v1.7.0` clone). Do not edit them — if they drift from upstream they stop
being useful as a reference. To refresh, re-clone at a new tag and update this table.

## Contents

`core/src/main/scala/cats/mtl/`

All nine capability type classes cats-mtl 1.7.0 defines, plus the lifting/evidence
infrastructure some of them use:

- `Ask.scala`, `Local.scala` — `Local extends Ask`.
- `Tell.scala`, `Listen.scala` — `Listen extends Tell`.
- `Stateful.scala`
- `Raise.scala`, `Handle.scala` — `Handle extends Raise`.
- `Censor.scala` — `Censor extends Listen` (and therefore `Tell`).
- `Chronicle.scala`

These are the complete set of capability traits in the `core` module; there is no
tenth one hiding in a different package. (`cats.mtl.syntax.*` and the
`scala-2`/`scala-2.12`/`scala-2.13+` compat sources were not vendored — they contain
only extension methods and instance derivations for older Scala versions, not
capability definitions.)

- `LiftValue.scala`, `LiftKind.scala`, `MonadPartialOrder.scala` — included for
  context, not because they're new capability type classes. `Ask.liftTo` and
  `Local.liftTo` are built on `LiftValue`/`LiftKind` respectively, and the
  `xForMonadPartialOrder` implicit instances (`Ask`, `Tell`, `Stateful`, and the
  internal `Raise`/`Handle` ones) all use `MonadPartialOrder`. See this repo's
  `.superpowers/sdd/m8-capability-verification.md` for why these three should **not**
  be read as "cats-mtl already solved M8's transport problem" — they lift in the
  opposite direction and only across canonical monad-transformer embeddings, not
  across an arbitrary `F ~> G`.

## License

Individual file headers in this vendored tree read "Licensed under the Apache
License, Version 2.0" — that header is preserved verbatim below, unedited, exactly as
the instruction to vendor byte-for-byte requires. Note for the record: the upstream
repository's own top-level license file (`COPYING`, copied alongside these sources) is
MIT, not Apache-2.0 — the per-file header is inconsistent with the repo's declared
license. This vendoring makes no claim about which governs; it preserves both exactly
as found upstream. `COPYING` in this directory is a byte-for-byte copy of the
upstream file of the same name at the pinned commit.

This repository (natchez-tagless) is MIT-licensed.

[repo]: https://github.com/typelevel/cats-mtl
