# M17 Implementation Plan — `otel4s-tagless-mtl`

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Trace `RaiseAspect` algebras through otel4s, and move the one genuinely
backend-agnostic piece of `natchez-tagless-mtl` into `raise-aspect-core` so both
backends share it.

**Architecture:** `raise-aspect-core` gains the `RaiseRecorder` priority
mechanism, generic over a backend-supplied `DefaultOnRaise[F, Err]` tag. Each
backend module supplies only its default and its syntax. The new
`otel4s-tagless-mtl` mirrors `natchez-tagless-mtl` file for file.

**Tech Stack:** Scala 2.12.21 / 2.13.18 / 3.3.8 (the new module: 2.13 and 3
only), cats-tagless, cats-mtl, otel4s 1.0.1, munit, sbt-typelevel 0.8.6.

**Design doc:** `32-milestone-M17-otel4s-tagless-mtl.md`. Decisions are cited as
D1–D9; read the decision before implementing the task that cites it.

## Global Constraints

- **Artifact/directory `otel4s-tagless-mtl`; sbt project `otel4sTaglessMtl`;
  package `com.dwolla.tracing.otel4s.mtl`.** Used verbatim everywhere.
- **The new module has zero content on 2.12** — it compiles nothing, tests
  nothing, publishes nothing there. Achieved by emptying it, never by narrowing
  `crossScalaVersions` (D8; the reasoning is in `build.sbt`'s comment above
  `otel4sTagless`, which must not be duplicated — cross-reference it).
- **Error attribute keys are `raise.error.type` and `raise.error.value`**,
  declared once in `raise-aspect-core` and referenced by both backends (D3, D5).
  Never re-declare the literals in a backend module or a test.
- **`ToAnyValue` stays total.** Omission of an empty value is the recording
  site's decision, never the type class's (D7).
- **`@experimental` goes on the companion object, not the algebra trait** (D8).
- **Additive to `otel4s-tagless` and `core`.** M17 changes `raise-aspect-core`
  and `natchez-tagless-mtl` (Task 1) and adds a module; it must not change
  `otel4s-tagless`'s or `core`'s public API. If a task needs to, stop and report.
- **Never use `--no-verify`** or any hook-bypass flag.
- **Do not push.** Standing instruction: all work stays local on the milestone
  branch.

## File Structure

**Modified — `raise-aspect-core`:**
- Create `raise-aspect-core/src/main/scala/com/dwolla/tagless/mtl/RaiseRecorder.scala`
- Create `raise-aspect-core/src/main/scala/com/dwolla/tagless/mtl/DefaultOnRaise.scala`
- Create `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/RaiseRecorderSpec.scala`

**Modified — `natchez-tagless-mtl`:**
- Delete `src/main/scala/com/dwolla/tracing/mtl/syntax/RaiseRecorder.scala`
- Create `src/main/scala/com/dwolla/tracing/mtl/syntax/NatchezDefaultOnRaise.scala`
- Modify `src/main/scala/com/dwolla/tracing/mtl/syntax/package.scala`
- Modify `src/main/scala/com/dwolla/tracing/mtl/syntax/RaiseTraceWeaveOps.scala` (imports only)
- Modify `src/main/scala/com/dwolla/tracing/mtl/package.scala` (scaladoc + one doctest import)
- Modify `src/test/scala/com/dwolla/tracing/mtl/syntax/RaiseRecorderPrioritySpec.scala`
- Modify `src/test/scala/com/dwolla/tracing/mtl/syntax/RaiseRecorderPriorityFixtures.scala` (scaladoc only)
- Modify `src/test/scala/com/dwolla/tracing/mtl/RaiseTraceIntegrationSuite.scala` (import only)
- Modify `src/test/scala-3/com/dwolla/tracing/mtl/DerivesBarTracingSpec.scala` (import only)

**New — `otel4s-tagless-mtl`:**
- `src/main/scala/com/dwolla/tracing/otel4s/mtl/Otel4sDefaultOnRaise.scala`
- `src/main/scala/com/dwolla/tracing/otel4s/mtl/package.scala`
- `src/main/scala/com/dwolla/tracing/otel4s/mtl/syntax/RaiseTracerWeaveOps.scala`
- `src/main/scala/com/dwolla/tracing/otel4s/mtl/syntax/package.scala`
- `src/main/scala-3/com/dwolla/tracing/otel4s/mtl/AnyValueRaiseAspect.scala`
- `src/main/scala-3/com/dwolla/tracing/otel4s/mtl/Scala3UsageNote.scala`
- `src/test/scala/com/dwolla/tracing/otel4s/mtl/FooRaiseFixture.scala`
- `src/test/scala/com/dwolla/tracing/otel4s/mtl/RaiseRecorderPrioritySpec.scala`
- `src/test/scala/com/dwolla/tracing/otel4s/mtl/RaiseTracerTransparencySpec.scala`
- `src/test/scala-jvm/com/dwolla/tracing/otel4s/mtl/RaiseSpanContentSpec.scala`
- `src/test/scala-3/com/dwolla/tracing/otel4s/mtl/DerivesFooRaiseSpec.scala`
- `build.sbt`

---

## Task 1: Extract `RaiseRecorder` into `raise-aspect-core`

Pure refactor. **No behaviour may change** on the natchez side except the
implicit-scope-to-lexical-scope move D2 describes. Read D1, D2 and D3 first.

**Files:** as listed under `raise-aspect-core` and `natchez-tagless-mtl` above.

**Interfaces produced** (Tasks 3–5 depend on these exact signatures):

