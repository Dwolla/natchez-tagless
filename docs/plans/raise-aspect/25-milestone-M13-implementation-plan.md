# Milestone M13 — implementation plan: `TraceableRaiseAspect`

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a Scala 3 user write
`@experimental trait Bar[F[_]] derives TraceableRaiseAspect` instead of a
companion object holding
`@experimental implicit val … : RaiseAspect[Bar, TraceableValue, TraceableValue, TraceableValue] = DeriveRaise.aspect[Bar, TraceableValue, TraceableValue, TraceableValue]`,
and prove the result is the same instance doing the same tracing.

**Architecture:** A one-parameter `trait TraceableRaiseAspect[Alg[_[_]]]`
extending `RaiseAspect` with all three of `Dom`, `Cod` and `Err` pinned to
`natchez.TraceableValue`. Its companion carries `derived`, which is what
`derives` desugars to. `derived` must be `inline` (it calls the inline
`DeriveRaise.aspect`), so the wrapper's anonymous class is hoisted into a
**non-inline** `fromRaiseAspect` factory to avoid duplicating it at every call
site. Nothing existing changes: the trait is a *subtype* of the type every
downstream demand is already phrased in, so `WeaveInterpreter`,
`RaiseTraceWeaveOps` and `RaiseRecorder` are untouched.

**Tech Stack:** Scala 3.3.8 only for the new sources (the module still
cross-builds 2.12.21 / 2.13.18 / 3.3.8), cats, cats-mtl, cats-tagless, natchez,
MUnit + munit-cats-effect + natchez-testkit's `InMemory`, sbt.

Read `24-milestone-M13-traceable-raise-aspect.md` first — it is the milestone
document this plan implements, and its Decisions section must be ratified
before Task 1 starts. Read `22-milestone-M12-fused-derivation.md` for why
`RaiseAspect`'s abstract surface is `intercept` + `mapK`.

## Global Constraints

- **The module still cross-builds on 2.12.21, 2.13.18 and 3.3.8.** Everything
  this plan adds lives under `src/main/scala-3` or `src/test/scala-3`, so the
  2.12 and 2.13 axes must come out with **identical test counts** to `main`.
  A change there means something leaked into shared sources.
- **No `build.sbt` change, no new dependency.** `natchez-tagless-mtl` already
  has `src/main/scala-3` and `src/test/scala-3` directories, and
  `natchezTaglessMtlJVM/Compile/unmanagedSourceDirectories` under 3.3.8 lists
  `natchez-tagless-mtl/src/main/scala-3` — verified 2026-08-02.
- **Additive only.** No existing main source file's signature may change. If a
  task appears to require editing `RaiseAspect`, `WeaveInterpreter`,
  `RaiseTraceWeaveOps` or `RaiseRecorder`, **stop and report** — that falsifies
  the milestone's premise.
- **No expected span history may be edited.** M13 adds no behaviour.
- **Zero new compiler warnings** — verify locally with `-Xfatal-warnings`
  forced. **CI does not enforce this**: `sbt-typelevel-settings` 0.8.6 defaults
  `tlFatalWarnings := false` and nothing in this repo overrides it. Verify
  anyway, as every recent milestone has.
- The Scala 3 warning set for this repo is
  `-Wunused:{implicits,explicits,imports,locals,params,privates} -Wvalue-discard -Ykind-projector`
  (from `show coreJVM/scalacOptions` under 3.3.8). Note `-Ykind-projector`
  **without** `:underscores`, so the placeholder is `*`, as in
  `RaiseAspect.scala`.
- **`@experimental` is required on any definition that mentions `derived`**,
  including the algebra carrying the `derives` clause and any test class that
  summons the instance. A sibling `@experimental` definition in the same file is
  not enough — measured on 3.3.8.
- New Scala-3-only sources use Scala 3 syntax (`object X:`, `using`), matching
  `raise-aspect-macros/src/main/scala-3/com/dwolla/tagless/mtl/DeriveRaise.scala`.
- There is no `scripts/check` in this repo. Canonical verification is the
  per-module sbt invocations named in each task.
- **Never use `--no-verify`** or any other hook-bypass flag.

---

## File Structure

**`natchez-tagless-mtl` main (Scala 3 only):**

- `src/main/scala-3/com/dwolla/tracing/mtl/TraceableRaiseAspect.scala` —
  **new.** The trait, and a companion with `apply`, `fromRaiseAspect` and
  `derived`.

**`natchez-tagless-mtl` test (Scala 3 only):**

- `src/test/scala-3/com/dwolla/tracing/mtl/HandWrittenBarRaiseAspect.scala` —
  **new.** The differential reference: a `RaiseAspect[Bar, TraceableValue,
  TraceableValue, TraceableValue]` written by hand, with no macro in it.
- `src/test/scala-3/com/dwolla/tracing/mtl/TraceableRaiseAspectSpec.scala` —
  **new.** Task 1's forwarding proof and Task 3's subsumption assertions.
- `src/test/scala-3/com/dwolla/tracing/mtl/DerivesBarFixture.scala` — **new.**
  The algebra declared with `derives TraceableRaiseAspect`.
- `src/test/scala-3/com/dwolla/tracing/mtl/DerivesBarTracingSpec.scala` —
  **new.** The end-to-end ergonomics gate, through `InMemory`.

**Unchanged and load-bearing:** `BarFixture.scala`,
`RaiseTraceIntegrationSuite.scala`, `RaiseTraceValueSuite.scala`, both
`RaiseTraceIntegrationSpec.scala`, `RaiseTraceWeaveOps.scala`,
`RaiseRecorder.scala`, `WeaveInterpreter.scala`, `RaiseAspect.scala`.

---

## Task 1: the trait, and `fromRaiseAspect`

