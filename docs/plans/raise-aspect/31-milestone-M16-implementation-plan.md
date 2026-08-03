# Milestone M16 — implementation plan: the otel4s module

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a new cross-built module that provides otel4s versions of
`TraceInstrumentation`, `TraceWeaveCapturingInputs` and
`TraceWeaveCapturingInputsAndOutputs`, plus the syntax that makes them one
method call on an algebra, without depending on natchez and without changing any
existing module.

**Architecture:** A project-owned `ToAnyValue[-A] { def toAnyValue(a: A):
AnyValue }` supplies the `Dom`/`Cod` that `natchez.TraceableValue` supplies on
the natchez side — otel4s ships nothing of the right kind. A traced call records
**at most two attributes**: `<Alg>.<method>.parameters`, whose value is a
structured `AnyValue.map` from parameter name to encoded value, and
`<Alg>.<method>.returnValue`. The interpreters use otel4s's **sealed,
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
   implements. Its decisions D1–D10 are settled and this plan builds them.
   **The one open question is Q3 (a circe fallback), and its current answer is
   "no"** — D5 is the decision that records it. Do not add a circe dependency;
   if Brian rules otherwise mid-flight, it is one low-priority instance and one
   dependency line, and it does not disturb anything else here.
2. `.superpowers/sdd/spike-anyvalue-attributes.md` — the spike that produced D1.
   Its `KeySelect` finding is a compile-time trap you will hit if you skip it.
3. `.superpowers/sdd/m16-otel4s-research.md` — the source-verified research.
4. `reference/upstream/otel4s/` — the vendored otel4s v1.0.1 sources every
   citation points at.

> **Do not read `/Users/bholt/Developer/github/otel4s`.** There is a clone of
> otel4s next door on this machine, and it is pinned at **v0.9.0-73-gae4ae7a0
> (September 2024)** — nearly two years and one major version stale. Every API
> in this plan is from **v1.0.1**. Use `reference/upstream/otel4s/`.

## Global Constraints

- **The encoder's declared result type is `AnyValue`, never an `AnyValue`
  subtype.** `AnyValue.map(…)` is typed at `AnyValue.MapValue` and
  `AttributeKey.KeySelect` is invariant, so `Attribute("k", AnyValue.map(…))`
  does not compile, and the error message is stale and misleading — it lists
  only the eight flat types and never mentions `AnyValue`:

  ```
  Could not find the `KeySelect` for org.typelevel.otel4s.AnyValue.MapValue. The `KeySelect` is defined for the following types:
  String, Boolean, Long, Double, Seq[String], Seq[Boolean], Seq[Long], Seq[Double].
  ```

  `implicit val anyValueKey: KeySelect[AnyValue]` does exist
  (`AttributeKey.scala:142-143`); the search just never reaches it from a
  subtype. Any widening fixes it. Declare `def toAnyValue(a: A): AnyValue`, and
  wherever the module builds an `AnyValue.map(…)` inline, bind it to an
  explicitly `AnyValue`-typed `val` first. Then no caller ever meets the
  message. `grep -rn "AnyValue\.MapValue\|AnyValue\.SeqValue\|AnyValue\.StringValue" otel4s-tagless/src/main`
  must be empty.
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
- **`-Ykind-projector` here has no `:underscores`.** The Scala 2 type-lambda
  placeholder is `*`, as everywhere else in this repo — `Weave[F, ToAnyValue,
  Cod, *] ~> F`, never `_`.
- **Cross-built dependencies use `%%%`, including test-scope ones.** `%%` is
  correct only inside `.jvmSettings`, where there is no JS artifact to get
  wrong. Two commits before this milestone, three `%%` test dependencies in
  `core`'s shared settings block meant `coreJS` ran **zero** munit tests while
  reporting success: the JVM jars type-checked but produced no `.sjsir`, the
  munit framework never registered, and dead-code elimination dropped every
  suite. Fixed in `106bf17`. **A green `otel4sTaglessJS/test` is not evidence
  that anything ran** — `show otel4sTaglessJS/Test/definedTests` must be
  non-empty, and every task that touches tests reports the count.
- **`otel4s-core-trace` is the only new direct dependency in the build, and only
  this module gets it.** No other module gains a dependency of any kind. Do not
  add `otel4s-core`, `otel4s-oteljava-*` or `otel4s-sdk-*` at compile scope —
  those are backends and belong in applications. (The one test-scope exception is
  `otel4s-oteljava-trace-testkit`, JVM only, ratified at Q2.) **No circe** —
  Q3's current answer is no.
- **Never call an otel4s macro or `inline` method**, in main sources *or* in
  scaladoc. `Tracer.span`, `Span.addAttribute(s)`, `Span.recordException`,
  `Span.setStatus`, `SpanBuilder.addAttribute(s)`, `withFinalizationStrategy`,
  `withSpanKind`, `withStartTimestamp` and `withParent` are all macros on Scala 2
  and `inline` with union-typed varargs on Scala 3. Task 6 greps for them.
- **Doctests compile and run.** `doctestOnlyCodeBlocksMode := true`
  (`build.sbt:46`) means every `{{{ }}}` block in this module is a compiled,
  executed test on both 2.13 and 3. Examples must work, not merely look right.
- **Zero new compiler warnings**, verified locally by forcing
  `-Xfatal-warnings`. **CI does not enforce this**: `sbt-typelevel-settings`
  0.8.6 defaults `tlFatalWarnings := false` and nothing in this repo overrides
  it. Forcing it locally is stricter than the gate, not catching up to one.
- **Tests assert on the decoded tree, never on JSON text.** `AnyValue.MapValue`
  wraps an unordered Scala `Map`; the spike observed keys returning in a
  different order than they were written. `Value.asString` and every `toString`
  in this stack render JSON-looking text. Compare `AnyValue`s (they have a
  lawful `Hash`, `AnyValue.scala:129`) or walk the Java
  `io.opentelemetry.api.common.Value` tree.
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
- **`mimaPreviousArtifacts := Set.empty` is carried forward, not fixed.** Five
  modules already have it and `otel4s-tagless` is the sixth. It is a live hazard
  — nothing suppresses publishing, so these modules will ship a real API at the
  next release with MiMa blind. The fix is `tlVersionIntroduced := Map(…)`, one
  decision covering all six, **needed before the next publish and out of scope
  here**. Copy the existing comment block verbatim so the count stays findable.
- There is no `scripts/check` in this repo. Canonical verification is the
  per-module sbt invocations named in each task.
- **`node` is not on the default PATH.** Any Scala.js test run needs
  `export PATH="$HOME/.nvm/versions/node/v22.21.1/bin:$PATH"` first. Linking
  (`Test/scalaJSLinkerResult`) does not need node; running (`test`) does.
- **Never use `--no-verify`** or any other hook-bypass flag.

**Names, fixed by Q1 and load-bearing for every command below.** Artifact
**`otel4s-tagless`**, directory `otel4s-tagless/`, sbt projects
`otel4sTaglessJVM` / `otel4sTaglessJS`, package **`com.dwolla.tracing.otel4s`**.
Q1 is ratified; do not substitute. The package name is not part of Q1; it
follows `natchez-tagless-mtl`'s `com.dwolla.tracing.mtl` precedent.

---

## File Structure

**New module, main:**

```
otel4s-tagless/src/main/scala/com/dwolla/tracing/otel4s/
  ToAnyValue.scala
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
  ToAnyValueSpec.scala
  ToAnyValueResolutionSpec.scala
  FooFixture.scala            -- hand-rolled Aspect, no macros
  WeaveAttributesOpsSpec.scala
  TracerTransparencySpec.scala
```

**New module, test (JVM only, via `Test / unmanagedSourceDirectories`):**

```
otel4s-tagless/src/test/scala-jvm/com/dwolla/tracing/otel4s/
  SpanContentSpec.scala
```

**Modified:** `build.sbt`, `.github/workflows/ci.yml` (regenerated),
`.mergify.yml` (regenerated),
`docs/plans/raise-aspect/01-overview-design-and-laws.md`,
`docs/plans/raise-aspect/28-milestone-M15-tagless-core-module.md`,
`docs/plans/raise-aspect/30-milestone-M16-otel4s-module.md` (status only).

**Deliberately unchanged:** every source file outside `otel4s-tagless/`.

---

## Task 1: the module, `ToAnyValue`, and its instances

The module cannot exist without a build entry and the build entry is pointless
without something to compile, so they land together. `ToAnyValue` is the right
first content: it is the milestone's load-bearing decision, it has no otel4s
*tracing* surface at all (only the attribute model), and every later task
depends on it.

**Files:**
- Modify: `build.sbt`
- Modify: `.github/workflows/ci.yml` and `.mergify.yml` (regenerated, not hand-edited)
- Create: `otel4s-tagless/src/main/scala/com/dwolla/tracing/otel4s/ToAnyValue.scala`
- Create: `otel4s-tagless/src/test/scala/com/dwolla/tracing/otel4s/ToAnyValueSpec.scala`
- Create: `otel4s-tagless/src/test/scala/com/dwolla/tracing/otel4s/ToAnyValueResolutionSpec.scala`

**Interfaces:**
- Consumes: `org.typelevel.otel4s.AnyValue` from `otel4s-core-trace`'s
  transitive `otel4s-core-common`; `cats.Show`.
- Produces:
  - `trait ToAnyValue[-A] { def toAnyValue(a: A): AnyValue }`
  - `object ToAnyValue extends LowPriorityToAnyValueInstances { def apply[A](implicit ev: ToAnyValue[A]): ToAnyValue[A]; def instance[A](f: A => AnyValue): ToAnyValue[A] }`
  - instances for `String`, `Boolean`, `Long`, `Double`, `Int`, `Short`,
    `Byte`, `Float`, `Unit`, `Option[A: ToAnyValue]`, `Seq[A: ToAnyValue]`,
    `Map[String, A: ToAnyValue]`
  - `trait LowPriorityToAnyValueInstances { implicit def showToAnyValue[A: Show]: ToAnyValue[A] }`
  - new sbt projects `otel4sTaglessJVM`, `otel4sTaglessJS`; artifact
    `com.dwolla:otel4s-tagless`