```scala
package com.dwolla.tagless.mtl

trait DefaultOnRaise[F[_], Err[_]] extends Serializable {
  def onRaise: OnRaise[F, Err]
}

sealed trait RaiseRecorder[F[_], Err[_]] {
  def onRaise: OnRaise[F, Err]
}

object RaiseRecorder extends LowPriorityRaiseRecorder {
  val ErrorTypeKey: String = "raise.error.type"
  val ErrorValueKey: String = "raise.error.value"
  implicit def fromOnRaise[F[_], Err[_]](implicit or: OnRaise[F, Err]): RaiseRecorder[F, Err]
}

trait LowPriorityRaiseRecorder {
  implicit def fromDefault[F[_], Err[_]](implicit d: DefaultOnRaise[F, Err]): RaiseRecorder[F, Err]
}
```

- [ ] **Step 1: Write the failing resolution test in `raise-aspect-core`**

`raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/RaiseRecorderSpec.scala`.
Core has no natchez, so the test uses a local evidence type class. This is the
backend-independent proof of the mechanism; the natchez-flavoured proof stays in
`RaiseRecorderPrioritySpec`.

```scala
package com.dwolla.tagless.mtl

import cats.Id
import munit.FunSuite

class RaiseRecorderSpec extends FunSuite {
  trait Rendered[A] { def render(a: A): String }
  object Rendered {
    implicit val intRendered: Rendered[Int] = (a: Int) => a.toString
  }

  // Distinguishable so the test observes WHICH instance resolved, not merely
  // that one did.
  private def recording(into: collection.mutable.Buffer[String], label: String): OnRaise[Id, Rendered] =
    new OnRaise[Id, Rendered] {
      def apply[E](e: E)(implicit ev: Rendered[E]): Id[Unit] = { into += s"$label:${ev.render(e)}"; () }
    }

  test("with only a DefaultOnRaise in scope, the default resolves and runs") {
    val log = collection.mutable.Buffer.empty[String]
    implicit val default: DefaultOnRaise[Id, Rendered] = new DefaultOnRaise[Id, Rendered] {
      def onRaise: OnRaise[Id, Rendered] = recording(log, "default")
    }

    implicitly[RaiseRecorder[Id, Rendered]].onRaise(42)
    assertEquals(log.toList, List("default:42"))
  }

  test("a user OnRaise outranks the DefaultOnRaise, and it is the one that runs") {
    val log = collection.mutable.Buffer.empty[String]
    implicit val default: DefaultOnRaise[Id, Rendered] = new DefaultOnRaise[Id, Rendered] {
      def onRaise: OnRaise[Id, Rendered] = recording(log, "default")
    }
    implicit val user: OnRaise[Id, Rendered] = recording(log, "user")

    implicitly[RaiseRecorder[Id, Rendered]].onRaise(42)
    assertEquals(log.toList, List("user:42"))
  }

  test("resolving with both in scope reports no ambiguous implicit") {
    // The 2.13-only failure D1's spike reproduced. Guards the shape: if someone
    // later gives fromDefault a different type-parameter list from fromOnRaise,
    // this fails on 2.13 and passes on 2.12 and 3.
    val errors: String = compileErrors(
      """import cats.Id
implicit val default: DefaultOnRaise[Id, Rendered] = new DefaultOnRaise[Id, Rendered] {
  def onRaise: OnRaise[Id, Rendered] = new OnRaise[Id, Rendered] {
    def apply[E](e: E)(implicit ev: Rendered[E]): Id[Unit] = ()
  }
}
implicit val user: OnRaise[Id, Rendered] = new OnRaise[Id, Rendered] {
  def apply[E](e: E)(implicit ev: Rendered[E]): Id[Unit] = ()
}
implicitly[RaiseRecorder[Id, Rendered]]"""
    )
    assertNoDiff(errors, "")
  }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
sbt "raiseAspectCoreJVM/testOnly com.dwolla.tagless.mtl.RaiseRecorderSpec"
```

Expected: compile failure, `not found: type DefaultOnRaise`.

- [ ] **Step 3: Create the two core files**

`DefaultOnRaise.scala` — the tag. Its scaladoc must say *why* it exists rather
than restate its shape: it is deliberately a distinct type from `OnRaise`, with
identical content, so that implicit priority can distinguish "the backend's
fallback" from "the user's hook". Two `OnRaise` instances at the same priority
would be ambiguous, which is the whole reason `RaiseRecorder` exists.

`RaiseRecorder.scala` — the mechanism, at the signatures in the Interfaces block
above. Carry over the existing scaladoc on `fromOnRaise`, and adapt
`RaiseRecorder`'s type-level doc: keep the cross-reference to `WeaveInterpreter`
("same sealed-typeclass low-priority-implicit mechanism"), drop everything
naming natchez.

Add a scaladoc note on `fromDefault` recording D1's finding, since it is the
reason the shapes must stay aligned and the next person to touch this will not
otherwise know:

> Both instances take `[F[_], Err[_]]`, and that alignment is load-bearing on
> Scala 2.13 specifically. If this one fixes `Err` directly — e.g. returning
> `RaiseRecorder[F, TraceableValue]` — 2.13 reports the two as ambiguous
> instead of ordering them by the object-extends-trait rule, while 2.12 and 3
> resolve it correctly. The failure appears only when a user hook is also in
> scope, so the default path keeps working and the break shows up as "my
> override stopped compiling".

**`IsTraceableValue` is not carried over.** It is deleted with the old file.

- [ ] **Step 4: Run the core test — expect green on all three versions**

```bash
sbt "+raiseAspectCoreJVM/testOnly com.dwolla.tagless.mtl.RaiseRecorderSpec"
```

