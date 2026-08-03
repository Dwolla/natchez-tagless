# Milestone M15 — implementation plan: extract `WeaveKnot` to its own module

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move `com.dwolla.tagless.WeaveKnot` out of the `natchez-tagless`
artifact into a module that depends only on cats and cats-tagless, so a future
otel4s module (M16, researched separately) can use it without pulling in
natchez — without changing the class's fully-qualified name and without breaking
any existing consumer.

**Architecture:** A new `crossProject(JVMPlatform, JSPlatform)` with
`CrossType.Pure`, holding `WeaveKnot.scala` and `WeaveKnotSpec.scala` at their
existing package. `core` gains `.dependsOn(…)` on it, so every current consumer
of `natchez-tagless` still receives the class transitively — source and runtime
compatibility are preserved by construction. **MiMa needs no filter**: the class
was added after `v0.2.6` and appears in none of the **34** artifacts MiMa
compares `core` against (7 × `_2.12`, `_sjs1_2.12`, `_2.13`, `_sjs1_2.13`,
3 × `_3`, `_sjs1_3`); **21** of those were also hand-scanned directly as
corroborating evidence. That is verified by this plan, not assumed.

> **Corrected 2026-08-02, final review.** This paragraph originally said "none
> of the 21 previous artifacts MiMa compares `core` against" — conflating the
> 21-jar manual scan with MiMa's actual, larger comparison set. It is this
> plan's headline Architecture paragraph, so it is exactly the text a future
> reader is most likely to trust; see the numbers above and the "Clarified"
> note under "The honest problem, and what the investigation found" in
> `28-milestone-M15-tagless-core-module.md` for the same correction.
>
> The same conflation survives in this document's pre-implementation
> contingency prose, checklist items and draft commit message, which are kept
> as the historical record rather than rewritten. **Wherever "21" appears
> below, read it as "21 hand-scanned published jars", not "everything MiMa
> checks".**

**Tech Stack:** sbt with sbt-typelevel 0.8.6 (`sbt-typelevel-ci-release`,
`-settings`, `-mergify`), MiMa via `TypelevelMimaPlugin`, Scala 2.12.21 /
2.13.18 / 3.3.8 cross-build, JVM and Scala.js, cats, cats-tagless, MUnit +
ScalaCheck.

Read `28-milestone-M15-tagless-core-module.md` first — it is the milestone
document this plan implements, it records the MiMa investigation, and its
**Q1 (the artifact name) must be answered by Brian before Task 1 starts.**

## Global Constraints

- **This plan is written for the artifact name `tagless-core`, directory
  `tagless-core/`, sbt project `taglessCore`.** If Brian ratified a different name
  at Q1, substitute it everywhere — in `build.sbt`, the aggregate list, the
  directory, and every command below. Do this **before** Task 1; renaming after
  publish is a breaking change.
- **`WeaveKnot.scala`'s body does not change.** Not one line. The acceptance
  check is `git show` on the move commit.
- **The package clause does not change.** `package com.dwolla.tagless`, and the
  file path within the source root stays `com/dwolla/tagless/WeaveKnot.scala`.
- **`core` has MiMa enabled against seven real published versions** (0.2.0–0.2.6,
  JVM and JS). It is **not** one of the four modules with
  `mimaPreviousArtifacts := Set.empty` (`build.sbt:108,139,159,172`).
- **Never add a `mimaBinaryIssueFilters` entry in this milestone.** The
  investigation in the milestone document establishes that none is needed —
  `WeaveKnot` was introduced after `v0.2.6` and is absent from all 34 artifacts
  MiMa compares `core` against, 21 of them also confirmed by direct hand scan.
  If MiMa reports a problem anyway, **stop and report it**: it means something
  about the situation is not what the investigation found, and a filter
  written for the expected problem would hide the real one.