- [ ] **Step 1: Write the failing test**

Create `otel4s-tagless/src/test/scala/com/dwolla/tracing/otel4s/ToAnyValueSpec.scala`.
Cover the primitives, the widenings, and each of D3's divergences from
`ToTraceValue` explicitly — those are the assertions a future reader will want
to find when they wonder whether the divergence was deliberate. Every expected
value below was observed from a compiled prototype on both 2.13.18 and 3.3.8;
they are not guesses.

```scala
package com.dwolla.tracing.otel4s

import cats.Show
import com.dwolla.tracing.otel4s.ToAnyValueSpec._
import munit.FunSuite
import org.typelevel.otel4s.AnyValue

object ToAnyValueSpec {
  final case class Money(cents: Long)
  object Money {
    implicit val moneyShow: Show[Money] = Show.show(m => s"$$${m.cents}")
  }
}

class ToAnyValueSpec extends FunSuite {
  private def enc[A](a: A)(implicit ev: ToAnyValue[A]): AnyValue = ev.toAnyValue(a)

  test("String, Boolean, Long and Double encode to their native leaves") {
    assertEquals(enc("v"), AnyValue.string("v"))
    assertEquals(enc(true), AnyValue.boolean(true))
    assertEquals(enc(7L), AnyValue.long(7L))
    assertEquals(enc(1.5d), AnyValue.double(1.5d))
  }

  test("Int, Short and Byte widen to LongValue — Long is otel4s's only integral leaf") {
    assertEquals(enc(7), AnyValue.long(7L))
    assertEquals(enc(7.toShort), AnyValue.long(7L))
    assertEquals(enc(7.toByte), AnyValue.long(7L))
  }

  // D3. otel4s has no Float leaf, so the widening is to the exact Double the
  // Float denotes. It prints with extra digits, and that is deliberate: the
  // alternative (_.toString.toDouble) prints prettily by changing the value.
  test("Float widens to the exact Double it denotes") {
    assertEquals(enc(0.1f), AnyValue.double(0.1f.toDouble))
    assertEquals(enc(0.1f).toString, "DoubleValue(0.10000000149011612)")
    assert(enc(0.1f) != AnyValue.double(0.1d))
  }

  // D3. natchez records the strings "()" and "None". A typed empty value beats
  // a string that looks like data, and EmptyValue is OTLP's own encoding of
  // absence — it reaches the wire as {}.
  test("Unit and None encode to EmptyValue, not to strings") {
    assertEquals(enc(()), AnyValue.empty)
    assertEquals(enc(Option.empty[String]), AnyValue.empty)
    assertEquals(enc(Option("v")), AnyValue.string("v"))
  }

  // D4. The flat design could not offer this instance, because Attributes
  // deduplicates by key and per-element attributes under one name would have
  // kept only the last. AnyValue.seq makes a sequence one value.
  test("the generic Seq instance composes, to any depth") {
    assertEquals(enc(Seq("a", "b")), AnyValue.seq(Seq(AnyValue.string("a"), AnyValue.string("b"))))
    assertEquals(enc(Seq(1, 2)), AnyValue.seq(Seq(AnyValue.long(1L), AnyValue.long(2L))))
    assertEquals(enc(List(List(1))), AnyValue.seq(Seq(AnyValue.seq(Seq(AnyValue.long(1L))))))
  }

  test("a Map[String, A] encodes as a MapValue") {
    assertEquals(enc(Map("k" -> 1)), AnyValue.map(Map("k" -> AnyValue.long(1L))))
  }

  test("a type with only a Show instance falls back to its rendering") {
    assertEquals(enc(Money(500)), AnyValue.string("$500"))
    assertEquals(enc(Seq(Money(1))), AnyValue.seq(Seq(AnyValue.string("$1"))))
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
sbt -batch "otel4sTaglessJVM/testOnly com.dwolla.tracing.otel4s.ToAnyValueSpec"
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
      // core-trace dependsOn core-common, which carries AnyValue, Attribute,
      // AttributeKey and Attributes, so this one coordinate brings both the
      // tracing API and the attribute model. otel4s-core is an umbrella that
      // would also drag in logs+metrics.
      "org.typelevel" %%% "otel4s-core-trace" % otel4sVersion,
      "org.typelevel" %%% "cats-core" % catsVersion,
      "org.typelevel" %%% "cats-tagless-core" % catsTaglessVersion,
      "org.scalameta" %%% "munit" % munitVersion % Test,
    ),
    // `Set.empty` is a live hazard, not a permanent no-op: nothing suppresses
    // publishing, so this module WILL be published with a real API at the
    // next release, and MiMa will not catch a breaking change after that. The
    // fix is `tlVersionIntroduced := Map(...)` in place of `Set.empty`, which
    // keeps MiMa live from the module's first release onward — one decision
    // covering all six `Set.empty` modules, needed before the next publish,
    // not made here. See "Anything a later milestone needs" in
    // docs/plans/raise-aspect/28-milestone-M15-tagless-core-module.md.
    mimaPreviousArtifacts := Set.empty,
  )
  .settings(doctestSettings *)
  .dependsOn(taglessCore)
```

and add `otel4sTagless` to `tlCrossRootProject.aggregate(…)`, after
`natchezTaglessMtl`.

Note the `%%%` on every coordinate including `munit % Test`. See the Global
Constraints — `%%` here is the bug that silenced `coreJS`'s entire suite.

Do **not** add the JVM-only testkit yet — that arrives in Task 2, where it has
a user.

- [ ] **Step 4: Regenerate the CI workflow and the mergify config**

```bash
sbt -batch githubWorkflowGenerate mergifyGenerate
git diff --stat .github/workflows/ .mergify.yml
sbt -batch githubWorkflowCheck
```

`.github/workflows/ci.yml:59` runs `githubWorkflowCheck` in CI, and `:78`/`:82`
enumerate every project's target directory by hand, so a new module makes the
committed workflow stale and CI red for a reason unrelated to the code.
Expected: `ci.yml` gains `otel4s-tagless/.jvm/target` and
`otel4s-tagless/.js/target` in the mkdir/compress steps, `.mergify.yml` gains a
`Label otel4s-tagless PRs` rule matching `files~=^otel4s-tagless/`, and
`githubWorkflowCheck` then passes. **Do not hand-edit either file** — both say
at the top that they are generated.

- [ ] **Step 5: Write `ToAnyValue.scala`**

Create `otel4s-tagless/src/main/scala/com/dwolla/tracing/otel4s/ToAnyValue.scala`.
This compiles as written on 2.13.18 and 3.3.8 — it was built and run against
`otel4s-core-trace:1.0.1` before this plan was written.

