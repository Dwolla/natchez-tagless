# Milestone M15 — extract `WeaveKnot` to its own module

## Status

**Planned, not started.** The implementation plan is
`29-milestone-M15-implementation-plan.md`. Ratify the Decisions section below —
and answer Q1, the artifact name — before Task 1 starts.

M13, M14 and M15 were planned together on 2026-08-02 and are independent of one
another. **M15 exists to prepare for M16 (otel4s), which is planned and is being
researched separately.** M16's design and plan are not in this round.

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
- Directly, rather than by inference: all **21** artifacts MiMa compares against
  (7 versions × `_2.13`, `_2.12`, `_sjs1_2.13`) were resolved from the
  repositories and scanned. Every one contains **zero** entries matching
  `WeaveKnot`. For contrast, a post-0.2.6 snapshot jar
  (`natchez-tagless_2.13-0.2.6-125-5ea4465-SNAPSHOT.jar`) does contain
  `com/dwolla/tagless/WeaveKnot.class` and `WeaveKnot$.class`, so the scan is
  capable of finding them.

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