The macro-free half. `fromRaiseAspect` is the only place the two abstract
members are actually implemented, so it is where forwarding can be proven
without `@experimental`, without `derives`, and without a macro in the picture.
Getting it green first means that when Task 2's one-line `derived` fails, the
failure is unambiguously about derivation.

**Files:**
- Create: `natchez-tagless-mtl/src/main/scala-3/com/dwolla/tracing/mtl/TraceableRaiseAspect.scala`
- Create: `natchez-tagless-mtl/src/test/scala-3/com/dwolla/tracing/mtl/HandWrittenBarRaiseAspect.scala`
- Create: `natchez-tagless-mtl/src/test/scala-3/com/dwolla/tracing/mtl/TraceableRaiseAspectSpec.scala`

**Interfaces:**
- Consumes:
  - `trait RaiseAspect[Alg[_[_]], Dom[_], Cod[_], Err[_]] extends RaiseFunctorK[Alg, Err]` with
    `def intercept[F[_]](af: Alg[F])(fk: Aspect.Weave[F, Dom, Cod, *] ~> F, onRaise: OnRaise[F, Err])(implicit F: Apply[F]): Alg[F]`
    (`raise-aspect-core/src/main/scala/com/dwolla/tagless/mtl/RaiseAspect.scala:41-59`)
  - `trait RaiseFunctorK[Alg[_[_]], Err[_]] extends Serializable { def mapK[F[_], G[_]](af: Alg[F])(arrow: RaiseArrow[F, G, Err]): Alg[G] }`
    (same file, `:14-16`)
  - `RaiseAspect.observing[F[_], E, Err[_]](R: Raise[F, E], onRaise: OnRaise[F, Err])(implicit F: Apply[F], ev: Err[E]): Raise[F, E]`
    (same file, `:70-79`)
  - `final case class RaiseArrow[F[_], G[_], Err[_]](fk: F ~> G, pull: RaisePull[G, F, Err])`
  - `natchez.TraceableValue`
- Produces:
  - `trait TraceableRaiseAspect[Alg[_[_]]] extends RaiseAspect[Alg, TraceableValue, TraceableValue, TraceableValue]`
  - `TraceableRaiseAspect.apply[Alg[_[_]]](implicit ev: TraceableRaiseAspect[Alg]): TraceableRaiseAspect[Alg]`
  - `TraceableRaiseAspect.fromRaiseAspect[Alg[_[_]]](underlying: RaiseAspect[Alg, TraceableValue, TraceableValue, TraceableValue]): TraceableRaiseAspect[Alg]`
  - `HandWrittenBarRaiseAspect.instance: RaiseAspect[Bar, TraceableValue, TraceableValue, TraceableValue]`

- [ ] **Step 1: Write the failing test**

Create `natchez-tagless-mtl/src/test/scala-3/com/dwolla/tracing/mtl/HandWrittenBarRaiseAspect.scala`.
This is the differential reference for the whole milestone, so it is written by
hand and contains no macro:

```scala
package com.dwolla.tracing.mtl

import cats.Apply
import cats.mtl.Raise
import cats.tagless.aop.Aspect
import cats.~>
import com.dwolla.tagless.mtl.{OnRaise, RaiseArrow, RaiseAspect}
import natchez.TraceableValue

/** The differential reference M13 checks `derives TraceableRaiseAspect`
  * against. Deliberately hand-written and macro-free: if this and the derived
  * instance disagree, the disagreement is about the derivation, not about two
  * copies of the same macro output.
  *
  * The body follows the fused expansion specification in
  * `01-overview-design-and-laws.md` §3.4 — build an `Aspect.Weave` as data,
  * hand it to `fk`, and pass the caller's own `Raise` through decorated with
  * `RaiseAspect.observing`.
  */
object HandWrittenBarRaiseAspect {
  val instance: RaiseAspect[Bar, TraceableValue, TraceableValue, TraceableValue] =
    new RaiseAspect[Bar, TraceableValue, TraceableValue, TraceableValue] {
      def intercept[F[_]](af: Bar[F])(
          fk: Aspect.Weave[F, TraceableValue, TraceableValue, *] ~> F,
          onRaise: OnRaise[F, TraceableValue]
      )(implicit F: Apply[F]): Bar[F] =
        new Bar[F] {
          def bar(i: Int)(implicit R: Raise[F, BarError]): F[String] =
            fk(
              Aspect.Weave[F, TraceableValue, TraceableValue, String](
                "Bar",
                List(List(Aspect.Advice.byValue[TraceableValue, Int]("i", i))),
                Aspect.Advice[F, TraceableValue, String](
                  "bar",
                  af.bar(i)(RaiseAspect.observing(R, onRaise))
                )
              )
            )
        }

      def mapK[F[_], G[_]](af: Bar[F])(arrow: RaiseArrow[F, G, TraceableValue]): Bar[G] =
        new Bar[G] {
          def bar(i: Int)(implicit R: Raise[G, BarError]): G[String] =
            arrow.fk(af.bar(i)(arrow.pull(R)))
        }
    }
}
```

Create `natchez-tagless-mtl/src/test/scala-3/com/dwolla/tracing/mtl/TraceableRaiseAspectSpec.scala`:

```scala
package com.dwolla.tracing.mtl

import cats.data.EitherT
import cats.mtl.Raise
import cats.syntax.all._
import cats.tagless.aop.Aspect
import cats.{Eval, Functor, Id, ~>}
import com.dwolla.tagless.mtl.{OnRaise, RaiseArrow, RaisePull, RaiseAspect}
import munit.FunSuite
import natchez.TraceableValue

import scala.collection.mutable.ListBuffer

/** `TraceableRaiseAspect` adds no behaviour: it pins three type parameters so
  * that `derives` has a one-parameter type constructor to work with. These
  * tests say exactly that — the wrapper forwards both abstract members to the
  * instance it was built from, unchanged.
  */
class TraceableRaiseAspectSpec extends FunSuite {
  private type F[A] = Either[BarError, A]

  private val wide = HandWrittenBarRaiseAspect.instance
  private val narrow: TraceableRaiseAspect[Bar] = TraceableRaiseAspect.fromRaiseAspect(wide)

  private val raiseF: Raise[F, BarError] = Raise[F, BarError]

  /** Records what the interpreter is handed, then behaves like the forgetful
    * arrow — the same technique `RecordingFk` uses in `raise-aspect-core`.
    */
  private final class Recorder {
    val seen: ListBuffer[String] = ListBuffer.empty

    val fk: Aspect.Weave[F, TraceableValue, TraceableValue, *] ~> F =
      new (Aspect.Weave[F, TraceableValue, TraceableValue, *] ~> F) {
        def apply[A](w: Aspect.Weave[F, TraceableValue, TraceableValue, A]): F[A] = {
          val _ = seen += s"${w.algebraName}.${w.codomain.name}(${w.domain.flatten.map(_.name).mkString(",")})"
          w.codomain.target
        }
      }
  }

  test("intercept forwards to the underlying instance, weave for weave") {
    val wideRec = new Recorder
    val narrowRec = new Recorder

    val viaWide = wide.intercept(Bar[F])(wideRec.fk, OnRaise.noop[F, TraceableValue])
    val viaNarrow = narrow.intercept(Bar[F])(narrowRec.fk, OnRaise.noop[F, TraceableValue])

    assertEquals(viaNarrow.bar(5)(raiseF), viaWide.bar(5)(raiseF))
    assertEquals(viaNarrow.bar(-1)(raiseF), viaWide.bar(-1)(raiseF))
    assertEquals(narrowRec.seen.toList, wideRec.seen.toList)
    assertEquals(narrowRec.seen.toList, List("Bar.bar(i)", "Bar.bar(i)"))
  }

  test("intercept forwards the hook, so a raise is still observed exactly once") {
    val rendered = ListBuffer.empty[String]

    val hook: OnRaise[F, TraceableValue] = new OnRaise[F, TraceableValue] {
      def apply[E](e: E)(implicit ev: TraceableValue[E]): F[Unit] = {
        val _ = rendered += ev.toTraceValue(e).toString
        Right(())
      }
    }

    val rec = new Recorder
    val intercepted = narrow.intercept(Bar[F])(rec.fk, hook)

    assertEquals(intercepted.bar(5)(raiseF), "bar:5".asRight[BarError])
    assertEquals(rendered.toList, List.empty[String], "no raise, so no hook firing")

    assertEquals(intercepted.bar(-1)(raiseF), BarError.Negative(-1).asLeft[String])
    assertEquals(rendered.size, 1, "the hook must fire exactly once per raise")
    assert(rendered.head.contains("negative:-1"), s"rendered through TraceableValue, got ${rendered.head}")
  }

  test("mapK forwards to the underlying instance") {
    type G[A] = EitherT[Eval, BarError, A]

    val arrow: RaiseArrow[F, G, TraceableValue] =
      RaiseArrow(
        new (F ~> G) { def apply[A](fa: F[A]): G[A] = EitherT(Eval.now(fa)) },
        new RaisePull[G, F, TraceableValue] {
          def apply[E](rg: Raise[G, E])(implicit ev: TraceableValue[E]): Raise[F, E] =
            new Raise[F, E] {
              val functor: Functor[F] = Functor[F]
              def raise[E2 <: E, A](e: E2): F[A] = rg.raise[E2, A](e).value.value
            }
        }
      )

    val raiseG: Raise[G, BarError] = Raise[G, BarError]

    assertEquals(
      narrow.mapK(Bar[F])(arrow).bar(5)(raiseG).value.value,
      wide.mapK(Bar[F])(arrow).bar(5)(raiseG).value.value
    )
    assertEquals(
      narrow.mapK(Bar[F])(arrow).bar(-1)(raiseG).value.value,
      BarError.Negative(-1).asLeft[String]
    )
  }

  test("the narrow instance is accepted wherever the wide one is") {
    val asWide: RaiseAspect[Bar, TraceableValue, TraceableValue, TraceableValue] = narrow
    assert(asWide ne null)
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
sbt "++3.3.8 natchezTaglessMtlJVM/testOnly com.dwolla.tracing.mtl.TraceableRaiseAspectSpec"
```

Expected: compile failure — `Not found: type TraceableRaiseAspect`.

- [ ] **Step 3: Create the trait and its companion**

Create `natchez-tagless-mtl/src/main/scala-3/com/dwolla/tracing/mtl/TraceableRaiseAspect.scala`:

```scala
package com.dwolla.tracing.mtl

import cats.Apply
import cats.tagless.aop.Aspect
import cats.~>
import com.dwolla.tagless.mtl.{OnRaise, RaiseArrow, RaiseAspect}
import natchez.TraceableValue

/** A [[com.dwolla.tagless.mtl.RaiseAspect]] with all three of `Dom`, `Cod` and
  * `Err` pinned to `natchez.TraceableValue` — the shape every natchez user
  * wants, and the only one `traceWithInputsAndOutputs` can use.
  *
  * It exists so Scala 3 can derive it with a `derives` clause. `derives` needs
  * a ''one-parameter'' type constructor whose companion carries `derived`;
  * `RaiseAspect` takes four parameters, and a type alias that pinned three of
  * them would have no companion to put `derived` on. A trait fixes both.
  *
  * The relationship is one-way and worth knowing: a `TraceableRaiseAspect[Alg]`
  * ''is'' a `RaiseAspect[Alg, TraceableValue, TraceableValue, TraceableValue]`,
  * so it satisfies `WeaveInterpreter` and the tracing syntax unchanged; the
  * converse is false, and [[TraceableRaiseAspect.fromRaiseAspect]] is how you
  * cross the other way.
  *
  * Scala 3 only — `derives` does not exist on Scala 2, and this type has no
  * other purpose. A cross-built algebra therefore cannot use `derives` in its
  * shared sources; that is inherent to the feature, not to this type.
  */
trait TraceableRaiseAspect[Alg[_[_]]]
    extends RaiseAspect[Alg, TraceableValue, TraceableValue, TraceableValue]

object TraceableRaiseAspect:
  def apply[Alg[_[_]]](implicit ev: TraceableRaiseAspect[Alg]): TraceableRaiseAspect[Alg] = ev

  /** Narrow an existing `RaiseAspect` at the natchez shape.
    *
    * Deliberately ''not'' `inline`, even though its only in-library caller is
    * the inline `derived`: an anonymous class written directly in an inline
    * method body is duplicated at every call site, and the compiler warns
    * accordingly. Hoisting it into a `private` class does not work either,
    * because the inline body is spliced at the call site and could not see it.
    * A plain method compiles the anonymous class exactly once, here.
    *
    * It is public because it is independently useful: it is the only way to
    * turn a hand-written or Scala 2-derived instance into the narrow type.
    */
  def fromRaiseAspect[Alg[_[_]]](
      underlying: RaiseAspect[Alg, TraceableValue, TraceableValue, TraceableValue]
  ): TraceableRaiseAspect[Alg] =
    new TraceableRaiseAspect[Alg]:
      def intercept[F[_]](af: Alg[F])(
          fk: Aspect.Weave[F, TraceableValue, TraceableValue, *] ~> F,
          onRaise: OnRaise[F, TraceableValue]
      )(implicit F: Apply[F]): Alg[F] =
        underlying.intercept(af)(fk, onRaise)

      def mapK[F[_], G[_]](af: Alg[F])(arrow: RaiseArrow[F, G, TraceableValue]): Alg[G] =
        underlying.mapK(af)(arrow)
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
sbt "++3.3.8 natchezTaglessMtlJVM/testOnly com.dwolla.tracing.mtl.TraceableRaiseAspectSpec"
```

Expected: PASS, 4 tests.

- [ ] **Step 5: Confirm the other two axes are untouched**

```bash
sbt "++2.13.18 natchezTaglessMtlJVM/test" "++2.12.21 natchezTaglessMtlJVM/test"
```

Expected: PASS with **exactly the test counts `main` produces** — the new
sources are Scala 3 only. Record both counts; a change means something leaked
into a shared source directory.

- [ ] **Step 6: Commit**

```bash
git add natchez-tagless-mtl/src/main/scala-3/ natchez-tagless-mtl/src/test/scala-3/
git commit -m "feat: add TraceableRaiseAspect, the natchez-shaped RaiseAspect subtype

A one-parameter type constructor is what a Scala 3 derives clause needs, and
RaiseAspect takes four. Pinning Dom, Cod and Err to TraceableValue in a trait
gives derives something to work with in the next commit, and gives everyone
else a narrower name for the shape the tracing syntax already uses."
```

---

## Task 2: `derived`, and the `derives` clause

One inline method, and the fixture that proves it does something. Split from
Task 1 because everything `@experimental` and macro-shaped is here: if the
`derives` clause misbehaves, Task 1 having passed localises it immediately.

**Files:**
- Modify: `natchez-tagless-mtl/src/main/scala-3/com/dwolla/tracing/mtl/TraceableRaiseAspect.scala`
- Create: `natchez-tagless-mtl/src/test/scala-3/com/dwolla/tracing/mtl/DerivesBarFixture.scala`
- Modify: `natchez-tagless-mtl/src/test/scala-3/com/dwolla/tracing/mtl/TraceableRaiseAspectSpec.scala`

**Interfaces:**
- Consumes:
  - Task 1's `TraceableRaiseAspect.fromRaiseAspect`
  - `DeriveRaise.aspect[Alg[_[_]], Dom[_], Cod[_], Err[_]]: RaiseAspect[Alg, Dom, Cod, Err]`, `@experimental inline`
    (`raise-aspect-macros/src/main/scala-3/com/dwolla/tagless/mtl/DeriveRaise.scala`)
  - `BarError`, with `implicit val traceableValueBarError: TraceableValue[BarError]`
    (`natchez-tagless-mtl/src/test/scala/com/dwolla/tracing/mtl/BarFixture.scala:20-25`)
- Produces:
  - `@experimental inline def TraceableRaiseAspect.derived[Alg[_[_]]]: TraceableRaiseAspect[Alg]`
  - `@experimental trait DerivesBar[F[_]] derives TraceableRaiseAspect { def bar(i: Int)(using R: Raise[F, BarError]): F[String] }`
  - `@experimental object DerivesBar { def apply[F[_]: Applicative]: DerivesBar[F] }`

- [ ] **Step 1: Write the failing test**

Create `natchez-tagless-mtl/src/test/scala-3/com/dwolla/tracing/mtl/DerivesBarFixture.scala`:

```scala
package com.dwolla.tracing.mtl

import cats.Applicative
import cats.mtl.Raise
import cats.syntax.all.*

import scala.annotation.experimental

/** `Bar`'s twin, declared the way M13 exists to make possible.
  *
  * Structurally identical to `Bar` (`BarFixture.scala`) — same parameter, same
  * error type, same implementation — so the two can be compared directly and so
  * the expected span history differs from the existing suites' only in the
  * algebra name. It cannot simply reuse `Bar`: `Bar` lives in
  * `src/test/scala`, which is compiled on 2.12 and 2.13 as well, where a
  * `derives` clause is a syntax error.
  *
  * `@experimental` is required, and it is required on the algebra itself (or an
  * enclosing scope) rather than merely somewhere in the file:
  * `TraceableRaiseAspect.derived` is `@experimental` because
  * `DeriveRaise.aspect` is, and on the 3.3.x LTS line there is no
  * `-experimental` flag to opt out with. The pre-M13 spelling needed the same
  * annotation on the companion's `implicit val`, so nothing is lost here.
  */
@experimental
trait DerivesBar[F[_]] derives TraceableRaiseAspect:
  def bar(i: Int)(using R: Raise[F, BarError]): F[String]

@experimental
object DerivesBar:
  def apply[F[_]: Applicative]: DerivesBar[F] = new DerivesBar[F]:
    def bar(i: Int)(using R: Raise[F, BarError]): F[String] =
      if (i < 0) R.raise(BarError.Negative(i)) else s"bar:$i".pure[F]
```

Append to `TraceableRaiseAspectSpec.scala` — note the class must now be
`@experimental`, so add `import scala.annotation.experimental` and the
annotation to the class declaration:

```scala
  test("the derives clause produces an instance, and it is the narrow type") {
    val derived: TraceableRaiseAspect[DerivesBar] = summon[TraceableRaiseAspect[DerivesBar]]
    assert(derived ne null)
  }

  test("the derived instance agrees with a hand-written one on intercept") {
    val derivedRec = new Recorder
    val handRec = new Recorder

    // Same algebra shape, so the hand-written Bar reference is a valid oracle
    // for DerivesBar once the algebra name is accounted for.
    val derivedAlg =
      summon[TraceableRaiseAspect[DerivesBar]]
        .intercept(DerivesBar[F])(derivedRec.fkFor[DerivesBar], OnRaise.noop[F, TraceableValue])
    val handAlg =
      HandWrittenBarRaiseAspect.instance
        .intercept(Bar[F])(handRec.fk, OnRaise.noop[F, TraceableValue])

    assertEquals(derivedAlg.bar(5)(using raiseF), handAlg.bar(5)(raiseF))
    assertEquals(derivedAlg.bar(-1)(using raiseF), handAlg.bar(-1)(raiseF))

    // identical but for the algebra name, which is the only thing that differs
    assertEquals(
      derivedRec.seen.toList,
      handRec.seen.toList.map(_.replace("Bar.bar", "DerivesBar.bar"))
    )
    assertEquals(derivedRec.seen.toList, List("DerivesBar.bar(i)", "DerivesBar.bar(i)"))
  }
```

`Recorder` is currently monomorphic in the algebra it observes only through
`F`; it already works for any algebra at `F`, so rename its `fk` usage
accordingly rather than adding `fkFor` — i.e. keep the single `fk` member and
use it for both. (Written as `fkFor` above only to make the two uses visually
distinct; collapse it to `fk` when implementing, and delete this note.)

- [ ] **Step 2: Run to verify it fails**

```bash
sbt "++3.3.8 natchezTaglessMtlJVM/testOnly com.dwolla.tracing.mtl.TraceableRaiseAspectSpec"
```

Expected: compile failure — `value derived is not a member of object TraceableRaiseAspect`,
reported at `DerivesBarFixture.scala`'s `derives` clause.

- [ ] **Step 3: Add `derived`**

Append to `TraceableRaiseAspect.scala`'s companion, and add
`import com.dwolla.tagless.mtl.DeriveRaise` and
`import scala.annotation.experimental` to the file:

```scala
  /** What a `derives TraceableRaiseAspect` clause calls.
    *
    * `@experimental` because `DeriveRaise.aspect` is: the derivation
    * synthesizes a class with `quotes.reflect`'s `Symbol.newClass`, which is
    * experimental on the 3.3.x LTS line. The annotation is therefore required
    * on the algebra carrying the `derives` clause, or on a scope enclosing it —
    * a sibling `@experimental` definition in the same file is not enough. The
    * hand-written spelling this replaces needed the same annotation on its
    * `implicit val`, so this is a move, not a new tax.
    *
    * {{{
    *   import cats.Applicative
    *   import cats.mtl.Raise
    *   import cats.syntax.all.*
    *   import com.dwolla.tracing.mtl.TraceableRaiseAspect
    *   import natchez.{TraceValue, TraceableValue}
    *
    *   import scala.annotation.experimental
    *
    *   sealed trait ValidationError extends Product with Serializable
    *   final case class TooSmall(i: Int) extends ValidationError
    *
    *   object ValidationError {
    *     implicit val traceableValue: TraceableValue[ValidationError] =
    *       new TraceableValue[ValidationError] {
    *         def toTraceValue(a: ValidationError): TraceValue = a match {
    *           case TooSmall(i) => TraceValue.StringValue("too small: " + i.toString)
    *         }
    *       }
    *   }
    *
    *   @experimental
    *   trait Validator[F[_]] derives TraceableRaiseAspect {
    *     def validate(i: Int)(using R: Raise[F, ValidationError]): F[String]
    *   }
    *
    *   @experimental
    *   object Validator {
    *     def apply[F[_]: Applicative]: Validator[F] = new Validator[F] {
    *       def validate(i: Int)(using R: Raise[F, ValidationError]): F[String] =
    *         if (i < 0) R.raise(TooSmall(i)) else ("ok:" + i.toString).pure[F]
    *     }
    *   }
    * }}}
    */
  @experimental
  inline def derived[Alg[_[_]]]: TraceableRaiseAspect[Alg] =
    fromRaiseAspect(DeriveRaise.aspect[Alg, TraceableValue, TraceableValue, TraceableValue])
```