```scala
package com.dwolla.tracing.otel4s

import cats.Show
import cats.syntax.all._
import org.typelevel.otel4s.AnyValue

/** Converts a value of type `A` into an otel4s `AnyValue`, OpenTelemetry's
  * recursive "any" type: a primitive leaf, a sequence, or a key-value map.
  *
  * This is the otel4s counterpart of `natchez.TraceableValue`, and it exists
  * because otel4s ships nothing of the right shape. `Attribute.From` and
  * `Attribute.Make` both take two type parameters. `Attributes.Make` takes one,
  * but it bakes the attribute ''name'' into the instance and upstream ships
  * essentially no instances for it — the Scala 2 `MakeCompanion` is empty.
  *
  * The name does not appear in this signature at all, and that is the design:
  * a method's parameters are recorded as '''one''' attribute,
  * `<algebraName>.<methodName>.parameters`, whose value is an `AnyValue` map
  * keyed by parameter name; the return value is recorded as
  * `<algebraName>.<methodName>.returnValue`. Names come from the `Aspect`'s
  * `Advice`, at the call site, where they belong.
  *
  * '''The result type is `AnyValue`, never one of its subtypes.'''
  * `AnyValue.map(...)` is typed at `AnyValue.MapValue`, and
  * `AttributeKey.KeySelect` is invariant, so `Attribute("k", AnyValue.map(...))`
  * does not compile — and the compiler's message lists only the eight flat
  * types and never mentions `AnyValue`, which makes it actively misleading.
  * Widening anywhere fixes it; declaring this method's result as `AnyValue` is
  * what keeps callers from ever seeing it.
  *
  * '''Requires `opentelemetry-api` 1.59.0 or newer on the `otel4s-oteljava`
  * backend.''' Structured attribute values reach the OpenTelemetry Java SDK
  * through `io.opentelemetry.api.common.AttributeType.VALUE`, which was added
  * in release 1.59.0. otel4s 1.0.1 pulls a newer version transitively, so the
  * default is fine; an application that pins an older SDK will fail to link
  * `AttributeKey.valueKey` inside otel4s's own converter. This module declares
  * no dependency on the Java SDK and cannot enforce the floor for you.
  *
  * Contravariant because `A` occurs only in negative position, matching
  * otel4s's own `Attributes.Make[-A]`; that is what lets a `List[String]` or
  * `Vector[Long]` parameter resolve the generic `Seq` instance.
  */
trait ToAnyValue[-A] {
  def toAnyValue(a: A): AnyValue
}

object ToAnyValue extends LowPriorityToAnyValueInstances {
  def apply[A](implicit ev: ToAnyValue[A]): ToAnyValue[A] = ev

  def instance[A](f: A => AnyValue): ToAnyValue[A] =
    new ToAnyValue[A] {
      override def toAnyValue(a: A): AnyValue = f(a)
    }

  implicit val stringToAnyValue: ToAnyValue[String] = instance[String](AnyValue.string)
  implicit val booleanToAnyValue: ToAnyValue[Boolean] = instance[Boolean](AnyValue.boolean)
  implicit val longToAnyValue: ToAnyValue[Long] = instance[Long](AnyValue.long)
  implicit val doubleToAnyValue: ToAnyValue[Double] = instance[Double](AnyValue.double)

  // Long is otel4s's only integral leaf; Int, Short and Byte widen exactly.
  implicit val intToAnyValue: ToAnyValue[Int] = instance[Int](i => AnyValue.long(i.toLong))
  implicit val shortToAnyValue: ToAnyValue[Short] = instance[Short](s => AnyValue.long(s.toLong))
  implicit val byteToAnyValue: ToAnyValue[Byte] = instance[Byte](b => AnyValue.long(b.toLong))

  /** otel4s has no `Float` leaf, so a `Float` records as the exact `Double` it
    * denotes — `0.1f` becomes `0.10000000149011612`. Shadow this instance if
    * you would rather record the shorter rendering; doing so changes the value,
    * which is why it is not the default.
    */
  implicit val floatToAnyValue: ToAnyValue[Float] = instance[Float](f => AnyValue.double(f.toDouble))

  /** A `Unit` return value records as an empty value, which is OTLP's own
    * encoding of "no value" and reaches the wire as `{}`.
    * (`natchez.TraceableValue` records the string `"()"`.)
    */
  implicit val unitToAnyValue: ToAnyValue[Unit] = instance[Unit](_ => AnyValue.empty)

  /** An absent value records as an empty value rather than being omitted: this
    * type class is a total `A => AnyValue` with no channel for "nothing", and
    * giving it one would force every instance — and every nesting site — to
    * answer what a sequence does with an absent element. As a map entry the
    * cost is a null-valued key, not a whole attribute slot.
    * (`natchez.TraceableValue` records the string `"None"`.)
    */
  implicit def optionToAnyValue[A](implicit ev: ToAnyValue[A]): ToAnyValue[Option[A]] =
    instance[Option[A]] {
      case Some(a) => ev.toAnyValue(a)
      case None => AnyValue.empty
    }

  /** A sequence is '''one''' `AnyValue`, so unlike a flat attribute model this
    * generic instance is safe: there is no per-element key to collide.
    * It nests to any depth.
    */
  implicit def seqToAnyValue[A](implicit ev: ToAnyValue[A]): ToAnyValue[Seq[A]] =
    instance[Seq[A]](as => AnyValue.seq(as.map(ev.toAnyValue)))

  implicit def mapToAnyValue[A](implicit ev: ToAnyValue[A]): ToAnyValue[Map[String, A]] =
    instance[Map[String, A]](m => AnyValue.map(m.map { case (k, v) => k -> ev.toAnyValue(v) }))
}

/** The fallback, at lower implicit priority than everything in the companion's
  * own body — which is why this type class needs none of the `NotGiven`
  * ambiguity guards `com.dwolla.tracing.ToTraceValue` carries. Those exist
  * because natchez's primitive instances live in an upstream companion at the
  * same priority as the fallback; ours live one rung up, in a companion we own.
  */
trait LowPriorityToAnyValueInstances {
  implicit def showToAnyValue[A: Show]: ToAnyValue[A] =
    ToAnyValue.instance[A](a => AnyValue.string(a.show))
}
```

Keep the scaladoc — every paragraph above records a decision someone will
otherwise re-litigate, and the `opentelemetry-api` paragraph is one of the two
places D10 requires it to appear.

- [ ] **Step 6: Run the test to verify it passes**

```bash
sbt -batch "otel4sTaglessJVM/testOnly com.dwolla.tracing.otel4s.ToAnyValueSpec"
```

Expected: PASS, 7 tests, on 2.13.18.

- [ ] **Step 7: Prove the priority ladder, and prove the contravariance claim**

This is decision D2 and half of D1. A prototype has already shown both hold
under scala-cli, but scala-cli is not sbt and does not carry this build's
scalacOptions, so re-assert them here. Create
`ToAnyValueResolutionSpec.scala`:

```scala
package com.dwolla.tracing.otel4s

import munit.FunSuite
import org.typelevel.otel4s.AnyValue

/** D1 and D2, made falsifiable.
  *
  * Each `implicitly` here is a compile-time assertion: if the priority ladder
  * did not work, or if contravariance did not do what the milestone document
  * claims, the module would not build and this file is where the error lands.
  */
class ToAnyValueResolutionSpec extends FunSuite {
  test("a primitive resolves to its own instance, not to the Show fallback") {
    // Show[String] exists, so without the priority ladder this is ambiguous.
    assertEquals(implicitly[ToAnyValue[String]].toAnyValue("v"), AnyValue.string("v"))
  }

  test("Show[Int] and Show[Boolean] do not shadow the primitive instances either") {
    assertEquals(implicitly[ToAnyValue[Int]].toAnyValue(3), AnyValue.long(3L))
    assertEquals(implicitly[ToAnyValue[Boolean]].toAnyValue(true), AnyValue.boolean(true))
  }

  test("contravariance lets List and Vector use the generic Seq instance") {
    assertEquals(implicitly[ToAnyValue[List[String]]].toAnyValue(List("a")), AnyValue.seq(Seq(AnyValue.string("a"))))
    assertEquals(implicitly[ToAnyValue[Vector[Long]]].toAnyValue(Vector(1L)), AnyValue.seq(Seq(AnyValue.long(1L))))
  }

  test("Show[List[String]] does not shadow the generic Seq instance") {
    // cats has Show[List[A]]; if the fallback outranked the companion, this
    // would be StringValue("List(a)") instead.
    assertEquals(implicitly[ToAnyValue[List[String]]].toAnyValue(List("a")).toString, "SeqValue([StringValue(a)])")
  }
}
```

Run it:

```bash
sbt -batch "otel4sTaglessJVM/testOnly com.dwolla.tracing.otel4s.ToAnyValueResolutionSpec"
```

**If any of these fails to compile with an ambiguity error, stop and report
before working around it.** The two possible corrections are named in the
milestone document: for the priority half, the `NotGiven` guards
`ToTraceValue.scala` uses, which costs a `scalac-compat-features` dependency;
for the contravariance half, invariance plus explicit `List`/`Vector`
instances. Both are design changes, not local fixes, and both belong in a
report rather than in a quiet edit.

Task 5, Step 1 adds two constraint-profile assertions to this same file and the
imports they need (`cats.Id`, `cats.tagless.aop.{Aspect, Instrument}`,
`cats.tagless.syntax.all._`, `com.dwolla.tracing.otel4s.syntax._` and
`org.typelevel.otel4s.trace.Tracer`). Do not add them now — an unused import is
a fatal warning under Step 9.

- [ ] **Step 8: Both Scala versions, both platforms, and the 2.12 exclusion**

```bash
export PATH="$HOME/.nvm/versions/node/v22.21.1/bin:$PATH"
sbt -batch "+otel4sTaglessJVM/test"
sbt -batch "+otel4sTaglessJS/test"
sbt -batch "show otel4sTaglessJS/Test/definedTests"
sbt -batch "show otel4sTaglessJVM/crossScalaVersions" "show coreJVM/crossScalaVersions"
sbt -batch "++ 2.12 natchez-tagless-rootJVM/test"
```

Expected, in order: `+` runs **two** versions (2.13.18 and 3.3.8) and both are
green; the JS suites run and report the **same test count** as the JVM ones;
`definedTests` is non-empty and names both specs; `otel4sTaglessJVM`'s list is
`Seq(2.13.18, 3.3.8)` while `coreJVM`'s still contains `2.12.21`; and the
2.12 aggregate run is green, having **skipped** the new module rather than
failed on it.

Two of those matter more than they look:

- **A green JS `test` with zero tests is the failure mode this build has already
  had.** If `definedTests` is empty, or the JS test count is lower than the JVM
  one, a dependency is resolving as a JVM artifact — check every `%%%`. Do not
  proceed.
- **The 2.12 run is the mechanism the whole exclusion rests on** — sbt ≥ 1.4's
  `++` only switches projects that support the version and drops the others from
  aggregation — and CI runs exactly this shape (`ci.yml:66`). **If it fails
  rather than skips, stop and report**; every downstream assumption about the
  exclusion is then wrong.

- [ ] **Step 9: Zero warnings**

```bash
sbt -batch "set otel4sTagless.jvm/scalacOptions += \"-Xfatal-warnings\"" "+otel4sTaglessJVM/test"
```

Expected: PASS on both versions. Note this module has `doctestSettings`, which
filters `-Wunused` out of the `Test` scope (`build.sbt:47-49`) — so this check
covers the main sources properly and the test sources only partially. That is
the same coverage `core` has and is not a new gap.

- [ ] **Step 10: Commit**

```bash
git add build.sbt .github/workflows/ .mergify.yml otel4s-tagless/
git commit -m "feat: add the otel4s-tagless module and its ToAnyValue type class

otel4s ships no TraceableValue analogue: Attribute.From and Attribute.Make both
take two type parameters, and Attributes.Make bakes the attribute name into the
instance while shipping no instances on Scala 2. A Weave's Dom needs kind
* -> *, so the module owns ToAnyValue[-A].

It encodes to otel4s's structured AnyValue rather than to flat attributes. A
spike confirmed AnyValue survives the oteljava round trip and reaches the OTLP
wire as a native kvlistValue, that a whole structured map costs one attribute
slot instead of one per parameter, and that maxAttributeValueLength still
recurses into the tree. The declared result type is AnyValue, never a subtype,
because KeySelect is invariant.

The module cross-builds 2.13 and 3 only. otel4s has never published a _2.12
artifact at any version, so this is one new module without 2.12 rather than a
2.12 drop for the library; every other module is unchanged."
```

---