All three must pass. If 2.13 reports an ambiguity, the shapes have drifted from
D1's validated form — fix the shapes, do not add a witness.

- [ ] **Step 5: Rewire `natchez-tagless-mtl`**

Delete `syntax/RaiseRecorder.scala`.

Create `syntax/NatchezDefaultOnRaise.scala` carrying `fromTrace`'s behaviour and
the substance of its scaladoc — the `raise.*`-vs-`exception.*` distinction, and
that the `Submarine` wrapper is what reaches the `Throwable` channel:

```scala
package com.dwolla.tracing.mtl
package syntax

import com.dwolla.tagless.mtl.{DefaultOnRaise, OnRaise, RaiseRecorder}
import natchez.{Trace, TraceableValue}

trait NatchezDefaultOnRaise {
  implicit def natchezDefaultOnRaise[F[_]](implicit T: Trace[F]): DefaultOnRaise[F, TraceableValue] =
    new DefaultOnRaise[F, TraceableValue] {
      def onRaise: OnRaise[F, TraceableValue] = new OnRaise[F, TraceableValue] {
        def apply[E](e: E)(implicit ev: TraceableValue[E]): F[Unit] =
          T.put(
            RaiseRecorder.ErrorTypeKey -> e.getClass.getName,
            RaiseRecorder.ErrorValueKey -> ev.toTraceValue(e)
          )
      }
    }
}
```

Note it needs no `IsTraceableValue`: `Err` is fixed at `TraceableValue` here and
nothing else has to match its shape.

Mix it into the syntax package object:

```scala
package com.dwolla.tracing.mtl

package object syntax extends ToRaiseTraceWeaveOps with NatchezDefaultOnRaise
```

Then fix imports in `RaiseTraceWeaveOps.scala`, `RaiseTraceIntegrationSuite.scala`
and `DerivesBarTracingSpec.scala` — `RaiseRecorder` now comes from
`com.dwolla.tagless.mtl`. The two suites reference only `RaiseRecorder.ErrorTypeKey`
/ `ErrorValueKey`, so the change is the import line and nothing else.

- [ ] **Step 6: Fix `RaiseRecorderPrioritySpec`**

Two things, and only these two:

1. The `compileErrors` snippet at line 27 imports
   `com.dwolla.tracing.mtl.syntax.RaiseRecorder`. Change to
   `com.dwolla.tagless.mtl.RaiseRecorder`.
2. Nothing else. **The three behavioural tests must keep passing unmodified** —
   the spec is declared `package com.dwolla.tracing.mtl; package syntax`, so
   members of that package's package object are already in scope without an
   import, which is exactly why the default still resolves there.

That second point is the local check on D2. If a behavioural test now fails to
resolve `RaiseRecorder[IO, TraceableValue]`, D2's analysis is wrong — stop and
report rather than sprinkling imports.

Update `RaiseRecorderPriorityFixtures.scala`'s scaladoc reference to
`RaiseRecorder.fromTrace`, which no longer exists; it is now
`NatchezDefaultOnRaise.natchezDefaultOnRaise`.

- [ ] **Step 7: Fix `mtl/package.scala`**

The scaladoc names `RaiseRecorder.ErrorTypeKey`/`ErrorValueKey` "on
`RaiseRecorder`'s companion" (lines ~127-128) — still true, new package; say
`com.dwolla.tagless.mtl.RaiseRecorder`.

The "Overriding the default recording" section (~148-163) explains resolution in
terms of `fromOnRaise` outranking `fromTrace`. Rewrite for the new names, and
**add the D2 fact**: the natchez default now arrives with
`import com.dwolla.tracing.mtl.syntax._` rather than from implicit scope. The
existing paragraph about a hook in the error ADT's companion never being found
stays — it is still true and still valuable.

The second doctest (~172-195) ends with a bare
`implicitly[RaiseRecorder[F, TraceableValue]]`. It needs
`import com.dwolla.tracing.mtl.syntax._` added to its imports, and
`RaiseRecorder` now imports from `com.dwolla.tagless.mtl`. **This doctest is the
executable proof of D2** — annotate it as such in a comment so a later reader
does not "clean up" the import.

- [ ] **Step 8: Full verification**

```bash
sbt "+raiseAspectCoreJVM/test" "+natchezTaglessMtlJVM/test" \
    "+raiseAspectCoreJS/test" "+natchezTaglessMtlJS/test" \
    "+natchezTaglessMtlJVM/doc"
```

Record per-version test counts for all four. **`natchezTaglessMtl`'s counts must
equal the pre-task counts** on every version and platform — capture them with a
run on the parent commit first. A changed count means the refactor changed
behaviour.

- [ ] **Step 9: Commit**

```bash
git add raise-aspect-core/src natchez-tagless-mtl/src
git commit -m "refactor: share the RaiseRecorder priority mechanism via raise-aspect-core"
```

### Acceptance criteria

- [ ] `RaiseRecorder` and `DefaultOnRaise` live in `com.dwolla.tagless.mtl`;
      `IsTraceableValue` exists nowhere in the repo.
- [ ] `+raiseAspectCore/test` green on 2.12, 2.13 and 3, both platforms,
      including the no-ambiguity `compileErrors` test.
- [ ] `natchezTaglessMtl` test counts **identical** to the parent commit's on
      every version and platform.
- [ ] `RaiseRecorderPrioritySpec`'s three behavioural tests pass with no import
      added to them.
- [ ] `natchezTaglessMtlJVM/doc` green; both doctests in `mtl/package.scala` run.
- [ ] `git diff` touches no file outside `raise-aspect-core/src` and
      `natchez-tagless-mtl/src`.

