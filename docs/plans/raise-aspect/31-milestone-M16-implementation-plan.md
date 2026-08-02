# Milestone M16 — implementation plan: the otel4s module

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a new cross-built module that provides otel4s versions of
`TraceInstrumentation`, `TraceWeaveCapturingInputs` and
`TraceWeaveCapturingInputsAndOutputs`, plus the syntax that makes them one
method call on an algebra, without depending on natchez and without changing any
existing module.

**Architecture:** A project-owned `ToAttributes[-A]` type class supplies the
`Dom`/`Cod` that `natchez.TraceableValue` supplies on the natchez side — otel4s
ships nothing of the right kind. The interpreters use otel4s's **sealed,
macro-free** span path (`spanBuilder → modifyState → build → surround`/`use`,
plus `span.backend.addAttributes`) throughout, including in scaladoc, because
this repo compiles doctests on both Scala axes. Input attributes attach to the
*builder*, so `TracerWeaveCapturingInputs` needs no `Apply[F]` and the
attributes exist before the sampler runs.

**Tech Stack:** Scala **2.13.18 and 3.3.8** (not 2.12), JVM and Scala.js;
otel4s 1.0.1 (`otel4s-core-trace`), cats, cats-tagless, MUnit; sbt 1.12.13 with
sbt-typelevel 0.8.6 and sbt-doctest 0.13.0.

**Read first, in this order:**

1. `30-milestone-M16-otel4s-module.md` — the milestone document this plan
   implements. **Its Decisions section must be ratified, and Q1/Q2/Q3 answered,
   before Task 1 starts.**
2. `.superpowers/sdd/m16-otel4s-research.md` — the source-verified research.
3. `reference/upstream/otel4s/` — the vendored otel4s v1.0.1 sources every
   citation points at.

> **Do not read `/Users/bholt/Developer/github/otel4s`.** There is a clone of
> otel4s next door on this machine, and it is pinned at **v0.9.0-73-gae4ae7a0
> (September 2024)** — nearly two years and one major version stale. Every API
> in this plan is from **v1.0.1**. Use `reference/upstream/otel4s/`.

## Global Constraints

- **Cross-builds 2.13.18 and 3.3.8 only — not 2.12**, JVM and JS. otel4s has
  never published a `_2.12` artifact at any version (`otel4s/build.sbt:35`;
  Maven Central 404). `crossScalaVersions` is narrowed **on this project
  alone**; every other module keeps 2.12. CLAUDE.md requires calling out a 2.12
  drop, and the milestone document does: this drops 2.12 for one new module, not
  for the library.
- **The 2.12 `val _ = …` double-assignment hazard does not apply here** — there
  is no 2.12 axis in this module. Do not copy 2.12 warning workarounds into it.
  The idiom that *does* apply is unchanged: a bare statement in statement
  position is fine; a non-`Unit` final expression in a `Unit`-returning method
  warns fatally, so bind it with `val _ = …`.