- [ ] **Step 4: Run to verify it passes**

```bash
sbt "++3.3.8 natchezTaglessMtlJVM/testOnly com.dwolla.tracing.mtl.TraceableRaiseAspectSpec"
```

Expected: PASS, 6 tests.

If the failure is instead
`Not found: given natchez.TraceableValue[…]` reported at the `derives` clause,
that is the derivation genuinely running and reporting a missing instance — add
the instance rather than working around it. If the failure is
`method derived is marked @experimental`, the missing annotation is on the
algebra or on the summoning test class, not on `derived`.

- [ ] **Step 5: Check the doctest actually ran**

`natchez-tagless-mtl` has `doctestSettings` (`build.sbt:174`), so the `{{{ }}}`
block above is compiled and run as a generated test. This is the first
`derives` clause in a doctest in this repo, so confirm it rather than assume:

```bash
sbt "++3.3.8 natchezTaglessMtlJVM/test" 2>&1 | grep -i "doctest"
```

Expected: a generated doctest suite for `TraceableRaiseAspect.scala` appears and
passes. If the doctest harness cannot handle `derives` or `@experimental` in a
snippet, **do not delete the example** — reduce it to the smallest form that
does run and record what failed in the task report; a worked example that is
only prose is exactly what `Scala3UsageNote` exists to prevent.

- [ ] **Step 6: Verify zero warnings under forced fatal warnings**

```bash
sbt "++3.3.8 set natchezTaglessMtl.jvm/scalacOptions += \"-Xfatal-warnings\"" "natchezTaglessMtlJVM/test"
```

Expected: PASS. In particular there must be **no**
`New anonymous class definition will be duplicated at each inline site` — that
warning means the anonymous class ended up inside `derived` rather than inside
`fromRaiseAspect`.

- [ ] **Step 7: Commit**

```bash
git add natchez-tagless-mtl/src/main/scala-3/ natchez-tagless-mtl/src/test/scala-3/
git commit -m "feat: derive TraceableRaiseAspect with a Scala 3 derives clause

TraceableRaiseAspect.derived wraps DeriveRaise.aspect at the natchez shape, so
an algebra can say 'derives TraceableRaiseAspect' instead of declaring a
companion instance with four type arguments. @experimental moves from the
implicit val to the algebra; it does not appear or disappear."
```

---

## Task 3: the ergonomics gate — end to end, and both subsumption directions

**This is the task the milestone is judged on.** Tasks 1 and 2 prove the type
exists and forwards; neither proves a user gets anything. A `derives`-declared
algebra has to actually trace, through the **unedited** existing syntax, against
the real natchez backend.

**Files:**
- Create: `natchez-tagless-mtl/src/test/scala-3/com/dwolla/tracing/mtl/DerivesBarTracingSpec.scala`
- Modify: `natchez-tagless-mtl/src/test/scala-3/com/dwolla/tracing/mtl/TraceableRaiseAspectSpec.scala`

**Interfaces:**
- Consumes: Tasks 1 and 2; `InMemorySuite` (from `core % "test->test"`,
  `core/shared/src/test/scala/com/dwolla/tracing/InMemorySuite.scala`) and its
  `traceTest(name, tt: TraceTest)` helper;
  `RaiseTraceWeaveOps#traceWithInputsAndOutputs` (unchanged).
- Produces: no new API.

- [ ] **Step 1: Write the failing test**

Create `natchez-tagless-mtl/src/test/scala-3/com/dwolla/tracing/mtl/DerivesBarTracingSpec.scala`:

```scala
package com.dwolla.tracing.mtl

import cats.data.Kleisli
import cats.effect.MonadCancelThrow
import cats.mtl.{Handle, Local}
import cats.syntax.all.*
import com.dwolla.tracing.InMemorySuite
import com.dwolla.tracing.mtl.syntax.*
import natchez.InMemory.Lineage.Root
import natchez.InMemory.NatchezCommand.*
import natchez.InMemory.{Lineage, NatchezCommand}
import natchez.TraceValue.{NumberValue, StringValue}
import natchez.*

import scala.annotation.experimental

/** The point of M13, stated as a test: an algebra that says
  * `derives TraceableRaiseAspect` and nothing else traces exactly as one with a
  * hand-declared instance does.
  *
  * Nothing here imports `DeriveRaise`, declares an instance, or mentions
  * `RaiseAspect`. The only difference from `RaiseTraceIntegrationSuite` is how
  * the instance came to exist — and the expected histories below are that
  * suite's, with `Bar` replaced by `DerivesBar`.
  */
@experimental
class DerivesBarTracingSpec extends InMemorySuite {
  traceTest(
    "an algebra deriving TraceableRaiseAspect captures span, input, and output",
    new TraceTest {
      def program[F[_]: MonadCancelThrow](entryPoint: EntryPoint[F])(implicit L: Local[F, Span[F]]): F[Unit] = {
        import natchez.mtl.*

        val traced: DerivesBar[F] = DerivesBar[F].traceWithInputsAndOutputs

        val effect: F[Unit] =
          Handle
            .allowF[F, BarError] { implicit h => traced.bar(5) }
            .rescue(_ => "unexpected raise".pure[F])
            .void

        entryPoint.root("test").use(L.scope(effect))
      }

      override def expectedHistory: List[(Lineage, NatchezCommand)] = List(
        Root -> CreateRootSpan("test", Kernel(Map.empty), Span.Options.Defaults),
        Root("test") -> CreateSpan("DerivesBar.bar", None, Span.Options.Defaults),
        Root("test") / "DerivesBar.bar" -> Put(List("DerivesBar.bar.i" -> NumberValue(5))),
        Root("test") / "DerivesBar.bar" -> Put(
          List("DerivesBar.bar.returnValue" -> StringValue("bar:5"))
        ),
        Root("test") -> ReleaseSpan("DerivesBar.bar"),
        Root -> ReleaseRootSpan("test")
      )
    }
  )

  /** The raise path, which is where the hook and the `Err` evidence matter.
    * Asserted structurally around the `AttachError` entry for the same reason
    * `RaiseTraceIntegrationSuite` does it that way: cats-mtl's `Submarine` is
    * `private[mtl]` and carries an unpredictable identity marker.
    */
  test("a raise through a derived instance still records the typed error") {
    import natchez.mtl.*

    InMemory.EntryPoint
      .create[Kleisli[cats.effect.IO, Span[cats.effect.IO], *]]
      .flatMap { ep =>
        type F[A] = Kleisli[cats.effect.IO, Span[cats.effect.IO], A]
        val traced: DerivesBar[F] = DerivesBar[F].traceWithInputsAndOutputs
        val effect: F[Unit] =
          Handle.allowF[F, BarError] { implicit h => traced.bar(-1) }.rescue(_ => "rescued".pure[F]).void

        ep.root("test").use(localSpan[cats.effect.IO].scope(effect)) *> ep.ref.get.map(_.toList)
      }
      .run(Span.noop[cats.effect.IO])
      .map { history =>
        assertEquals(history.size, 7)
        assertEquals(
          history(3),
          Root("test") / "DerivesBar.bar" -> Put(
            List(
              RaiseRecorder.ErrorTypeKey -> StringValue(classOf[BarError.Negative].getName),
              RaiseRecorder.ErrorValueKey -> StringValue("negative:-1")
            )
          )
        )
      }
  }
}
```