---

## Task 2: Scaffold `otel4s-tagless-mtl` and prove the 2.12 containment

Build-only, plus one trivial source and one trivial test. Read D8.

Containment is verified **before any content exists**, so a leak is diagnosed
against an empty module rather than blamed on the code. This is deliberate: M16
leaked twice, both times late.

**Files:** `build.sbt`; a placeholder source and test, both deleted in Task 4.

- [ ] **Step 1: Add the cross-project to `build.sbt`**

Place it immediately after `otel4sTagless`. Mirror that project's containment
exactly — the four gates are: the otel4s dependency, `Compile /
unmanagedSourceDirectories`, `Test / unmanagedSourceDirectories`, and
`publish / skip`; plus, in `.jvmSettings`, the testkit dependencies **and** the
`scala-jvm` test source directory.

Do not duplicate `otel4sTagless`'s long explanatory comment. Write a short one
that cross-references it:

```scala
// The mtl counterpart of otel4s-tagless: traces algebras whose methods take
// cats.mtl.Raise capability parameters, which plain Aspect cannot weave.
//
// 2.12 containment is identical to otel4sTagless's, for the identical reason —
// see that project's comment above for why this is done by emptying the module
// rather than by narrowing crossScalaVersions. All four gates are required, and
// the .jvmSettings test-source-directory gate is required *separately*, because
// .jvmSettings are appended after the shared `:=` that empties the list.
lazy val otel4sTaglessMtl = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("otel4s-tagless-mtl"))
  .settings(
    name := "otel4s-tagless-mtl",
    libraryDependencies ++= Seq(
      "org.scalameta" %%% "munit" % munitVersion % Test,
    ),
    libraryDependencies ++= {
      if (isOtel4sScalaVersion.value) Seq("org.typelevel" %%% "otel4s-core-trace" % otel4sVersion)
      else Seq.empty
    },
    Compile / unmanagedSourceDirectories := {
      if (isOtel4sScalaVersion.value) (Compile / unmanagedSourceDirectories).value else Seq.empty
    },
    Test / unmanagedSourceDirectories := {
      if (isOtel4sScalaVersion.value) (Test / unmanagedSourceDirectories).value else Seq.empty
    },
    publish / skip := !isOtel4sScalaVersion.value,
    // `Set.empty` is a live hazard, not a permanent no-op: nothing suppresses
    // publishing, so this module WILL be published with a real API at the
    // next release, and MiMa will not catch a breaking change after that. The
    // fix is `tlVersionIntroduced := Map(...)` in place of `Set.empty`, which
    // keeps MiMa live from the module's first release onward — one decision
    // covering all seven `Set.empty` modules, needed before the next publish,
    // not made here. See "Anything a later milestone needs" in
    // docs/plans/raise-aspect/28-milestone-M15-tagless-core-module.md.
    mimaPreviousArtifacts := Set.empty,
  )
  .jvmSettings(
    libraryDependencies ++= {
      if (isOtel4sScalaVersion.value)
        Seq(
          "org.typelevel" %% "otel4s-oteljava-trace-testkit" % otel4sVersion % Test,
          "org.typelevel" %% "otel4s-oteljava-common" % otel4sVersion % Test,
          "org.typelevel" %% "munit-cats-effect" % "2.2.0" % Test,
        )
      else Seq.empty
    },
    Test / unmanagedSourceDirectories ++= {
      if (isOtel4sScalaVersion.value) Seq(baseDirectory.value.getParentFile / "src" / "test" / "scala-jvm")
      else Seq.empty
    },
  )
  .settings(doctestSettings *)
  .dependsOn(otel4sTagless, raiseAspectCore, raiseAspectMacros)
```

Add `otel4sTaglessMtl` to the root aggregate at `build.sbt:32`.

Note the `%%%`-everywhere rule in the shared block and `%%` only in
`.jvmSettings`. Getting this backwards is the bug that made `coreJS` run zero
tests while reporting success.

- [ ] **Step 2: Add a placeholder source and test**

`src/main/scala/com/dwolla/tracing/otel4s/mtl/Placeholder.scala` with a single
`private[mtl] object Placeholder`, and a `src/test/scala/.../PlaceholderSpec.scala`
with one `assert(true)` test. These exist so `test` has something to run and the
JS linker has an entry point; Task 4 deletes both.

- [ ] **Step 3: Verify containment**

```bash
sbt "++2.12.21 natchez-tagless-rootJVM/test" "++2.12.21 natchez-tagless-rootJS/test"
sbt "++2.13.18 otel4sTaglessMtlJVM/test" "++3.3.8 otel4sTaglessMtlJVM/test"
sbt "++2.13.18 otel4sTaglessMtlJS/test" "++3.3.8 otel4sTaglessMtlJS/test"
sbt githubWorkflowCheck
```

Expected: both 2.12 root runs green with the new module compiling and running
nothing; 1 test on 2.13 and 3, both platforms; `githubWorkflowCheck` clean.

A 2.12 failure resolving `otel4s-core-trace_2.12` or `..._sjs1_2.12` means a
gate is missing. Find which of the six, do not add a seventh.

- [ ] **Step 4: Commit**

```bash
git add build.sbt otel4s-tagless-mtl/
git commit -m "build: add the otel4s-tagless-mtl module"
```

### Acceptance criteria

- [ ] Both 2.12 root runs green; the module contributes zero compiled sources
      and zero tests there.
- [ ] 1 test passes on 2.13.18 and 3.3.8, JVM and JS.
- [ ] `githubWorkflowCheck` clean.
- [ ] Every coordinate in the shared settings block uses `%%%`; every one in
      `.jvmSettings` uses `%%`.

