# Milestone M15 — extract `WeaveKnot` to its own module

## Status

**Complete (2026-08-02).** Branch `milestone/m15-tagless-core-module`, stacked on
`106bf17`. The Decisions section below (D1–D6) was ratified as proposed and
implemented as written. The implementation plan is
`29-milestone-M15-implementation-plan.md`; each task's brief and report live
under `.superpowers/sdd/29-milestone-M15-implementation-plan/`.

**The two open questions, as answered by Brian (2026-08-02):**

- **Q1 — the name is `tagless-core`.** Artifact `tagless-core`, directory
  `tagless-core/`, sbt cross-project `taglessCore` (`taglessCoreJVM` /
  `taglessCoreJS`). Used verbatim everywhere.
- **Q2 — yes, more is destined for this module.** Choosing the broader name over
  `weave-knot` answered the companion question: this is the general home for
  natchez-free `com.dwolla.tagless` utilities, not a single-type artifact.
  Anything added later with no natchez dependency belongs here rather than in a
  new module.

M13, M14 and M15 were planned together on 2026-08-02 and are independent of one
another. **M15 exists to prepare for M16 (otel4s), which is planned and is being
researched separately.** M16's design and plan are not in this round.

**What landed, task by task:**

- **Task 1** (`c6e0a73`, "refactor: extract WeaveKnot into its own
  cats-tagless-only module") created the module and moved both files. Two
  `git mv`s and no edits: git records both as `R100`, 0 insertions, 0 deletions,
  and the post-move SHA-256s equal the pre-move ones
  (`76100ce5…` for `WeaveKnot.scala`, `3a73d99d…` for `WeaveKnotSpec.scala`).
  `build.sbt` gained the `taglessCore` cross-project — `CrossType.Pure`,
  2.12.21 / 2.13.18 / 3.3.8, JVM and JS, cats-core and cats-tagless-core in
  compile scope with munit and munit-scalacheck in test scope, all `%%%`, no
  natchez and no `dependsOn(core)` — plus `taglessCore` at the head of the root
  aggregate and `core.dependsOn(taglessCore, buildInfoForTests % Test)`.
  `sbt githubWorkflowGenerate` then updated `.github/workflows/ci.yml` (both
  target-directory enumerations) and `.mergify.yml` (one per-module label rule);
  `githubWorkflowCheck` passes.
- **Task 2** (no commit — evidence only, by design) ran the compatibility gate:
  jar inspection, MiMa, and transitive resolution of
  `com.dwolla.tagless.WeaveKnot` from `coreJVM`, `scalacacheJVM` and
  `natchezTaglessMtlJVM` via `sbt "<module>/console"` fed the FQN by heredoc.
  Nothing was left in the tree; `git status` was clean throughout. **This gate
  is one-time by construction** — it verified the build that Task 1 produced. It
  is not a standing regression test, and nothing in this milestone became one.
- **Task 3** (this commit) is documentation, plus one `build.sbt` comment
  correction described below.

**What diverged from the plan, and why:**

- **The plan's Step 3 was wrong, and is now corrected in the plan document.** It
  predicted `coreJVM/Test/compile` would *fail* after the move. It cannot: no
  Scala source in this repository references `WeaveKnot` outside its own two
  files, so removing them from `core`'s source roots breaks nothing — and the
  step contradicted itself one sentence later ("the files are simply invisible
  to the build" is the accurate half). Task 1 hit the success, recognised it as
  correct behaviour rather than stale output, and satisfied the step's real
  intent — prove the *next* step does real work — with a `clean` plus jar
  inspection instead: post-move, `natchez-tagless_2.13` and
  `natchez-tagless_sjs1_2.13` contain **0** `WeaveKnot` entries and
  `tagless-core_2.13` contains `WeaveKnot.class` and `WeaveKnot$.class` (4
  entries on JS, counting the `.sjsir` siblings).
- **The `build.sbt` comment on the new module claimed something false, and Task
  3 reworded it.** As drafted in the plan and as landed in `c6e0a73`, it ended
  "`core` depends on this module, so the class remains available, at the same
  fully-qualified name, to everything that already had it." **Nothing had it**
  (see the next section). The comment now says the edge is a design choice
  rather than a compatibility rescue. The edge itself stays — D1 is a real
  decision, and M16 already plans `.dependsOn(taglessCore)` of its own.
- **`-Xsource:3` needed no hand-added flag on the 2.12 axis.** The plan flagged
  this as the riskiest inference (`WeaveKnot.scala` uses `import cats.*`).
  `show taglessCoreJVM/scalacOptions` on 2.12.21 lists `-Xsource:3`:
  `TypelevelSettingsPlugin` reaches a brand-new project as argued.
- **`WeaveKnotSpec` needed no changes under `-Wunused`.** The plan expected the
  spec to see `-Wunused` for the first time (`core`'s `doctestSettings` filters
  it out of `Test / scalacOptions`, and `doctestSettings` was deliberately *not*
  imported here). It was already clean on all six axis/platform combinations.

### The thing this milestone actually preserved: nothing, yet

Stated plainly, because the milestone is easy to misread as a compatibility
exercise: **`WeaveKnot` has no consumers.** It was introduced after `v0.2.6`, so
it appears in no published artifact, and `grep` finds no Scala source in this
repository that references it outside its own definition and its own spec. The
source and runtime compatibility that D1 and D2 preserve is therefore a
**forward-looking design property, not a live requirement**.

Two consequences a later milestone should know:

1. **`core.dependsOn(taglessCore)` is unprotected.** If that edge were deleted
   today, `core`, `scalacache` and `natchezTaglessMtl` would all still compile,
   every test would still pass, and **MiMa would stay green** — MiMa reports a
   class as missing only when it vanished from a previously *published* surface,
   and `WeaveKnot` was never published. Nothing in the suite would notice. A
   permanent synthetic test was deliberately **not** added: it would bake an
   artificial call site into the tree to guard a guarantee nobody yet depends
   on. **The gap closes for free in M16** — its otel4s module references
   `WeaveKnot` for real (`30-milestone-M16-otel4s-module.md` §"The `WeaveKnot`
   dependency"; its doctest exercises the knot), at which point that module's
   own compilation is the test.
2. **The MiMa result proves less than it looks like it proves.** A clean MiMa
   run on `core` after this move is consistent both with "the move was safe" and
   with "MiMa was never going to see this class either way". It is the latter.
   The measurement below is what a future mover of a *published* class should
   read; the general rule — mima builds its "new" package from the new jar alone,
   so a published class moved to a dependency **is** reported missing — is in
   "The mechanism, recorded for the next time this comes up" and is untested by
   this milestone.

### Verification actually run

Counts are ordered **2.13.18 / 2.12.21 / 3.3.8** and were confirmed per Scala
version, not read off an aggregate.

| module | before | after |
| --- | --- | --- |
| `taglessCoreJVM` | — | **4 / 4 / 4** |
| `taglessCoreJS` | — | **4 / 4 / 4** |
| `coreJVM` | 19 / 19 / 35 | **15 / 15 / 31** |
| `coreJS` | 19 / 19 / 33 | **15 / 15 / 29** |
| `natchezTaglessMtlJVM` | 18 / 18 / 31 | 18 / 18 / 31 |
| `natchezTaglessMtlJS` | 18 / 18 / 31 | 18 / 18 / 31 |
| `scalacacheJVM` | 0 / 0 / 0 (no test sources) | unchanged |

Exactly −4 on every axis of `core`, and the four are accounted for exactly once:
they are `WeaveKnotSpec`'s, and they now run in `taglessCore` on both platforms.

**The `coreJS` numbers mean something only because of `106bf17`**, the commit
immediately before this branch. It changed `build.sbt:65-67` from `%%` to `%%%`
for `munit-cats-effect`, `scalacheck-effect` and `scalacheck-effect-munit`
inside `core`'s *shared* settings block; before that fix `coreJS` ran **zero**
munit tests and reported success anyway (found during M14). Every "JS green"
claim about `core` older than `106bf17` means the linker succeeded and nothing
more. M15's do not — the JS suites really executed.

- `sbt "+test"`: green on all three Scala versions, both platforms.
- JS linker: `+…/Test/scalaJSLinkerResult` for `taglessCoreJS`, `coreJS`,
  `raiseAspectCoreJS`, `raiseAspectLawsJS`, `raiseAspectMacrosJS` and
  `natchezTaglessMtlJS` — 18 `[success]` lines (6 modules × 3 Scala versions).
- **Zero new warnings under forced `-Xfatal-warnings`** on all six
  axis/platform combinations of `taglessCore`, after `clean` so the compile
  provably re-ran. Verified the flag lands in `Test / scalacOptions` and not
  only `Compile`. Pre-existing warnings elsewhere are unchanged and unfixed
  here: four Scala 3 unused-import warnings in `core/shared/.../syntax/`, and
  one Scala 2 outer-reference warning in `natchezTaglessMtl`'s *generated*
  doctest source.
- `+coreJVM/doc` and `+taglessCoreJVM/doc` succeed on all three Scala versions.
- `grep -rn "mimaBinaryIssueFilters" build.sbt` → **no matches**, at every point
  in the milestone.
- `com.dwolla.tagless.WeaveKnot` resolves from `coreJVM`, `scalacacheJVM` and
  `natchezTaglessMtlJVM` with the FQN alone, no import and no build change to
  the latter two.
- Jars, every Scala version: `tagless-core_{2.12,2.13,3}` and their `_sjs1_`
  siblings contain `com/dwolla/tagless/WeaveKnot.class` and `WeaveKnot$.class`
  (plus `.tasty` on 3 and `.sjsir` on JS); `natchez-tagless_{2.12,2.13,3}`
  contain **zero** `WeaveKnot` entries. The class does not ship from both places.
- `sbt githubWorkflowCheck` → `[success]` after `githubWorkflowGenerate`.
- **Re-run at Task 3**, after the `build.sbt` comment edit, as one invocation:
  `+test`, `+coreJVM/mimaReportBinaryIssues`, `+coreJS/mimaReportBinaryIssues`,
  `+scalacacheJVM/mimaReportBinaryIssues`, `+coreJVM/doc`, `+taglessCoreJVM/doc`
  and `githubWorkflowCheck` — exit 0, zero `[error]` lines, six "Main Scala API
  documentation successful", and the same per-module counts as the table above.
  The only `[warn]` lines are the pre-existing ones listed above.

### What MiMa actually did, with the set size beside it

This is the measurement the next person to move a class out of `core` should
read instead of repeating the investigation. Run with `mimaFindBinaryIssues`
rather than `mimaReportBinaryIssues`, deliberately: the latter prints nothing on
success and so cannot distinguish "clean" from "compared against nothing".

| project | 2.12.21 | 2.13.18 | 3.3.8 | result |
| --- | --- | --- | --- | --- |
| `coreJVM` | 7 previous artifacts | 7 | 3 | every entry `(List(), List())` |
| `coreJS` | 7 | 7 | 3 | every entry `(List(), List())` |
| `scalacacheJVM` | 7 | 7 | 3 | every entry `(List(), List())` |
| `taglessCoreJVM` / `taglessCoreJS` | 0 | 0 | 0 | "mimaPreviousArtifacts is empty, not analyzing binary compatibility" |

**51 artifact/module/version comparisons, all empty, no filter added.** The
seven are `com.dwolla:natchez-tagless:0.2.0`…`0.2.6`; the Scala 3 axis is
limited to `0.2.4`–`0.2.6` by `ThisBuild / tlVersionIntroduced := Map("3" -> "0.2.4")`.
The set being non-empty is the load-bearing half of this table — a green run
against `Set.empty` proves nothing, which is exactly what the `taglessCore` row
is.

Note the two evidence sources are **not** the same and should not be conflated:
the hand scan covered 21 published jars across `_2.12`, `_2.13` and
`_sjs1_2.13` and **excluded Scala 3**; MiMa's own run covers Scala 3 as well.
Both came back with no `WeaveKnot`.

### Anything a later milestone needs

- **`tagless-core` ships with MiMa permanently disabled, and that is a live
  hazard for the next release — not a "not yet published" marker.**
  `mimaPreviousArtifacts := Set.empty` is a hardcode, and nothing suppresses
  publishing for the module (`publish / skip` is `false`; no `tlSkipPublish`),
  so `com.dwolla:tagless-core` **will** be published at the next
  release with a real public API (`WeaveKnot.instrument`, `WeaveKnot.weave`,
  `WeaveKnot.apply`). At the release *after* that, changing one of those
  signatures produces no MiMa finding, CI stays green, and a consumer gets a
  `NoSuchMethodError` at runtime. The idiomatic fix is `tlVersionIntroduced :=
  Map(...)` on the project in place of the `Set.empty`, which keeps MiMa live
  from the module's first release onward. **Four sibling modules already carry
  the identical hazard** — `raiseAspectCore`, `raiseAspectLaws`,
  `raiseAspectMacros` and `natchezTaglessMtl`, at `build.sbt:139,170,190,203`
  (they were `108,139,159,172` before this milestone inserted `taglessCore`).
  The M15 plan mandated `Set.empty` verbatim, so changing it was out of scope
  here. **This belongs on a release checklist**, not in a task report: it is one
  decision covering all five modules and it must be made before the next
  publish.
- **The "not one line changed" claim is contingent on a rename-aware git
  invocation.** `git show --stat HEAD -- <new-path-only>` reports a **38-line
  add** for `WeaveKnot.scala`, because restricting the pathspec to only the new
  side of a rename breaks git's rename correlation and git falls back to
  reporting a plain add. `git show --name-status -M HEAD` and an unrestricted
  `git show --stat HEAD` both show the truth: `R100`, 0 insertions, 0 deletions,
  on both files. The move really was a pure rename — but an auditor who runs the
  path-restricted form will conclude otherwise. Use the unrestricted form, or
  `--name-status -M`.
- **`node` is not on the default `PATH` on this machine.** Any `*JS/test`
  invocation needs `PATH="$HOME/.nvm/versions/node/v22.21.1/bin:$PATH"`;
  without it the run fails with "failed to start node", which is an environment
  error, not a regression. CI installs node itself.
- **A new `crossProject` needs `sbt githubWorkflowGenerate`.** Both the "Make
  target directories" and "Compress target directories" enumerations in
  `.github/workflows/ci.yml` list every module's target directory, and
  `.mergify.yml` carries a per-module label rule; both are generated, both
  changed, and `githubWorkflowCheck` fails if they are not regenerated.
- **Everything below this Status section is the pre-implementation record**,
  written in the present tense of 2026-08-02 *before* the move. Paths like
  `core/shared/src/main/scala/com/dwolla/tagless/WeaveKnot.scala` and sentences
  like "It lives in `core`" describe where the files **were**; they are now at
  `tagless-core/src/{main,test}/scala/com/dwolla/tagless/`. The record is left
  as written, with dated correction notes inline where a claim turned out to be
  imprecise.
- **For M16:** `com.dwolla.tagless.WeaveKnot` is in `tagless-core`, sbt project
  `taglessCore`, cross-built JVM + JS on all three Scala versions, and depends
  only on cats-core and cats-tagless-core. Depend on it directly
  (`.dependsOn(taglessCore)`) rather than reaching it through `core` — reaching
  it through `core` would drag in natchez, which is the whole reason M15
  happened. Check `WeaveKnot`'s actual signature in the module before writing
  the doctest; M15 did not change it.

### Why this milestone exists, in one paragraph

`com.dwolla.tagless.WeaveKnot` ties the knot that lets an instrumented algebra's
methods call the *instrumented* versions of its own other methods. It is written
against cats and cats-tagless and nothing else — `Eval`, `~>`, `Aspect`,
`Instrument`, `Instrumentation` — and it mentions natchez nowhere. It lives in
`core`, whose artifact is `natchez-tagless` and which depends on natchez-core,
natchez-mtl, circe and log4cats. A future otel4s module needs `WeaveKnot` and
must not depend on natchez to get it. M15 moves the class into a module that
carries only what it actually uses, without changing its fully-qualified name
and without breaking anyone.

---

## The honest problem, and what the investigation found

**The stated risk.** The package and FQN must not change (source compatibility),
and `core` must keep depending on the new module so existing consumers still get
the class transitively (runtime compatibility). Both are straightforward. The
one that is not straightforward is **MiMa**: `core` is one of the two modules in
this repo with MiMa genuinely enabled — `mimaPreviousArtifacts := Set.empty`
appears only on the four newer modules (`build.sbt:108,139,159,172`), and
neither `core` nor `scalacache` is among them. `show coreJVM/mimaPreviousArtifacts`
returns seven real published versions, 0.2.0 through 0.2.6, and `coreJS` the
same. The expectation going in was that MiMa would report the class as missing
from `core`'s jar even though it is on the classpath, requiring a
`mimaBinaryIssueFilters` entry with a comment recording why the filter is safe.

**That expectation is wrong here, for a specific and checkable reason:
`WeaveKnot` has never been published.**

- It was added in commit `2eefa10` ("add WeaveKnot to give instrumented algebras
  a way to call their own instrumented metthods"), which is **not an ancestor of
  any of `v0.2.0` … `v0.2.6`** — checked tag by tag with
  `git merge-base --is-ancestor`.
- Directly, rather than by inference: **21** published artifacts
  (7 versions × `_2.13`, `_2.12`, `_sjs1_2.13`) were resolved from the
  repositories and scanned. Every one contains **zero** entries matching
  `WeaveKnot`. For contrast, a post-0.2.6 snapshot jar
  (`natchez-tagless_2.13-0.2.6-125-5ea4465-SNAPSHOT.jar`) does contain
  `com/dwolla/tagless/WeaveKnot.class` and `WeaveKnot$.class`, so the scan is
  capable of finding them.

  > **Clarified 2026-08-02, after the milestone ran.** Those three classifiers
  > are **not** the whole of what MiMa compares `core` against — the manual scan
  > excludes Scala 3 entirely, and MiMa additionally compares
  > `natchez-tagless_3` and `natchez-tagless_sjs1_3` for 0.2.4–0.2.6 (see
  > `ThisBuild / tlVersionIntroduced := Map("3" -> "0.2.4")`). The conclusion is
  > unaffected — MiMa's own run *did* cover Scala 3 and came back clean — but
  > the 21-jar scan and the MiMa run are two distinct pieces of evidence and
  > should be cited as such. Where "21" appears below, read it as "21
  > hand-scanned published jars", not "everything MiMa checks".

MiMa reports `MissingClassProblem` only for classes present in the *old*
artifact (`Analyzer.analyze` iterates `oldpkg.accessibleClasses`). A class that
was never in any old artifact cannot go missing from the new one.

**Conclusion: no `mimaBinaryIssueFilters` entry is needed, and none should be
added.** The plan verifies this by running MiMa rather than by trusting this
paragraph, and if MiMa does speak, that is a finding to report — not something
to silence.

### The mechanism, recorded for the next time this comes up

The premise behind the expected filter is *correct in general*, and it is worth
writing down, because the next class someone moves may well be a published one.
From mima-core 1.1.4's own source:

```scala
final class MiMaLib(cp: Seq[File], log: Logging = ConsoleLogging) {
  private val classpath = ClassPath.of(cp.flatMap(ClassPath.fromJarOrDir(_)) :+ ClassPath.base)
  …
  def collectProblems(oldJarOrDir: File, newJarOrDir: File, excludeAnnots: List[String]): List[Problem] = {
    val oldPackage = createPackage(oldJarOrDir)
    val newPackage = createPackage(newJarOrDir)
    …
```

`createPackage` builds its `PackageInfo` from `ClassPath.fromJarOrDir(dirOrJar)`
alone. The `cp` handed to `MiMaLib` is used only to construct `Definitions` —
i.e. to *resolve types referred to* by the classfiles under comparison — never
to decide package membership. `Analyzer.analyze` then does

```scala
problem <- newpkg.classes.get(oldclazz.bytecodeName) match {
  case Some(newclazz) => analyze(oldclazz, newclazz, log, excludeAnnots)
  case None           => List(MissingClassProblem(oldclazz))
}
```

and `mimaCurrentClassfiles` for `coreJVM` is
`core/jvm/target/scala-2.13/classes` — the project's own output directory.

So: **moving a published class to a dependency does produce
`MissingClassProblem`, and being on the runtime classpath does not save it.**
Two classfiles would be reported for a Scala `object` (`Foo` and `Foo$`). That
is not this milestone's situation, but it is the rule.

---

## What references `WeaveKnot`

Searched the whole repository with `grep -rIln "WeaveKnot"`, excluding only
`target/`, `.git/` and `reference/` (which is inert, non-compiled upstream
material). Three files, total:

| File | What it does |
| --- | --- |
| `core/shared/src/main/scala/com/dwolla/tagless/WeaveKnot.scala` | the definition |
| `core/shared/src/test/scala/com/dwolla/tagless/WeaveKnotSpec.scala` | its only test |
| `docs/plans/raise-aspect/22-milestone-M12-fused-derivation.md:429` | one table row: "`WeaveKnot` (in `core`) \| untouched — already fused-shaped" |

Nothing else — no other main source, no other test, no `README`, no build file,
no CI config. `scalacache` and `natchez-tagless-mtl` both depend on `core` but
neither mentions it.

`WeaveKnotSpec.scala` is **entirely self-contained**: its fixtures
`MyAlgebra`, `WriteInstrumentation` and `WriteWeave` appear in no other file
(checked separately), so the spec moves with the class and takes nothing with
it. It needs munit, munit-scalacheck, `cats.data.WriterT`, `cats.Id` and
`cats.tagless.Trivial` — no natchez, no cats-effect.

This is about as clean an extraction as exists. The milestone's difficulty is
entirely in the build, not in the code.

---

## The new module

### Shape

```scala
lazy val taglessCore = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("tagless-core"))
  .settings(
    name := "tagless-core",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-core" % catsVersion,
      "org.typelevel" %%% "cats-tagless-core" % catsTaglessVersion,
      "org.scalameta" %%% "munit" % munitVersion % Test,
      "org.scalameta" %%% "munit-scalacheck" % munitVersion % Test,
    ),
    mimaPreviousArtifacts := Set.empty,
  )
```

with `taglessCore` added to `tlCrossRootProject.aggregate(…)` and
`core` gaining `.dependsOn(taglessCore)`.

### The four questions the request asked to settle

**Cross-built for JS? Yes — `JVMPlatform, JSPlatform`.** `core` is
`crossProject(JVMPlatform, JSPlatform)` and `WeaveKnot.scala` lives in
`core/shared`, so it is in the published `natchez-tagless_sjs1_*` artifacts
today. A JVM-only module would silently drop it from the JS build. (`scalacache`
is the repo's JVM-only module and is JVM-only because scalacache-core is; that
does not apply here.)

**`CrossType.Pure`, not `Full`.** `core` is `Full` because it has genuinely
platform-specific sources — `core/jvm/src/test/.../TraceInitializationExample.scala`
and a JVM-only `dwolla-otel-natchez` test dependency. `WeaveKnot` has none, and
`Pure` is what all four newer modules use. Version-specific directories
(`src/main/scala-3` and friends) still work under `Pure`; `natchez-tagless-mtl`
is `Pure` and has one.

**Dependencies: cats-core and cats-tagless-core, compile scope; munit and
munit-scalacheck, test scope.** Read off the imports —
`WeaveKnot.scala` uses `cats.*` (`Eval`, `~>`), `cats.tagless.aop.*` (`Aspect`,
`Instrument`, `Instrumentation`) and `cats.tagless.syntax.all.*`; the spec adds
`cats.data.*`, `cats.syntax.all.*`, `cats.tagless.Trivial`, munit and
scalacheck. **No cats-mtl, no natchez, no circe, no log4cats, no cats-effect** —
which is the entire point of the milestone.

**`mimaPreviousArtifacts := Set.empty`,** following the precedent the four
newer modules set. A note rather than a decision: sbt-typelevel's own mechanism
for a newly-introduced artifact is `tlVersionIntroduced`, which is a per-project
setting (`TypelevelMimaPlugin.projectSettings` reads it) and which would keep
MiMa live for the module from its first release onward, whereas `Set.empty`
disables it permanently. All five new modules in this repo will need that
revisited before they are first published; it applies equally to all of them and
is not M15's decision to make alone.

---

## Decisions (proposed — ratify before starting, then final)

**D1 — The FQN does not change.** `com.dwolla.tagless.WeaveKnot` stays exactly
that. The file moves; the package clause does not. This is what makes the change
invisible to every consumer, and it is the acceptance criterion the plan checks
by disassembling the produced jar rather than by reading the source.

**D2 — `core` depends on the new module, so consumers still get the class
transitively.** `scalacache` and `natchez-tagless-mtl` both depend on `core` and
therefore inherit the dependency without their own build entries. Anyone
depending on `natchez-tagless` continues to compile and run unchanged.

**D3 — No MiMa filter.** Per the investigation above. If MiMa reports anything,
**report it**; do not add a filter to make it quiet. A filter added on the basis
of the *expected* problem, when the actual problem is different, is worse than
no filter.

**D4 — The spec moves with the class.** It is self-contained and it is
`WeaveKnot`'s only coverage. Leaving it in `core` would mean `core`'s tests
depend on a class `core` no longer defines — legal, since the dependency is
transitive, but it puts the test somewhere a reader will not look for it.

**D5 — No behaviour change and no API change.** Not a single line of
`WeaveKnot.scala`'s body is touched. The plan's acceptance criterion is
`git log --follow -p` showing the move as a pure rename.

**D6 — Nothing else moves.** `WeaveKnot` is the only natchez-free thing in
`core` that M16 is known to need. `TraceWeaveCapturingInputs(AndOutputs)`,
`ToTraceValue` and the tracing syntax are all natchez-typed and stay. Resist the
urge to sweep — a bigger move is a bigger MiMa surface, and this one is
provably empty.

---

## Evidence: demonstrated versus argued

**Demonstrated (2026-08-02):**

- `show coreJVM/mimaPreviousArtifacts` and `show coreJS/mimaPreviousArtifacts`
  each return seven `com.dwolla:natchez-tagless:0.2.x` entries — MiMa is live
  for this module, not a no-op.
- `coreJVM/mimaReportBinaryIssues` succeeds on `main` today, so the baseline is
  green and any post-move problem is attributable to the move.
- `git merge-base --is-ancestor` against all seven release tags: the commit that
  introduced `WeaveKnot` is an ancestor of none of them.
- All 21 previous artifacts fetched and scanned: zero `WeaveKnot` entries. A
  post-0.2.6 snapshot jar does contain them, proving the scan works.
- mima-core 1.1.4's `MiMaLib.collectProblems` and `Analyzer.analyze` read in
  full; `mimaCurrentClassfiles` for `coreJVM` confirmed to be the project's own
  classes directory.
- `grep -rIln "WeaveKnot"` and a separate grep for the spec's three fixtures:
  the three files listed above and nothing else.

**Argued from source, not compiled — each a task-level check in the plan:**

- That `WeaveKnot.scala` compiles in the new module with only cats-core and
  cats-tagless-core. Read off its imports, and strongly supported by the class's
  content, but not built.
- **That `import cats.*` still compiles on the 2.12 and 2.13 axes in the new
  module.** `WeaveKnot.scala` uses Scala 3 wildcard-import syntax, which on the
  2.x axes needs `-Xsource:3`. That flag is present for `coreJVM` on 2.13
  (confirmed by `show coreJVM/scalacOptions`) and comes from
  `TypelevelSettingsPlugin`, which triggers on all requirements and so applies
  to every project in the build — including a new one. Sound, but it is an
  inference about a plugin, on the axis (2.12) that has caused this repo the
  most grief, so the plan checks 2.12 explicitly and first.
- **That `WeaveKnotSpec` compiles with `-Wunused` in force.** It does not today:
  `core` has `doctestSettings`, which includes
  `Test / scalacOptions ~= { _.filterNot(_.contains("Wunused")) }`
  (`build.sbt:46-48`). The new module has no doctests and should not import
  those settings, so the spec will see `-Wunused` for the first time. Reading it,
  every wildcard import is used and nothing obviously discards a value — but this
  is exactly the sort of thing that is cheaper to check than to reason about. If
  a warning appears, **fix the spec**, do not import `doctestSettings`.
- That no downstream consumer outside this repository references `WeaveKnot` in
  a way the move breaks. Source and runtime compatibility are preserved by D1
  and D2, and the class was never published, so there is no consumer that could
  have one. Stated for completeness rather than because it is in doubt.

---

## Open questions for Brian

**Q1 — What should the module and artifact be called?** This one genuinely has
no precedent to read off, and the artifact name becomes permanent the moment it
is published, so I am not picking it silently.

The repo has two naming families:

- **`natchez-tagless`, `natchez-tagless-scalacache`, `natchez-tagless-mtl`** —
  natchez-coupled modules, prefixed accordingly.
- **`raise-aspect-core`, `raise-aspect-laws`, `raise-aspect-macros`** —
  natchez-free modules, named for the concept they contain, directory name equal
  to artifact name, no prefix.

The new module is natchez-free, so it belongs to the second family and must
**not** be called `natchez-tagless-knot` — that name would assert exactly the
dependency the milestone exists to remove.

| Candidate | For | Against |
| --- | --- | --- |
| **`tagless-core`** (recommended) | matches the type name exactly; dir = artifact, as the `raise-aspect-*` family does; says what is in it | slightly generic as a standalone Maven coordinate under `com.dwolla` |
| `tagless-knot` | names the ecosystem rather than the operation; reads better as a coordinate | `WeaveKnot` also does `instrument`, and "tagless knot" is vaguer about what it ties |
| `cats-tagless-knot` | unambiguous about the ecosystem | invites confusion with an upstream `org.typelevel` artifact that does not exist, and M9 contemplates upstreaming |

> **Ruled by Brian, 2026-08-02: the module is `tagless-core`.** The table below
> is preserved as the reasoning that was put to him. He chose the broader name
> over `weave-knot`, which answers the companion question too: this module is a
> general home for natchez-free `com.dwolla.tagless` utilities, not a
> single-type artifact. Anything added later that has no natchez dependency
> belongs here rather than in a new module.

**The plan is written for `tagless-core`** and every occurrence is in `build.sbt`
plus the docs, so changing it is a five-minute edit *before* the first publish
and a breaking change after. If you prefer another, say so at ratification.

**Q2 — is anything else destined for this module?** Answering "no" is fine and
is what the plan assumes (D6). Asking because the module's *name* depends on it:
if it is only ever going to hold `WeaveKnot`, `tagless-core` is right; if it is to
become the natchez-free `com.dwolla.tagless` utility module in general, then
`tagless-knot` is wrong too and something like `cats-tagless-extras` would be
the honest name. I have no evidence either way from the codebase, and M16's
research — which is happening separately — may answer it.

---

## Acceptance criteria

- [ ] A new cross-built (JVM + JS) module exists containing
      `com.dwolla.tagless.WeaveKnot` and its spec, depending only on cats-core
      and cats-tagless-core (plus munit/munit-scalacheck in test scope).
- [ ] The FQN is unchanged. Verified against the **built artifact**, not the
      source: `unzip -l` on the new module's jar shows
      `com/dwolla/tagless/WeaveKnot.class` and `WeaveKnot$.class`.
- [ ] `core` depends on the new module; `scalacache` and `natchez-tagless-mtl`
      get it transitively without their own build entries. Verified by
      compiling a reference to `WeaveKnot` from each.
- [ ] `+coreJVM/mimaReportBinaryIssues` and `+coreJS/mimaReportBinaryIssues`
      pass with **no `mimaBinaryIssueFilters` entry added**. If they do not,
      the milestone stops and reports.
- [ ] `git log --follow` shows the move as a rename; `git show` on the move
      commit shows **no** content change to `WeaveKnot.scala`.
- [ ] `sbt +test` green on 2.12.21, 2.13.18 and 3.3.8 for the new module,
      `core`, `scalacache` and `natchez-tagless-mtl`; all JS linkers green.
      `WeaveKnotSpec`'s four tests run in the new module and no longer in `core`,
      and `coreJVM`'s count drops by exactly four.
- [ ] Zero new warnings, verified locally with `-Xfatal-warnings` forced — in
      particular the new module's spec compiles clean under `-Wunused`, which
      `core`'s `doctestSettings` had been suppressing.
- [ ] `docs/plans/raise-aspect/22-milestone-M12-fused-derivation.md:429`'s
      "`WeaveKnot` (in `core`)" is corrected, and §3.1's module list in the
      overview names the new module.
- [ ] The artifact name is the one Brian ratified (Q1).

## Ground rules reminder

- **Never silence MiMa with a filter in this milestone.** The investigation says
  it will not speak; if it does, that is the most interesting thing that
  happened and it belongs in the report.
- Not a single line of `WeaveKnot.scala`'s body changes. If a task appears to
  require one, stop and report.
- Do not sweep other classes out of `core` (D6).
- Do not do M16's (otel4s) work — M15 prepares for it and stops there.
- Never use `--no-verify` or any other hook-bypass flag.