## Task 2: `TracerInstrumentation`, and the split test harness

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
  - `FooFixture`: a `Foo[F[_]]` algebra with a hand-written
    `Aspect[Foo, ToAnyValue, ToAnyValue]` (no macros, so the fixture is
    identical on 2.13 and 3).

- [ ] **Step 1: Read the testkit's API before writing a line against it**

The plan cannot quote an API it has not read, and two pieces are flagged
unverified in the milestone document. Do this first:

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

and `TracerProvider#get(name: String): F[Tracer[F]]`
(`reference/upstream/otel4s/core/trace/.../TracerProvider.scala:41`).

`SpanData` is the **OpenTelemetry Java** model, so assertions read `.getName`
and `.getAttributes`, or use the testkit's own `SpanExpectation` /
`TraceExpectations` DSL in the same package.

**Two things to resolve here, not guess:**

1. **Where `LocalContextProvider[IO]` comes from.** It is a type alias for
   `org.typelevel.otel4s.context.LocalProvider[F, Context]`. otel4s's own
   examples call `TracesTestkit.inMemory[IO]()` with no extra wiring, which
   implies an implicit instance for `IO`, but the instance was not located in
   the 1.0.1 sources. Find it and write down where it lives. If no implicit
   instance exists for `IO` without extra wiring, say so in the task report — it
   changes how the suite is set up and possibly which artifact is needed.
2. **Whether reading a Java `Attributes` back into otel4s `Attributes` has a
   public entry point.** `org.typelevel.otel4s.oteljava.AttributeConverters`
   exists in `otel4s-oteljava-common`, but the conversion logic lives in a
   `private object Explicit`; check the enclosing object for public syntax.
   **If it is public, use it** — comparing `AnyValue`s is far more readable than
   walking the Java tree, and `AnyValue` has a lawful `Hash`. **If it is not,
   assert on `io.opentelemetry.api.common.Value`**, which is unambiguously
   public: `value.getType == ValueType.KEY_VALUE_LIST` and `value.getValue`
   returns a `List<KeyValue>` of typed `Value`s. Record which path you took.

Under **no** circumstances assert on `Value.asString` or any `toString`: the map
is unordered and the JSON text is not stable.

- [ ] **Step 2: Write the failing tests**

Three files.

First the fixture, `FooFixture.scala` — a hand-written `Aspect` rather than
`Derive.aspect`, so the module needs no `cats-tagless-macros` dependency on 2.13
and the fixture is byte-identical on both axes. Model it on the hand-rolled
`Aspect` in `TraceWeaveCapturingInputsAndOutputs`'s scaladoc
(`core/shared/src/main/scala/com/dwolla/tracing/TraceWeaveCapturingInputsAndOutputs.scala:66-84`):

```scala
package com.dwolla.tracing.otel4s

import cats.tagless.aop.Aspect
import cats.~>

trait Foo[F[_]] {
  def greet(name: String, times: Int): F[String]
  def ping(): F[Unit]
}

object Foo {
  // Only the Aspect is implicit. Aspect extends Instrument, so `Instrument[Foo]`
  // resolves to this by subtyping; declaring a second `implicit val
  // fooInstrument: Instrument[Foo] = fooAspect` would make every
  // `Instrument[Foo]` summon ambiguous.
  implicit val fooAspect: Aspect[Foo, ToAnyValue, ToAnyValue] =
    new Aspect[Foo, ToAnyValue, ToAnyValue] {
      override def weave[F[_]](af: Foo[F]): Foo[Aspect.Weave[F, ToAnyValue, ToAnyValue, *]] =
        new Foo[Aspect.Weave[F, ToAnyValue, ToAnyValue, *]] {
          override def greet(name: String, times: Int): Aspect.Weave[F, ToAnyValue, ToAnyValue, String] =
            Aspect.Weave[F, ToAnyValue, ToAnyValue, String](
              "Foo",
              List(List(
                Aspect.Advice.byValue[ToAnyValue, String]("name", name),
                Aspect.Advice.byValue[ToAnyValue, Int]("times", times),
              )),
              Aspect.Advice[F, ToAnyValue, String]("greet", af.greet(name, times))
            )

          override def ping(): Aspect.Weave[F, ToAnyValue, ToAnyValue, Unit] =
            Aspect.Weave[F, ToAnyValue, ToAnyValue, Unit](
              "Foo",
              List(List.empty),
              Aspect.Advice[F, ToAnyValue, Unit]("ping", af.ping())
            )
        }

      override def mapK[F[_], G[_]](af: Foo[F])(fk: F ~> G): Foo[G] =
        new Foo[G] {
          override def greet(name: String, times: Int): G[String] = fk(af.greet(name, times))
          override def ping(): G[Unit] = fk(af.ping())
        }
    }
}
```

`ping()` exists so that Tasks 3 and 4 can assert the no-parameter and
`Unit`-return cases end to end.

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

  private val underlying: Foo[Id] = new Foo[Id] {
    override def greet(name: String, times: Int): Id[String] = s"hello $name" * times
    override def ping(): Id[Unit] = ()
  }

  test("an instrumented call returns exactly what the underlying call returns") {
    val instrumented: Foo[Id] = underlying.instrument.mapK(TracerInstrumentation[Id])

    assertEquals(instrumented.greet("world", 2), underlying.greet("world", 2))
    assertEquals(instrumented.ping(), underlying.ping())
  }
}
```

And `src/test/scala-jvm/.../SpanContentSpec.scala`, the JVM half, using
`TracesTestkit.inMemory[IO]()` from Step 1 and `munit-cats-effect`:

```scala
package com.dwolla.tracing.otel4s

import cats.effect.IO
import cats.syntax.all._
import cats.tagless.syntax.all._
import io.opentelemetry.sdk.trace.data.SpanData
import munit.CatsEffectSuite
import org.typelevel.otel4s.oteljava.testkit.trace.TracesTestkit
import org.typelevel.otel4s.trace.Tracer

class SpanContentSpec extends CatsEffectSuite {
  /** Runs `f` with a recording `Tracer[IO]` and returns the spans it finished. */
  protected def spansFrom(f: Tracer[IO] => IO[Unit]): IO[List[SpanData]] =
    TracesTestkit.inMemory[IO]().use { testkit =>
      testkit.tracerProvider.get("otel4s-tagless-test").flatMap(f) >> testkit.finishedSpans
    }

  protected val underlying: Foo[IO] = new Foo[IO] {
    override def greet(name: String, times: Int): IO[String] = IO.pure(s"hello $name" * times)
    override def ping(): IO[Unit] = IO.unit
  }

  test("each method call opens one span named algebraName.methodName") {
    spansFrom { implicit tracer =>
      underlying.instrument.mapK(TracerInstrumentation[IO]).greet("world", 2).void
    }.map(spans => assertEquals(spans.map(_.getName), List("Foo.greet")))
  }

  test("TracerInstrumentation records no attributes of its own") {
    spansFrom { implicit tracer =>
      underlying.instrument.mapK(TracerInstrumentation[IO]).greet("world", 2).void
    }.map(spans => assertEquals(spans.map(_.getAttributes.size), List(0)))
  }
}
```

`spansFrom` and `underlying` are `protected` because Tasks 3 and 4 add tests to
this same class. Adjust the attribute-reading idiom to whatever Step 1
established for the richer assertions; here the claim is just "no attributes".

- [ ] **Step 3: Run them to verify they fail**

```bash
sbt -batch "otel4sTaglessJVM/testOnly com.dwolla.tracing.otel4s.*"
```

Expected: compile failure — `TracerInstrumentation` does not exist, and the
JVM test source directory is not on the build's source path yet.

- [ ] **Step 4: Wire the JVM-only test dependency and source directory**

In `build.sbt`, add to the `otel4sTagless` definition, following
`raiseAspectCore`'s existing split (`build.sbt:164-166`) and `core`'s existing
JVM-only test dependency (`build.sbt:112-116`):

```scala
  // Span *content* can only be asserted with a testkit: every otel4s span type
  // is sealed and its Unsealed variant is private[otel4s], so a recording
  // Tracer cannot be hand-rolled. The cross-platform testkit
  // (otel4s-sdk-trace-testkit) has not been released at 1.0.x — it stops at
  // 0.19.0 — so at otel4s 1.0.1 the only option is the JVM one. Cross-platform
  // coverage lives in TracerTransparencySpec, which needs no testkit.
  //
  // `%%` is correct here and only here: .jvmSettings has no JS artifact to
  // resolve. Everything in the shared settings block above uses `%%%`.
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
sbt -batch githubWorkflowGenerate mergifyGenerate && sbt -batch githubWorkflowCheck
```

- [ ] **Step 5: Write `TracerInstrumentation.scala`**

```scala
package com.dwolla.tracing.otel4s

import cats.tagless.aop.Instrumentation
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

- [ ] **Step 7: Confirm the JS build excludes the JVM-only sources and still runs**

```bash
export PATH="$HOME/.nvm/versions/node/v22.21.1/bin:$PATH"
sbt -batch "+otel4sTaglessJS/test"
sbt -batch "show otel4sTaglessJS/Test/definedTests" "show otel4sTaglessJS/Test/unmanagedSourceDirectories"
```

Expected: the JS suites **run** on both versions, with a count equal to the JVM
count minus the `scala-jvm` tests; `definedTests` names the cross-platform specs
and not `SpanContentSpec`; and the JS project's source-directory list does
**not** contain `src/test/scala-jvm`. If the JS build fails on
`io.opentelemetry.sdk.…`, the directory leaked into the shared build — fix the
`.jvmSettings` placement, do not stub the class. If `definedTests` is empty,
re-read every `%%%`.

- [ ] **Step 8: Commit**

```bash
git add build.sbt .github/workflows/ .mergify.yml otel4s-tagless/
git commit -m "feat: add TracerInstrumentation and the module's test harness

One span per method call, named algebraName.methodName, over the sealed
spanBuilder/build/surround path rather than the Tracer.span macro.