- **`otel4s-core-trace` is the only new direct dependency in the build, and only
  this module gets it.** No other module gains a dependency of any kind. Do not
  add `otel4s-core`, `otel4s-oteljava-*` or `otel4s-sdk-*` at compile scope —
  those are backends and belong in applications. (The one test-scope exception is
  Q2's `otel4s-oteljava-trace-testkit`, JVM only.)
- **Never call an otel4s macro or `inline` method**, in main sources *or* in
  scaladoc. `Tracer.span`, `Span.addAttribute(s)`, `Span.recordException`,
  `Span.setStatus`, `SpanBuilder.addAttribute(s)`, `withFinalizationStrategy`,
  `withSpanKind`, `withStartTimestamp` and `withParent` are all macros on Scala 2
  and `inline` with union-typed varargs on Scala 3. Task 6 greps for them.
- **Doctests compile and run.** `doctestOnlyCodeBlocksMode := true`
  (`build.sbt:45`) means every `{{{ }}}` block in this module is a compiled,
  executed test on both 2.13 and 3. Examples must work, not merely look right.
- **Zero new compiler warnings**, verified locally by forcing
  `-Xfatal-warnings`. **CI does not enforce this**: `sbt-typelevel-settings`
  0.8.6 defaults `tlFatalWarnings := false` and nothing in this repo overrides
  it. Forcing it locally is stricter than the gate, not catching up to one.
- **`RaiseAspect` and the `raise-aspect-*` family are out of scope.** No
  `OnRaise`, no `RaiseAspect`, no otel4s `RaiseTraceWeaveOps`, no otel4s
  equivalent of `natchez-tagless-mtl`. If a task starts reaching there, **stop
  and report** — that is a later milestone and nobody has asked for it.
- **M16 stacks on M15.** `com.dwolla.tagless.WeaveKnot` must already be in the
  `tagless-core` module. If it is still in `core`, stop: M16 cannot depend on it
  without depending on natchez, which is the whole point.
- **No changes to `core`, `scalacache`, `natchez-tagless-mtl`, `tagless-core` or
  the three `raise-aspect-*` modules.** M16 is additive. If it appears to
  require a change to an existing module, stop and report.
- There is no `scripts/check` in this repo. Canonical verification is the
  per-module sbt invocations named in each task.
- **Never use `--no-verify`** or any other hook-bypass flag.

**Names, fixed by Q1 and load-bearing for every command below.** This plan is
written for artifact **`otel4s-tagless`**, directory `otel4s-tagless/`, sbt
projects `otel4sTaglessJVM` / `otel4sTaglessJS`, package
**`com.dwolla.tracing.otel4s`**. If Brian ratified a different artifact name at
Q1, substitute it everywhere **before Task 1** — renaming after a publish is a
breaking change. The package name is not part of Q1; it follows
`natchez-tagless-mtl`'s `com.dwolla.tracing.mtl` precedent.

---

## File Structure

**New module, main:**

```
otel4s-tagless/src/main/scala/com/dwolla/tracing/otel4s/
  ToAttributes.scala
  TracerInstrumentation.scala
  TracerWeaveCapturingInputs.scala
  TracerWeaveCapturingInputsAndOutputs.scala
  syntax/WeaveAttributesOps.scala
  syntax/TracerWeaveOps.scala
  syntax/InstrumentableAndTraceableOps.scala
  syntax/package.scala
otel4s-tagless/src/main/scala/README.md
```

**New module, test (cross-platform):**

```
otel4s-tagless/src/test/scala/com/dwolla/tracing/otel4s/
  ToAttributesSpec.scala
  ToAttributesResolutionSpec.scala
  FooFixture.scala            -- hand-rolled Aspect + Instrument, no macros
  WeaveAttributesOpsSpec.scala
  TracerTransparencySpec.scala
```

**New module, test (JVM only, via `Test / unmanagedSourceDirectories`):**

```
otel4s-tagless/src/test/scala-jvm/com/dwolla/tracing/otel4s/
  SpanContentSpec.scala
```

**Modified:** `build.sbt`, `.github/workflows/ci.yml` (regenerated),
`docs/plans/raise-aspect/01-overview-design-and-laws.md`,
`docs/plans/raise-aspect/28-milestone-M15-tagless-core-module.md`,
`docs/plans/raise-aspect/30-milestone-M16-otel4s-module.md` (status only).

**Deliberately unchanged:** every source file outside `otel4s-tagless/`.

---

## Task 1: the module, `ToAttributes`, and its instances

The module cannot exist without a build entry and the build entry is pointless
without something to compile, so they land together. `ToAttributes` is the right
first content: it is the milestone's load-bearing decision, it has no otel4s
*tracing* surface at all (only the attribute model), and every later task
depends on it.

**Files:**
- Modify: `build.sbt`
- Modify: `.github/workflows/ci.yml` (regenerated, not hand-edited)
- Create: `otel4s-tagless/src/main/scala/com/dwolla/tracing/otel4s/ToAttributes.scala`
- Create: `otel4s-tagless/src/test/scala/com/dwolla/tracing/otel4s/ToAttributesSpec.scala`
- Create: `otel4s-tagless/src/test/scala/com/dwolla/tracing/otel4s/ToAttributesResolutionSpec.scala`

**Interfaces:**
- Consumes: `org.typelevel.otel4s.{Attribute, Attributes}` and
  `org.typelevel.otel4s.AttributeKey.KeySelect` from `otel4s-core-trace`'s
  transitive `otel4s-core-common`; `cats.Show`.
- Produces:
  - `trait ToAttributes[-A] { def toAttributes(name: String, value: A): Attributes }`
  - `object ToAttributes extends LowPriorityToAttributesInstances { def apply[A](implicit ev: ToAttributes[A]): ToAttributes[A]; def instance[A](f: (String, A) => Attributes): ToAttributes[A]; def primitive[A: AttributeKey.KeySelect]: ToAttributes[A] }`
  - instances for `String`, `Boolean`, `Long`, `Double`, `Int`, `Short`,
    `Byte`, `Float`, `Unit`, `Option[A: ToAttributes]`, `Seq[String]`,
    `Seq[Boolean]`, `Seq[Long]`, `Seq[Double]`, `Seq[Int]`, `Seq[Short]`,
    `Seq[Byte]`
  - `trait LowPriorityToAttributesInstances { implicit def showToAttributes[A: Show]: ToAttributes[A] }`
  - new sbt projects `otel4sTaglessJVM`, `otel4sTaglessJS`; artifact
    `com.dwolla:otel4s-tagless`

- [ ] **Step 1: Write the failing test**

Create `otel4s-tagless/src/test/scala/com/dwolla/tracing/otel4s/ToAttributesSpec.scala`.
Cover the primitives, the three widenings, and each of D3's three divergences
from `ToTraceValue` explicitly — those are the assertions a future reader will
want to find when they wonder whether the divergence was deliberate:

```scala
package com.dwolla.tracing.otel4s

import munit.FunSuite
import org.typelevel.otel4s.{Attribute, Attributes}

class ToAttributesSpec extends FunSuite {
  private def attrs[A](name: String, a: A)(implicit ev: ToAttributes[A]): Attributes =
    ev.toAttributes(name, a)

  test("String, Boolean, Long and Double record at their native key types") {
    assertEquals(attrs("k", "v"), Attributes(Attribute("k", "v")))
    assertEquals(attrs("k", true), Attributes(Attribute("k", true)))
    assertEquals(attrs("k", 7L), Attributes(Attribute("k", 7L)))
    assertEquals(attrs("k", 1.5d), Attributes(Attribute("k", 1.5d)))
  }

  test("Int, Short and Byte widen to Long — otel4s has no Int key type") {
    assertEquals(attrs("k", 7), Attributes(Attribute("k", 7L)))
    assertEquals(attrs("k", 7.toShort), Attributes(Attribute("k", 7L)))
    assertEquals(attrs("k", 7.toByte), Attributes(Attribute("k", 7L)))
  }

  // D3. otel4s has no Float key type and no Attribute.From for one, so the
  // widening is to the exact Double nearest the Float. It prints with extra
  // digits, and that is deliberate: the alternative (_.toString.toDouble)
  // prints prettily by changing the value.
  test("Float widens to the exact Double it denotes") {
    assertEquals(attrs("k", 0.1f), Attributes(Attribute("k", 0.1f.toDouble)))
    assert(attrs("k", 0.1f) != Attributes(Attribute("k", 0.1d)))
  }

  // D3. natchez records "()"; an absent attribute is better.
  test("Unit records nothing at all") {
    assertEquals(attrs("k", ()), Attributes.empty)
  }

  // D3. natchez records the string "None".
  test("Option delegates for Some and records nothing for None") {
    assertEquals(attrs("k", Option("v")), Attributes(Attribute("k", "v")))
    assertEquals(attrs("k", Option.empty[String]), Attributes.empty)
  }

  test("the four native Seq key types record as sequences") {
    assertEquals(attrs("k", Seq("a", "b")), Attributes(Attribute("k", Seq("a", "b"))))
    assertEquals(attrs("k", Seq(1L, 2L)), Attributes(Attribute("k", Seq(1L, 2L))))
  }

  test("Seq[Int] widens elementwise to Seq[Long]") {
    assertEquals(attrs("k", Seq(1, 2)), Attributes(Attribute("k", Seq(1L, 2L))))
  }

  test("a type with only a Show instance falls back to its rendering") {
    final case class Money(cents: Long)
    implicit val show: cats.Show[Money] = cats.Show.show(m => s"$$${m.cents}")

    assertEquals(attrs("k", Money(500)), Attributes(Attribute("k", "$500")))
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
sbt -batch "otel4sTaglessJVM/testOnly com.dwolla.tracing.otel4s.ToAttributesSpec"
```

Expected: sbt cannot resolve the project at all — `otel4sTaglessJVM` is not a
known project. That is the correct red for a module that does not exist yet.

- [ ] **Step 3: Add the module to `build.sbt`**

Add a version val next to the others (`build.sbt:25-29`):

```scala
val otel4sVersion = "1.0.1"
```

Insert the project after `natchezTaglessMtl` and before `buildInfoForTests`:

```scala
// otel4s versions of core's three tracing interpreters. Deliberately *not* a
// natchez module: it depends on otel4s-core-trace and taglessCore and nothing
// else of ours, so an application on otel4s never pulls natchez in to get it.
//
// 2.12 is excluded here and only here. otel4s has never published a _2.12
// artifact at any version (otel4s's own build.sbt sets
// crossScalaVersions := Seq("2.13.18", "3.3.8"), and Maven Central 404s for
// otel4s-core-trace_2.12), so the choice is a 2.13/3 module or no module. Every
// other project in this build still cross-builds 2.12.
lazy val otel4sTagless = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("otel4s-tagless"))
  .settings(
    name := "otel4s-tagless",
    crossScalaVersions := Seq(Scala213, "3.3.8"),
    libraryDependencies ++= Seq(
      // core-trace dependsOn core-common, which carries Attribute/Attributes,
      // so this one coordinate brings both the tracing API and the attribute
      // model. otel4s-core is an umbrella that would also drag in logs+metrics.
      "org.typelevel" %%% "otel4s-core-trace" % otel4sVersion,
      "org.typelevel" %%% "cats-core" % catsVersion,
      "org.typelevel" %%% "cats-tagless-core" % catsTaglessVersion,
      "org.scalameta" %%% "munit" % munitVersion % Test,
    ),
    mimaPreviousArtifacts := Set.empty,
  )
  .settings(doctestSettings *)
  .dependsOn(taglessCore)
```

and add `otel4sTagless` to `tlCrossRootProject.aggregate(…)`, after
`natchezTaglessMtl`.

Do **not** add the JVM-only testkit yet — that arrives in Task 2, where it has
a user.

- [ ] **Step 4: Regenerate the CI workflow**

```bash
sbt -batch githubWorkflowGenerate
git diff --stat .github/workflows/
sbt -batch githubWorkflowCheck
```

`.github/workflows/ci.yml:58` runs `githubWorkflowCheck` in CI, and `:78`/`:82`
enumerate every project's target directory by hand, so a new module makes the
committed workflow stale and CI red for a reason unrelated to the code.
Expected: `ci.yml` gains `otel4s-tagless/.jvm/target` and
`otel4s-tagless/.js/target` in the compress/inflate steps, and
`githubWorkflowCheck` then passes. **Do not hand-edit the file** — it says at
the top that it is generated.

- [ ] **Step 5: Write `ToAttributes.scala`**

Create `otel4s-tagless/src/main/scala/com/dwolla/tracing/otel4s/ToAttributes.scala`.

The shape, with the reasoning that must survive in the scaladoc:

```scala
package com.dwolla.tracing.otel4s

import cats.Show
import cats.syntax.all._
import org.typelevel.otel4s.{Attribute, AttributeKey, Attributes}

/** Converts a value of type `A` into otel4s `Attributes`, under an attribute
  * name supplied by the caller.
  *
  * This is the otel4s counterpart of `natchez.TraceableValue`, and it exists
  * because otel4s ships nothing of the right shape: `Attribute.From` and
  * `Attribute.Make` both take two type parameters, and `Attributes.Make`, which
  * takes one, bakes the attribute ''name'' into the instance. A `Weave`'s
  * `Dom`/`Cod` must be a single-parameter type constructor, and the name has to
  * come from the `Advice` at the call site so that a parameter can be recorded
  * as `algebraName.methodName.paramName`.
  *
  * Returning `Attributes` rather than a single `Attribute` lets one value
  * expand to several (a case class), or to none at all (`Unit`, `None`), and
  * lets a `Weave`'s parameter lists compose through `Attributes`' `Monoid`.
  *
  * '''Do not write a generic instance for a collection type.''' `Attributes`
  * holds only unique keys, so combining per-element `Attributes` under one name
  * silently keeps the last element. The sequence instances below are the four
  * native OpenTelemetry sequence key types, which record as one array-valued
  * attribute.
  *
  * Contravariant because `A` occurs only in negative position, matching
  * otel4s's own `Attributes.Make[-A]`; that is what lets a `List[String]`
  * parameter resolve the `Seq[String]` instance.
  */
trait ToAttributes[-A] {
  def toAttributes(name: String, value: A): Attributes
}

object ToAttributes extends LowPriorityToAttributesInstances {
  def apply[A](implicit ev: ToAttributes[A]): ToAttributes[A] = ev

  def instance[A](f: (String, A) => Attributes): ToAttributes[A] =
    new ToAttributes[A] {
      override def toAttributes(name: String, value: A): Attributes = f(name, value)
    }

  /** One attribute, at one of otel4s's nine legal key types. */
  def primitive[A: AttributeKey.KeySelect]: ToAttributes[A] =
    instance((name, value) => Attributes(Attribute(name, value)))

  implicit val stringToAttributes: ToAttributes[String] = primitive[String]
  implicit val booleanToAttributes: ToAttributes[Boolean] = primitive[Boolean]
  implicit val longToAttributes: ToAttributes[Long] = primitive[Long]
  implicit val doubleToAttributes: ToAttributes[Double] = primitive[Double]

  // otel4s has no Int, Short or Byte key type; Long is the only integral one.
  implicit val intToAttributes: ToAttributes[Int] = instance((n, i) => Attributes(Attribute(n, i.toLong)))
  // ... Short, Byte likewise

  /** otel4s has no `Float` key type and no `Attribute.From` for one, so a
    * `Float` records as the exact `Double` it denotes — `0.1f` becomes
    * `0.10000000149011612`. Shadow this instance if you would rather record
    * the shorter rendering; doing so changes the value, which is why it is not
    * the default.
    */
  implicit val floatToAttributes: ToAttributes[Float] = instance((n, f) => Attributes(Attribute(n, f.toDouble)))

  /** A `Unit` return value records nothing. The span's existence already says
    * the method ran. (`natchez.TraceableValue` records the string `"()"`.)
    */
  implicit val unitToAttributes: ToAttributes[Unit] = instance((_, _) => Attributes.empty)

  /** An absent value becomes an absent attribute, matching
    * `AttributeKey#maybe`. (`natchez.TraceableValue` records the string
    * `"None"`.)
    */
  implicit def optionToAttributes[A](implicit ev: ToAttributes[A]): ToAttributes[Option[A]] =
    instance((name, oa) => oa.fold(Attributes.empty)(ev.toAttributes(name, _)))

  implicit val stringSeqToAttributes: ToAttributes[Seq[String]] = primitive[Seq[String]]
  // ... Seq[Boolean], Seq[Long], Seq[Double], plus Seq[Int]/[Short]/[Byte] mapped to Seq[Long]
}