Note on the second test: the `Local`/`scope` wiring above must match whatever
`RaiseTraceIntegrationSuite`'s `raisingProgram` does at
`natchez-tagless-mtl/src/test/scala/com/dwolla/tracing/mtl/RaiseTraceIntegrationSuite.scala:66-80`.
Copy that shape rather than inventing one; if it does not compile as written,
the suite's version is the specification.

Append to `TraceableRaiseAspectSpec.scala`, to pin the *direction* of the
subtyping so a later refactor cannot quietly invert it:

```scala
  test("a wide RaiseAspect does not satisfy a demand for the narrow type") {
    assert(
      compileErrors(
        "summon[TraceableRaiseAspect[Bar]](using HandWrittenBarRaiseAspect.instance)"
      ).nonEmpty,
      "RaiseAspect[Bar, TraceableValue, TraceableValue, TraceableValue] must not be a TraceableRaiseAspect[Bar]"
    )
  }

  test("...and fromRaiseAspect is how you get one anyway") {
    val fixed: TraceableRaiseAspect[Bar] =
      TraceableRaiseAspect.fromRaiseAspect(HandWrittenBarRaiseAspect.instance)
    assert(fixed ne null)
  }
```

- [ ] **Step 2: Run to verify it fails**

```bash
sbt "++3.3.8 natchezTaglessMtlJVM/testOnly com.dwolla.tracing.mtl.DerivesBarTracingSpec"
```

Expected: red. Before Task 2 this would not compile at all; here the expected
failure is whatever the wiring gets wrong first. **The point of this step is to
see the test fail for a reason you understand** — if it passes on the first run,
check that the spec is actually being collected (`testOnly` reports 0 tests when
the class name is wrong) before believing it.

- [ ] **Step 3: Make it pass without touching any main source**

There is no production change in this task. If making it pass requires editing
`RaiseTraceWeaveOps`, `WeaveInterpreter`, `RaiseRecorder` or
`TraceableRaiseAspect` itself, **stop and report** — the milestone's premise is
that the narrow type satisfies the existing demands unchanged.

Legitimate fixes here are: the `Local` wiring, the `import natchez.mtl.*`
placement, and the `@experimental` annotations.

- [ ] **Step 4: Run the whole module on Scala 3**

```bash
sbt "++3.3.8 natchezTaglessMtlJVM/test"
```

Expected: PASS. Record the count and the delta from `main`.

- [ ] **Step 5: Confirm the derived and hand-declared paths produce the same history**

The two expected histories now in the tree —
`RaiseTraceIntegrationSuite`'s and `DerivesBarTracingSpec`'s — must differ
**only** by the algebra name. Check it mechanically rather than by eye:

```bash
diff <(sed -n '55,62p' natchez-tagless-mtl/src/test/scala/com/dwolla/tracing/mtl/RaiseTraceIntegrationSuite.scala | sed 's/Bar\./DerivesBar./g; s/"Bar/"DerivesBar/g') \
     <(grep -n "" natchez-tagless-mtl/src/test/scala-3/com/dwolla/tracing/mtl/DerivesBarTracingSpec.scala | sed -n '/expectedHistory/,/^ *)/p' | cut -d: -f2-)
```

(adjust the line ranges to what is actually there). Any difference beyond the
name is a finding: it means the derived instance weaves differently, and the
whole milestone rests on it not doing so.

- [ ] **Step 6: Cross-build and JS linker**

```bash
sbt "+natchezTaglessMtlJVM/test" "+natchezTaglessMtlJS/Test/scalaJSLinkerResult"
```

Expected: green on 2.12.21, 2.13.18 and 3.3.8, with the 2.12/2.13 counts
**unchanged from `main`**; all JS linkers green. (Test execution on JS is not
available locally — no Node — consistent with every prior milestone.)

- [ ] **Step 7: Commit**

