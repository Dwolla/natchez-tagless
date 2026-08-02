# Milestone M14 — implementation plan: `TraceableAspect`

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a Scala 3 user write `trait Foo[F[_]] derives TraceableAspect`
(with `@experimental` on `object Foo`, not the trait — see Global Constraints)
instead of a companion-object `implicit val fooAspect: Aspect[Foo,
TraceableValue, TraceableValue] = …`, and resolve the `TODO` in
`TraceWeaveCapturingInputsAndOutputs`' scaladoc that asks for exactly this —
introduced in `d0dfcf9` (2023-11-28, "remove cats-tagless-macros in preparation
for adding Scala 3 to the build").

**Architecture:** A one-parameter `trait TraceableAspect[Alg[_[_]]]` extending
`cats.tagless.aop.Aspect` with `Dom` and `Cod` pinned to
`natchez.TraceableValue` — **two** pinned parameters, because `Aspect` takes
three, not four; the fourth in M13 is `Err`, which only `RaiseAspect` has. Its
companion carries `derived`, which is what `derives` desugars to. `derived` is
`inline` (it calls the inline `Derive.aspect`), so the wrapper's anonymous class
is hoisted into a **non-inline** `fromAspect` factory. `instrument` is inherited
from `Aspect`'s default and is not implemented here. Nothing existing changes.

**Tech Stack:** Scala 3.3.8 only for the new sources (`core` still cross-builds
2.12.21 / 2.13.18 / 3.3.8, JVM and JS), cats, cats-tagless 0.16.5, natchez
0.3.10, natchez-testkit's `InMemory`, MUnit + munit-cats-effect, MiMa, sbt.

Read `26-milestone-M14-traceable-aspect.md` first — it is the milestone document
this plan implements, and its Decisions section must be ratified before Task 1
starts. It also records the arity correction (three parameters, not four) and
why.

## Global Constraints

- **`core` cross-builds on 2.12.21, 2.13.18 and 3.3.8, on JVM and JS.**
  Everything this plan adds lives under `core/shared/src/main/scala-3`,
  `core/shared/src/test/scala-3` or `core/jvm/src/test/scala`, so the 2.12 and
  2.13 axes must come out with **identical test counts** to `main`.
- **`core` has MiMa enabled against seven real published versions** (0.2.0–0.2.6,
  JVM and JS; `tlVersionIntroduced := Map("3" -> "0.2.4")` narrows the Scala 3
  axis to 0.2.4–0.2.6). It is **not** one of the four modules with
  `mimaPreviousArtifacts := Set.empty` (`build.sbt:108,139,159,172`). Every task
  that changes `core`'s main sources runs MiMa.
- **If MiMa reports anything, report it — do not add a
  `mimaBinaryIssueFilters` entry to silence it.** An additive Scala-3-only class
  cannot produce a MiMa problem; one appearing means the change is not what the
  milestone document says it is.
- **No `build.sbt` change, no new dependency.** `core/shared/src/main/scala-3`
  is already a source root under 3.3.8 (verified 2026-08-02 via
  `show coreJVM/Compile/unmanagedSourceDirectories`).
- **Additive only.** No existing main source file's signature may change. One
  scaladoc paragraph in `TraceWeaveCapturingInputsAndOutputs.scala` is the sole
  edit to an existing main file, and it must not change any signature.
- **Zero new compiler warnings** — verify locally with `-Xfatal-warnings`
  forced. **CI does not enforce this**: `sbt-typelevel-settings` 0.8.6 defaults
  `tlFatalWarnings := false` and nothing in this repo overrides it.
- The Scala 3 warning set is
  `-Wunused:{implicits,explicits,imports,locals,params,privates} -Wvalue-discard -Ykind-projector`.
  `-Ykind-projector` has no `:underscores`, so the placeholder is `*`.
- **`@experimental` is required, and where it goes matters.** A `derives`
  clause invokes `derived` from a given the compiler synthesizes into the
  algebra's companion object, so the annotation belongs on the **companion
  object** — `object Foo`, not `trait Foo` — plus any test class that summons
  the instance. Annotating the trait instead also compiles, but it is the
  placement to avoid: it makes the algebra *type* experimental, so the
  annotation goes viral across the algebra's whole consumer surface, including
  untraced call sites that never touch the instance. A sibling `@experimental`
  definition elsewhere in the same file is not enough — a companion object is
  not "a scope enclosing" the trait — and with the annotation nowhere at all the
  `derives` clause itself fails. See Task 2's `derived` scaladoc for the
  three-way measurement.
- **`core`'s test sources already declare top-level `Foo` and `Bar` in
  `com.dwolla.tracing`** (`ImplicitPrioritizationSpec.scala:33,39`). Do not reuse
  those names; this plan uses `Lookup`.
- `core` has `doctestSettings`, so `{{{ }}}` blocks in main sources are compiled
  and run on **all three** Scala versions. A Scala 3-only construct must not
  appear in a doctest in `core/shared/src/main/scala` — only in
  `core/shared/src/main/scala-3`.
- There is no `scripts/check` in this repo. Canonical verification is the
  per-module sbt invocations named in each task.
- **Never use `--no-verify`** or any other hook-bypass flag.

---

## File Structure

**`core` main:**

- `shared/src/main/scala-3/com/dwolla/tracing/TraceableAspect.scala` — **new.**
- `shared/src/main/scala/com/dwolla/tracing/TraceWeaveCapturingInputsAndOutputs.scala`
  — one scaladoc paragraph replaces the `TODO` at line 67. No signature change.

**`core` test:**

- `shared/src/test/scala-3/com/dwolla/tracing/LookupFixture.scala` — **new.**
  The algebra declared with `derives TraceableAspect`, plus a hand-written
  `Aspect` for the same algebra to serve as the differential oracle.
- `shared/src/test/scala-3/com/dwolla/tracing/TraceableAspectSpec.scala` —
  **new.** Forwarding, differential agreement, and both subsumption directions.
- `shared/src/test/scala-3/com/dwolla/tracing/TraceableAspectTracingSpec.scala`
  — **new.** The end-to-end gate, through `InMemory`.
- `jvm/src/test/scala/com/dwolla/tracing/TraceableAspectSerializationSpec.scala`
  — **new.** JVM-only `ObjectOutputStream` round trip.

**Unchanged and load-bearing:** `TraceWeaveOps.scala`, `TraceInstrumentation.scala`,
`InMemorySuite.scala`, `ImplicitPrioritizationSpec.scala`, `WeaveKnot.scala`.

---

## Task 1: the trait, `fromAspect`, and the first MiMa check

The macro-free half, plus the milestone's one real packaging risk checked as
early as it can be. `fromAspect` is where both abstract members are actually
implemented, so it is where forwarding is provable without `@experimental`,
without `derives`, and without a macro.

**Files:**
- Create: `core/shared/src/main/scala-3/com/dwolla/tracing/TraceableAspect.scala`
- Create: `core/shared/src/test/scala-3/com/dwolla/tracing/LookupFixture.scala`
- Create: `core/shared/src/test/scala-3/com/dwolla/tracing/TraceableAspectSpec.scala`

**Interfaces:**
- Consumes:
  - `trait Aspect[Alg[_[_]], Dom[_], Cod[_]] extends Instrument[Alg]` with
    `def weave[F[_]](af: Alg[F]): Alg[Aspect.Weave[F, Dom, Cod, *]]` and an
    inherited `def mapK[F[_], G[_]](af: Alg[F])(fk: F ~> G): Alg[G]`; a
    defaulted `def instrument[F[_]](af: Alg[F]): Alg[Instrumentation[F, *]]`
    (cats-tagless-core 0.16.5, `cats/tagless/aop/Aspect.scala:38-42`;
    mirrored at `reference/upstream/core/src/main/scala/cats/tagless/aop/Aspect.scala`)
  - `final case class Aspect.Weave[F[_], Dom[_], Cod[_], A](algebraName: String, domain: List[List[Advice[Eval, Dom]]], codomain: Advice.Aux[F, Cod, A])`
    (same file, `:72-81`)
  - `Aspect.Advice.byValue[G[_], T: G](name: String, value: T): Aux[Eval, G, T]`
    (same file, `:120-121`)
  - `natchez.TraceableValue`
- Produces:
  - `trait TraceableAspect[Alg[_[_]]] extends Aspect[Alg, TraceableValue, TraceableValue]`
  - `TraceableAspect.apply[Alg[_[_]]](implicit ev: TraceableAspect[Alg]): TraceableAspect[Alg]`
  - `TraceableAspect.fromAspect[Alg[_[_]]](underlying: Aspect[Alg, TraceableValue, TraceableValue]): TraceableAspect[Alg]`
  - `LookupFixture`'s `Lookup[F[_]]`, `Lookup.id`, and
    `HandWrittenLookupAspect.instance: Aspect[Lookup, TraceableValue, TraceableValue]`

- [ ] **Step 1: Write the failing test**

Create `core/shared/src/test/scala-3/com/dwolla/tracing/LookupFixture.scala`.
Only the hand-written half for now; the `derives` half arrives in Task 2:

```scala
package com.dwolla.tracing

import cats.tagless.aop.Aspect
import cats.{Applicative, ~>}
import cats.syntax.all.*
import natchez.TraceableValue

/** M14's fixture algebra. Named `Lookup` rather than `Foo`/`Bar` because
  * `ImplicitPrioritizationSpec` already declares top-level `Foo` and `Bar` in
  * this package.
  *
  * Lives in `src/test/scala-3` because Task 2 gives it a `derives` clause,
  * which is a syntax error on the 2.12 and 2.13 axes.
  */
trait Lookup[F[_]]:
  def get(key: String): F[String]

object Lookup:
  def apply[F[_]: Applicative]: Lookup[F] = new Lookup[F]:
    def get(key: String): F[String] = s"v:$key".pure[F]

/** The differential oracle: the instance a user writes by hand today, which is
  * literally the shape `TraceWeaveCapturingInputsAndOutputs`' scaladoc carries
  * as a worked example. `derives TraceableAspect` must agree with it.
  */
object HandWrittenLookupAspect:
  val instance: Aspect[Lookup, TraceableValue, TraceableValue] =
    new Aspect[Lookup, TraceableValue, TraceableValue]:
      def weave[F[_]](af: Lookup[F]): Lookup[Aspect.Weave[F, TraceableValue, TraceableValue, *]] =
        new Lookup[Aspect.Weave[F, TraceableValue, TraceableValue, *]]:
          def get(key: String): Aspect.Weave[F, TraceableValue, TraceableValue, String] =
            Aspect.Weave[F, TraceableValue, TraceableValue, String](
              "Lookup",
              List(List(Aspect.Advice.byValue[TraceableValue, String]("key", key))),
              Aspect.Advice[F, TraceableValue, String]("get", af.get(key))
            )

      def mapK[F[_], G[_]](af: Lookup[F])(fk: F ~> G): Lookup[G] =
        new Lookup[G]:
          def get(key: String): G[String] = fk(af.get(key))
```

Create `core/shared/src/test/scala-3/com/dwolla/tracing/TraceableAspectSpec.scala`:

```scala
package com.dwolla.tracing

import cats.Id
import cats.tagless.aop.Aspect
import munit.FunSuite
import natchez.TraceValue.StringValue
import natchez.TraceableValue

/** `TraceableAspect` adds no behaviour: it pins two type parameters so that
  * `derives` has a one-parameter type constructor to work with. These tests say
  * exactly that.
  */
class TraceableAspectSpec extends FunSuite {
  private val wide = HandWrittenLookupAspect.instance
  private val narrow: TraceableAspect[Lookup] = TraceableAspect.fromAspect(wide)

  private val impl: Lookup[Id] = Lookup[Id]

  /** Reduce a woven method to the parts that are comparable — `Aspect.Advice`
    * has reference equality (it overrides `toString` but not `equals`), which
    * is why every law suite in this repo renders rather than compares. */
  private def render(w: Aspect.Weave[Id, TraceableValue, TraceableValue, String])
      : (String, String, List[List[(String, StringValue)]], String) =
    (
      w.algebraName,
      w.codomain.name,
      w.domain.map(_.map(a => a.name -> StringValue(a.instance.toTraceValue(a.target.value).toString))),
      w.codomain.target
    )

  test("weave forwards to the underlying instance") {
    assertEquals(
      render(narrow.weave(impl).get("k")),
      render(wide.weave(impl).get("k"))
    )
  }

  test("mapK forwards to the underlying instance") {
    val fk = new cats.~>[Id, Option] { def apply[A](fa: Id[A]): Option[A] = Some(fa) }
    assertEquals(narrow.mapK(impl)(fk).get("k"), wide.mapK(impl)(fk).get("k"))
    assertEquals(narrow.mapK(impl)(fk).get("k"), Some("v:k"))
  }

  test("instrument is inherited from Aspect's default and works") {
    val i = narrow.instrument(impl).get("k")
    assertEquals(i.algebraName, "Lookup")
    assertEquals(i.methodName, "get")
    assertEquals(i.value, "v:k")
  }

  test("the narrow instance is accepted wherever the wide one is") {
    val asWide: Aspect[Lookup, TraceableValue, TraceableValue] = narrow
    assert(asWide ne null)
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
sbt "++3.3.8 coreJVM/testOnly com.dwolla.tracing.TraceableAspectSpec"
```

Expected: compile failure — `Not found: type TraceableAspect`.

- [ ] **Step 3: Create the trait and its companion**

Create `core/shared/src/main/scala-3/com/dwolla/tracing/TraceableAspect.scala`:

```scala
package com.dwolla.tracing

import cats.tagless.aop.Aspect
import cats.~>
import natchez.TraceableValue

/** A `cats.tagless.aop.Aspect` with `Dom` and `Cod` pinned to
  * `natchez.TraceableValue` — the shape `com.dwolla.tracing.syntax`'s
  * `traceWithInputsAndOutputs` demands.
  *
  * It exists so Scala 3 can derive it with a `derives` clause. `derives` needs
  * a ''one-parameter'' type constructor whose companion carries `derived`;
  * `Aspect` takes three, which is why upstream cats-tagless offers
  * `derives Instrument` but no `derives Aspect`. Pinning two of them in a trait
  * supplies the missing shape. (Two, not three: the `Err` parameter in
  * `com.dwolla.tracing.mtl.TraceableRaiseAspect` belongs to `RaiseAspect`, and
  * a plain `Aspect` algebra has no `Raise` capability for it to be about.)
  *
  * The relationship is one-way: a `TraceableAspect[Alg]` ''is'' an
  * `Aspect[Alg, TraceableValue, TraceableValue]`, so it satisfies the tracing
  * syntax, `WeaveInterpreter` and everything phrased in terms of `Instrument`;
  * the converse is false, and [[TraceableAspect.fromAspect]] is how you cross
  * the other way.
  *
  * Scala 3 only — `derives` does not exist on Scala 2, and this type has no
  * other purpose. A cross-built algebra therefore cannot use `derives` in its
  * shared sources; that is inherent to the feature.
  */
trait TraceableAspect[Alg[_[_]]] extends Aspect[Alg, TraceableValue, TraceableValue]

object TraceableAspect:
  def apply[Alg[_[_]]](implicit ev: TraceableAspect[Alg]): TraceableAspect[Alg] = ev

  /** Narrow an existing `Aspect` at the natchez shape.
    *
    * Deliberately ''not'' `inline`, even though its only in-library caller is
    * the inline `derived`: an anonymous class written directly in an inline
    * method body is duplicated at every call site and the compiler warns
    * accordingly, while hoisting it into a `private` class fails outright
    * because the inline body is spliced at the call site and could not see it.
    * A plain method compiles the anonymous class exactly once, here.
    *
    * Public because it is independently useful: it is the only way to turn a
    * hand-written or Scala 2-derived `Aspect` into the narrow type.
    */
  def fromAspect[Alg[_[_]]](
      underlying: Aspect[Alg, TraceableValue, TraceableValue]
  ): TraceableAspect[Alg] =
    new TraceableAspect[Alg]:
      def weave[F[_]](af: Alg[F]): Alg[Aspect.Weave[F, TraceableValue, TraceableValue, *]] =
        underlying.weave(af)

      def mapK[F[_], G[_]](af: Alg[F])(fk: F ~> G): Alg[G] =
        underlying.mapK(af)(fk)
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
sbt "++3.3.8 coreJVM/testOnly com.dwolla.tracing.TraceableAspectSpec"
```

Expected: PASS, 4 tests.

- [ ] **Step 5: Run MiMa — the first of two checks**

This is the point of doing it now rather than at the end: the new public type
exists, and nothing else has changed, so anything MiMa says is unambiguously
about it.

```bash
sbt "+coreJVM/mimaReportBinaryIssues" "+coreJS/mimaReportBinaryIssues"
```

Expected: `[success]` on all three Scala versions, both platforms, with **no
problems reported and no filter added**. Record the output.

If MiMa reports anything, **stop and report it** rather than adding a
`mimaBinaryIssueFilters` entry. A new class in a Scala-3-only source directory
cannot remove or change anything present in a previous artifact; a problem here
means the change is not additive and the milestone's premise is wrong.

Sanity-check that MiMa is actually comparing against something, so a green run
is not green for the wrong reason:

```bash
sbt "show coreJVM/mimaPreviousArtifacts"
```

Expected: a non-empty set — seven entries (`natchez-tagless:0.2.0` …
`0.2.6`) on 2.13.

- [ ] **Step 6: Confirm the other two axes are untouched**

```bash
sbt "++2.13.18 coreJVM/test" "++2.12.21 coreJVM/test"
```

Expected: PASS with **exactly the counts `main` produces**. Record both.

- [ ] **Step 7: Commit**

```bash
git add core/shared/src/main/scala-3/ core/shared/src/test/scala-3/
git commit -m "feat: add TraceableAspect, the natchez-shaped Aspect subtype

A Scala 3 derives clause needs a one-parameter type constructor and Aspect takes
three, which is why upstream has derives Instrument but no derives Aspect.
Pinning Dom and Cod to TraceableValue in a trait supplies the shape, for exactly
the Aspect the tracing syntax already demands. MiMa on core is clean, JVM and
JS, with no filter."
```

---

## Task 2: `derived`, the `derives` clause, and serializability

**Files:**
- Modify: `core/shared/src/main/scala-3/com/dwolla/tracing/TraceableAspect.scala`
- Modify: `core/shared/src/test/scala-3/com/dwolla/tracing/LookupFixture.scala`
- Modify: `core/shared/src/test/scala-3/com/dwolla/tracing/TraceableAspectSpec.scala`
- Create: `core/jvm/src/test/scala/com/dwolla/tracing/TraceableAspectSerializationSpec.scala`

**Interfaces:**
- Consumes:
  - Task 1's `TraceableAspect.fromAspect`
  - `object Derive` — **`@experimental` in its entirety** — and
    `inline def Derive.aspect[Alg[_[_]], Dom[_], Cod[_]]: Aspect[Alg, Dom, Cod]`
    (cats-tagless-core 0.16.5, `cats/tagless/Derive.scala:28-29,53`)
- Produces:
  - `@experimental inline def TraceableAspect.derived[Alg[_[_]]]: TraceableAspect[Alg]`
  - `trait DerivesLookup[F[_]] derives TraceableAspect` with a separate
    `@experimental object DerivesLookup`

- [ ] **Step 1: Write the failing test**

Append to `LookupFixture.scala`:

```scala
/** The point of M14, declared. Structurally identical to `Lookup`, so the two
  * can be compared directly and the expected span history differs only in the
  * algebra name.
  *
  * `@experimental` is required, and where it goes matters: a `derives` clause
  * invokes `derived` from a given the compiler synthesizes into the algebra's
  * companion object, so the annotation belongs on the companion — not the
  * trait, which stays unannotated so the algebra type itself is usable from
  * ordinary code. `TraceableAspect.derived` is `@experimental` because the
  * whole of cats-tagless's `object Derive` is, and the 3.3.x LTS line has no
  * `-experimental` flag to opt out with.
  */
trait DerivesLookup[F[_]] derives TraceableAspect:
  def get(key: String): F[String]

@experimental
object DerivesLookup:
  def apply[F[_]: Applicative]: DerivesLookup[F] = new DerivesLookup[F]:
    def get(key: String): F[String] = s"v:$key".pure[F]
```

with `import scala.annotation.experimental` added to the file.

Append to `TraceableAspectSpec.scala` (and annotate the class `@experimental`,
adding the import):

```scala
  test("the derives clause produces an instance, and it is the narrow type") {
    val derived: TraceableAspect[DerivesLookup] = summon[TraceableAspect[DerivesLookup]]
    assert(derived ne null)
  }

  test("the derived instance agrees with the hand-written one, modulo the algebra name") {
    val derivedImpl: DerivesLookup[Id] = DerivesLookup[Id]
    val d = summon[TraceableAspect[DerivesLookup]].weave(derivedImpl).get("k")
    val h = wide.weave(impl).get("k")

    assertEquals(d.algebraName, "DerivesLookup")
    assertEquals(h.algebraName, "Lookup")
    assertEquals(d.codomain.name, h.codomain.name)
    assertEquals(d.codomain.target, h.codomain.target)
    assertEquals(
      d.domain.map(_.map(a => a.name -> a.instance.toTraceValue(a.target.value))),
      h.domain.map(_.map(a => a.name -> a.instance.toTraceValue(a.target.value)))
    )
  }

  test("a wide Aspect does not satisfy a demand for the narrow type") {
    assert(
      compileErrors(
        "summon[TraceableAspect[Lookup]](using HandWrittenLookupAspect.instance)"
      ).nonEmpty,
      "Aspect[Lookup, TraceableValue, TraceableValue] must not be a TraceableAspect[Lookup]"
    )
  }

  test("...and fromAspect is how you get one anyway") {
    val fixed: TraceableAspect[Lookup] = TraceableAspect.fromAspect(HandWrittenLookupAspect.instance)
    assert(fixed ne null)
  }
```

Create `core/jvm/src/test/scala/com/dwolla/tracing/TraceableAspectSerializationSpec.scala`:

```scala
package com.dwolla.tracing

import munit.FunSuite

import java.io.{ByteArrayOutputStream, ObjectOutputStream}
import scala.annotation.experimental

/** `Aspect extends Instrument extends FunctorK extends InvariantK extends
  * Serializable`, so `TraceableAspect` inherits a declared contract that
  * `fromAspect`'s wrapper could break by capturing something unserializable.
  * It captures exactly one field — the underlying `Aspect`, itself declared
  * `Serializable` — so this should hold by construction; assert it anyway,
  * because "by construction" is an argument and this is a check.
  *
  * JVM-only: `ObjectOutputStream` does not exist on Scala.js. `core` is
  * `CrossType.Full`, so this file simply lives under `core/jvm` rather than
  * needing the `Platform.isJvm` constant `raise-aspect-core` uses.
  */
@experimental
class TraceableAspectSerializationSpec extends FunSuite {
  private def roundTrips(a: AnyRef): Boolean = {
    val bytes = new ByteArrayOutputStream()
    val out = new ObjectOutputStream(bytes)
    out.writeObject(a)
    out.close()
    bytes.size() > 0
  }

  test("a derived TraceableAspect is Serializable") {
    assert(roundTrips(summon[TraceableAspect[DerivesLookup]]))
  }

  test("a TraceableAspect built with fromAspect is Serializable") {
    assert(roundTrips(TraceableAspect.fromAspect(HandWrittenLookupAspect.instance)))
  }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
sbt "++3.3.8 coreJVM/testOnly com.dwolla.tracing.TraceableAspectSpec com.dwolla.tracing.TraceableAspectSerializationSpec"
```

Expected: compile failure — `value derived is not a member of object TraceableAspect`,
reported at `LookupFixture.scala`'s `derives` clause.

- [ ] **Step 3: Add `derived`**

Append to `TraceableAspect.scala`'s companion, adding
`import cats.tagless.Derive` and `import scala.annotation.experimental`:

```scala
  /** What a `derives TraceableAspect` clause calls.
    *
    * `@experimental` because cats-tagless's `object Derive` is annotated in its
    * entirety. The annotation is required wherever `derived` is ''invoked''
    * from, and a `derives` clause invokes it from a given the compiler
    * synthesizes into the algebra's '''companion object''' — so `@experimental`
    * belongs on the companion, as below, not on the trait. A sibling
    * `@experimental` definition elsewhere in the same file is not enough; with
    * the annotation nowhere at all the `derives` clause itself reports `method
    * derived is marked @experimental and therefore may only be used in an
    * experimental scope`. On Scala 3.4+ the `-experimental` compiler flag is an
    * alternative; this repository targets the 3.3.x LTS line, where that flag
    * does not exist.
    *
    * Annotating the trait instead also compiles, and it is the placement to
    * avoid: it makes the algebra ''type'' experimental, so the annotation goes
    * viral across the algebra's whole consumer surface — an unrelated, untraced
    * `def use[F[_]](v: Greeter[F])` would then fail with `trait Greeter is
    * marked @experimental and therefore may only be used in an experimental
    * scope`. On the companion it reaches only the companion's own members, and
    * every consumer of the algebra type is unaffected. This is still slightly
    * more than the hand-written spelling costs — there the annotation sits on
    * a single `implicit val`, whereas `derives` has no way to annotate the
    * synthesized given alone — but both leave the algebra type itself clean.
    *
    * The one case with no good answer: an algebra that declares no companion at
    * all has nowhere to put the annotation but the trait. Declaring an empty
    * `@experimental object Alg` alongside it avoids the virality.
    *
    * {{{
    *   import cats.Applicative
    *   import cats.syntax.all.*
    *   import com.dwolla.tracing.TraceableAspect
    *
    *   import scala.annotation.experimental
    *
    *   // no @experimental here: the algebra type stays usable from ordinary code
    *   trait Greeter[F[_]] derives TraceableAspect {
    *     def greet(name: String): F[String]
    *   }
    *
    *   // ...it goes here instead, where the synthesized given lands
    *   @experimental
    *   object Greeter {
    *     def apply[F[_]: Applicative]: Greeter[F] = new Greeter[F] {
    *       def greet(name: String): F[String] = ("hello, " + name).pure[F]
    *     }
    *   }
    * }}}
    */
  @experimental
  inline def derived[Alg[_[_]]]: TraceableAspect[Alg] =
    fromAspect(Derive.aspect[Alg, TraceableValue, TraceableValue])
```

- [ ] **Step 4: Run to verify it passes**

```bash
sbt "++3.3.8 coreJVM/testOnly com.dwolla.tracing.TraceableAspectSpec com.dwolla.tracing.TraceableAspectSerializationSpec"
```

Expected: PASS, 8 + 2 tests.

If the failure is instead `Not found: given natchez.TraceableValue[…]` at the
`derives` clause, the macro is genuinely running and reporting a missing
instance — add it. If it is `method derived is marked @experimental`, the
annotation is missing on the algebra's companion object or on the summoning
class, not on `derived`.

- [ ] **Step 5: Check the doctest ran, and that it did not leak to Scala 2**

```bash
sbt "++3.3.8 coreJVM/test" 2>&1 | grep -i "doctest"
sbt "++2.13.18 coreJVM/test" 2>&1 | grep -ci "TraceableAspect"
```

Expected: the Scala 3 run produces and passes a doctest suite for
`TraceableAspect.scala`; the 2.13 run mentions it **zero** times, because the
file is not in that axis's source set. If the harness cannot handle `derives` in
a snippet, reduce the example to the smallest form that runs and record what
failed — do not delete it.

- [ ] **Step 6: MiMa again, and zero warnings**

```bash
sbt "+coreJVM/mimaReportBinaryIssues" "+coreJS/mimaReportBinaryIssues"
sbt "++3.3.8 set core.jvm/scalacOptions += \"-Xfatal-warnings\"" "coreJVM/test"
```

Expected: MiMa clean on both platforms and all versions, no filter. Tests pass
under fatal warnings — in particular **no** `New anonymous class definition will
be duplicated at each inline site`; that warning means the anonymous class ended
up inside `derived` rather than inside `fromAspect`.

Note: `core`'s Scala 3 build has four pre-existing unused-import warnings
(recorded in `22-milestone-M12-fused-derivation.md`'s status section). Under
forced fatal warnings those will fail the build. Either scope the flag to
`Test` only, or confirm the failures are exactly those four pre-existing ones
and no others — and say which you did in the task report. **Do not fix them
here**; that is its own task.

- [ ] **Step 7: Commit**

```bash
git add core/shared/src/main/scala-3/ core/shared/src/test/scala-3/ core/jvm/src/test/
git commit -m "feat: derive TraceableAspect with a Scala 3 derives clause

TraceableAspect.derived wraps cats-tagless's Derive.aspect at the natchez shape,
so an algebra can say 'derives TraceableAspect' instead of hand-writing weave
and mapK. The derived instance agrees with a hand-written Aspect on every
comparable field, inherits instrument from Aspect's default, and survives an
ObjectOutputStream round trip. MiMa on core stays clean."
```

---

## Task 3: the ergonomics gate — end to end through the tracing syntax

Tasks 1 and 2 prove the type exists, forwards, and matches a hand-written
oracle. Neither proves a *user* gets anything. This does.

**Files:**
- Create: `core/shared/src/test/scala-3/com/dwolla/tracing/TraceableAspectTracingSpec.scala`

**Interfaces:**
- Consumes: Tasks 1 and 2; `InMemorySuite` and its
  `traceTest(name: String, tt: TraceTest)` helper
  (`core/shared/src/test/scala/com/dwolla/tracing/InMemorySuite.scala:14-28`);
  `com.dwolla.tracing.syntax.ToTraceWeaveOps#traceWithInputsAndOutputs`
  (`core/shared/src/main/scala/com/dwolla/tracing/syntax/TraceWeaveOps.scala:21-25`),
  which demands `Aspect[Alg, TraceableValue, TraceableValue]` and is **not
  edited**.
- Produces: no new API.

- [ ] **Step 1: Write the failing test**

Create `core/shared/src/test/scala-3/com/dwolla/tracing/TraceableAspectTracingSpec.scala`:

```scala
package com.dwolla.tracing

import cats.effect.MonadCancelThrow
import cats.mtl.Local
import cats.syntax.all.*
import com.dwolla.tracing.syntax.*
import natchez.InMemory.Lineage.Root
import natchez.InMemory.NatchezCommand.*
import natchez.InMemory.{Lineage, NatchezCommand}
import natchez.TraceValue.StringValue
import natchez.*

import scala.annotation.experimental

/** The point of M14, stated as a test: an algebra that says
  * `derives TraceableAspect` and nothing else traces exactly as one with a
  * hand-declared `Aspect` does.
  *
  * Nothing here mentions `Derive`, declares an instance, or names `Aspect`. The
  * two `expectedHistory` lists below differ only in the algebra name — that
  * difference, and no other, is the whole claim.
  */
@experimental
class TraceableAspectTracingSpec extends InMemorySuite {
  private def historyFor(alg: String): List[(Lineage, NatchezCommand)] = List(
    Root -> CreateRootSpan("test", Kernel(Map.empty), Span.Options.Defaults),
    Root("test") -> CreateSpan(s"$alg.get", None, Span.Options.Defaults),
    Root("test") / s"$alg.get" -> Put(List(s"$alg.get.key" -> StringValue("k"))),
    Root("test") / s"$alg.get" -> Put(List(s"$alg.get.returnValue" -> StringValue("v:k"))),
    Root("test") -> ReleaseSpan(s"$alg.get"),
    Root -> ReleaseRootSpan("test")
  )

  traceTest(
    "an algebra deriving TraceableAspect captures span, input, and output",
    new TraceTest {
      def program[F[_]: MonadCancelThrow](entryPoint: EntryPoint[F])(implicit L: Local[F, Span[F]]): F[Unit] = {
        import natchez.mtl.*
        val traced: DerivesLookup[F] = DerivesLookup[F].traceWithInputsAndOutputs
        entryPoint.root("test").use(L.scope(traced.get("k").void))
      }

      override def expectedHistory: List[(Lineage, NatchezCommand)] = historyFor("DerivesLookup")
    }
  )

  traceTest(
    "...identically to the same algebra with a hand-written Aspect",
    new TraceTest {
      def program[F[_]: MonadCancelThrow](entryPoint: EntryPoint[F])(implicit L: Local[F, Span[F]]): F[Unit] = {
        import natchez.mtl.*
        implicit val a: cats.tagless.aop.Aspect[Lookup, TraceableValue, TraceableValue] =
          HandWrittenLookupAspect.instance
        val traced: Lookup[F] = Lookup[F].traceWithInputsAndOutputs
        entryPoint.root("test").use(L.scope(traced.get("k").void))
      }

      override def expectedHistory: List[(Lineage, NatchezCommand)] = historyFor("Lookup")
    }
  )
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
sbt "++3.3.8 coreJVM/testOnly com.dwolla.tracing.TraceableAspectTracingSpec"
```

Expected: red, for a reason you can name. **If it passes on the first run,
check the class is actually being collected** — `testOnly` reports 0 tests for a
name that does not resolve — before believing it.

Two failure modes are informative rather than alarming and should be fixed in
the test, not the main sources: the `natchez.mtl.*` import placement (needed for
`Trace[F]` from `Local[F, Span[F]]`), and the `Local`/`scope` wiring, whose
specification is `InMemorySuite`'s `TraceTest`.

- [ ] **Step 3: Make it pass without touching any main source**

There is no production change in this task. If making it pass requires editing
`TraceWeaveOps`, `TraceWeaveCapturingInputsAndOutputs` or `TraceableAspect`,
**stop and report** — the milestone's premise is that the narrow type satisfies
the existing demand unchanged.

- [ ] **Step 4: Confirm the two histories differ only by the algebra name**

Both come from the same `historyFor` function in this spec, so the property is
structural rather than something to diff. Confirm that is still true — that
nobody has replaced one of the two `historyFor(…)` calls with a literal list —
and say so in the task report:

```bash
grep -n "expectedHistory" core/shared/src/test/scala-3/com/dwolla/tracing/TraceableAspectTracingSpec.scala
```

Expected: two lines, both `= historyFor(…)`.

- [ ] **Step 5: Run the whole module, and `ImplicitPrioritizationSpec` deliberately**

```bash
sbt "++3.3.8 coreJVM/test"
sbt "+coreJVM/testOnly com.dwolla.tracing.ImplicitPrioritizationSpec"
```

Expected: PASS. `ImplicitPrioritizationSpec` is the suite that would notice if
adding a `TraceableValue`-shaped type to this package disturbed instance
resolution; run it on all three versions explicitly rather than trusting it to
be swept up.

- [ ] **Step 6: Cross-build, JS linker, MiMa**

```bash
sbt "+coreJVM/test" "+coreJS/Test/scalaJSLinkerResult" "+coreJVM/mimaReportBinaryIssues" "+coreJS/mimaReportBinaryIssues"
```

Expected: green on 2.12.21, 2.13.18 and 3.3.8, with the 2.12 and 2.13 counts
**unchanged from `main`**; JS linker green; MiMa clean with no filter. (Test
execution on JS is not available locally — no Node — consistent with every prior
milestone.)

- [ ] **Step 7: Commit**

```bash
git add core/shared/src/test/scala-3/
git commit -m "test: prove derives TraceableAspect traces end to end

An algebra whose only instance declaration is a derives clause produces, through
the unedited com.dwolla.tracing.syntax and the real natchez InMemory backend,
the same span history as the same algebra with a hand-written Aspect — the two
expected histories are the same function of the algebra name."
```

---

## Task 4: documentation, and the `TODO` that asked for this

**No new production behaviour.** One scaladoc paragraph in an existing main
file, and the plan docs.

**Files:**
- Modify: `core/shared/src/main/scala/com/dwolla/tracing/TraceWeaveCapturingInputsAndOutputs.scala` (scaladoc only)
- Modify: `docs/plans/raise-aspect/01-overview-design-and-laws.md` §5
- Modify: `docs/plans/raise-aspect/26-milestone-M14-traceable-aspect.md` (status only)

**Interfaces:**
- Consumes: Tasks 1–3. No new API.

- [ ] **Step 1: Resolve the `TODO`**

`core/shared/src/main/scala/com/dwolla/tracing/TraceWeaveCapturingInputsAndOutputs.scala:66-67`
currently reads:

```scala
 *     implicit val fooTracingAspect: Aspect[Foo, TraceableValue, TraceableValue] = { // Derive.aspect
 *       // TODO reintroduce derived instance when cats-tagless-macros supports Scala 3
```

**The example itself must stay.** It is inside a `{{{ }}}` block, and `core` has
`doctestSettings`, so it is compiled and run on 2.12 and 2.13 as well — where
`derives` is a syntax error. Replace only the `TODO` line, and add a short
paragraph after the block:

```
 *     implicit val fooTracingAspect: Aspect[Foo, TraceableValue, TraceableValue] = {
 *       // hand-written so this example compiles on 2.12 and 2.13 too; see the
 *       // note below for the Scala 3 one-liner
```

and, after the closing `}}}`:

```
 * On Scala 3 the whole instance above collapses to a `derives` clause:
 * `trait Foo[F[_]] derives TraceableAspect` with a separate `@experimental
 * object Foo`. See `com.dwolla.tracing.TraceableAspect`, which pins `Dom` and
 * `Cod` to `TraceableValue` so that `derives` has the one-parameter type
 * constructor it requires. `@experimental` is still required, and ''where'' it
 * goes matters: a `derives` clause invokes `derived` from a given the compiler
 * synthesizes into the algebra's companion object, so the annotation belongs
 * on the companion, not the trait — annotating the trait instead also
 * compiles, but makes the algebra ''type'' experimental, forcing
 * `@experimental` onto every reference to it, including untraced call sites
 * that never touch the instance. The 3.3.x LTS line has no `-experimental`
 * flag to opt out with. There is no Scala 2 equivalent — `derives` does not
 * exist there — so a cross-built algebra keeps the form above.
```

Verify the doctest still compiles on all three versions after the edit; the
example's *code* must be byte-identical apart from the comment line.

- [ ] **Step 2: Reconcile the overview's milestone map with what actually happened**

`01-overview-design-and-laws.md` §5 **already carries** the
"Fourth round (2026-08-02)" block naming M13, M14 and M15 — it was written when
these three were planned. Do not re-add it.

Check it against reality. The M14-specific claims it makes are:

- **"Two pinned parameters, not three: `Aspect[Alg, Dom, Cod]` has no `Err`"** —
  the arity correction. Confirm the shipped trait says the same thing.
- that upstream offers `derives Instrument` but no `derives Aspect`, and why.
- that the change is strictly additive and `TraceWeaveOps` is untouched —
  confirm with `git diff`.

Fix anything the implementation falsified, and confirm the block's closing
sentence about M16 survives intact.

- [ ] **Step 3: Record the arity correction where a reader will find it**

The request that produced this milestone asked for four type parameters. The
correction is already in `26-milestone-M14-traceable-aspect.md`; make sure the
*code* also carries it, so a reader who never opens the plan docs still learns
it — the trait's scaladoc paragraph beginning "(Two, not three: …)" added in
Task 1 is that carrier. Confirm it survived review, and cross-reference
`TraceableRaiseAspect` from it.

- [ ] **Step 4: Update M14's status section**

In `26-milestone-M14-traceable-aspect.md`, replace "Planned, not started" with
the outcome, following the shape M6, M7 and M12 use: what landed task by task,
what diverged and why, verification actually run with counts (including both
MiMa runs), and anything found for later milestones. Answer Q1 explicitly.

- [ ] **Step 5: Full verification**

```bash
sbt "+coreJVM/test" "+coreJS/Test/scalaJSLinkerResult" "+coreJVM/doc" \
    "+coreJVM/mimaReportBinaryIssues" "+coreJS/mimaReportBinaryIssues"
```

Expected: all green. `doc` matters — the new scaladoc has a `{{{ }}}` block and
a cross-reference, and a broken link fails there rather than in `test`.

- [ ] **Step 6: Commit**

```bash
git add core/ docs/plans/raise-aspect/
git commit -m "docs: record M14 and answer the TODO that asked for a derived Aspect

TraceWeaveCapturingInputsAndOutputs' scaladoc has asked for a derived instance
since before cats-tagless supported Scala 3. The hand-written example stays,
because that doctest runs on 2.12 and 2.13 too; a note now points Scala 3
readers at the one-word form."
```

---

## Acceptance criteria

- [ ] `trait TraceableAspect[Alg[_[_]]] extends Aspect[Alg, TraceableValue, TraceableValue]`
      exists in `com.dwolla.tracing`, under `core/shared/src/main/scala-3`, with
      `apply`, `fromAspect` and `@experimental inline derived`.
- [ ] `trait DerivesLookup[F[_]] derives TraceableAspect` with a separate
      `@experimental object DerivesLookup` compiles and traces through the
      **unedited** `com.dwolla.tracing.syntax`, producing the same span history
      as the hand-written `Aspect` for the same algebra — literally the same
      function of the algebra name.
- [ ] Derived and hand-written agree on `weave`'s algebra name, method name,
      rendered domain and codomain target, and on `mapK`; `instrument` is
      inherited and works.
- [ ] `compileErrors` confirms a wide `Aspect[…]` is not a `TraceableAspect[…]`;
      `fromAspect` converts it.
- [ ] `+coreJVM/mimaReportBinaryIssues` and `+coreJS/mimaReportBinaryIssues`
      report no problems, run at least twice, with **no**
      `mimaBinaryIssueFilters` entry added; and `show coreJVM/mimaPreviousArtifacts`
      confirms it was comparing against a non-empty set.
- [ ] A derived instance and a `fromAspect` instance both survive
      `ObjectOutputStream`.
- [ ] `+coreJVM/test` green on all three versions with 2.12/2.13 counts
      **identical to `main`**; JS linker green; `doc` succeeds;
      `ImplicitPrioritizationSpec` passes unedited on all three.
- [ ] `git diff` on `TraceWeaveCapturingInputsAndOutputs.scala` shows scaladoc
      changes only — no signature, and the doctest's Scala code unchanged apart
      from one comment line. The `TODO` is gone.
- [ ] Zero new warnings under forced `-Xfatal-warnings`, or exactly the four
      pre-existing Scala 3 unused-import warnings and no others, stated
      explicitly.

## Ground rules reminder

- Additive only. Editing an existing main source signature falsifies the
  milestone; stop and report.
- **Never silence MiMa with a filter in this milestone.** If it speaks, that is
  the finding.
- Do not fix `core`'s four pre-existing Scala 3 unused-import warnings here;
  flag them, as M12 did.
- Do not do M13's or M15's work here, and do not touch M16 (otel4s).
- Never use `--no-verify` or any other hook-bypass flag.