- **The 2.12 axis is checked first, not last.** `WeaveKnot.scala` uses Scala 3
  wildcard-import syntax (`import cats.*`), which on the 2.x axes requires
  `-Xsource:3`. That comes from `TypelevelSettingsPlugin` and should apply to a
  new project automatically, but it is an inference about a plugin on the axis
  that has caused this repo the most trouble.
- **Zero new compiler warnings** — verify locally with `-Xfatal-warnings`
  forced. **CI does not enforce this**: `sbt-typelevel-settings` 0.8.6 defaults
  `tlFatalWarnings := false` and nothing in this repo overrides it.
  Note specifically that `WeaveKnotSpec` will see `-Wunused` for the first time:
  `core`'s `doctestSettings` filters it out of the `Test` scope
  (`build.sbt:46-48`) and the new module has no doctests and must not import
  those settings. **Fix the spec if it warns; do not import `doctestSettings`.**
- There is no `scripts/check` in this repo. Canonical verification is the
  per-module sbt invocations named in each task.
- **Never use `--no-verify`** or any other hook-bypass flag.

---

## File Structure

**New module:**

- `tagless-core/src/main/scala/com/dwolla/tagless/WeaveKnot.scala` — moved from
  `core/shared/src/main/scala/com/dwolla/tagless/WeaveKnot.scala`, byte for byte.
- `tagless-core/src/test/scala/com/dwolla/tagless/WeaveKnotSpec.scala` — moved
  from `core/shared/src/test/scala/com/dwolla/tagless/WeaveKnotSpec.scala`.

**Modified:**

- `build.sbt` — new `taglessCore` project; added to `tlCrossRootProject.aggregate`;
- `.github/workflows/ci.yml` — **regenerated, not hand-edited.** See the note
  below; this file was missing from the plan's original file list.
  `core` gains `.dependsOn(taglessCore)`.

**Deliberately unchanged:** every other source file in the repository.
`scalacache` and `natchez-tagless-mtl` need no build change — they depend on
`core`, which now depends on `taglessCore`.

---

> **Added 2026-08-02, after the M16 planning pass found it missing.** A new
> module changes the generated CI workflow, and CI will fail without it.
> `.github/workflows/ci.yml` enumerates **every module's target directory
> explicitly** — see the `Make target directories` and `Compress target
> directories` steps, which today list `scalacache/.jvm/target`,
> `raise-aspect-core/.jvm/target`, `natchez-tagless-mtl/.js/target` and the
> rest by name. Adding `tagless-core` changes what `sbt-typelevel` generates,
> and `ci.yml` itself runs `sbt githubWorkflowCheck`, which fails when the
> committed workflow has drifted from the generated one.
>
> So the build-wiring task must end with `sbt githubWorkflowGenerate` and
> commit the resulting `ci.yml` diff. **Do not hand-edit the workflow** —
> `githubWorkflowCheck` compares against generator output, so a hand-edit that
> looks right will still fail. Verify by running `sbt githubWorkflowCheck`
> locally and seeing it pass. The same applies to M16, whose plan already
> carries this step.

## Task 1: create the module and move the class

The move is genuinely atomic: the class cannot exist in two places, and the
build wiring, the source and the spec must land together or nothing compiles.
What can be split off is the *verification* — Task 2 is a gate with no
production code in it, following the precedent M12's Task 6 set.

**Files:**
- Modify: `build.sbt`
- Move: `core/shared/src/main/scala/com/dwolla/tagless/WeaveKnot.scala` → `tagless-core/src/main/scala/com/dwolla/tagless/WeaveKnot.scala`
- Move: `core/shared/src/test/scala/com/dwolla/tagless/WeaveKnotSpec.scala` → `tagless-core/src/test/scala/com/dwolla/tagless/WeaveKnotSpec.scala`

**Interfaces:**
- Consumes: nothing new. `WeaveKnot` already depends only on
  `cats.Eval`, `cats.~>`, `cats.tagless.aop.{Aspect, Instrument, Instrumentation}`
  and `cats.tagless.syntax.all._`.
