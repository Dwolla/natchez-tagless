# Milestone M0 — Scaffolding

Read `01-overview-design-and-laws.md` first. Your scope is build plumbing
only: no typeclass code, no laws, no macros.

## Tasks

1. Add four modules to the natchez-tagless build per overview §3.1:
   `raise-aspect-core`, `raise-aspect-laws`, `raise-aspect-macros`,
   `natchez-tagless-mtl`, cross-built for the repo's existing Scala 2.13 and
   Scala 3 versions, following the repo's existing sbt idioms (settings
   helpers, publishing config, headers, scalafmt).
2. Dependencies:
   - `raise-aspect-core`: cats-core, cats-mtl, cats-tagless-core `0.16.5`.
     Nothing else. No natchez.
   - `raise-aspect-laws`: core + cats-laws/discipline + the repo's test
     framework's discipline bridge, mirroring how cats-tagless-laws is set up
     (see `reference/upstream/` laws sources for shape).
   - `raise-aspect-macros`: core; `scala-reflect` (provided) for the 2.13
     axis; empty `src/main/scala-2` and `src/main/scala-3` trees created.
   - `natchez-tagless-mtl`: core + macros + the existing natchez-tagless
     core module; cats-mtl ≥ 1.4.0 in test scope.
3. Kind-projector: ensure the 2.13 axis has the kind-projector plugin and the
   Scala 3 axis has the corresponding setting the repo already uses, since
   core sources will use `*` type lambdas.
4. Wire the new modules into CI and the aggregate project. Configure MiMa for
   the new modules but disabled/`mimaPreviousArtifacts := Set.empty` until a
   first release.
5. If `reference/upstream/` does not already exist (see the prep doc §1),
   create it exactly as specified there, ensure it is excluded from
   compilation and packaging, and record provenance (repo, tag `v0.16.5`,
   commit SHA, Apache-2.0 notice) in `reference/upstream/README.md`.
6. Add a placeholder `package.scala` or minimal marker source per module so
   every module compiles on both Scala versions.

## Acceptance criteria

- `sbt +compile +test` (or the repo's CI entrypoint) succeeds on both Scala
  versions with the new modules included; CI is green.
- `raise-aspect-core` has no natchez dependency (verify with the dependency
  report).
- `reference/upstream/` is present, not compiled, and documented.

## Ground rules reminder

Do not begin implementing the types from overview §3.2 — that is M1. Report
any version conflicts (cats, cats-mtl, cats-tagless) you had to resolve and
how.