Testing is split because otel4s's span types are all sealed with private[otel4s]
Unsealed variants, so a recording Tracer cannot be hand-rolled: transparency is
asserted cross-platform over Tracer.noop, and span content is asserted on the
JVM with otel4s-oteljava-trace-testkit. The cross-platform SDK testkit has not
been released at 1.0.x, so span content is unverified on Scala.js — a known,
accepted asymmetry."
```

---

## Task 3: `TracerWeaveCapturingInputs` and `Weave#asAttributes`

The first interpreter that reads the domain, so the `asAttributes` syntax lands
with it — the interpreter is its only caller and neither is testable without the
other. This is where D1's one-attribute layout is actually built.

**Files:**
- Create: `otel4s-tagless/src/main/scala/com/dwolla/tracing/otel4s/syntax/WeaveAttributesOps.scala`
- Create: `otel4s-tagless/src/main/scala/com/dwolla/tracing/otel4s/TracerWeaveCapturingInputs.scala`
- Create: `otel4s-tagless/src/test/scala/com/dwolla/tracing/otel4s/WeaveAttributesOpsSpec.scala`
- Modify: `otel4s-tagless/src/test/scala-jvm/com/dwolla/tracing/otel4s/SpanContentSpec.scala`
- Modify: `otel4s-tagless/src/test/scala/com/dwolla/tracing/otel4s/TracerTransparencySpec.scala`

**Interfaces:**
- Consumes: Task 1's `ToAnyValue`; `AnyValue.map` (`AnyValue.scala:121-122`);
  `SpanBuilder#modifyState` (`SpanBuilder.scala:43`);
  `SpanBuilder.State#addAttributes` (`SpanBuilder.scala:140`), which takes
  `immutable.Iterable[Attribute[_]]` and therefore accepts `Attributes`
  directly (`Attributes.scala:36-38`).
- Produces:
  - `trait ToWeaveAttributesOps { implicit def toWeaveAttributesOps[F[_], Cod[_], A](fa: Weave[F, ToAnyValue, Cod, A]): WeaveAttributesOps[F, Cod, A] }`
  - `class WeaveAttributesOps[F[_], Cod[_], A](val fa: Weave[F, ToAnyValue, Cod, A]) extends AnyVal { def asAttributes: Attributes }`
  - `object TracerWeaveCapturingInputs { def apply[F[_]: Tracer, Cod[_]]: Weave[F, ToAnyValue, Cod, *] ~> F }`
  - `class TracerWeaveCapturingInputs[F[_]: Tracer, Cod[_]] extends (Weave[F, ToAnyValue, Cod, *] ~> F)`

- [ ] **Step 1: Write the failing test**

`WeaveAttributesOpsSpec.scala` asserts the layout against hand-built `Weave`s,
so no algebra is needed:

```scala
package com.dwolla.tracing.otel4s

import cats.{Eval, Id}
import cats.tagless.aop.Aspect
import com.dwolla.tracing.otel4s.syntax._
import munit.FunSuite
import org.typelevel.otel4s.{AnyValue, Attribute, Attributes}

class WeaveAttributesOpsSpec extends FunSuite {
  private def weaveOf(domain: List[List[Aspect.Advice[Eval, ToAnyValue]]]): Aspect.Weave[Id, ToAnyValue, ToAnyValue, String] =
    Aspect.Weave[Id, ToAnyValue, ToAnyValue, String](
      "Foo",
      domain,
      Aspect.Advice[Id, ToAnyValue, String]("greet", "hi")
    )

  // The explicit [AnyValue] is the D1 rule in test form: AnyValue.map(...) is
  // typed at AnyValue.MapValue and KeySelect is invariant, so without a
  // widening somewhere this line does not compile.
  private def expected(entries: (String, AnyValue)*): Attributes =
    Attributes(Attribute[AnyValue]("Foo.greet.parameters", AnyValue.map(entries.toMap)))

  test("every parameter of every parameter list lands in one `parameters` map") {
    val weave = weaveOf(List(
      List(Aspect.Advice.byValue[ToAnyValue, String]("name", "world")),
      List(Aspect.Advice.byValue[ToAnyValue, Int]("times", 2)),
    ))

    assertEquals(
      weave.asAttributes,
      expected("name" -> AnyValue.string("world"), "times" -> AnyValue.long(2L))
    )
  }

  test("exactly one attribute is produced, whatever the parameter count") {
    val weave = weaveOf(List(List(
      Aspect.Advice.byValue[ToAnyValue, Int]("a", 1),
      Aspect.Advice.byValue[ToAnyValue, Int]("b", 2),
      Aspect.Advice.byValue[ToAnyValue, Int]("c", 3),
    )))

    assertEquals(weave.asAttributes.size, 1)
  }

  // D3: absence is an EmptyValue entry, not a missing one.
  test("a parameter that encodes to nothing is an EmptyValue entry") {
    val weave = weaveOf(List(List(
      Aspect.Advice.byValue[ToAnyValue, Option[String]]("note", None),
      Aspect.Advice.byValue[ToAnyValue, Unit]("nothing", ()),
    )))

    assertEquals(weave.asAttributes, expected("note" -> AnyValue.empty, "nothing" -> AnyValue.empty))
  }

  // D3: no special case for the empty domain either.
  test("a method with no parameters still records an empty parameters map") {
    assertEquals(weaveOf(List(List.empty)).asAttributes, expected())
    assertEquals(weaveOf(List.empty).asAttributes, expected())
  }

  test("a by-name parameter is forced exactly once, when asAttributes is called") {
    var forced = 0
    val weave = weaveOf(List(List(
      Aspect.Advice.byName[ToAnyValue, String]("lazyParam", { forced += 1; "x" })
    )))

    assertEquals(forced, 0)
    assertEquals(weave.asAttributes, expected("lazyParam" -> AnyValue.string("x")))
    assertEquals(forced, 1)
  }
}
```

Extend `SpanContentSpec` with the inputs assertion — one call to
`underlying.weave.mapK(TracerWeaveCapturingInputs[IO, ToAnyValue])` produces one
span named `Foo.greet` carrying exactly one attribute, `Foo.greet.parameters`,
whose decoded value is the map `{name -> "world", times -> 2}` with `times` a
**long**, not a string. Read the tree, not the text.

Extend `TracerTransparencySpec` with two cases for this interpreter:

```scala
  test("TracerWeaveCapturingInputs returns exactly what the underlying call returns") {
    val traced: Foo[Id] = underlying.weave.mapK(TracerWeaveCapturingInputs[Id, ToAnyValue])

    assertEquals(traced.greet("world", 2), underlying.greet("world", 2))
  }

  // Tracer.noop's SpanBuilder#modifyState is `this` — it never applies the
  // function — so with tracing disabled the parameters are never encoded at
  // all. That only holds if the interpreter builds `asAttributes` *inside* the
  // modifyState lambda. Hoisting it to a val outside would break this test,
  // which is the point of having it.
  test("with a noop Tracer the parameter encoding is never performed") {
    var forced = 0
    val weave = Aspect.Weave[Id, ToAnyValue, ToAnyValue, String](
      "Foo",
      List(List(Aspect.Advice.byName[ToAnyValue, String]("lazyParam", { forced += 1; "x" }))),
      Aspect.Advice[Id, ToAnyValue, String]("greet", "hi")
    )

    assertEquals(TracerWeaveCapturingInputs[Id, ToAnyValue].apply(weave), "hi")
    assertEquals(forced, 0)
  }
```

- [ ] **Step 2: Run to verify it fails**

```bash
sbt -batch "otel4sTaglessJVM/test"
```

Expected: compile failure on `asAttributes` and `TracerWeaveCapturingInputs`.

- [ ] **Step 3: Write `WeaveAttributesOps.scala`**

Structurally this is `TraceParamsOps`'s two-level traversal with a different
destination: one `AnyValue.map` rather than a `List[(String, TraceValue)]`.

```scala
package com.dwolla.tracing.otel4s
package syntax

import cats.tagless.aop.Aspect.Weave
import org.typelevel.otel4s.{AnyValue, Attribute, Attributes}

trait ToWeaveAttributesOps {
  implicit def toWeaveAttributesOps[F[_], Cod[_], A](fa: Weave[F, ToAnyValue, Cod, A]): WeaveAttributesOps[F, Cod, A] =
    new WeaveAttributesOps(fa)
}

class WeaveAttributesOps[F[_], Cod[_], A](val fa: Weave[F, ToAnyValue, Cod, A]) extends AnyVal {
  /** All of the call's parameters, from every parameter list, as exactly one
    * attribute named `algebraName.methodName.parameters` whose value is an
    * `AnyValue` map keyed by parameter name.
    *
    * One attribute rather than one per parameter is deliberate: a structured
    * map counts once against `SpanLimits.maxNumberOfAttributes` (default 128),
    * so a twenty-parameter method costs one slot instead of twenty, and
    * `maxAttributeValueLength` still recurses into the tree, so nothing escapes
    * truncation by being nested.
    *
    * Flattening the parameter lists cannot lose a parameter: Scala rejects
    * duplicate parameter names within a method signature, including across
    * parameter lists.
    *
    * The `parameters` local is typed `AnyValue` on purpose — `AnyValue.map`
    * returns the precise subtype `AnyValue.MapValue`, and `KeySelect` is
    * invariant, so `Attribute(name, AnyValue.map(...))` does not compile.
    */
  def asAttributes: Attributes = {
    val parameters: AnyValue =
      AnyValue.map(
        fa.domain.flatten.map { advice =>
          // Verbose keys, but the OpenTelemetry attribute-naming spec says to
          // namespace everything:
          // https://opentelemetry.io/docs/specs/semconv/general/attribute-naming/
          advice.name -> advice.instance.toAnyValue(advice.target.value)
        }.toMap
      )

    Attributes(Attribute(s"${fa.algebraName}.${fa.codomain.name}.parameters", parameters))
  }
}
```