---

## Task 3: `Otel4sDefaultOnRaise`

The backend default. Read D4, D5, D6 and D7.

**Interfaces consumed:** `DefaultOnRaise`, `OnRaise`, `RaiseRecorder.ErrorTypeKey`
/ `ErrorValueKey` from Task 1; `ToAnyValue` from `otel4s-tagless`.

**Interfaces produced:**

```scala
package com.dwolla.tracing.otel4s.mtl

trait Otel4sDefaultOnRaise {
  implicit def otel4sDefaultOnRaise[F[_]: FlatMap: Tracer]: DefaultOnRaise[F, ToAnyValue]
}
object Otel4sDefaultOnRaise extends Otel4sDefaultOnRaise
```

Both trait and object: the trait mixes into the syntax package object (Task 4),
the object lets a test import it without the rest of the syntax.

- [ ] **Step 1: Write the failing test**

`src/test/scala/com/dwolla/tracing/otel4s/mtl/RaiseRecorderPrioritySpec.scala`.
Cross-platform, so no testkit — it asserts *resolution*, not span content.
Task 6 asserts content on the JVM.

```scala
package com.dwolla.tracing.otel4s.mtl

import cats.effect.IO
import com.dwolla.tagless.mtl.{OnRaise, RaiseRecorder}
import com.dwolla.tracing.otel4s.ToAnyValue
import munit.CatsEffectSuite
import org.typelevel.otel4s.trace.Tracer

class RaiseRecorderPrioritySpec extends CatsEffectSuite {
  private implicit val tracer: Tracer[IO] = Tracer.noop[IO]

  test("the otel4s default is reachable when no OnRaise is in scope") {
    import com.dwolla.tracing.otel4s.mtl.syntax._
    implicitly[RaiseRecorder[IO, ToAnyValue]].onRaise(42).assertEquals(())
  }

  test("a user-supplied OnRaise wins over the otel4s default, and it is the one that runs") {
    import com.dwolla.tracing.otel4s.mtl.syntax._
    implicit val poison: OnRaise[IO, ToAnyValue] = new OnRaise[IO, ToAnyValue] {
      def apply[E](e: E)(implicit ev: ToAnyValue[E]): IO[Unit] =
        IO.raiseError(new AssertionError("user hook ran"))
    }

    interceptMessage[AssertionError]("user hook ran") {
      implicitly[RaiseRecorder[IO, ToAnyValue]].onRaise(42).unsafeRunSync()
    }
  }

  test("resolving with both a user OnRaise and a Tracer in scope reports no ambiguous implicit") {
    // The 2.13 shape guard, at the otel4s Err. Task 1's core test covers the
    // mechanism; this covers this module's actual instantiation of it.
    val errors: String = compileErrors(
      """import cats.effect.IO
import com.dwolla.tagless.mtl.{OnRaise, RaiseRecorder}
import com.dwolla.tracing.otel4s.ToAnyValue
import com.dwolla.tracing.otel4s.mtl.syntax._
import org.typelevel.otel4s.trace.Tracer
implicit val tracer: Tracer[IO] = Tracer.noop[IO]
implicit val userOnRaise: OnRaise[IO, ToAnyValue] = new OnRaise[IO, ToAnyValue] {
  def apply[E](e: E)(implicit ev: ToAnyValue[E]): IO[Unit] = IO.unit
}
implicitly[RaiseRecorder[IO, ToAnyValue]]"""
    )
    assertNoDiff(errors, "")
  }
}
```

The second test needs `import cats.effect.unsafe.implicits.global`; use
whichever spelling the repo's other suites use — check
`otel4s-tagless/src/test/scala/.../TracerTransparencySpec.scala` first and match
it rather than introducing a new one.

- [ ] **Step 2: Run it and watch it fail**

```bash
sbt "otel4sTaglessMtlJVM/testOnly com.dwolla.tracing.otel4s.mtl.RaiseRecorderPrioritySpec"
```

Expected: `object mtl is not a member of package com.dwolla.tracing.otel4s` or
`not found: value syntax`.

- [ ] **Step 3: Implement**

```scala
package com.dwolla.tracing.otel4s.mtl

import cats.FlatMap
import cats.syntax.all._
import com.dwolla.tagless.mtl.{DefaultOnRaise, OnRaise, RaiseRecorder}
import com.dwolla.tracing.otel4s.ToAnyValue
import org.typelevel.otel4s.{AnyValue, Attribute, Attributes}
import org.typelevel.otel4s.trace.Tracer

trait Otel4sDefaultOnRaise {
  implicit def otel4sDefaultOnRaise[F[_] : FlatMap : Tracer]: DefaultOnRaise[F, ToAnyValue] =
    new DefaultOnRaise[F, ToAnyValue] {
      def onRaise: OnRaise[F, ToAnyValue] = new OnRaise[F, ToAnyValue] {
        def apply[E](e: E)(implicit ev: ToAnyValue[E]): F[Unit] = {
          val value: AnyValue = ev.toAnyValue(e)

          val attributes: Attributes =
            Attributes(Attribute(RaiseRecorder.ErrorTypeKey, e.getClass.getName)) ++ (
              if (value == AnyValue.empty) Attributes.empty
              else Attributes(Attribute(RaiseRecorder.ErrorValueKey, value))
            )

          Tracer[F].currentSpanOrNoop.flatMap(_.backend.addAttributes(attributes))
        }
      }
    }
}

object Otel4sDefaultOnRaise extends Otel4sDefaultOnRaise
```

