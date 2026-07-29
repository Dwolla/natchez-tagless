# Milestone M0 — Scaffolding

## Status

**Complete** (branch `milestone/m0-scaffolding`). Next: M1 runtime core.

Decisions taken during the session (Brian confirmed all three):

- **2.12 is included.** Task 1 below says "the repo's existing Scala 2.13 and
  Scala 3 versions", which contradicts overview §2/ground rule 6 and prep §2.
  Treated as a drafting slip: all four modules cross-build 2.12/2.13/3.
  Verified on Maven Central that nothing forces dropping 2.12 —
  cats-tagless-core and -laws 0.16.5, cats-mtl 1.7.0, cats-laws 2.13.0, and
  discipline-munit 2.0.0 all publish 2.12, 2.13, and 3, JVM and Scala.js.
  (cats-tagless-*macros* is 2.12/2.13 only; the Scala 3 macros live in its
  core module. Relevant to M3/M4, not here.)
- **Flat layout, not `modules/`.** Overview §3.1 sketches
  `modules/raise-aspect-core/`, but the repo is flat and ground rule 4 says
  match build structure. §3.1 is Design, not a §2 final decision. New modules
  sit alongside `core/` and `scalacache/`.
- **JVM + JS for all four**, matching `core`. All deps verified on sjs1.

Version conflicts to resolve: **none.** cats-core is pinned to 2.13.0, which
is what cats-mtl 1.7.0 and cats-tagless 0.16.5 already agree on. Note the repo
is on cats-mtl **1.7.0**, not the 1.4.0 recorded in prep §2 — well above the
`Handle.allow`/`rescue` floor M5 needs.

Deviations from the task list, and why:

- **Task 6 (placeholder `package.scala` per module): done differently.** A
  package-clause-only Scala file makes Scala 3 emit "No class, trait or object
  is defined in the compilation unit" — a new warning in every new module,
  against CLAUDE.md's zero-new-warnings rule. (`tlFatalWarnings` is `false`
  here, so it would have been noise, not a red CI.) Each source tree instead
  holds a `README.md` saying what lands there and in which milestone; scalac
  ignores non-`.scala` files, git keeps the directory, and the M3/M4 trees
  carry the Apache-2.0 attribution reminder where those sessions will look.
  Task 6's stated purpose — every module compiles on both Scala versions — is
  met and verified; source-tree wiring was confirmed via
  `raiseAspectMacrosJVM/Compile/unmanagedSourceDirectories` instead.
- **Task 3 (kind-projector): nothing to do.** sbt-typelevel already puts
  `org.typelevel:kind-projector:0.13.4` on the Scala 2 axes and
  `-Ykind-projector` in Scala 3 `scalacOptions`, at ThisBuild level. New
  modules inherit both. Verified, not assumed.
- **Task 5 (`reference/upstream/`): already present** from the prior
  `reference/cats-tagless-upstream` branch, and not compiled — no sbt project
  is rooted under `reference/` and nothing adds it to
  `unmanagedSourceDirectories`.
- **`natchez-tagless-mtl` has no explicit test-scope cats-mtl.** It arrives
  transitively from `raise-aspect-core` at 1.7.0 in compile scope, which
  subsumes test scope; a second declaration would be a no-op and a second
  place to bump. Add one line if you'd rather see the floor stated.
- **No `doctestSettings` on the new modules**, since task 2 says
  raise-aspect-core takes "nothing else" and doctest pulls in scalacheck and
  newtypes-core. M5 can add it when docs arrive.

Worth a second look in review:

- `raise-aspect-laws` takes **discipline-munit in compile scope**, per task 2
  ("the repo's test framework's discipline bridge"). Upstream cats-tagless-laws
  does *not* — it takes cats-laws + discipline-core and leaves the munit bridge
  to its `tests` module. Following the plan puts MUnit on the compile classpath
  of a published module. Reasonable for a test kit, but it is a deliberate
  divergence from the upstream shape M2 is told to mirror.

Known gaps:

- **Scala.js tests were not executed locally** — node isn't installed on this
  machine, and `coreJS` fails identically, so it is environmental and
  pre-existing rather than introduced here. Covered instead with
  `+natchez-tagless-rootJS/Test/scalaJSLinkerResult`, which is what CI runs
  before `test`; GitHub's runners have node preinstalled.
- **Pre-existing Scala 3 unused-import warnings** in four
  `core/shared/src/main/scala/com/dwolla/tracing/syntax/*Ops.scala` files
  (`import cats.effect.{Trace => _, _}`). Untouched by M0 — flagging, not
  fixing, per the broken-windows rule; cleanup is its own task.

Verification (clean build, `+clean` first — an incremental run replayed stale
zinc diagnostics for deleted files and was misleading):

- `+compile` across 2.12.21 / 2.13.18 / 3.3.8 × JVM+JS: 0 errors, and 0
  occurrences of the placeholder warning.
- `+natchez-tagless-rootJVM/test`: 19 tests passed, 0 failed, on each of the
  three Scala versions.
- `+natchez-tagless-rootJS/Test/scalaJSLinkerResult`: clean.
- `+natchez-tagless-rootJVM/mimaReportBinaryIssues`: all four new modules
  report "mimaPreviousArtifacts is empty, not analyzing binary compatibility".
- `githubWorkflowCheck`: passes. Both `.github/workflows/ci.yml` and
  `.mergify.yml` are generated and were regenerated — mergify adds a label
  rule per module, and the workflow's target-dir list grew.
- `raiseAspectCoreJVM/Compile/externalDependencyClasspath` resolves to exactly
  cats-core, cats-kernel, cats-mtl, cats-tagless-core, scala-library, and
  scalac-compat-annotation. **No natchez.**

---

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