Note `advice.target.value` forces the `Eval` — same as natchez, and the reason
`TracerWeaveCapturingInputs` must not call `asAttributes` before it means to.

- [ ] **Step 4: Write `TracerWeaveCapturingInputs.scala`**

```scala
package com.dwolla.tracing.otel4s

import cats.tagless.aop.Aspect.Weave
import cats.~>
import com.dwolla.tracing.otel4s.syntax._
import org.typelevel.otel4s.trace.Tracer

object TracerWeaveCapturingInputs {
  def apply[F[_]: Tracer, Cod[_]]: Weave[F, ToAnyValue, Cod, *] ~> F =
    new TracerWeaveCapturingInputs[F, Cod]
}

class TracerWeaveCapturingInputs[F[_]: Tracer, Cod[_]] extends (Weave[F, ToAnyValue, Cod, *] ~> F) {
  override def apply[A](fa: Weave[F, ToAnyValue, Cod, A]): F[A] =
    Tracer[F]
      .spanBuilder(s"${fa.algebraName}.${fa.codomain.name}")
      // asAttributes stays *inside* this lambda. Tracer.noop's modifyState
      // never applies the function, so a disabled tracer pays nothing for
      // encoding — and by-name parameters are never forced.
      .modifyState(_.addAttributes(fa.asAttributes))
      .build
      .surround(fa.codomain.target)
}
```

Three things the scaladoc must say, because each is a difference a reader will
otherwise assume away:

1. **No `Apply[F]`.** The natchez version needs it to sequence
   `Trace[F].put(…) *> target` (`TraceWeaveCapturingInputs.scala:117`). Here the
   attributes go onto the builder, so there is nothing to sequence. Only
   `Tracer[F]`.
2. **Input attributes exist at span start**, not just after it, so a sampler can
   see them — otel4s samples at span start and warns that renaming later has
   implementation-defined sampling effects (`Span.scala:91`).
3. **One structured attribute, not one per parameter**, with the
   `maxNumberOfAttributes` and `maxAttributeValueLength` reasoning above.

Carry over the redaction guidance from `TraceWeaveCapturingInputs`'s scaladoc,
retyped for `ToAnyValue`: a sensitive parameter gets a newtype and a
hand-written instance that returns `AnyValue.string("redacted")`.

- [ ] **Step 5: Run to verify it passes**

```bash
export PATH="$HOME/.nvm/versions/node/v22.21.1/bin:$PATH"
sbt -batch "+otel4sTaglessJVM/test" "+otel4sTaglessJS/test"
```

Expected: PASS on 2.13.18 and 3.3.8, on both platforms, with the JS count
tracking the JVM count minus the `scala-jvm` tests.

- [ ] **Step 6: Commit**

```bash
git add otel4s-tagless/
git commit -m "feat: add TracerWeaveCapturingInputs and Weave#asAttributes

All of a call's parameters record as one attribute, algebraName.methodName.
parameters, whose value is an AnyValue map keyed by parameter name. One
structured attribute costs one slot against maxNumberOfAttributes where twenty
flat ones would cost twenty, and maxAttributeValueLength still recurses into the
tree, so nothing escapes truncation by being nested.

Unlike the natchez interpreter this needs no Apply[F]: the attributes attach to
the SpanBuilder rather than being put inside the span, which also means they
exist before the sampler runs, and that a noop Tracer never encodes them at
all."
```

---

## Task 4: `TracerWeaveCapturingInputsAndOutputs`

The only interpreter that needs a `Span[F]` handle, and therefore the only one
whose shape is genuinely forced by otel4s's lack of an ambient `put`. It adds
the second and last attribute, `<Alg>.<method>.returnValue`.

**Files:**
- Create: `otel4s-tagless/src/main/scala/com/dwolla/tracing/otel4s/TracerWeaveCapturingInputsAndOutputs.scala`
- Modify: `otel4s-tagless/src/test/scala-jvm/com/dwolla/tracing/otel4s/SpanContentSpec.scala`
- Modify: `otel4s-tagless/src/test/scala/com/dwolla/tracing/otel4s/TracerTransparencySpec.scala`

**Interfaces:**
- Consumes: Task 3's `asAttributes`; `SpanOps#use` (`SpanOps.scala:219`);
  `Span#backend` (`Span.scala:66`) and
  `Span.Backend#addAttributes` (`Span.scala:166`).
- Produces:
  - `object TracerWeaveCapturingInputsAndOutputs { def apply[F[_]: FlatMap: Tracer]: Weave[F, ToAnyValue, ToAnyValue, *] ~> F }`
  - `class TracerWeaveCapturingInputsAndOutputs[F[_]: FlatMap: Tracer] extends (Weave[F, ToAnyValue, ToAnyValue, *] ~> F)`

- [ ] **Step 1: Write the failing test**

In `SpanContentSpec`, assert that one call to
`underlying.weave.mapK(TracerWeaveCapturingInputsAndOutputs[IO])` produces one
span carrying **exactly two** attributes:

- `Foo.greet.parameters` → the map `{name -> "world", times -> 2}`
- `Foo.greet.returnValue` → the string `"hello worldhello world"`

The key name `returnValue` is not a fresh invention; it mirrors
`TraceWeaveCapturingInputsAndOutputs.scala:139` verbatim so a query written
against the natchez module keeps working after a migration. Assert on the
decoded tree.

Then add the `ping()` case, because it is the divergence most likely to be
"fixed" by a later reader who has not read the milestone document: a
`Unit`-returning, zero-parameter method still records **both** attributes, with
`Foo.ping.parameters` an empty map and `Foo.ping.returnValue` an empty value
(D3). Empty is recorded, never omitted, and there are no conditionals in the
interpreter to make it otherwise.

In `TracerTransparencySpec`, assert the result is unchanged:

```scala
  test("TracerWeaveCapturingInputsAndOutputs returns exactly what the underlying call returns") {
    val traced: Foo[Id] = underlying.weave.mapK(TracerWeaveCapturingInputsAndOutputs[Id])

    assertEquals(traced.greet("world", 2), underlying.greet("world", 2))
  }
```

- [ ] **Step 2: Run to verify it fails**

```bash
sbt -batch "otel4sTaglessJVM/test"
```

- [ ] **Step 3: Write the interpreter**

```scala
package com.dwolla.tracing.otel4s

import cats.FlatMap
import cats.syntax.all._
import cats.tagless.aop.Aspect.Weave
import cats.~>
import com.dwolla.tracing.otel4s.syntax._
import org.typelevel.otel4s.{AnyValue, Attribute, Attributes}
import org.typelevel.otel4s.trace.Tracer

object TracerWeaveCapturingInputsAndOutputs {
  def apply[F[_]: FlatMap: Tracer]: Weave[F, ToAnyValue, ToAnyValue, *] ~> F =
    new TracerWeaveCapturingInputsAndOutputs[F]
}

class TracerWeaveCapturingInputsAndOutputs[F[_]: FlatMap: Tracer]
  extends (Weave[F, ToAnyValue, ToAnyValue, *] ~> F) {

  override def apply[A](fa: Weave[F, ToAnyValue, ToAnyValue, A]): F[A] = {
    val name = s"${fa.algebraName}.${fa.codomain.name}"

    Tracer[F]
      .spanBuilder(name)
      .modifyState(_.addAttributes(fa.asAttributes))
      .build
      .use { span =>
        fa.codomain.target.flatTap { out =>
          // Typed AnyValue on purpose: see ToAnyValue's scaladoc. It is also
          // what makes `Attribute(name, returnValue)` resolve KeySelect.
          val returnValue: AnyValue = fa.codomain.instance.toAnyValue(out)

          // `.backend` deliberately: Span#addAttributes is a macro on Scala 2
          // and inline on Scala 3, and Span.Backend#addAttributes is the sealed
          // method underneath it. Attributes is already an
          // immutable.Iterable[Attribute[_]], so it passes straight through.
          span.backend.addAttributes(Attributes(Attribute(s"$name.returnValue", returnValue)))
        }
      }
  }
}
```

`FlatMap[F]`, matching the natchez version — `Monad` would work but nothing here
needs `pure`.

Scaladoc: mirror `TraceWeaveCapturingInputsAndOutputs`, including the redaction
example retyped for `ToAnyValue`, and add the automatic-error-recording note
from Task 2's scaladoc. Say plainly that a call records at most two attributes
and name both keys, because that is the contract a user writes queries against.
Also note the asymmetry with Task 3: the return value **is** encoded even under
a noop `Tracer`, because `SpanOps.use` does apply its function, where
`SpanBuilder.modifyState` does not.

- [ ] **Step 4: Run to verify it passes**

```bash
export PATH="$HOME/.nvm/versions/node/v22.21.1/bin:$PATH"
sbt -batch "+otel4sTaglessJVM/test" "+otel4sTaglessJS/test"
```

- [ ] **Step 5: Commit**