/** The fallback, at lower implicit priority than everything in the companion's
  * own body — which is why this type class needs none of the `NotGiven`
  * ambiguity guards `com.dwolla.tracing.ToTraceValue` carries. Those exist
  * because natchez's primitive instances live in an upstream companion at the
  * same priority as the fallback; ours live one rung up, in a companion we own.
  */
trait LowPriorityToAttributesInstances {
  implicit def showToAttributes[A: Show]: ToAttributes[A] =
    ToAttributes.instance((name, a) => Attributes(Attribute(name, a.show)))
}
```

Fill in the elided instances. Keep the scaladoc — every paragraph above records
a decision someone will otherwise re-litigate.

- [ ] **Step 6: Run the test to verify it passes**

```bash
sbt -batch "otel4sTaglessJVM/testOnly com.dwolla.tracing.otel4s.ToAttributesSpec"
```

Expected: PASS, 8 tests, on 2.13.18.

- [ ] **Step 7: Prove the priority ladder, and prove the contravariance claim**

This is decision D2 and half of D1, and neither should be believed on the
strength of the argument in the milestone document. Create
`ToAttributesResolutionSpec.scala`:

```scala
package com.dwolla.tracing.otel4s

import munit.FunSuite
import org.typelevel.otel4s.{Attribute, Attributes}

/** D1 and D2, made falsifiable.
  *
  * Each `implicitly` here is a compile-time assertion: if the priority ladder
  * did not work, or if contravariance did not do what the milestone document
  * claims, the module would not build and this file is where the error lands.
  */
class ToAttributesResolutionSpec extends FunSuite {
  test("a primitive resolves to its own instance, not to the Show fallback") {
    // Show[String] exists, so without the priority ladder this is ambiguous.
    assertEquals(implicitly[ToAttributes[String]].toAttributes("k", "v"), Attributes(Attribute("k", "v")))
  }

  test("Show[Int] and Show[Boolean] do not shadow the primitive instances either") {
    assertEquals(implicitly[ToAttributes[Int]].toAttributes("k", 3), Attributes(Attribute("k", 3L)))
    assertEquals(implicitly[ToAttributes[Boolean]].toAttributes("k", true), Attributes(Attribute("k", true)))
  }