```bash
git add natchez-tagless-mtl/src/test/scala-3/
git commit -m "test: prove derives TraceableRaiseAspect traces end to end

An algebra whose only instance declaration is a derives clause produces, through
the unedited tracing syntax and the real natchez InMemory backend, the same span
history as one with a hand-declared instance — including the typed-error Put on
the raise path. Both subtyping directions are pinned, the negative one with
compileErrors."
```

---

## Task 4: documentation

**No production code.** The overview's milestone map does not mention M13, and
the module's package scaladoc teaches the old spelling as the only spelling.

**Files:**
- Modify: `docs/plans/raise-aspect/01-overview-design-and-laws.md` §5
- Modify: `docs/plans/raise-aspect/24-milestone-M13-traceable-raise-aspect.md` (status only)
- Modify: `natchez-tagless-mtl/src/main/scala/com/dwolla/tracing/mtl/package.scala` (a pointer, not a rewrite)

**Interfaces:**
- Consumes: Tasks 1–3. No new API.

- [ ] **Step 1: Point the package scaladoc at the new spelling**

`natchez-tagless-mtl/src/main/scala/com/dwolla/tracing/mtl/package.scala` is
shared across all three Scala versions, so it must not *teach* `derives` as the
only way. Add a short paragraph, not a rewrite:

```
On Scala 3, an algebra can declare its instance with a `derives` clause instead
of a companion-object `implicit val` — see
`com.dwolla.tracing.mtl.TraceableRaiseAspect`, which pins `Dom`, `Cod` and `Err`
to `TraceableValue` so that `derives` has a one-parameter type constructor to
work with. The `@experimental` requirement is unchanged, and moves from the
`implicit val` to the algebra. There is no Scala 2 equivalent: `derives` does
not exist there, so a cross-built algebra keeps the declaration below.
```

Check whether the surrounding text is a doctest before editing — if the
paragraph lands inside a `{{{ }}}` block it will be compiled.

- [ ] **Step 2: Reconcile the overview's milestone map with what actually happened**

`01-overview-design-and-laws.md` §5 **already carries** the
"Fourth round (2026-08-02)" block naming M13, M14 and M15 — it was written when
these three were planned, not as part of implementing them. Do not re-add it.

What this step does is check it against reality and correct anything the
implementation falsified. In particular the block asserts:

- that the new type is a subtype of the one every existing demand is phrased in,
  so `WeaveInterpreter` and `RaiseTraceWeaveOps` are untouched — confirm with
  `git diff` that they are;
- that `@experimental` "moves from the companion's `implicit val` to the algebra
  rather than appearing or disappearing" — confirm this matched what Task 2
  found;
- that both new types are Scala 3 only.

If any of those is now wrong, fix the overview and say so in the task report.
Also confirm the block's closing sentence about M16 survives intact.

- [ ] **Step 3: Update M13's status section**

In `24-milestone-M13-traceable-raise-aspect.md`, replace "Planned, not started"
with the outcome, following the shape M6, M7 and M12 use: what landed task by
task, what diverged from this plan and why, the verification actually run with
counts, and anything found along the way a later milestone needs. Answer Q1
explicitly — either "Brian chose (a), no `Trivial` sibling" or record what he
chose.

- [ ] **Step 4: Full verification**

```bash
sbt "+natchezTaglessMtlJVM/test" "+natchezTaglessMtlJS/Test/scalaJSLinkerResult" "+natchezTaglessMtlJVM/doc"
```

Expected: all green. `doc` matters here — the new scaladoc has a `{{{ }}}` block
and cross-references, and a broken link fails there rather than in `test`.

- [ ] **Step 5: Commit**

```bash
git add docs/plans/raise-aspect/ natchez-tagless-mtl/src/main/scala/
git commit -m "docs: record M13 and point the mtl package at the derives spelling"
```

---

## Acceptance criteria

- [ ] `trait TraceableRaiseAspect[Alg[_[_]]] extends RaiseAspect[Alg, TraceableValue, TraceableValue, TraceableValue]`
      exists in `com.dwolla.tracing.mtl`, under `natchez-tagless-mtl/src/main/scala-3`.
- [ ] `@experimental trait DerivesBar[F[_]] derives TraceableRaiseAspect` compiles,
      and `DerivesBar[F].traceWithInputsAndOutputs` produces
      `RaiseTraceIntegrationSuite`'s span history with `Bar` replaced by
      `DerivesBar` and **nothing else changed**, on both the success and the
      raise paths.
- [ ] The derived instance and `HandWrittenBarRaiseAspect.instance` agree on
      `intercept` (results and weave arrival order) and on `mapK`.
- [ ] `compileErrors` confirms a wide `RaiseAspect[…]` is not a
      `TraceableRaiseAspect[…]`, and `fromRaiseAspect` converts it.
- [ ] `git diff --stat` shows **no** change to `RaiseAspect.scala`,
      `WeaveInterpreter.scala`, `RaiseTraceWeaveOps.scala`, `RaiseRecorder.scala`,
      `build.sbt`, or any expected span history.
- [ ] `+natchezTaglessMtlJVM/test` green on all three versions, with the 2.12
      and 2.13 counts **identical to `main`**; JS linker green; `doc` succeeds.
- [ ] Zero new warnings with `-Xfatal-warnings` forced — in particular no
      "anonymous class definition will be duplicated at each inline site".
- [ ] The `{{{ }}}` example on `derived` is confirmed to have run as a doctest,
      or its reduction is recorded with the reason.

## Ground rules reminder

- Additive only. Editing an existing main source signature falsifies the
  milestone; stop and report instead.
- Do not do M14's or M15's work here, and do not touch M16 (otel4s).
- Q1 (a `Trivial`-codomain sibling) is Brian's call, not a task's. If you find
  yourself wanting one mid-implementation, record it and carry on with (a).
- Never use `--no-verify` or any other hook-bypass flag.