- Produces (all pre-existing signatures, at an unchanged FQN):
  - `object com.dwolla.tagless.WeaveKnot`
  - `def instrument[Alg[_[_]], F[_]](constructor: Eval[Alg[F]] => Alg[F], transformation: Instrumentation[F, *] ~> F)(implicit I: Instrument[Alg]): Alg[F]`
  - `def weave[Alg[_[_]], F[_], Dom[_], Cod[_]](constructor: Eval[Alg[F]] => Alg[F], transformation: Aspect.Weave[F, Dom, Cod, *] ~> F)(implicit A: Aspect[Alg, Dom, Cod]): Alg[F]`
  - `def apply[Alg[_[_]], F[_]](constructor: Eval[Alg[F]] => Alg[F])(transform: Alg[F] => Alg[F]): Alg[F]`
  - new sbt projects `taglessCoreJVM`, `taglessCoreJS`; artifact `com.dwolla:tagless-core`

- [ ] **Step 1: Record the "before" state, so the move can be proved to be a move**

```bash
sbt -batch "coreJVM/test" 2>&1 | tail -5
git log --oneline -1 -- core/shared/src/main/scala/com/dwolla/tagless/WeaveKnot.scala
shasum -a 256 core/shared/src/main/scala/com/dwolla/tagless/WeaveKnot.scala \
              core/shared/src/test/scala/com/dwolla/tagless/WeaveKnotSpec.scala
```

Write the `coreJVM` test count and both checksums into the task report. Step 6
compares against them, and Task 2 Step 1 re-checks the hashes.

- [ ] **Step 2: Move the two files**

```bash
mkdir -p tagless-core/src/main/scala/com/dwolla/tagless tagless-core/src/test/scala/com/dwolla/tagless
git mv core/shared/src/main/scala/com/dwolla/tagless/WeaveKnot.scala \
       tagless-core/src/main/scala/com/dwolla/tagless/WeaveKnot.scala
git mv core/shared/src/test/scala/com/dwolla/tagless/WeaveKnotSpec.scala \
       tagless-core/src/test/scala/com/dwolla/tagless/WeaveKnotSpec.scala
```

Note the source-root shape changes from `core`'s `shared/src/...` to the new
module's `src/...` — that is `CrossType.Full` versus `CrossType.Pure`, not a
package change. The path *below* the source root is identical, which is what
matters.

Do **not** edit either file. `git status` should show two renames and nothing
else.

- [ ] **Step 3: Prove the next step will do real work**