  test("contravariance lets List and Vector use the Seq instance") {
    assertEquals(implicitly[ToAttributes[List[String]]].toAttributes("k", List("a")), Attributes(Attribute("k", Seq("a"))))
    assertEquals(implicitly[ToAttributes[Vector[Long]]].toAttributes("k", Vector(1L)), Attributes(Attribute("k", Seq(1L))))
  }
}
```

Run it:

```bash
sbt -batch "otel4sTaglessJVM/testOnly com.dwolla.tracing.otel4s.ToAttributesResolutionSpec"
```

**If any of these fails to compile with an ambiguity error, stop and report
before working around it.** The two possible corrections are named in the
milestone document: for the priority half, the `NotGiven` guards
`ToTraceValue.scala` uses, which costs a `scalac-compat-features` dependency;
for the contravariance half, invariance plus explicit `List`/`Vector`
instances. Both are design changes, not local fixes, and both belong in a
report rather than in a quiet edit.

- [ ] **Step 8: Both Scala versions, both platforms, and the 2.12 exclusion**

```bash
sbt -batch "+otel4sTaglessJVM/test"
sbt -batch "+otel4sTaglessJS/Test/scalaJSLinkerResult"
sbt -batch "show otel4sTaglessJVM/crossScalaVersions" "show coreJVM/crossScalaVersions"
sbt -batch "++ 2.12 natchez-tagless-rootJVM/test"
```

Expected, in order: `+` runs **two** versions (2.13.18 and 3.3.8) and both are
green; both JS linkers green; `otel4sTaglessJVM`'s list is
`Seq(2.13.18, 3.3.8)` while `coreJVM`'s still contains `2.12.21`; and the
2.12 aggregate run is green, having **skipped** the new module rather than
failed on it.

That last command is the one that matters. It is the mechanism the whole 2.12
exclusion rests on — sbt ≥ 1.4's `++` only switches projects that support the
version and drops the others from aggregation — and CI runs exactly this shape
(`ci.yml:66`). **If it fails rather than skips, stop and report**; every
downstream assumption about the exclusion is then wrong.

- [ ] **Step 9: Zero warnings**

```bash
sbt -batch "set otel4sTagless.jvm/scalacOptions += \"-Xfatal-warnings\"" "+otel4sTaglessJVM/test"
```

Expected: PASS on both versions. Note this module has `doctestSettings`, which
filters `-Wunused` out of the `Test` scope (`build.sbt:46-48`) — so this check
covers the main sources properly and the test sources only partially. That is
the same coverage `core` has and is not a new gap.

- [ ] **Step 10: Commit**

```bash
git add build.sbt .github/workflows/ otel4s-tagless/
git commit -m "feat: add the otel4s-tagless module and its ToAttributes type class

otel4s ships no TraceableValue analogue: Attribute.From and Attribute.Make both
take two type parameters, and Attributes.Make bakes the attribute name into the
instance. A Weave's Dom needs kind * -> * with the name supplied at the call
site, so the module owns ToAttributes[-A], returning otel4s Attributes so one
value can expand to several or to none.

The module cross-builds 2.13 and 3 only. otel4s has never published a _2.12
artifact at any version, so this is one new module without 2.12 rather than a
2.12 drop for the library; every other module is unchanged."
```

---

## Task 2: `TracerInstrumentation`, and the test harness

The smallest of the three interpreters, taken first because it establishes both
halves of the testing strategy: the cross-platform transparency suite over
`Tracer.noop`, and the JVM-only span-content suite over the oteljava testkit.
Those cost more than the four lines of interpreter they first verify, and every
later task reuses them.

**Files:**
- Modify: `build.sbt` (JVM-only test dependency and source directory)
- Create: `otel4s-tagless/src/main/scala/com/dwolla/tracing/otel4s/TracerInstrumentation.scala`
- Create: `otel4s-tagless/src/test/scala/com/dwolla/tracing/otel4s/FooFixture.scala`
- Create: `otel4s-tagless/src/test/scala/com/dwolla/tracing/otel4s/TracerTransparencySpec.scala`
- Create: `otel4s-tagless/src/test/scala-jvm/com/dwolla/tracing/otel4s/SpanContentSpec.scala`

**Interfaces:**
- Consumes: `org.typelevel.otel4s.trace.Tracer`,
  `cats.tagless.aop.{Instrument, Instrumentation}`.
- Produces:
  - `object TracerInstrumentation { def apply[F[_]: Tracer]: Instrumentation[F, *] ~> F }`
  - `class TracerInstrumentation[F[_]: Tracer] extends (Instrumentation[F, *] ~> F)`
  - `FooFixture`: a `Foo[F[_]]` algebra with hand-written `Instrument[Foo]` and
    `Aspect[Foo, ToAttributes, ToAttributes]` instances (no macros, so the
    fixture is identical on 2.13 and 3).

- [ ] **Step 1: Read the testkit's API before writing a line against it**

The plan cannot quote an API it has not read, and the milestone document is
explicit that one piece is unverified. Do this first:

```bash
cd "$(mktemp -d)" && \
  curl -sO "https://repo1.maven.org/maven2/org/typelevel/otel4s-oteljava-trace-testkit_2.13/1.0.1/otel4s-oteljava-trace-testkit_2.13-1.0.1-sources.jar" && \
  unzip -q *.jar -d src && \
  cat src/org/typelevel/otel4s/oteljava/testkit/trace/TracesTestkit.scala
```

What is already verified from that jar, and can be relied on:

```scala
object TracesTestkit {
  def inMemory[F[_]: Async: LocalContextProvider](
      customize: Builder[F] => Builder[F] = identity[Builder[F]](_)
  ): Resource[F, TracesTestkit[F]]
}
sealed trait TracesTestkit[F[_]] {
  def tracerProvider: TracerProvider[F]
  def finishedSpans: F[List[io.opentelemetry.sdk.trace.data.SpanData]]
  def resetSpans: F[Unit]
}
```

`SpanData` is the **OpenTelemetry Java** model, so assertions read `.getName`
and `.getAttributes`, or use the testkit's own `SpanExpectation` /
`TraceExpectations` DSL in the same package.

**What is not verified: where `LocalContextProvider[IO]` comes from.** It is a
type alias for `org.typelevel.otel4s.context.LocalProvider[F, Context]`. Find
the instance and write down where it lives; do not guess an import. If no
implicit instance exists for `IO` without extra wiring, say so in the task
report — it changes how the suite is set up and possibly which artifact is
needed.

- [ ] **Step 2: Write the failing tests**

Two files. First the fixture, `FooFixture.scala` — hand-written instances
rather than `Derive.aspect`, so the module needs no `cats-tagless-macros`
dependency on 2.13 and the fixture is byte-identical on both axes. Model it on
the hand-rolled `Aspect` in `TraceWeaveCapturingInputsAndOutputs`'s scaladoc
(`core/shared/src/main/scala/com/dwolla/tracing/TraceWeaveCapturingInputsAndOutputs.scala:66-84`):

```scala
package com.dwolla.tracing.otel4s

import cats.tagless.aop._
import cats.~>

trait Foo[F[_]] {
  def greet(name: String, times: Int): F[String]
}

object Foo {
  implicit val fooInstrument: Instrument[Foo] = ???       // instrument + mapK
  implicit val fooAspect: Aspect[Foo, ToAttributes, ToAttributes] = ???  // weave + mapK
}
```

Then `TracerTransparencySpec.scala`, the cross-platform half. It runs on JVM
and JS, needs no testkit, and asserts the parts that are ours:

```scala
package com.dwolla.tracing.otel4s

import cats.Id
import cats.tagless.syntax.all._
import munit.FunSuite
import org.typelevel.otel4s.trace.Tracer

/** What can be checked without a testkit, on every platform.
  *
  * `Tracer[F]`, `Span[F]`, `SpanOps[F]`, `SpanBuilder[F]` and `Span.Backend[F]`
  * are all sealed and their `Unsealed` variants are `private[otel4s]`, so a
  * recording `Tracer` cannot be hand-rolled the way this repo hand-rolls a
  * `natchez.Trace`. Span ''content'' is therefore asserted in `SpanContentSpec`,
  * which is JVM-only because otel4s's cross-platform SDK testkit has not been
  * released at 1.0.x. What is left here is transparency, and it is not nothing:
  * `Tracer.noop`'s `build.use(f)` is `f(span)`, so `surround(fa)` reduces to
  * `fa` and any interference by the interpreter shows up immediately.
  */
class TracerTransparencySpec extends FunSuite {
  private implicit val tracer: Tracer[Id] = Tracer.noop[Id]