Verify `Attributes#++` exists with that name; if not, build one `Attributes`
from a `List[Attribute[_]]` instead. Do not restructure the omission logic to
work around a missing operator.

Also create the syntax package object here, since this task's test imports it:

```scala
package com.dwolla.tracing.otel4s.mtl

package object syntax extends Otel4sDefaultOnRaise
```

Task 4 extends that declaration with `ToRaiseTracerWeaveOps` rather than
recreating it.

The scaladoc must record four things, none of which are guessable from the code:

1. **Why `currentSpanOrNoop` rather than being handed the span** (D6) — the hook
   resolves independently of the interpreter and fires inside the method body,
   which the interpreter does not wrap.
2. **Why that finds the right span** — `.use` makes it current;
   `SpanOps#use` is `resource.use { res => res.trace(f(res.span)) }`.
3. **Why `.backend`** — cross-reference
   `TracerWeaveCapturingInputsAndOutputs`'s existing comment rather than
   restating it.
4. **The overwrite case** (D4) — a method that raises, rescues internally and
   raises again fires this twice against one span, and the second
   `addAttributes` wins. State it plainly as a known limitation.

- [ ] **Step 4: Run the test**

```bash
sbt "otel4sTaglessMtlJVM/testOnly com.dwolla.tracing.otel4s.mtl.RaiseRecorderPrioritySpec"
```

All three green.

- [ ] **Step 5: Commit**

```bash
git add otel4s-tagless-mtl/src
git commit -m "feat(m17): record typed raised errors as otel4s span attributes"
```

### Acceptance criteria

- [ ] `implicitly[RaiseRecorder[IO, ToAnyValue]]` resolves to the default with no
      user hook, and to the user hook with one, on 2.13 and 3, JVM and JS.
- [ ] The `compileErrors` no-ambiguity test passes on 2.13.
- [ ] `raise.error.type`/`raise.error.value` appear nowhere as literals in this
      module — both come from `RaiseRecorder`.
- [ ] `Tracer.noop` costs nothing: the default resolves and runs without error.

---

## Task 4: `RaiseTracerWeaveOps` and the syntax package

Read D9 for the `Apply[F]` divergence.

**Interfaces produced:**

```scala
package com.dwolla.tracing.otel4s.mtl.syntax

class RaiseTracerWeaveOps[Alg[_[_]], F[_]](val alg: Alg[F]) extends AnyVal {
  def traceWithInputs[Cod[_]](implicit
      F: Apply[F], T: Tracer[F],
      R: RaiseRecorder[F, ToAnyValue],
      ev: WeaveInterpreter[Alg, ToAnyValue, Cod, ToAnyValue, F]): Alg[F]

  def traceWithInputsAndOutputs(implicit
      F: FlatMap[F], T: Tracer[F],
      R: RaiseRecorder[F, ToAnyValue],
      ev: WeaveInterpreter[Alg, ToAnyValue, ToAnyValue, ToAnyValue, F]): Alg[F]
}
```

- [ ] **Step 1: Write the failing transparency test**

`src/test/scala/com/dwolla/tracing/otel4s/mtl/RaiseTracerTransparencySpec.scala`,
plus `FooRaiseFixture.scala` holding a `RaiseAspect` algebra. Model the fixture
on `natchez-tagless-mtl`'s `BarFixture.scala` — read it and match its shape,
substituting `ToAnyValue` for `TraceableValue`. Transparency means: under
`Tracer.noop`, a traced algebra returns exactly what the untraced one does, on
both the success and the raise paths.

Assert both paths. The raise path is the one that exercises `OnRaise`, and a
suite that only covers success will pass against a hook that throws.

- [ ] **Step 2: Run it and watch it fail**

```bash
sbt "otel4sTaglessMtlJVM/testOnly com.dwolla.tracing.otel4s.mtl.RaiseTracerTransparencySpec"
```

- [ ] **Step 3: Implement**

```scala
package com.dwolla.tracing.otel4s.mtl
package syntax

import cats.{Apply, FlatMap}
import com.dwolla.tagless.mtl.{RaiseRecorder, WeaveInterpreter}
import com.dwolla.tracing.otel4s.{ToAnyValue, TracerWeaveCapturingInputs, TracerWeaveCapturingInputsAndOutputs}
import org.typelevel.otel4s.trace.Tracer

trait ToRaiseTracerWeaveOps {
  implicit def toRaiseTracerWeaveOps[Alg[_[_]], F[_]](alg: Alg[F]): RaiseTracerWeaveOps[Alg, F] =
    new RaiseTracerWeaveOps(alg)
}

class RaiseTracerWeaveOps[Alg[_[_]], F[_]](val alg: Alg[F]) extends AnyVal {
  def traceWithInputs[Cod[_]](implicit
      F: Apply[F],
      T: Tracer[F],
      R: RaiseRecorder[F, ToAnyValue],
      ev: WeaveInterpreter[Alg, ToAnyValue, Cod, ToAnyValue, F]
  ): Alg[F] =
    ev(alg)(new TracerWeaveCapturingInputs, R.onRaise)

  def traceWithInputsAndOutputs(implicit
      F: FlatMap[F],
      T: Tracer[F],
      R: RaiseRecorder[F, ToAnyValue],
      ev: WeaveInterpreter[Alg, ToAnyValue, ToAnyValue, ToAnyValue, F]
  ): Alg[F] =
    ev(alg)(new TracerWeaveCapturingInputsAndOutputs, R.onRaise)
}
```

Check `TracerWeaveCapturingInputs`'s constructor: M16 declares it
`class TracerWeaveCapturingInputs[F[_]: Tracer]` — confirm whether it needs
`Cod` as a type parameter and supply it if so. Mirror
`RaiseTraceWeaveOps.scala`'s use of `TraceWeaveCapturingInputs[F, Cod]`.