> **Corrected 2026-08-02, during M15's Task 3.** This step originally read "Run
> the build to verify it fails" and expected `coreJVM/Test/compile` to fail. It
> cannot fail, and the step contradicted itself one sentence later: "the files
> are simply invisible to the build" is the accurate half. Nothing in the
> repository references `WeaveKnot` outside its own two files (the milestone
> document's "What references `WeaveKnot`" section says exactly this), so
> removing them from `core`'s source roots cannot break `core`'s compilation.
> Task 1 hit the success and correctly treated it as correct behaviour rather
> than stale output — but a reader who trusts the original wording would go
> hunting for a phantom problem. The step's *intent* — make sure Step 4 is doing
> real work rather than riding on stale output — is preserved below, satisfied
> by a `clean` plus jar inspection instead, which is what Task 1 actually ran.

```bash
sbt -batch "coreJVM/clean" "coreJVM/Test/compile"
```

Expected: **success**, from a clean output directory. `core` no longer has the
sources and the new directory is not a project yet, so the files are simply
invisible to the build — and no `core` source refers to them, so nothing breaks.
Because a green compile therefore proves nothing on its own, confirm the class
actually left `core`'s output by packaging and looking inside the jars:

```bash
sbt -batch "coreJVM/package" "coreJS/package"
for j in core/*/target/scala-*/natchez-tagless*.jar; do
  echo "== $j"; unzip -l "$j" | grep -ci "WeaveKnot"
done
```

Expected: `0` for every jar, from output that provably postdates the move.

- [ ] **Step 4: Add the module to `build.sbt`**

Insert before the `core` definition, so the file reads in dependency order:

> **Corrected 2026-08-02, after the milestone landed.** This step originally
> said "insert after the `core` definition and before `scalacache`" — backwards
> from what "dependency order" actually requires: `core` depends on
> `taglessCore`, so the dependency belongs *before* its dependent, not after
> it. `c6e0a73` landed `taglessCore` at `build.sbt:63`, before `core`'s own
> definition, and that placement is the correct one; this sentence was stale,
> not the code.

> **Corrected 2026-08-02, during M15's Task 3.** The comment below, as
> originally drafted here and as landed in `c6e0a73`, ended with "the class
> remains available … to everything that already had it" — and nothing had it.
> `WeaveKnot` was never published (it postdates `v0.2.6`) and has no caller in
> this repository, so the `core → tagless-core` edge preserves a
> *forward-looking* design property (D1: the FQN does not change), not a live
> compatibility requirement. The wording shown here is the corrected one, which
> is what `build.sbt` now carries.

```scala
// WeaveKnot is written against cats and cats-tagless only — it mentions natchez
// nowhere — but it lived in `core`, whose artifact is `natchez-tagless`. Extracted
// here so a backend module that isn't natchez (otel4s, M16) can use it without
// taking on natchez, circe and log4cats to get it. `core` still depends on this
// module, so `com.dwolla.tagless.WeaveKnot` keeps its fully-qualified name and
// stays reachable from `natchez-tagless`. That edge is a design choice, not a
// compatibility rescue: WeaveKnot was added after v0.2.6, appears in none of the
// published artifacts, and has no caller in this repository — so nothing in the
// test suite would notice if the edge were dropped. M16's otel4s module will be
// its first real user. See
// docs/plans/raise-aspect/28-milestone-M15-tagless-core-module.md.
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
    // A brand-new artifact has no previous versions to be compatible with.
    // Matches the four other unpublished modules in this build; note that
    // sbt-typelevel's `tlVersionIntroduced` is the mechanism that would keep
    // MiMa live from this module's first release onward, and all five modules
    // will need that decision made before they are first published.
    mimaPreviousArtifacts := Set.empty,
  )
```

Add it to the aggregate:

```scala
lazy val `natchez-tagless-root` = tlCrossRootProject.aggregate(
  taglessCore,
  core,
  scalacache,
  raiseAspectCore,
  raiseAspectLaws,
  raiseAspectMacros,
  natchezTaglessMtl,
)
```

and make `core` depend on it — `core` currently ends with
`.dependsOn(buildInfoForTests % Test)`:

```scala
  .dependsOn(taglessCore, buildInfoForTests % Test)
```

- [ ] **Step 5: Build the new module on 2.12 first**

The riskiest axis, checked first: `WeaveKnot.scala` uses `import cats.*`, which
on 2.x needs `-Xsource:3` from `TypelevelSettingsPlugin`.

```bash
sbt "++2.12.21 taglessCoreJVM/test"
```

Expected: PASS, 4 tests.

If it fails with `Not found: value cats` or a wildcard-import parse error,
`-Xsource:3` is missing on this axis for this project. **Do not add
`-Xsource:3` by hand and do not rewrite the imports** — find out why the plugin
did not apply (`sbt "++2.12.21 show taglessCoreJVM/scalacOptions"`) and report,
because the same gap would affect any future module.

- [ ] **Step 6: Build everything, and account for the moved tests**

```bash
sbt "+taglessCoreJVM/test" "+coreJVM/test" "+scalacacheJVM/test" "+natchezTaglessMtlJVM/test"
```

Expected: green on 2.12.21, 2.13.18 and 3.3.8.

- `taglessCoreJVM`: **4 tests** (`WeaveKnotSpec`'s four).
- `coreJVM`: exactly **four fewer** than Step 1 recorded.
- `scalacacheJVM`, `natchezTaglessMtlJVM`: **unchanged** — they get `WeaveKnot`
  transitively and never referenced it anyway.

Any other movement in the counts is a finding.

- [ ] **Step 7: Verify the JS side**

```bash
sbt "+taglessCoreJS/Test/scalaJSLinkerResult" "+coreJS/Test/scalaJSLinkerResult"
```

Expected: green on all three versions. This is the step that catches a
`JVMPlatform`-only mistake in Step 4 — `WeaveKnot` is in the published
`natchez-tagless_sjs1_*` artifacts today, and dropping it from the JS build
would be a silent regression that no JVM test can see.

- [ ] **Step 8: Verify zero warnings, with `-Wunused` now in force on the spec**

```bash
sbt "set taglessCore.jvm/scalacOptions += \"-Xfatal-warnings\"" "+taglessCoreJVM/test"
```

Expected: PASS. `WeaveKnotSpec` compiled under `core`'s `doctestSettings`, which
filter `-Wunused` out of the `Test` scope; the new module does not, so this is
the first time those warnings apply to it. If any appear, **fix the spec** —
remove the unused import, name the discarded value — and do **not** add
`doctestSettings` to the new module, which has no doctests and would gain three
unrelated test dependencies.

- [ ] **Step 9: Commit**

```bash
git add build.sbt tagless-core/ core/
git commit -m "refactor: extract WeaveKnot into its own cats-tagless-only module

WeaveKnot is written against cats and cats-tagless and mentions natchez nowhere,
but it shipped inside the natchez-tagless artifact. It now lives in tagless-core,
which core depends on, so the class stays available at the same fully-qualified
name to everything that already had it — while a backend module that isn't
natchez can depend on it directly.

No line of WeaveKnot.scala changes; git records both files as renames."
```

---

## Task 2: the compatibility gate

**No production code.** The milestone's entire risk is that a move which looks
invisible is not, and "the tests pass" does not measure that: the tests
recompile from source in the same build, which is precisely the situation in
which a source-compatible-but-binary-incompatible change hides.

Run this task against the **built artifacts**, not the sources.

**Files:** none modified. Findings go in the task report.

**Interfaces:**
- Consumes: Task 1. Produces no API.

- [ ] **Step 1: Prove the move was a move**

```bash
git log --follow --oneline -- tagless-core/src/main/scala/com/dwolla/tagless/WeaveKnot.scala | head -5
git show --stat HEAD -- tagless-core/src/main/scala/com/dwolla/tagless/WeaveKnot.scala
shasum -a 256 tagless-core/src/main/scala/com/dwolla/tagless/WeaveKnot.scala \
              tagless-core/src/test/scala/com/dwolla/tagless/WeaveKnotSpec.scala
```

Expected: `--follow` reaches back past the move to the original commit
`2eefa10`; `--stat` shows a rename with **0 insertions, 0 deletions** for
`WeaveKnot.scala`; both checksums equal what Task 1 Step 1 recorded.

- [ ] **Step 2: Prove the FQN is unchanged, in the bytecode**

Source can lie about this — a package clause is easy to read and easy to
misread. Ask the jar.

```bash
sbt "+taglessCoreJVM/package" "+taglessCoreJS/package"
for j in tagless-core/.jvm/target/scala-*/tagless-core_*.jar; do
  echo "== $j"; unzip -l "$j" | grep -i "com/dwolla/tagless"
done
```

Expected, for each Scala version: exactly
`com/dwolla/tagless/WeaveKnot.class` and `com/dwolla/tagless/WeaveKnot$.class`.
These are the same two entries a post-0.2.6 snapshot of `natchez-tagless`
contains, which is what "the FQN did not change" means concretely.

Also confirm `core`'s jar no longer has them, so the two are not both shipping
the class:

```bash
for j in core/jvm/target/scala-*/natchez-tagless_*.jar; do
  echo "== $j"; unzip -l "$j" | grep -ci "WeaveKnot"
done
```

Expected: `0` for each.

- [ ] **Step 3: Run MiMa — the whole point of this gate**

```bash
sbt "show coreJVM/mimaPreviousArtifacts"
sbt "+coreJVM/mimaReportBinaryIssues" "+coreJS/mimaReportBinaryIssues"
sbt "+scalacacheJVM/mimaReportBinaryIssues"
```

Expected: `mimaPreviousArtifacts` is a **non-empty** set of seven
`com.dwolla:natchez-tagless:0.2.x` entries — a green MiMa run against an empty
set proves nothing, so check this first. Then `[success]`, with **no problems
reported and no `mimaBinaryIssueFilters` entry anywhere in `build.sbt`**.

`scalacache` is included because it is the other MiMa-enabled module and it
depends on `core`; it should be entirely unaffected, and confirming that is
cheap.

**If MiMa reports `MissingClassProblem` for `com.dwolla.tagless.WeaveKnot`:
stop, and report.** The milestone document's investigation says this cannot
happen — the class is in none of the 21 previous artifacts — so it happening
means one of those findings is wrong. Do not add a filter. Re-run the artifact
scan from `28-milestone-M15-tagless-core-module.md`'s Evidence section and find
out which claim failed. (For the record, the *general* rule is that such a move
**would** be reported for a published class: mima-core builds the "new" package
from the new jar alone and uses the classpath only to resolve referenced types.
So the surprise here would be that `WeaveKnot` turned out to have been published
after all.)

> **Resolved 2026-08-02, during M15's Task 3 — this branch did not fire, and
> could not have.** MiMa ran clean on `coreJVM`, `coreJS` and `scalacacheJVM`,
> all three Scala versions, and no filter was added. The contingency was already
> a dead branch when it was written: `Analyzer.analyze` iterates the *old*
> package's classes, so a class absent from every previous artifact has no way
> to be reported missing from the new one — and two independent lines of
> evidence say `WeaveKnot` is absent from every previous artifact. The manual
> jar scan covered 21 jars; MiMa's own comparison set for `core` is 34, so the
> scan's 13-jar shortfall is 7 × `_sjs1_2.12` plus 6 × Scala 3, and MiMa covers
> all of them. See the 34/21/13 breakdown in
> `28-milestone-M15-tagless-core-module.md`. Kept for the general rule in its
> parenthetical, which is
> still the thing worth remembering the next time a **published** class moves.

- [ ] **Step 4: Prove transitive availability from every downstream module**

Source compatibility for existing consumers rests entirely on `core`'s new
dependency being transitive. Check it from each dependent, rather than assuming
sbt does what it says.

```bash
sbt "coreJVM/console" <<'EOF'
com.dwolla.tagless.WeaveKnot
:quit
EOF
```

and the same for `scalacacheJVM/console` and `natchezTaglessMtlJVM/console`.
Expected in each: the object resolves, no import needed beyond the FQN.

If `console` is awkward to drive non-interactively, the equivalent is a
throwaway one-line reference added to each module's test sources, compiled, and
reverted — but say in the report which method you used, and make sure the
throwaway really was reverted (`git status` clean).

- [ ] **Step 5: Confirm no other build file needed changing**

```bash
git diff HEAD~1 --stat
grep -n "dependsOn" build.sbt
```

Expected: the previous commit touched `build.sbt`, `tagless-core/` and `core/`
only. `scalacache` still reads `.dependsOn(core)` and `natchezTaglessMtl` still
reads `.dependsOn(core % "compile->compile;test->test", raiseAspectCore, raiseAspectMacros)`
— neither gains an entry. If either needed one, the dependency is not
transitive and Step 4 should have caught it; report the discrepancy.

- [ ] **Step 6: Full cross-build**

```bash
sbt "+test"
sbt "+taglessCoreJS/Test/scalaJSLinkerResult" "+coreJS/Test/scalaJSLinkerResult" \
    "+raiseAspectCoreJS/Test/scalaJSLinkerResult" "+raiseAspectLawsJS/Test/scalaJSLinkerResult" \
    "+raiseAspectMacrosJS/Test/scalaJSLinkerResult" "+natchezTaglessMtlJS/Test/scalaJSLinkerResult"
```

Expected: every module green on 2.12.21, 2.13.18 and 3.3.8; all five JS linkers
green. (`sbt +test` unqualified also attempts the JS test-execution projects,
which abort for lack of a local Node install — the same pre-existing environment
gap every prior milestone has hit. The JVM-scoped runs in Task 1 Step 6 are what
actually exercise the suites.)

- [ ] **Step 7: Write the gate's conclusion into the task report**

The artifact a future reader needs is not "it passed" but *what was measured*.
Record: the two checksums, the jar-entry listings for both `tagless-core` and
`core`, the `mimaPreviousArtifacts` set size, the MiMa output for `coreJVM`,
`coreJS` and `scalacacheJVM`, and the three transitive-availability checks.
There is no commit in this step — the gate produces evidence, not code.

---

## Task 3: documentation

**No production code.** Two documents actively state things this milestone makes
false.

**Files:**
- Modify: `docs/plans/raise-aspect/01-overview-design-and-laws.md` §3.1 and §5
- Modify: `docs/plans/raise-aspect/22-milestone-M12-fused-derivation.md` (one table row)
- Modify: `docs/plans/raise-aspect/28-milestone-M15-tagless-core-module.md` (status only)

**Interfaces:**
- Consumes: Tasks 1 and 2. No new API.

- [ ] **Step 1: Correct the M12 table row**

`22-milestone-M12-fused-derivation.md:429` currently reads:

```
| `WeaveKnot` (in `core`) | untouched — already fused-shaped (`Alg[F] => Alg[F]`). |
```

M12's statement was true when written and stays true in substance; only the
location changed. Amend rather than rewrite, in the style M10 and M12 used:

```
| `WeaveKnot` (in `core` at the time; moved to its own module by M15) | untouched — already fused-shaped (`Alg[F] => Alg[F]`). |
```

- [ ] **Step 2: Amend the overview's module list**

`01-overview-design-and-laws.md` §3.1's code block lists the four raise-aspect
modules and does not mention `core` or the new one. Add an amendment note below
it, dated, matching the M10/M11/M12 notes already there:

```markdown
> **Amended 2026-08-02 by M15.** `com.dwolla.tagless.WeaveKnot` moved out of
> `core` (artifact `natchez-tagless`) into its own module, `tagless-core`, which
> depends only on cats-core and cats-tagless-core. Its fully-qualified name is
> unchanged and `core` depends on the new module, so every existing consumer
> still gets it transitively; MiMa on `core` is clean without a filter, because
> the class was introduced after `v0.2.6` and appears in none of the previously
> published artifacts. The extraction exists so M16's otel4s module can use
> `WeaveKnot` without depending on natchez. See
> `28-milestone-M15-tagless-core-module.md`.
```

Substitute the ratified artifact name if it is not `tagless-core`.

- [ ] **Step 3: Reconcile the milestone map with what actually happened**

`01-overview-design-and-laws.md` §5 **already carries** the
"Fourth round (2026-08-02)" block naming M13, M14 and M15, including a paragraph
beginning "**Why M15 exists, and what the investigation found.**" Do not re-add
it — check it.

That paragraph makes two falsifiable claims:

1. `WeaveKnot` "appears in **none** of the 21 artifacts MiMa compares against",
   so no filter is needed. Task 2 Step 3 either confirmed this or found
   otherwise. If otherwise, this paragraph is the **first** thing to correct,
   because it is the version of the finding that future readers will hit.
2. The general rule — mima builds its "new" package from the new jar alone, so a
   published class moved to a dependency *does* get reported. Nothing in this
   milestone tests that directly (no published class moved), so leave it as
   stated and sourced.

Also confirm the block's closing sentence about M16 survives intact:

```markdown
**M16 (otel4s) is planned and is being researched separately.** M15 exists to
prepare for it; M16's design and plan are not in this round and are not any of
these three milestones' work.
```

- [ ] **Step 4: Update M15's status section**

In `28-milestone-M15-tagless-core-module.md`, replace "Planned, not started" with
the outcome, following the shape M6, M7 and M12 use. This one has a specific
obligation the others do not: **record what MiMa actually did**, with the
`mimaPreviousArtifacts` set size beside it, so the next person to move a class
out of `core` learns from a measurement rather than repeating the
investigation. Record the answer Brian gave to Q1 and Q2.

- [ ] **Step 5: Final verification**

```bash
sbt "+test" "+coreJVM/mimaReportBinaryIssues" "+coreJS/mimaReportBinaryIssues" "+coreJVM/doc"
grep -rn "mimaBinaryIssueFilters" build.sbt
```

Expected: all green; the `grep` returns **nothing**.

- [ ] **Step 6: Commit**

```bash
git add docs/plans/raise-aspect/
git commit -m "docs: record the WeaveKnot extraction and why MiMa stayed quiet

WeaveKnot was added after v0.2.6 and appears in none of the 21 artifacts MiMa
compares core against, so moving it out of that jar needs no binary-issue
filter. The general rule is the opposite — mima builds the 'new' package from
the new jar alone, so a published class moved to a dependency does get reported
— and that is written down here for the next time."
```

---

## Acceptance criteria

- [ ] A `crossProject(JVMPlatform, JSPlatform)` / `CrossType.Pure` module exists
      at `tagless-core/` (or the ratified name), containing `WeaveKnot.scala` and
      `WeaveKnotSpec.scala`, depending only on cats-core and cats-tagless-core
      plus munit/munit-scalacheck in test scope.
- [ ] `git show` on the move commit: `WeaveKnot.scala` is a rename with **zero**
      content change; checksums match the pre-move ones.
- [ ] `unzip -l` on the new module's jars shows
      `com/dwolla/tagless/WeaveKnot.class` and `WeaveKnot$.class`;
      `core`'s jars show **zero** `WeaveKnot` entries.
- [ ] `show coreJVM/mimaPreviousArtifacts` is non-empty (seven entries), and
      `+coreJVM/mimaReportBinaryIssues`, `+coreJS/mimaReportBinaryIssues` and
      `+scalacacheJVM/mimaReportBinaryIssues` all pass with
      `grep -rn "mimaBinaryIssueFilters" build.sbt` returning **nothing**.
- [ ] `com.dwolla.tagless.WeaveKnot` resolves from `coreJVM`, `scalacacheJVM`
      and `natchezTaglessMtlJVM` with no build change to the latter two.
- [ ] `sbt +test` green on 2.12.21, 2.13.18 and 3.3.8; all five JS linkers
      green; `taglessCoreJVM` runs 4 tests and `coreJVM`'s count drops by exactly
      4; `scalacacheJVM` and `natchezTaglessMtlJVM` counts unchanged.
- [ ] Zero new warnings under forced `-Xfatal-warnings`, including
      `WeaveKnotSpec` under `-Wunused`, which `core`'s `doctestSettings` had
      been suppressing — and `doctestSettings` was **not** added to the new
      module.
- [ ] `22-milestone-M12-fused-derivation.md`'s `WeaveKnot` row and the
      overview's §3.1 and §5 are corrected.

## Ground rules reminder

- **Never add a `mimaBinaryIssueFilters` entry in this milestone.** If MiMa
  speaks, that is the finding and it goes in the report.
- Not one line of `WeaveKnot.scala`'s body changes.
- Do not sweep other classes out of `core` — `WeaveKnot` is the only one M16 is
  known to need, and a bigger move is a bigger MiMa surface.
- Do not do M16's (otel4s) work. M15 prepares for it and stops there.
- Do not do M13's or M14's work here.
- Never use `--no-verify` or any other hook-bypass flag.