```bash
git add otel4s-tagless/
git commit -m "feat: add TracerWeaveCapturingInputsAndOutputs

The return value needs a Span handle, which otel4s only provides through
SpanOps#use — there is no ambient Tracer[F].put. It goes on as
algebraName.methodName.returnValue, the same key the natchez interpreter uses,
via span.backend.addAttributes, the sealed method underneath the
Span#addAttributes macro.

A call records at most two attributes. A Unit return records returnValue as an
empty value rather than omitting it: the encoder is a total A => AnyValue, and
EmptyValue is OTLP's own encoding of absence."
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
- Modify: `otel4s-tagless/src/test/scala/com/dwolla/tracing/otel4s/ToAnyValueResolutionSpec.scala`
- Modify: the two behavioural suites, to go through the syntax rather than
  constructing interpreters by hand.

**Interfaces:**
- Produces:
  - `class TracerWeaveOps[Alg[_[_]], F[_]](val alg: Alg[F]) extends AnyVal`
    - `def traceWithInputs[Cod[_]](implicit T: Tracer[F], A: Aspect[Alg, ToAnyValue, Cod]): Alg[F]`
    - `def traceWithInputsAndOutputs(implicit F: FlatMap[F], T: Tracer[F], A: Aspect[Alg, ToAnyValue, ToAnyValue]): Alg[F]`
  - `class InstrumentableAndTraceableOps[F[_], Alg[_[_]]](val alg: Alg[F]) extends AnyVal`
    - `def instrumentAndTrace(implicit I: Instrument[Alg], T: Tracer[F]): Alg[F]`
  - `package object syntax extends ToTracerWeaveOps with ToInstrumentableAndTraceableOps with ToWeaveAttributesOps`

- [ ] **Step 1: Write the failing test**

Rewrite the entry points of both behavioural suites to use the syntax:

```scala
import com.dwolla.tracing.otel4s.syntax._

val traced: Foo[Id] = underlying.traceWithInputsAndOutputs
val tracedInputsOnly: Foo[Id] = underlying.traceWithInputs[ToAnyValue]
val instrumented: Foo[Id] = underlying.instrumentAndTrace
```

Then add to `ToAnyValueResolutionSpec` the assertion that could not be made
before — that `traceWithInputs` requires **no `Apply[F]`**. `Id` has one, so
exercising it there proves nothing; the proof is a method whose only implicits
are `Tracer` and `Aspect`, over an abstract `F` for which no other instance can
possibly be found:

```scala
  test("traceWithInputs needs nothing but Tracer[F] — a compile-time assertion") {
    // If traceWithInputs required Apply[F] (as the natchez version does), or
    // any other capability, this method would not compile: F is abstract and
    // Tracer is the only instance in scope.
    def onlyTracer[Alg[_[_]], F[_], Cod[_]](alg: Alg[F])(implicit
                                                         T: Tracer[F],
                                                         A: Aspect[Alg, ToAnyValue, Cod]): Alg[F] =
      alg.traceWithInputs[Cod]

    def onlyTracerInstrument[Alg[_[_]], F[_]](alg: Alg[F])(implicit
                                                           I: Instrument[Alg],
                                                           T: Tracer[F]): Alg[F] =
      alg.instrumentAndTrace

    val _ = (onlyTracer[Foo, Id, ToAnyValue] _, onlyTracerInstrument[Foo, Id] _)
  }
```

(That last line exists only so the two methods are used; a non-`Unit` final
expression in a `Unit`-returning position warns fatally, hence `val _ =`.)

- [ ] **Step 2: Run to verify it fails**

```bash
sbt -batch "otel4sTaglessJVM/test"
```

- [ ] **Step 3: Write the two syntax files and the package object**

`TracerWeaveOps` mirrors `TraceWeaveOps` with `Tracer` for `Trace`,
`ToAnyValue` for `TraceableValue`, and **`Apply[F]` dropped from
`traceWithInputs`**:

```scala
package com.dwolla.tracing.otel4s
package syntax

import cats.FlatMap
import cats.tagless.aop.Aspect
import cats.tagless.syntax.all._
import org.typelevel.otel4s.trace.Tracer

trait ToTracerWeaveOps {
  implicit def toTracerWeaveOps[Alg[_[_]], F[_]](alg: Alg[F]): TracerWeaveOps[Alg, F] =
    new TracerWeaveOps(alg)
}

class TracerWeaveOps[Alg[_[_]], F[_]](val alg: Alg[F]) extends AnyVal {
  def traceWithInputs[Cod[_]](implicit
                              T: Tracer[F],
                              A: Aspect[Alg, ToAnyValue, Cod]): Alg[F] =
    alg.weave.mapK(new TracerWeaveCapturingInputs)