  test("an instrumented call returns exactly what the underlying call returns") {
    val underlying: Foo[Id] = ???
    val instrumented: Foo[Id] = underlying.instrument.mapK(TracerInstrumentation[Id])

    assertEquals(instrumented.greet("world", 2), underlying.greet("world", 2))
  }
}
```

And `src/test/scala-jvm/.../SpanContentSpec.scala`, the JVM half, using
`TracesTestkit.inMemory[IO]()` from Step 1 and `munit-cats-effect`:

```scala
test("each method call opens one span named algebraName.methodName") { ... }
```

asserting `finishedSpans.map(_.getName) == List("Foo.greet")`.

- [ ] **Step 3: Run them to verify they fail**

```bash
sbt -batch "otel4sTaglessJVM/testOnly com.dwolla.tracing.otel4s.*"
```

Expected: compile failure — `TracerInstrumentation` does not exist, and the
JVM test source directory is not on the build's source path yet.

- [ ] **Step 4: Wire the JVM-only test dependency and source directory**

Only if Q2 was answered yes. In `build.sbt`, add to the `otel4sTagless`
definition, following `raiseAspectCore`'s existing split (`build.sbt:115-120`)
and `core`'s existing JVM-only test dependency (`build.sbt:71-75`):

```scala
  // Span *content* can only be asserted with a testkit: every otel4s span type
  // is sealed and its Unsealed variant is private[otel4s], so a recording
  // Tracer cannot be hand-rolled. The cross-platform testkit
  // (otel4s-sdk-trace-testkit) has not been released at 1.0.x — it stops at
  // 0.19.0 — so at otel4s 1.0.1 the only option is the JVM one. Cross-platform
  // coverage lives in TracerTransparencySpec, which needs no testkit.
  .jvmSettings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "otel4s-oteljava-trace-testkit" % otel4sVersion % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.2.0" % Test,
    ),
    Test / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "src" / "test" / "scala-jvm",
  )
```

Then regenerate the workflow again if the target-directory list changed:

```bash
sbt -batch githubWorkflowGenerate && sbt -batch githubWorkflowCheck
```

- [ ] **Step 5: Write `TracerInstrumentation.scala`**

```scala
package com.dwolla.tracing.otel4s

import cats.tagless.aop._
import cats.~>
import org.typelevel.otel4s.trace.Tracer

object TracerInstrumentation {
  def apply[F[_]: Tracer]: Instrumentation[F, *] ~> F = new TracerInstrumentation[F]
}

class TracerInstrumentation[F[_]: Tracer] extends (Instrumentation[F, *] ~> F) {
  override def apply[A](fa: Instrumentation[F, A]): F[A] =
    Tracer[F]
      .spanBuilder(s"${fa.algebraName}.${fa.methodName}")
      .build
      .surround(fa.value)
}
```

Mirror `TraceInstrumentation`'s scaladoc, with two substantive changes: the
example uses `Tracer` and the macro-free path, and it notes that otel4s marks
the span as errored automatically on an escaping `Throwable`
(`SpanFinalizer.Strategy.reportAbnormal` is the default finalization strategy,
`SpanBuilder.scala:192`) where natchez does not. Doctest examples come in
Task 6; keep the prose now and add the `{{{ }}}` block then, so that a broken
example never blocks an interpreter's own test.

**Note what did *not* appear: no `Functor`, no `Apply`, no `FlatMap`.
`Tracer[F]` alone is enough — `surround` and `use` impose no constraint on `F`
at the call site. Same profile as the natchez version.**

- [ ] **Step 6: Run both suites to verify they pass**

```bash
sbt -batch "otel4sTaglessJVM/test"
```

Expected: PASS. Record the split — how many tests came from the cross-platform
sources and how many from `scala-jvm` — because Step 7 must show the JS build
running the former and not the latter.

- [ ] **Step 7: Confirm the JS build excludes the JVM-only sources**

```bash
sbt -batch "+otel4sTaglessJS/Test/scalaJSLinkerResult"
sbt -batch "show otel4sTaglessJS/Test/unmanagedSourceDirectories"
```

Expected: linker green on both versions, and the JS project's list does **not**
contain `src/test/scala-jvm`. If the JS linker fails on
`io.opentelemetry.sdk.…`, the directory leaked into the shared build — fix the
`.jvmSettings` placement, do not stub the class.

- [ ] **Step 8: Commit**

```bash
git add build.sbt .github/workflows/ otel4s-tagless/
git commit -m "feat: add TracerInstrumentation and the module's test harness

One span per method call, named algebraName.methodName, over the sealed
spanBuilder/build/surround path rather than the Tracer.span macro.

Testing is split because otel4s's span types are all sealed with private[otel4s]
Unsealed variants, so a recording Tracer cannot be hand-rolled: transparency is
asserted cross-platform over Tracer.noop, and span content is asserted on the
JVM with otel4s-oteljava-trace-testkit. The cross-platform SDK testkit has not
been released at 1.0.x."
```

---

## Task 3: `TracerWeaveCapturingInputs` and `asAttributes`

The first interpreter that reads the domain, so the `asAttributes` syntax lands
with it — the interpreter is its only caller and neither is testable without the
other.

**Files:**
- Create: `otel4s-tagless/src/main/scala/com/dwolla/tracing/otel4s/syntax/WeaveAttributesOps.scala`
- Create: `otel4s-tagless/src/main/scala/com/dwolla/tracing/otel4s/TracerWeaveCapturingInputs.scala`
- Create: `otel4s-tagless/src/test/scala/com/dwolla/tracing/otel4s/WeaveAttributesOpsSpec.scala`
- Modify: `otel4s-tagless/src/test/scala-jvm/com/dwolla/tracing/otel4s/SpanContentSpec.scala`
- Modify: `otel4s-tagless/src/test/scala/com/dwolla/tracing/otel4s/TracerTransparencySpec.scala`

**Interfaces:**
- Consumes: Task 1's `ToAttributes`, `Attributes`' `Monoid`
  (`Attributes.scala:204`), `SpanBuilder#modifyState` (`SpanBuilder.scala:43`),
  `SpanBuilder.State#addAttributes` (`SpanBuilder.scala:140`).
- Produces:
  - `trait ToWeaveAttributesOps { implicit def toWeaveAttributesOps[F[_], Cod[_], A](fa: Weave[F, ToAttributes, Cod, A]): WeaveAttributesOps[F, Cod, A] }`
  - `class WeaveAttributesOps[F[_], Cod[_], A](val fa: Weave[F, ToAttributes, Cod, A]) extends AnyVal { def asAttributes: Attributes }`
  - `object TracerWeaveCapturingInputs { def apply[F[_]: Tracer, Cod[_]]: Weave[F, ToAttributes, Cod, *] ~> F }`
  - `class TracerWeaveCapturingInputs[F[_]: Tracer, Cod[_]] extends (Weave[F, ToAttributes, Cod, *] ~> F)`

- [ ] **Step 1: Write the failing test**

`WeaveAttributesOpsSpec.scala` asserts the naming scheme and the flattening
across parameter lists, against a hand-built `Weave` (no algebra needed):

```scala
test("every parameter records as algebraName.methodName.paramName") { ... }
test("multiple parameter lists flatten into one Attributes") { ... }
test("a parameter whose ToAttributes yields nothing contributes nothing") { ... }
```

The third case is the one worth writing down: a `Unit` or `None` parameter
produces `Attributes.empty`, so the attribute is absent rather than present and
empty. That is D3, observed through the syntax.

Extend `SpanContentSpec` with the inputs assertion — the span carries
`Foo.greet.name` and `Foo.greet.times` with the expected values and types — and
extend `TracerTransparencySpec` with the transparency and by-name cases for this
interpreter.

- [ ] **Step 2: Run to verify it fails**

```bash
sbt -batch "otel4sTaglessJVM/test"
```

Expected: compile failure on `asAttributes` and `TracerWeaveCapturingInputs`.

- [ ] **Step 3: Write `WeaveAttributesOps.scala`**

Mirror `TraceParamsOps` structurally — same two-level traversal, same naming —
with `foldMap` in place of `flatMap`/`map` because `Attributes` is a `Monoid`:

```scala
package com.dwolla.tracing.otel4s
package syntax

import cats.syntax.all._
import cats.tagless.aop.Aspect.Weave
import org.typelevel.otel4s.Attributes

trait ToWeaveAttributesOps {
  implicit def toWeaveAttributesOps[F[_], Cod[_], A](fa: Weave[F, ToAttributes, Cod, A]): WeaveAttributesOps[F, Cod, A] =
    new WeaveAttributesOps(fa)
}

class WeaveAttributesOps[F[_], Cod[_], A](val fa: Weave[F, ToAttributes, Cod, A]) extends AnyVal {
  def asAttributes: Attributes =
    fa.domain.foldMap {
      _.foldMap { advice =>
        // Same naming scheme as the natchez side. Verbose, but the OpenTelemetry
        // attribute-naming spec says to namespace everything:
        // https://opentelemetry.io/docs/specs/semconv/general/attribute-naming/
        advice.instance.toAttributes(
          s"${fa.algebraName}.${fa.codomain.name}.${advice.name}",
          advice.target.value
        )
      }
    }
}
```

Note `advice.target.value` forces the `Eval` — same as natchez, and the reason
`TracerWeaveCapturingInputs` must not call `asAttributes` before it means to.

- [ ] **Step 4: Write `TracerWeaveCapturingInputs.scala`**

```scala
class TracerWeaveCapturingInputs[F[_]: Tracer, Cod[_]] extends (Weave[F, ToAttributes, Cod, *] ~> F) {
  override def apply[A](fa: Weave[F, ToAttributes, Cod, A]): F[A] =
    Tracer[F]
      .spanBuilder(s"${fa.algebraName}.${fa.codomain.name}")
      .modifyState(_.addAttributes(fa.asAttributes))
      .build
      .surround(fa.codomain.target)
}
```

Two things the scaladoc must say, because both are differences a reader will
otherwise assume away:

1. **No `Apply[F]`.** The natchez version needs it to sequence
   `Trace[F].put(…) *> target` (`TraceWeaveCapturingInputs.scala:117`). Here the
   attributes go onto the builder, so there is nothing to sequence. Only
   `Tracer[F]`.
2. **Input attributes exist at span start**, not just after it, so a sampler can
   see them — otel4s samples at span start and warns that renaming later has
   implementation-defined sampling effects (`Span.scala:91`).

Carry over the redaction guidance from `TraceWeaveCapturingInputs`'s scaladoc,
retyped for `ToAttributes`: a sensitive parameter gets a newtype and a
hand-written instance that records a placeholder.

- [ ] **Step 5: Run to verify it passes**

```bash
sbt -batch "+otel4sTaglessJVM/test" "+otel4sTaglessJS/Test/scalaJSLinkerResult"
```

Expected: PASS on 2.13.18 and 3.3.8; both linkers green.

- [ ] **Step 6: Commit**

```bash
git add otel4s-tagless/
git commit -m "feat: add TracerWeaveCapturingInputs and Weave#asAttributes

Parameters record as algebraName.methodName.paramName, matching the natchez
side, and flatten across parameter lists through Attributes' Monoid.

Unlike the natchez interpreter this needs no Apply[F]: the attributes attach to
the SpanBuilder rather than being put inside the span, which also means they
exist before the sampler runs."
```

---

## Task 4: `TracerWeaveCapturingInputsAndOutputs`

The only interpreter that needs a `Span[F]` handle, and therefore the only one
whose shape is genuinely forced by otel4s's lack of an ambient `put`.

**Files:**
- Create: `otel4s-tagless/src/main/scala/com/dwolla/tracing/otel4s/TracerWeaveCapturingInputsAndOutputs.scala`
- Modify: `otel4s-tagless/src/test/scala-jvm/com/dwolla/tracing/otel4s/SpanContentSpec.scala`
- Modify: `otel4s-tagless/src/test/scala/com/dwolla/tracing/otel4s/TracerTransparencySpec.scala`

**Interfaces:**
- Consumes: Task 3's `asAttributes`; `SpanOps#use` (`SpanOps.scala:219`);
  `Span#backend` (`Span.scala:66`) and
  `Span.Backend#addAttributes` (`Span.scala:166`).
- Produces:
  - `object TracerWeaveCapturingInputsAndOutputs { def apply[F[_]: FlatMap: Tracer]: Weave[F, ToAttributes, ToAttributes, *] ~> F }`
  - `class TracerWeaveCapturingInputsAndOutputs[F[_]: FlatMap: Tracer] extends (Weave[F, ToAttributes, ToAttributes, *] ~> F)`

- [ ] **Step 1: Write the failing test**

In `SpanContentSpec`, assert that one call produces one span carrying both the
inputs *and* `Foo.greet.returnValue`. Add the case that a `Unit`-returning
method records **no** `returnValue` attribute at all (D3, end to end), because
that is the divergence most likely to be "fixed" by a later reader who has not
read the milestone document.

In `TracerTransparencySpec`, assert the result is unchanged.

- [ ] **Step 2: Run to verify it fails**

```bash
sbt -batch "otel4sTaglessJVM/test"
```

- [ ] **Step 3: Write the interpreter**

```scala
class TracerWeaveCapturingInputsAndOutputs[F[_]: FlatMap: Tracer]
    extends (Weave[F, ToAttributes, ToAttributes, *] ~> F) {
  override def apply[A](fa: Weave[F, ToAttributes, ToAttributes, A]): F[A] = {
    val name = s"${fa.algebraName}.${fa.codomain.name}"

    Tracer[F]
      .spanBuilder(name)
      .modifyState(_.addAttributes(fa.asAttributes))
      .build
      .use { span =>
        fa.codomain.target.flatTap { out =>
          // `.backend` deliberately: Span#addAttributes is a macro on Scala 2
          // and inline on Scala 3, and Span.Backend#addAttributes is the sealed
          // method underneath it. Attributes is already an
          // immutable.Iterable[Attribute[_]], so it passes straight through.
          span.backend.addAttributes(fa.codomain.instance.toAttributes(s"$name.returnValue", out))
        }
      }
  }
}
```

`FlatMap[F]`, matching the natchez version — `Monad` would work but nothing here
needs `pure`.

Scaladoc: mirror `TraceWeaveCapturingInputsAndOutputs`, including the redaction
example, and add the automatic-error-recording note from Task 2's scaladoc.

- [ ] **Step 4: Run to verify it passes**

```bash
sbt -batch "+otel4sTaglessJVM/test" "+otel4sTaglessJS/Test/scalaJSLinkerResult"
```

- [ ] **Step 5: Commit**

```bash
git add otel4s-tagless/
git commit -m "feat: add TracerWeaveCapturingInputsAndOutputs

The return value needs a Span handle, which otel4s only provides through
SpanOps#use — there is no ambient Tracer[F].put. Attributes go on via
span.backend.addAttributes, the sealed method underneath the Span#addAttributes
macro. A Unit return records no returnValue attribute at all."
```

---

## Task 5: the syntax package

Everything above becomes one method call on an algebra. This is the surface
users actually touch, and the point at which the module's ergonomics either
match `com.dwolla.tracing.syntax` or do not.

**Files:**
- Create: `otel4s-tagless/src/main/scala/com/dwolla/tracing/otel4s/syntax/TracerWeaveOps.scala`
- Create: `otel4s-tagless/src/main/scala/com/dwolla/tracing/otel4s/syntax/InstrumentableAndTraceableOps.scala`
- Create: `otel4s-tagless/src/main/scala/com/dwolla/tracing/otel4s/syntax/package.scala`
- Modify: the two test suites, to go through the syntax rather than constructing
  interpreters by hand.