`traceWithInputs`'s scaladoc must carry D9: it requires `Apply[F]` where
`com.dwolla.tracing.otel4s.syntax.traceWithInputs` does not, because
`WeaveInterpreter.fromRaiseAspect` needs it to sequence the hook and the method
cannot know which interpreter will resolve. Say that switching a call site from
the non-mtl syntax adds this constraint.

Keep `Err` pinned to `ToAnyValue` independently of `Cod`, and say why in the
scaladoc — mirroring `RaiseTraceWeaveOps`: opting out of return-value rendering
must not silently disable error recording.

Create the syntax package object, mirroring the natchez one and mixing in
Task 3's trait:

```scala
package com.dwolla.tracing.otel4s.mtl

package object syntax extends ToRaiseTracerWeaveOps with Otel4sDefaultOnRaise
```

Delete Task 2's `Placeholder.scala` and `PlaceholderSpec.scala`.

- [ ] **Step 4: Verify**

```bash
sbt "otel4sTaglessMtlJVM/test" "otel4sTaglessMtlJS/test"
sbt "++3.3.8 otel4sTaglessMtlJVM/test" "++3.3.8 otel4sTaglessMtlJS/test"
```

- [ ] **Step 5: Commit**

```bash
git add otel4s-tagless-mtl/src
git commit -m "feat(m17): add RaiseAspect-aware otel4s tracing syntax"
```

### Acceptance criteria

- [ ] Both methods compile and are reachable via
      `import com.dwolla.tracing.otel4s.mtl.syntax._`.
- [ ] Transparency holds on the success **and** raise paths under `Tracer.noop`.
- [ ] The placeholder files are gone.
- [ ] `Err` is `ToAnyValue` in both signatures, independently of `Cod`.

---

## Task 5: `AnyValueRaiseAspect` (Scala 3 only)

The M13 parallel. Read D8's `@experimental` note. **Read
`natchez-tagless-mtl/src/main/scala-3/com/dwolla/tracing/mtl/TraceableRaiseAspect.scala`
first and mirror it** — including the non-inline `fromRaiseAspect` escape hatch
and the corrected `@experimental` scaladoc, which was wrong once already and
must not regress.

**Files:**
- Create `src/main/scala-3/com/dwolla/tracing/otel4s/mtl/AnyValueRaiseAspect.scala`
- Create `src/main/scala-3/com/dwolla/tracing/otel4s/mtl/Scala3UsageNote.scala`
- Create `src/test/scala-3/com/dwolla/tracing/otel4s/mtl/DerivesFooRaiseSpec.scala`

- [ ] **Step 1: Write the failing test**

Mirror `natchez-tagless-mtl/src/test/scala-3/.../DerivesBarTracingSpec.scala`.
It must assert that a `derives AnyValueRaiseAspect` algebra produces the **same**
observable result as a hand-written
`RaiseAspect[Alg, ToAnyValue, ToAnyValue, ToAnyValue]`, on the success and raise
paths. Read the natchez spec and keep its structure.

- [ ] **Step 2: Run it and watch it fail**

```bash
sbt "++3.3.8 otel4sTaglessMtlJVM/testOnly com.dwolla.tracing.otel4s.mtl.DerivesFooRaiseSpec"
```

- [ ] **Step 3: Implement**

```scala
package com.dwolla.tracing.otel4s.mtl

import cats.tagless.aop.Aspect
import com.dwolla.tagless.mtl.RaiseAspect
import com.dwolla.tagless.mtl.macros.DeriveRaise
import com.dwolla.tracing.otel4s.ToAnyValue
import scala.annotation.experimental

trait AnyValueRaiseAspect[Alg[_[_]]]
  extends RaiseAspect[Alg, ToAnyValue, ToAnyValue, ToAnyValue]

object AnyValueRaiseAspect {
  def apply[Alg[_[_]]](using ev: AnyValueRaiseAspect[Alg]): AnyValueRaiseAspect[Alg] = ev

  def fromRaiseAspect[Alg[_[_]]](
    ra: RaiseAspect[Alg, ToAnyValue, ToAnyValue, ToAnyValue]
  ): AnyValueRaiseAspect[Alg] = ???  // delegate every member, per TraceableRaiseAspect

  @experimental inline def derived[Alg[_[_]]]: AnyValueRaiseAspect[Alg] =
    fromRaiseAspect(DeriveRaise.aspect[Alg, ToAnyValue, ToAnyValue, ToAnyValue])
}
```

`DeriveRaise`'s exact package and method name come from
`TraceableRaiseAspect.scala` — copy them from there, do not guess.

**`fromRaiseAspect` must delegate every abstract member of `RaiseAspect`.**
M14's counterpart shipped a wrapper that silently dropped `Aspect#instrument`,
a concrete-and-overridable member, and the bug survived review because the
differential oracle did not override it either. `RaiseAspect` has two abstract
members (`intercept`, `mapK`) and no concrete ones — confirm that against
`RaiseAspect.scala` before writing the wrapper, and if a concrete member has
appeared since, forward it and give the oracle an override that would catch its
absence.

`Scala3UsageNote.scala` mirrors the natchez one: a compiled example showing
`@experimental` on the **companion object**, with the explanation of why not the
trait.

- [ ] **Step 4: Verify**

```bash
sbt "++3.3.8 otel4sTaglessMtlJVM/test" "++3.3.8 otel4sTaglessMtlJS/test"
sbt "++2.13.18 otel4sTaglessMtlJVM/test"
```