  def traceWithInputsAndOutputs(implicit
                                F: FlatMap[F],
                                T: Tracer[F],
                                A: Aspect[Alg, ToAnyValue, ToAnyValue]): Alg[F] =
    alg.weave.mapK(new TracerWeaveCapturingInputsAndOutputs)
}
```

`InstrumentableAndTraceableOps` mirrors its natchez namesake exactly, with
`Tracer` for `Trace`:

```scala
class InstrumentableAndTraceableOps[F[_], Alg[_[_]]](val alg: Alg[F]) extends AnyVal {
  def instrumentAndTrace(implicit I: Instrument[Alg], T: Tracer[F]): Alg[F] =
    alg.instrument.mapK(TracerInstrumentation[F])
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
export PATH="$HOME/.nvm/versions/node/v22.21.1/bin:$PATH"
sbt -batch "+otel4sTaglessJVM/test" "+otel4sTaglessJS/test"
```

- [ ] **Step 5: Prove the collision is exactly as documented, then delete the proof**

Temporarily add a test source that wildcard-imports **both**
`com.dwolla.tracing.syntax._` and `com.dwolla.tracing.otel4s.syntax._` and calls
`traceWithInputs`. Compile it, read the error, and confirm it is a
comprehensible ambiguity rather than something baffling. Then **revert the
file** — this module must not depend on `core`, and the throwaway needs `core`
on the test classpath to compile at all, so do the experiment with a temporary
`.dependsOn(core % Test)` and remove both together.

Record the actual error text in the task report and, if it is clear enough to be
worth quoting, in the package object's scaladoc. If the compile *succeeds*, the
milestone document's claim is wrong and the note should be corrected rather than
left stating a hazard that does not exist.

```bash
git status   # must be clean of the throwaway before committing
git diff --stat build.sbt   # must show no leftover test dependency on core
```

- [ ] **Step 6: Commit**

```bash
git add otel4s-tagless/
git commit -m "feat: add the otel4s syntax package

traceWithInputs, traceWithInputsAndOutputs and instrumentAndTrace, with the same
names as the natchez syntax so a migrating file changes one import and no call
sites. traceWithInputs drops the Apply[F] the natchez version needs, because
input attributes attach to the SpanBuilder rather than being put in the span —
asserted by a method over an abstract F where Tracer is the only instance in
scope."
```

---

## Task 6: scaladoc, doctests, README and the documentation reconciliation

**Little production code, and the task most likely to fail.** Every `{{{ }}}`
block in this module is compiled and executed on 2.13 and 3
(`doctestOnlyCodeBlocksMode := true`, `build.sbt:46`), so an example that calls
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
existing natchez examples but with four mandatory differences:

- `implicit val tracer: Tracer[IO] = Tracer.noop[IO]` (or
  `import Tracer.Implicits.noop`, `Tracer.scala:295`) — examples need a `Tracer`
  and `noop` is the only one in `otel4s-core-trace`. Both require
  `Applicative[F]`, which `IO` has.
- **No macros.** No `Tracer[F].span(...)`, no `span.addAttributes(...)`. If an
  example wants to show a span being created by hand, it shows
  `spanBuilder(name).build.surround(fa)`.
- **No `AnyValue` subtype in any declared type.** A custom-instance example
  writes `ToAnyValue.instance[Password](_ => AnyValue.string("redacted"))`, never
  a `val x: AnyValue.StringValue`.
- The hand-written `Aspect` in the example must compile on both axes, exactly as
  `core`'s existing examples do. Reuse the shape from `FooFixture`.

Add one example that ties the knot with `WeaveKnot`, so the `taglessCore`
dependency is exercised rather than merely declared:

```scala
val traced: Foo[IO] = WeaveKnot.weave[Foo, IO, ToAnyValue, ToAnyValue](
  self => new Foo[IO] { /* methods may call self.value's other methods */ },
  TracerWeaveCapturingInputsAndOutputs[IO]
)
```

That signature is verified against
`tagless-core/src/main/scala/com/dwolla/tagless/WeaveKnot.scala:20-23`:
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

- [ ] **Step 3: Grep for the two banned shapes**

```bash
grep -rnE "\.span\(|\.addAttribute\(|\.recordException\(|\.setStatus\(|\.withFinalizationStrategy\(|\.withSpanKind\(|\.withStartTimestamp\(|\.withParent\(" otel4s-tagless/src
grep -rn "AnyValue\.MapValue\|AnyValue\.SeqValue\|AnyValue\.StringValue\|AnyValue\.LongValue\|AnyValue\.DoubleValue\|AnyValue\.BooleanValue\|AnyValue\.EmptyValue" otel4s-tagless/src/main
```

Expected, first command: no output, **except** `.backend.addAttributes(` and the
`_.addAttributes(` inside `modifyState`, which are the sealed `Span.Backend` and
`SpanBuilder.State` methods rather than the macros of similar name (D6). Any
other hit is a macro call and must go.

Expected, second command: no output at all. No declared type in the module's
main sources is an `AnyValue` subtype (D1).

- [ ] **Step 4: Write the module README**

`otel4s-tagless/src/main/scala/README.md`, following the shape of
`raise-aspect-core/src/main/scala/README.md`. It must cover, because nowhere
else does:

- What is here and what is not (no `Raise`/mtl support, no `Resource` helpers,
  no `EntryPoint` analogue).
- **The attribute layout**, with a worked example: a call to
  `def greet(name: String, times: Int): F[String]` on algebra `Foo` produces one
  span named `Foo.greet` with `Foo.greet.parameters =
  {"name": "world", "times": 2}` and `Foo.greet.returnValue = "..."`, and the
  `parameters` value is a real OTLP `kvlistValue`, not a JSON string.
- **`opentelemetry-api` 1.59.0 is a hard floor** on the `otel4s-oteljava`
  backend — `AttributeType.VALUE` does not exist before it, and an application
  pinning an older SDK will fail to link `AttributeKey.valueKey`. otel4s 1.0.1
  pulls a newer version transitively. This is D10 and it must be here as well as
  in the scaladoc.
- **Structured span attributes are new enough that backend support varies.**
  The OTLP wire format is verified; whether a given collector or vendor renders
  `kvlistValue` span attributes rather than flattening or dropping them is not,
  and is worth checking against your own pipeline. A user who needs flat
  attributes today can write a `ToAnyValue` instance that encodes to a string.
- **2.12 is not published for this module, and why** — one paragraph, with the
  Maven Central fact, so nobody files a bug.
- **`ToAnyValue` is not `TraceableValue`**: a user with both modules writes
  two instances per domain type, and there is deliberately no bridge.
- The semantic divergences from `ToTraceValue` — `Unit` and `None` are
  `AnyValue.empty` rather than the strings `"()"` and `"None"`; `Float` widens to
  `Double`; `BigDecimal`/`BigInt` fall to the `Show` fallback and record as
  strings where natchez accepted them as numbers.
- **Error recording.** otel4s records an escaping `Throwable` as an exception
  event and sets `StatusCode.Error` automatically — this module adds nothing and
  removes nothing. **And cats-mtl `Raise` errors are invisible to that**, because
  `reportAbnormal` keys off `Resource.ExitCase` and a `Raise` error completes
  successfully as far as `F` is concerned. M6's docs record the same *gap* for
  natchez but by a different *mechanism* ("we never called `attachError`"), so
  **write this paragraph fresh; do not copy M6's wording.**
- **Span content is unverified on Scala.js.** State the asymmetry plainly and
  give the reason: `otel4s-oteljava-trace-testkit` publishes no `_sjs1_`
  artifact, and the cross-platform `otel4s-sdk-trace-testkit` stops at 0.19.0.
  Encoding and transparency are covered on both platforms.
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
  actually needed — `WeaveKnot` and nothing more. Update the `Set.empty` module
  count from five to six.
- `30-milestone-M16-otel4s-module.md`: replace "Planned, not started" with the
  outcome, in the shape M6, M7 and M12 use. Record: the test counts per platform
  and Scala version; whether the priority ladder held without `NotGiven`;
  whether contravariance caused any ambiguity; **what the
  `LocalContextProvider[IO]` instance turned out to be**; **whether
  `AttributeConverters` had a public entry point** and which assertion style the
  JVM suite ended up using; and Brian's answer to Q3 if it arrived.

- [ ] **Step 6: Full verification**

```bash
export PATH="$HOME/.nvm/versions/node/v22.21.1/bin:$PATH"
sbt -batch "+test"
sbt -batch "+otel4sTaglessJS/Test/scalaJSLinkerResult" "+coreJS/Test/scalaJSLinkerResult" \
    "+taglessCoreJS/Test/scalaJSLinkerResult" "+raiseAspectCoreJS/Test/scalaJSLinkerResult" \
    "+raiseAspectLawsJS/Test/scalaJSLinkerResult" "+raiseAspectMacrosJS/Test/scalaJSLinkerResult" \
    "+natchezTaglessMtlJS/Test/scalaJSLinkerResult"
sbt -batch "show otel4sTaglessJS/Test/definedTests"
sbt -batch "set otel4sTagless.jvm/scalacOptions += \"-Xfatal-warnings\"" "+otel4sTaglessJVM/test"
sbt -batch "+coreJVM/mimaReportBinaryIssues" "+coreJS/mimaReportBinaryIssues"
sbt -batch githubWorkflowCheck
git diff --stat main...HEAD -- . ':!otel4s-tagless' ':!docs' ':!build.sbt' ':!.github' ':!.mergify.yml'
```

Expected: every module green on the versions it supports; every JS linker green;
`definedTests` non-empty for the new module; zero warnings under forced fatal
warnings; MiMa on `core` unaffected (M16 adds a module, it does not move
anything out of `core`); the workflow check passes; and **the last command
prints nothing**, proving M16 touched no existing source.

- [ ] **Step 7: Commit**

```bash
git add otel4s-tagless/ docs/plans/raise-aspect/
git commit -m "docs: document the otel4s module and reconcile the planning docs

Scaladoc examples use the sealed spanBuilder/build/surround path throughout,
because this repo compiles doctests and otel4s's span, addAttributes and
recordException are Scala 2 macros and Scala 3 inline methods with union-typed
varargs.

The README records what a user cannot infer: the two-attribute layout and its
kvlistValue wire form, the opentelemetry-api 1.59.0 floor that structured
attributes require, that vendor rendering of structured span attributes is
unverified, that 2.12 is unpublished upstream, that ToAnyValue is not
TraceableValue and needs its own instances, that span content is unverified on
Scala.js, and that otel4s marks abnormal termination automatically while
remaining blind to cats-mtl Raise errors."
```

---

## Acceptance criteria

- [ ] `otel4s-tagless/` is a `crossProject(JVMPlatform, JSPlatform)` /
      `CrossType.Pure` module with
      `crossScalaVersions := Seq(Scala213, "3.3.8")`,
      `mimaPreviousArtifacts := Set.empty` carrying the six-module note,
      `doctestSettings`, `.dependsOn(taglessCore)`, and `%%%` on every
      cross-built coordinate outside `.jvmSettings`.
- [ ] `show otel4sTaglessJVM/crossScalaVersions` excludes 2.12; every other
      module's still includes it; `sbt "++ 2.12 natchez-tagless-rootJVM/test"`
      is green having **skipped** the new module.
- [ ] `com.dwolla.tracing.otel4s` provides `ToAnyValue`,
      `TracerInstrumentation`, `TracerWeaveCapturingInputs`,
      `TracerWeaveCapturingInputsAndOutputs`; `…otel4s.syntax` provides
      `traceWithInputs`, `traceWithInputsAndOutputs`, `instrumentAndTrace` and
      `asAttributes`.
- [ ] A traced call records **at most two** attributes, asserted through the
      testkit on the decoded tree: `<Alg>.<method>.parameters`, an
      `AnyValue.map` keyed by parameter name with correctly typed leaves, and
      `<Alg>.<method>.returnValue`. No test asserts on JSON text.
- [ ] `traceWithInputs` requires **no** `Apply[F]`, and
      `TracerInstrumentation` requires nothing but `Tracer[F]` — both proved by
      a method over an abstract `F` where no other instance exists.
- [ ] `ToAnyValueResolutionSpec` compiles and passes with **no `NotGiven`
      guards anywhere in the module** (`grep -rn "NotGiven" otel4s-tagless/src`
      is empty) and no `scalac-compat-features` dependency.
- [ ] `grep -rn "AnyValue\.MapValue\|AnyValue\.SeqValue\|AnyValue\.StringValue\|AnyValue\.LongValue\|AnyValue\.DoubleValue\|AnyValue\.BooleanValue\|AnyValue\.EmptyValue" otel4s-tagless/src/main`
      is empty: no declared type is an `AnyValue` subtype.
- [ ] The D3 divergences are each asserted by name: `Unit` and `None` encode to
      `AnyValue.empty`; `Float` records the exact widened `Double`; a
      zero-parameter method still records an empty `parameters` map; and a
      `Unit`-returning method still records an empty `returnValue`, asserted
      end to end through the testkit.
- [ ] `grep -rnE "\.span\(|\.addAttribute\(|\.recordException\(|\.setStatus\(|\.withFinalizationStrategy\(|\.withSpanKind\(|\.withStartTimestamp\(|\.withParent\(" otel4s-tagless/src`
      returns nothing but `.backend.addAttributes(` and `modifyState`'s
      `_.addAttributes(`.
- [ ] Cross-platform suites **run** on JVM and JS with matching counts (JS =
      JVM minus the `scala-jvm` tests); `show otel4sTaglessJS/Test/definedTests`
      is non-empty; the JVM-only `scala-jvm` directory is absent from
      `otel4sTaglessJS/Test/unmanagedSourceDirectories`.
- [ ] The `opentelemetry-api >= 1.59.0` floor appears in both `ToAnyValue`'s
      scaladoc and the module README.
- [ ] Doctests compile **and run** on 2.13.18 and 3.3.8;
      `+otel4sTaglessJVM/doc` succeeds.
- [ ] Zero new warnings under forced `-Xfatal-warnings` on both versions. CI
      does not enforce this (`tlFatalWarnings := false`, unoverridden).
- [ ] `.github/workflows/ci.yml` and `.mergify.yml` regenerated and committed;
      `sbt githubWorkflowCheck` passes.
- [ ] `git diff --stat main...HEAD` touches only `otel4s-tagless/`, `build.sbt`,
      `.github/workflows/`, `.mergify.yml` and `docs/`.
- [ ] The module README exists and covers the attribute layout, the 1.59.0
      floor, the unverified vendor rendering, the 2.12 exclusion, the
      two-type-classes consequence, the semantic divergences, the Scala.js
      coverage asymmetry, and the error-recording story including the `Raise`
      blind spot in fresh wording.

## Ground rules reminder

- **The encoder's result type is `AnyValue`, never an `AnyValue` subtype.** If
  a `KeySelect` error appears, widen; do not add a `KeySelect` instance.
- **`RaiseAspect` and `raise-aspect-*` are out of scope.** If a task drifts
  toward an otel4s mtl module, stop and report.
- **No otel4s macro or `inline` call**, main sources or scaladoc.
- **No change to any existing module.** If M16 seems to need one, stop — that is
  a design problem, not a task.
- **No second otel4s compile dependency**, and no circe: Q3's current answer is
  no. `otel4s-core-trace` only.
- **`%%%`, not `%%`**, for anything outside `.jvmSettings`. A green
  `otel4sTaglessJS/test` proves nothing until `definedTests` is non-empty.
- **Do not read the stale otel4s clone at `~/Developer/github/otel4s`** (v0.9.0,
  September 2024). Use `reference/upstream/otel4s/`.
- If the `ToAnyValue` priority ladder or its contravariance turns out not to
  work, that is a **design report**, not a quiet workaround. Both fallbacks are
  named in the milestone document.
- Never use `--no-verify` or any other hook-bypass flag.