**Interfaces:**
- Produces:
  - `class TracerWeaveOps[Alg[_[_]], F[_]](val alg: Alg[F]) extends AnyVal`
    - `def traceWithInputs[Cod[_]](implicit T: Tracer[F], A: Aspect[Alg, ToAttributes, Cod]): Alg[F]`
    - `def traceWithInputsAndOutputs(implicit F: FlatMap[F], T: Tracer[F], A: Aspect[Alg, ToAttributes, ToAttributes]): Alg[F]`
  - `class InstrumentableAndTraceableOps[F[_], Alg[_[_]]](val alg: Alg[F]) extends AnyVal`
    - `def instrumentAndTrace(implicit I: Instrument[Alg], T: Tracer[F]): Alg[F]`
  - `package object syntax extends ToTracerWeaveOps with ToInstrumentableAndTraceableOps with ToWeaveAttributesOps`

- [ ] **Step 1: Write the failing test**

Rewrite the entry points of both suites to use the syntax:

```scala
import com.dwolla.tracing.otel4s.syntax._

val traced: Foo[IO] = underlying.traceWithInputsAndOutputs
```

and add one assertion that could not be made before: `traceWithInputs` requires
**no `Apply[F]`**, which the test states by summoning it at an `F` for which
only `Tracer` is available.

- [ ] **Step 2: Run to verify it fails**

```bash
sbt -batch "otel4sTaglessJVM/test"
```

- [ ] **Step 3: Write the three syntax files and the package object**

`TracerWeaveOps` mirrors `TraceWeaveOps` with `Tracer` for `Trace`,
`ToAttributes` for `TraceableValue`, and **`Apply[F]` dropped from
`traceWithInputs`**:

```scala
class TracerWeaveOps[Alg[_[_]], F[_]](val alg: Alg[F]) extends AnyVal {
  def traceWithInputs[Cod[_]](implicit
                              T: Tracer[F],
                              A: Aspect[Alg, ToAttributes, Cod]): Alg[F] =
    alg.weave.mapK(new TracerWeaveCapturingInputs)

  def traceWithInputsAndOutputs(implicit
                                F: FlatMap[F],
                                T: Tracer[F],
                                A: Aspect[Alg, ToAttributes, ToAttributes]): Alg[F] =
    alg.weave.mapK(new TracerWeaveCapturingInputsAndOutputs)
}
```

The package object carries the one sentence users need:

```scala
/** Syntax for the otel4s interpreters.
  *
  * The method names deliberately match `com.dwolla.tracing.syntax`'s, so
  * migrating a file from natchez to otel4s changes one import and no call
  * sites. The cost is that a single file cannot wildcard-import both packages —
  * the two implicit conversions would be ambiguous. Import one of them
  * selectively if you genuinely need both backends in one file.
  */
package object syntax
  extends ToTracerWeaveOps
    with ToInstrumentableAndTraceableOps
    with ToWeaveAttributesOps
```

- [ ] **Step 4: Run to verify it passes, on both axes and both platforms**

```bash
sbt -batch "+otel4sTaglessJVM/test" "+otel4sTaglessJS/Test/scalaJSLinkerResult"
```

- [ ] **Step 5: Prove the collision is exactly as documented, then delete the proof**

Temporarily add a test source that wildcard-imports **both**
`com.dwolla.tracing.syntax._` and `com.dwolla.tracing.otel4s.syntax._` and calls
`traceWithInputs`. Compile it, read the error, and confirm it is a
comprehensible ambiguity rather than something baffling. Then **revert the
file** — this module must not depend on `core`.

Record the actual error text in the task report and, if it is clear enough to be
worth quoting, in the package object's scaladoc. If the compile *succeeds*, the
milestone document's claim is wrong and the note should be corrected rather than
left stating a hazard that does not exist.

```bash
git status   # must be clean of the throwaway before committing
```

- [ ] **Step 6: Commit**

```bash
git add otel4s-tagless/
git commit -m "feat: add the otel4s syntax package

traceWithInputs, traceWithInputsAndOutputs and instrumentAndTrace, with the same
names as the natchez syntax so a migrating file changes one import and no call
sites. traceWithInputs drops the Apply[F] the natchez version needs, because
input attributes attach to the SpanBuilder rather than being put in the span."
```

---

## Task 6: scaladoc, doctests, README and the documentation reconciliation

**Little production code, and the task most likely to fail.** Every `{{{ }}}`
block in this module is compiled and executed on 2.13 and 3
(`doctestOnlyCodeBlocksMode := true`, `build.sbt:45`), so an example that calls
an otel4s macro, or that needs a `Tracer` it does not construct, breaks the
build. That is why the examples land last, as a set, rather than being sprinkled
through Tasks 2–5.

**Files:**
- Modify: all four main sources and the three syntax sources (examples)
- Create: `otel4s-tagless/src/main/scala/README.md`
- Modify: `docs/plans/raise-aspect/01-overview-design-and-laws.md`
- Modify: `docs/plans/raise-aspect/28-milestone-M15-tagless-core-module.md`
- Modify: `docs/plans/raise-aspect/30-milestone-M16-otel4s-module.md` (status)

- [ ] **Step 1: Add the doctest examples**

One per interpreter plus one on the syntax package object, modelled on the
existing natchez examples but with three mandatory differences:

- `implicit val tracer: Tracer[IO] = Tracer.noop[IO]` (or
  `import Tracer.Implicits.noop`) — examples need a `Tracer` and `noop` is the
  only one in `otel4s-core-trace`.
- **No macros.** No `Tracer[F].span(...)`, no `span.addAttributes(...)`. If an
  example wants to show a span being created by hand, it shows
  `spanBuilder(name).build.surround(fa)`.
- The hand-written `Aspect`/`Instrument` in the example must compile on both
  axes, exactly as `core`'s existing examples do.

Add one example that ties the knot with `WeaveKnot`, so the `taglessCore`
dependency is exercised rather than merely declared:

```scala
val traced: Foo[IO] = WeaveKnot.weave[Foo, IO, ToAttributes, ToAttributes](
  self => new Foo[IO] { /* methods may call self.value's other methods */ },
  TracerWeaveCapturingInputsAndOutputs[IO]
)
```

Check `WeaveKnot`'s actual signature in `tagless-core` before writing this —
M15's plan records it as
`weave[Alg[_[_]], F[_], Dom[_], Cod[_]](constructor: Eval[Alg[F]] => Alg[F], transformation: Aspect.Weave[F, Dom, Cod, *] ~> F)(implicit A: Aspect[Alg, Dom, Cod]): Alg[F]`.

- [ ] **Step 2: Run the doctests on both axes**

```bash
sbt -batch "+otel4sTaglessJVM/test"
sbt -batch "+otel4sTaglessJVM/doc"
```

Expected: the generated doctests compile **and run** on 2.13.18 and 3.3.8, and
`doc` succeeds. A doctest failure here is the single most likely outcome of this
whole milestone; treat it as information about the examples, not as a reason to
delete them.

- [ ] **Step 3: Grep for macro calls — the D6 acceptance criterion**

```bash
grep -rnE "\.span\(|\.addAttribute\(|\.recordException\(|\.setStatus\(|\.withFinalizationStrategy\(|\.withSpanKind\(|\.withStartTimestamp\(|\.withParent\(" otel4s-tagless/src
```

Expected: no output, **except** `.backend.addAttributes(` and the
`_.addAttributes(` inside `modifyState`, which are the sealed `Span.Backend` and
`SpanBuilder.State` methods rather than the macros of similar name. Any other
hit is a macro call and must go.

- [ ] **Step 4: Write the module README**

`otel4s-tagless/src/main/scala/README.md`, following the shape of
`raise-aspect-core/src/main/scala/README.md`. It must cover, because nowhere
else does:

- What is here and what is not (no `Raise`/mtl support, no `Resource` helpers,
  no `EntryPoint` analogue).