2.13 must be unaffected — these are `scala-3` sources.

- [ ] **Step 5: Commit**

```bash
git add otel4s-tagless-mtl/src
git commit -m "feat(m17): add AnyValueRaiseAspect for Scala 3 derives clauses"
```

### Acceptance criteria

- [ ] `@experimental object Foo` + `trait Foo[F[_]] derives AnyValueRaiseAspect`
      compiles, and the algebra type is usable from non-experimental code.
- [ ] The derived instance and a hand-written one agree on both paths.
- [ ] `fromRaiseAspect` forwards every member of `RaiseAspect`, verified against
      `RaiseAspect.scala` rather than assumed.
- [ ] 2.13 test counts unchanged.

---

## Task 6: Span-content proof, docs, and full verification

The test that matters most (D6) plus the module's documentation.

**Files:**
- Create `src/test/scala-jvm/com/dwolla/tracing/otel4s/mtl/RaiseSpanContentSpec.scala`
- Create `src/main/scala/com/dwolla/tracing/otel4s/mtl/package.scala`
- Modify `otel4s-tagless/src/main/scala/README.md`
- Modify `docs/plans/raise-aspect/32-milestone-M17-otel4s-tagless-mtl.md` (status)
- Modify `docs/plans/raise-aspect/01-overview-design-and-laws.md` §5

- [ ] **Step 1: Write the span-content test**

JVM-only, using the oteljava testkit. **Read
`otel4s-tagless/src/test/scala-jvm/com/dwolla/tracing/otel4s/SpanContentSpec.scala`
first** and reuse its harness — in particular `resultAndSpansFrom`.

Three assertions:

1. A raise records `raise.error.type` and `raise.error.value` with the expected
   values.
2. **The attributes land on the method's own span, not the parent.** Wrap the
   traced call in an outer span, assert the `raise.error.*` attributes are on the
   child whose name is `<Alg>.<method>` and that the outer span has none. This is
   D6's proof and the single most likely thing in M17 to be wrong.
3. An error rendering to `AnyValue.empty` records `raise.error.type` and **no**
   `raise.error.value` (D7).

Prove the second assertion discriminates before trusting it: mutate the
implementation to record on the parent and confirm it fails. Note the mutation
and its result in the task report.

- [ ] **Step 2: Run**

```bash
sbt "otel4sTaglessMtlJVM/testOnly com.dwolla.tracing.otel4s.mtl.RaiseSpanContentSpec"
sbt "++3.3.8 otel4sTaglessMtlJVM/testOnly com.dwolla.tracing.otel4s.mtl.RaiseSpanContentSpec"
```

- [ ] **Step 3: Write the package scaladoc**

`src/main/scala/com/dwolla/tracing/otel4s/mtl/package.scala`, mirroring
`natchez-tagless-mtl`'s. Adapt the worked example to otel4s and keep the
`Handle.allowF`/`.rescue` boundary pattern.

Cover, because they are otel4s-specific and appear nowhere else:

- the `Apply[F]` divergence (D9);
- what is recorded and where (D4, D5), and that the keys match the natchez
  module's exactly, so a migration produces comparable data;
- the overwrite case (D4);
- the **Submarine caveat**, which still applies — but verify whether otel4s's
  span finalizer reports the escaping `Submarine` the way natchez's `attachError`
  does, and write what you actually observe. Do not copy natchez's wording
  without checking; the natchez claim was verified against
  `natchez.mtl.LocalTrace#span` and the otel4s path is
  `SpanFinalizer.Strategy.reportAbnormal`, which is not the same code.
- the **circe divergence** (risk 4), extended to error values: an error ADT with
  a circe `Encoder` and a `Show` traces structurally under natchez and, after an
  import swap, compiles unchanged and records its `Show` rendering.

Check whether the surrounding text is a doctest before adding anything inside
`{{{ }}}`.

- [ ] **Step 4: Update the README and the plan docs**

Add an `otel4s-tagless-mtl` section to `otel4s-tagless/src/main/scala/README.md`
covering what the module is for and the recording behaviour. Add M17 to
`01-overview-design-and-laws.md` §5. Rewrite `32-…`'s Status section following
the shape M15 and M16 use: what landed task by task, what diverged and why,
verification with counts, and anything a later milestone needs.

- [ ] **Step 5: Full verification**

```bash
sbt +test
sbt "++2.12.21 natchez-tagless-rootJVM/test" "++2.12.21 natchez-tagless-rootJS/test"
sbt "+otel4sTaglessMtlJVM/doc" "+natchezTaglessMtlJVM/doc" "+raiseAspectCoreJVM/doc"
sbt githubWorkflowCheck
```

Record every module's per-version test counts. Compare `core`, `otel4sTagless`,
`natchezTaglessMtl` and `raiseAspectCore` against the branch point: only
`raiseAspectCore` (Task 1's new suite) and the new module should have moved.

- [ ] **Step 6: Commit**

```bash
git add otel4s-tagless-mtl/src otel4s-tagless/src/main/scala/README.md docs/plans/raise-aspect/
git commit -m "docs(m17): document the otel4s mtl module and record the milestone"
```

### Acceptance criteria

- [ ] All three span-content assertions pass on 2.13 and 3.
- [ ] The parent-vs-child assertion is proven discriminating by a recorded
      mutation.
- [ ] `+test` green; both 2.12 root runs green; all `doc` tasks green;
      `githubWorkflowCheck` clean.
- [ ] No module's test count changed except `raiseAspectCore` and the new one.
- [ ] The Submarine paragraph states what was observed against otel4s, not what
      natchez does.