- **2.12 is not published for this module, and why** — one paragraph, with the
  Maven Central fact, so nobody files a bug.
- **`ToAttributes` is not `TraceableValue`**: a user with both modules writes
  two instances per domain type, and there is deliberately no bridge.
- The three semantic divergences from `ToTraceValue` (`Unit`, `None`, `Float`)
  and the no-generic-`Seq` rule, with the unique-keys reason.
- **Error recording.** otel4s records an escaping `Throwable` as an exception
  event and sets `StatusCode.Error` automatically — this module adds nothing and
  removes nothing. **And cats-mtl `Raise` errors are invisible to that**, because
  `reportAbnormal` keys off `Resource.ExitCase` and a `Raise` error completes
  successfully as far as `F` is concerned. M6's docs record the same *gap* for
  natchez but by a different *mechanism* ("we never called `attachError`"), so
  **write this paragraph fresh; do not copy M6's wording.**
- Where to get a real `Tracer[F]`: `TracerProvider[F].get(name)` from a backend
  module (`otel4s-oteljava` on the JVM, `otel4s-sdk` cross-platform), which the
  application supplies — not this library.

- [ ] **Step 5: Reconcile the planning documents**

- `01-overview-design-and-laws.md` §3.1: add a dated amendment note in the style
  of the M10/M11/M12/M15 notes already there, naming the new module, its
  artifact, and the 2.12 exclusion with its reason.
- `28-milestone-M15-tagless-core-module.md`: its status section says M15 exists
  to prepare for M16. Record that M16 landed and consumes `tagless-core`, and
  answer its **Q2** ("is anything else destined for this module?") with what M16
  actually needed — `WeaveKnot` and nothing more.
- `30-milestone-M16-otel4s-module.md`: replace "Planned, not started" with the
  outcome, in the shape M6, M7 and M12 use. Record Brian's answers to Q1, Q2 and
  Q3; the test counts per platform and Scala version; whether the priority
  ladder held without `NotGiven`; whether contravariance caused any ambiguity;
  and **what the `LocalContextProvider[IO]` instance turned out to be**, since
  the milestone document flags it as unverified.

- [ ] **Step 6: Full verification**

```bash
sbt -batch "+test"
sbt -batch "+otel4sTaglessJS/Test/scalaJSLinkerResult" "+coreJS/Test/scalaJSLinkerResult" \
    "+taglessCoreJS/Test/scalaJSLinkerResult" "+raiseAspectCoreJS/Test/scalaJSLinkerResult" \
    "+raiseAspectLawsJS/Test/scalaJSLinkerResult" "+raiseAspectMacrosJS/Test/scalaJSLinkerResult" \
    "+natchezTaglessMtlJS/Test/scalaJSLinkerResult"
sbt -batch "set otel4sTagless.jvm/scalacOptions += \"-Xfatal-warnings\"" "+otel4sTaglessJVM/test"
sbt -batch "+coreJVM/mimaReportBinaryIssues" "+coreJS/mimaReportBinaryIssues"
sbt -batch githubWorkflowCheck
git diff --stat main...HEAD -- . ':!otel4s-tagless' ':!docs' ':!build.sbt' ':!.github'
```

Expected: every module green on the versions it supports; every JS linker green;
zero warnings under forced fatal warnings; MiMa on `core` unaffected (M16 adds a
module, it does not move anything out of `core`); the workflow check passes; and
**the last command prints nothing**, proving M16 touched no existing source.

- [ ] **Step 7: Commit**

```bash
git add otel4s-tagless/ docs/plans/raise-aspect/
git commit -m "docs: document the otel4s module and reconcile the planning docs

Scaladoc examples use the sealed spanBuilder/build/surround path throughout,
because this repo compiles doctests and otel4s's span, addAttributes and
recordException are Scala 2 macros and Scala 3 inline methods with union-typed
varargs.

The README records the three things a user cannot infer: 2.12 is unpublished
upstream, ToAttributes is not TraceableValue and needs its own instances, and
otel4s marks abnormal termination automatically while remaining blind to
cats-mtl Raise errors."
```

---

## Acceptance criteria

- [ ] `otel4s-tagless/` is a `crossProject(JVMPlatform, JSPlatform)` /
      `CrossType.Pure` module with
      `crossScalaVersions := Seq(Scala213, "3.3.8")`,
      `mimaPreviousArtifacts := Set.empty`, `doctestSettings`, and
      `.dependsOn(taglessCore)`.
- [ ] `show otel4sTaglessJVM/crossScalaVersions` excludes 2.12; every other
      module's still includes it; `sbt "++ 2.12 natchez-tagless-rootJVM/test"`
      is green having **skipped** the new module.
- [ ] `com.dwolla.tracing.otel4s` provides `ToAttributes`,
      `TracerInstrumentation`, `TracerWeaveCapturingInputs`,
      `TracerWeaveCapturingInputsAndOutputs`; `…otel4s.syntax` provides
      `traceWithInputs`, `traceWithInputsAndOutputs`, `instrumentAndTrace` and
      `asAttributes`.
- [ ] `traceWithInputs` requires **no** `Apply[F]`, and
      `TracerInstrumentation` requires nothing but `Tracer[F]`.
- [ ] `ToAttributesResolutionSpec` compiles and passes with **no `NotGiven`
      guards anywhere in the module** (`grep -rn "NotGiven" otel4s-tagless/src`
      is empty) and no `scalac-compat-features` dependency.
- [ ] The three D3 divergences are each asserted by name: `Unit` and `None`
      produce `Attributes.empty`, and `Float` records the exact widened
      `Double`. `Unit`-returning methods record no `returnValue` attribute,
      asserted end to end through the testkit.
- [ ] `grep -rnE "\.span\(|\.addAttribute\(|\.recordException\(|\.setStatus\(|\.withFinalizationStrategy\(|\.withSpanKind\(|\.withStartTimestamp\(|\.withParent\(" otel4s-tagless/src`
      returns nothing but `.backend.addAttributes(` and `modifyState`'s
      `_.addAttributes(`.
- [ ] Cross-platform suites pass on JVM and link on JS; the JVM-only
      `scala-jvm` directory is absent from `otel4sTaglessJS/Test/unmanagedSourceDirectories`.
- [ ] Doctests compile **and run** on 2.13.18 and 3.3.8;
      `+otel4sTaglessJVM/doc` succeeds.
- [ ] Zero new warnings under forced `-Xfatal-warnings` on both versions. CI
      does not enforce this (`tlFatalWarnings := false`, unoverridden).
- [ ] `.github/workflows/ci.yml` regenerated and committed;
      `sbt githubWorkflowCheck` passes.
- [ ] `git diff --stat main...HEAD` touches only `otel4s-tagless/`, `build.sbt`,
      `.github/workflows/` and `docs/`.
- [ ] The module README exists and covers the 2.12 exclusion, the
      two-type-classes consequence, the three semantic divergences, and the
      error-recording story including the `Raise` blind spot in fresh wording.

## Ground rules reminder

- **`RaiseAspect` and `raise-aspect-*` are out of scope.** If a task drifts
  toward an otel4s mtl module, stop and report.
- **No otel4s macro or `inline` call**, main sources or scaladoc.
- **No change to any existing module.** If M16 seems to need one, stop — that is
  a design problem, not a task.
- **No second otel4s compile dependency.** `otel4s-core-trace` only.
- **Do not read the stale otel4s clone at `~/Developer/github/otel4s`** (v0.9.0,
  September 2024). Use `reference/upstream/otel4s/`.
- If the `ToAttributes` priority ladder or its contravariance turns out not to
  work, that is a **design report**, not a quiet workaround. Both fallbacks are
  named in the milestone document.
- Never use `--no-verify` or any other hook-bypass flag.
