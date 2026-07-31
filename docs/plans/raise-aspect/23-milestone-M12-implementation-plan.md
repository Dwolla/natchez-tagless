# Milestone M12 — implementation plan: fused derivation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `RaiseAspect`'s two operations (`weave` + `mapK`) with one
carrier-preserving `intercept`, so the caller's `Raise[F, E]` is handed
straight to the underlying implementation and no capability ever crosses a
carrier boundary. That deletes `Synthetic` and the unlawful synthesized
`Functor[Weave[F, Dom, Cod, *]]` it exists to feed.

**Architecture:** `Aspect.Weave` stops being an effect type and becomes plain
data handed to the interpreter's `fk`. The observation hook is preserved by
decorating the caller's capability *at the same carrier*
(`functor = R.functor`, `raise = onRaise(e) *> R.raise(e)`), which is sound by
construction. `Weave`'s shape does not change, so
`TraceWeaveCapturingInputs`/`TraceWeaveCapturingInputsAndOutputs` keep their
exact types and `RaiseTraceWeaveOps`' public signatures are untouched.
`RaiseFunctorK`, `RaiseArrow` and `RaisePull` survive unchanged: `mapK` still
does real transport, and one derivation emits both operations.

**Tech Stack:** Scala 2.12 / 2.13 / 3.3 LTS cross-build, cats, cats-mtl,
cats-tagless, natchez, MUnit + ScalaCheck + discipline, sbt.

Read `22-milestone-M12-fused-derivation.md` first — it is the milestone
document this plan implements, and its Decisions section must be ratified
before Task 1 starts. Read `01-overview-design-and-laws.md` §3.4 for the
expansion specification the reference fixture follows, and §4 for the law
numbering used throughout.

## Global Constraints

- Cross-compiles on **2.12.21, 2.13.18 and 3.3.8**. Every task's final
  verification runs under `+` unless a step says otherwise.
- **No new dependencies** in any module. `raise-aspect-core` must not gain a
  natchez dependency.
- **Zero new compiler warnings** — verify locally with `-Xfatal-warnings`.
  **CI does not enforce this**: `sbt-typelevel-settings` defaults
  `tlFatalWarnings` to `false` and nothing in this repo overrides it
  (corrected 2026-07-31; see `22-milestone-M12-fused-derivation.md`'s Status
  section). Use the repo's existing `val _ = …` idiom for discarded
  non-`Unit` statements.
- **No compatibility shims, deprecated overloads, or dual code paths** —
  decision D5, following M10's D7. Every type here is unpublished
  (`mimaPreviousArtifacts := Set.empty` on all four modules). The
  two-operation form is deleted, not deprecated.
- **`ExpectedWeaves.scala`'s `expected` value must not be edited.** Fusion
  changes which code compiles and who holds the weave, not what a woven call
  produces. The `rendered` helper next to it does change. If a task appears to
  require editing `expected`, stop and report — something is wrong.
- **`Raise`-only recognition is retained** (decision D3). Do not admit any
  other cats-mtl capability and do not remove a rejection diagnostic. If a
  task starts drifting toward M8, stop.
- There is no `scripts/check` in this repo. Canonical verification is the
  per-module sbt invocations named in each task.
- **Never use `--no-verify`** or any other hook-bypass flag.

**Naming, fixed by decision D1 and load-bearing for every code block below.**
The macro spike's sources named the type `RaiseInstrument`, its helper
`RaiseInstrument.observing`, and its entry point `DeriveRaise.intercept`,
because they had to coexist with the real `RaiseAspect` in one tree. This
milestone keeps the existing names: the type stays **`RaiseAspect`**, the entry
point stays **`DeriveRaise.aspect`**, the helper is **`RaiseAspect.observing`**,
and only the *method* is new — **`intercept`**. Every code block taken from
the spike below has exactly those identifiers substituted and nothing else.

**The core change is atomic.** Task 1 changes a type class every other module
uses. Between Tasks 1 and 7 the downstream modules do **not** compile — that is
expected and is not evidence of a mistake. Each task's deliverable is *its own
module* green; the dependency order is `raise-aspect-core` → `raise-aspect-laws`
→ the two macro axes → `natchez-tagless-mtl`, and the plan follows it. Do not
try to keep every module green at every step; there is no such sequence.

---

## File Structure

**`raise-aspect-core` main:**

- `RaiseAspect.scala` — `weave` becomes `intercept`; gains a companion object
  holding `observing`. `RaiseFunctorK` unchanged.
- `WeaveInterpreter.scala` — `fromRaiseAspect` becomes a one-liner and drops
  its `Synthetic[Cod]` parameter. `fromAspect` unchanged.
- `WeaveArrows.scala` — reduced to `codomainTarget`.
- `Synthetic.scala` — **deleted.**
- `RaiseArrow.scala`, `OnRaise.scala` — **unchanged.**

**`raise-aspect-core` test:**

- `RecordingFk.scala` — **new.** The instrumented interpreter side; how every
  downstream test observes weaves now that no `Alg[Weave[…]]` value exists.
- `CarrierArrows.scala` — **new.** A genuine non-identity `RaiseArrow`, to
  replace `eraseWeave` as L1/L2's non-trivial instantiation.
- `ObservingCapabilitySpec.scala` — **new.** The hook coverage rescued from
  `WeaveArrowsOnRaiseSpec` before that file is deleted.
- `TestAlgReference.scala` — the differential oracle, transposed to the fused
  shape. Still the specification both macros are checked against.
- `TestAlgReferenceSpec.scala`, `TestAlgReferencePropertySpec.scala`,
  `EvidenceThreadingSpec.scala`, `WeaveInterpreterFixtures.scala`,
  `WeaveInterpreterSpec.scala`, `WeaveArrowsSpec.scala` — transposed.
- `WeaveArrowsOnRaiseSpec.scala` — **deleted** (Task 2, after its coverage is
  rescued in Task 1).

**`raise-aspect-laws`:** L3 → L3′, L5/L6a–d/L7 deleted, L8 and L9 re-anchored
on a recording `fk`, L1/L2 given a real arrow.

**`raise-aspect-macros`:** `raiseWeave` → `raiseInstrument` (Scala 2, with
`substituteCapabilities` and `Method#transformedParamLists` deleted),
`deriveWeave` → `deriveInstrument` (Scala 3). Diagnostics unchanged.

**`natchez-tagless-mtl`:** `syntheticTraceableValue` and its caveat delete;
public signatures and span histories unchanged.

---

## Task 1: `RaiseAspect#intercept` and `RaiseAspect.observing`

The atomic change. `RaiseAspect`, `WeaveInterpreter`, the reference oracle and
every core test that mentions `weave` move together — each references the next,
so there is no smaller compiling step. The task's deliverable is
`raise-aspect-core` green on all three Scala versions.

`Synthetic` and the rest of `WeaveArrows` are deliberately left in place and
unused; Task 2 deletes them. Splitting it that way means no test disappears in
the same commit that deletes the code it covered.

**Files:**
- Modify: `raise-aspect-core/src/main/scala/com/dwolla/tagless/mtl/RaiseAspect.scala`
- Modify: `raise-aspect-core/src/main/scala/com/dwolla/tagless/mtl/WeaveInterpreter.scala`
- Create: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/ObservingCapabilitySpec.scala`
- Create: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/RecordingFk.scala`
- Modify: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/TestAlgReference.scala`
- Modify: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/TestAlgReferenceSpec.scala`
- Modify: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/TestAlgReferencePropertySpec.scala`
- Modify: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/EvidenceThreadingSpec.scala`
- Modify: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/WeaveInterpreterFixtures.scala`
- Modify: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/WeaveInterpreterSpec.scala`

**Interfaces:**
- Consumes: `OnRaise[F, Err]`, `RaiseArrow[F, G, Err]`, `RaisePull[G, F, Err]`,
  `WeaveArrows.codomainTarget` — all unchanged from M10.
- Produces:
  - `trait RaiseAspect[Alg[_[_]], Dom[_], Cod[_], Err[_]] extends RaiseFunctorK[Alg, Err] { def intercept[F[_]](af: Alg[F])(fk: Aspect.Weave[F, Dom, Cod, *] ~> F, onRaise: OnRaise[F, Err])(implicit F: Apply[F]): Alg[F] }`
  - `RaiseAspect.observing[F[_], E, Err[_]](R: Raise[F, E], onRaise: OnRaise[F, Err])(implicit F: Apply[F], ev: Err[E]): Raise[F, E]`
  - `LowPriorityWeaveInterpreter.fromRaiseAspect[Alg[_[_]], Dom[_], Cod[_], Err[_], F[_]](implicit F: Apply[F], A: RaiseAspect[Alg, Dom, Cod, Err]): WeaveInterpreter[Alg, Dom, Cod, Err, F]` — **no `syn` parameter**
  - `final class RecordingFk[F[_], Dom[_], Cod[_]]` with
    `val fk: Aspect.Weave[F, Dom, Cod, *] ~> F`,
    `def record(event: String): Unit`,
    `def weaves: List[RecordedWeave[F, Dom, Cod]]`,
    `def events: List[String]`
  - `sealed trait RecordedWeave[F[_], Dom[_], Cod[_]] { type A; def weave: Aspect.Weave[F, Dom, Cod, A] }`
  - `TestAlgReference.referenceRaiseAspect[Dom[_], Cod[_], Err[_]]` — same
    implicit parameter list, fused body.

- [ ] **Step 1: Write the failing test**

Create `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/ObservingCapabilitySpec.scala`:

```scala
package com.dwolla.tagless.mtl

import cats.data.EitherT
import cats.mtl.Raise
import cats.syntax.all._
import cats.{Eval, Functor}
import munit.FunSuite

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ListBuffer

import TestError._

/** `RaiseAspect.observing` is the entire capability-side surface of the fused
  * derivation, and it takes over the hook behaviour that
  * `WeaveArrows.raiseLift(onRaise)` had. Everything `WeaveArrowsOnRaiseSpec`
  * proved about the hook is proved here instead — Task 2 deletes that file
  * only after this one passes.
  *
  * The first test is the one the old design could not have written: the
  * decorated capability reports the caller's ''own'' `Functor[F]`, so there is
  * no synthesized functor for a generic `R.functor.map(fa)(f)` to corrupt.
  * See `22-milestone-M12-fused-derivation.md` for what that corruption was.
  */
class ObservingCapabilitySpec extends FunSuite {
  private type F[A] = Either[TestError, A]
  private type Lazily[A] = EitherT[Eval, TestError, A]

  test("the decorated capability reports the caller's own Functor, never a synthesized one") {
    val callerFunctor: Functor[F] = Functor[F]

    val caller: Raise[F, ErrA] = new Raise[F, ErrA] {
      val functor: Functor[F] = callerFunctor
      def raise[E2 <: ErrA, A](e: E2): F[A] = e.asLeft[A].leftWiden[TestError]
    }

    val decorated = RaiseAspect.observing[F, ErrA, Render](caller, OnRaise.noop[F, Render])

    assert(decorated.functor eq callerFunctor)
  }

  test("decorating does not change the raised value") {
    val caller = Raise[F, ErrA]
    val decorated = RaiseAspect.observing[F, ErrA, Render](caller, OnRaise.noop[F, Render])

    assertEquals(
      decorated.raise[NegativeInput, Int](NegativeInput(-7)),
      caller.raise[NegativeInput, Int](NegativeInput(-7))
    )
  }

  test("the hook renders the raised error through its Err evidence, exactly once") {
    val rendered = ListBuffer.empty[String]

    val hook: OnRaise[F, Render] = new OnRaise[F, Render] {
      def apply[E](e: E)(implicit ev: Render[E]): F[Unit] = {
        val _ = rendered += ev.render(e)
        Right(())
      }
    }

    val decorated = RaiseAspect.observing[F, ErrA, Render](Raise[F, ErrA], hook)
    val out = decorated.raise[NegativeInput, Int](NegativeInput(-3))

    assertEquals(out, NegativeInput(-3).asLeft[Int].leftWiden[TestError])
    // `errA:` proves the Render instance ran; `toString` alone would give
    // "NegativeInput(-3)".
    assertEquals(rendered.toList, List("errA:NegativeInput(-3)"))
  }

  test("the hook's effect is sequenced before the raise, and neither runs until the value is forced") {
    val counter = new AtomicInteger(0)
    val log = ListBuffer.empty[String]

    val hook: OnRaise[Lazily, Render] = new OnRaise[Lazily, Render] {
      def apply[E](e: E)(implicit ev: Render[E]): Lazily[Unit] =
        EitherT.liftF(Eval.always {
          val _ = counter.incrementAndGet()
          val _ = log += s"hook:${ev.render(e)}"
          ()
        })
    }

    val caller: Raise[Lazily, ErrA] = new Raise[Lazily, ErrA] {
      val functor: Functor[Lazily] = Functor[Lazily]
      def raise[E2 <: ErrA, A](e: E2): Lazily[A] =
        EitherT(Eval.always {
          val _ = log += "raise"
          e.asLeft[A].leftWiden[TestError]
        })
    }

    val decorated = RaiseAspect.observing[Lazily, ErrA, Render](caller, hook)
    val raised = decorated.raise[NegativeInput, Int](NegativeInput(-1))

    assertEquals(counter.get(), 0, "building the raised value must run no effects")
    assertEquals(log.toList, List.empty[String])

    assertEquals(raised.value.value, NegativeInput(-1).asLeft[Int].leftWiden[TestError])
    assertEquals(counter.get(), 1, "the hook must run exactly once")
    assertEquals(log.toList, List("hook:errA:NegativeInput(-1)", "raise"))
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
sbt "raiseAspectCoreJVM/testOnly com.dwolla.tagless.mtl.ObservingCapabilitySpec"
```

Expected: compile failure — `value observing is not a member of object RaiseAspect`
(and, on Scala 2, `not found: value RaiseAspect` for the companion, which does
not exist yet).

- [ ] **Step 3: Rewrite `RaiseAspect.scala`**

Replace the file's contents below the package clause with:

```scala
import cats.mtl.Raise
import cats.syntax.all._
import cats.tagless.aop.Aspect
import cats.{Apply, Functor}
import cats.~>

/** The `FunctorK` analogue for algebras whose methods take `Raise` capability
  * parameters. Plain `FunctorK` is uninhabited for such algebras, because
  * `mapK` over an `F ~> G` cannot turn the `Raise[G, E]` a `G`-side method
  * receives into the `Raise[F, E]` the underlying method needs.
  */
trait RaiseFunctorK[Alg[_[_]], Err[_]] extends Serializable {
  def mapK[F[_], G[_]](af: Alg[F])(arrow: RaiseArrow[F, G, Err]): Alg[G]
}

/** The `Aspect` analogue for algebras with `Raise` capability parameters.
  *
  * `Aspect.Weave` and `Aspect.Advice` are reused from cats-tagless verbatim so
  * that natchez-tagless's existing `Weave ~> F` interpreters keep working —
  * but a `Weave` here is ''data'' handed to `fk`, never an effect type. That
  * is the whole point of the operation being fused: no method ever receives a
  * `Raise[Aspect.Weave[F, Dom, Cod, *], E]`, so nothing ever has to synthesize
  * a `Functor` for the woven carrier. See
  * `docs/plans/raise-aspect/22-milestone-M12-fused-derivation.md` for the
  * defect that design removed.
  *
  * There is deliberately no `E` parameter: transport is uniform in the error
  * type, so each method is handled with whatever error types it declares,
  * including several `Raise` parameters on one method.
  *
  * `Err` is not an `E` parameter. It is a per-error-type ''evidence'' type
  * class — `RaisePull#apply` still quantifies over `E` itself and merely
  * demands an `Err[E]` at each application — so transport stays uniform while
  * an interception point gains something better than `toString` to render a
  * raised error with. Use `cats.tagless.Trivial` for `Err` when no evidence is
  * wanted.
  */
trait RaiseAspect[Alg[_[_]], Dom[_], Cod[_], Err[_]] extends RaiseFunctorK[Alg, Err] {

  /** Weave every method, hand each `Aspect.Weave` to `fk`, and pass every
    * `Raise` capability straight through to the underlying implementation,
    * decorated with `onRaise`.
    *
    * `Apply[F]` is needed only to sequence the hook's effect before the raise.
    */
  def intercept[F[_]](af: Alg[F])(
      fk: Aspect.Weave[F, Dom, Cod, *] ~> F,
      onRaise: OnRaise[F, Err]
  )(implicit F: Apply[F]): Alg[F]
}

object RaiseAspect {

  /** The only capability-side helper the fused expansion needs: decorate a
    * `Raise[F, E]` with the observation hook, ''at the same carrier''.
    *
    * `functor` is `R`'s own — the real `Functor[F]`. Nothing is synthesized,
    * which is why this decorator is sound by construction: it can only produce
    * what `R` produces, prefixed by the hook's effect.
    */
  def observing[F[_], E, Err[_]](R: Raise[F, E], onRaise: OnRaise[F, Err])(implicit
      F: Apply[F],
      ev: Err[E]
  ): Raise[F, E] =
    new Raise[F, E] {
      val functor: Functor[F] = R.functor

      def raise[E2 <: E, A](e: E2): F[A] =
        onRaise.apply[E](e)(ev) *> R.raise[E2, A](e)
    }
}
```

- [ ] **Step 4: Run the new test to verify it passes**

```bash
sbt "raiseAspectCoreJVM/testOnly com.dwolla.tagless.mtl.ObservingCapabilitySpec"
```

Expected: PASS, 4 tests. The rest of the module does not compile yet — that is
the next four steps.

- [ ] **Step 5: Rewrite `WeaveInterpreter.fromRaiseAspect`**

In `WeaveInterpreter.scala`, replace `LowPriorityWeaveInterpreter`'s body. Note
that `WeaveInterpreter#apply`'s signature was **already** `intercept`'s — this
is the deletion of an adapter, not a redesign. `fromAspect` is untouched.

```scala
trait LowPriorityWeaveInterpreter {

  /** Lower priority: used only when no `Aspect` instance is available. The
    * caller's interpreter and hook go straight to `RaiseAspect#intercept` —
    * this type class's `apply` and `intercept` are the same signature.
    *
    * `Apply[F]` is `intercept`'s own constraint, needed to sequence the hook.
    */
  implicit def fromRaiseAspect[Alg[_[_]], Dom[_], Cod[_], Err[_], F[_]](implicit
      F: Apply[F],
      A: RaiseAspect[Alg, Dom, Cod, Err]
  ): WeaveInterpreter[Alg, Dom, Cod, Err, F] =
    new WeaveInterpreter[Alg, Dom, Cod, Err, F] {
      def apply(alg: Alg[F])(
          fk: Aspect.Weave[F, Dom, Cod, *] ~> F,
          onRaise: OnRaise[F, Err]
      ): Alg[F] = A.intercept(alg)(fk, onRaise)
    }
}
```

Delete the now-unused `Synthetic` mention from the file's imports if the
compiler flags it (`-Wunused:imports` is fatal).

- [ ] **Step 6: Add the recording interpreter**

Create `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/RecordingFk.scala`:

```scala
package com.dwolla.tagless.mtl

import cats.tagless.aop.Aspect
import cats.~>

import scala.collection.mutable.ListBuffer

/** One `Aspect.Weave` as the interpreter received it.
  *
  * The result type is held as a type member rather than a wildcard so this
  * file compiles identically on 2.12, 2.13 and 3 — `Weave` is invariant in its
  * last parameter, so a `List[Weave[F, Dom, Cod, _]]` would need either an
  * existential or a cast.
  */
sealed trait RecordedWeave[F[_], Dom[_], Cod[_]] {
  type A
  def weave: Aspect.Weave[F, Dom, Cod, A]
}

object RecordedWeave {
  def apply[F[_], Dom[_], Cod[_], A0](w: Aspect.Weave[F, Dom, Cod, A0]): RecordedWeave[F, Dom, Cod] =
    new RecordedWeave[F, Dom, Cod] {
      type A = A0
      val weave: Aspect.Weave[F, Dom, Cod, A0] = w
    }
}

/** A `Weave ~> F` that records what it is handed and then behaves exactly like
  * `WeaveArrows.codomainTarget`.
  *
  * After M12 there is no `Alg[Aspect.Weave[F, Dom, Cod, *]]` value for a test
  * to reach into: `intercept` hands each weave to `fk` and returns `F[A]`.
  * What the interpreter sees is therefore the entire observable surface of
  * weaving, and this is how a test sees it. It is strictly ''more'' than the
  * pre-M12 tests could see, because it pins the order in which weaves arrive;
  * inspecting a returned value cannot.
  *
  * `record` lets a test's `OnRaise` hook append to the same log, so one buffer
  * holds both kinds of event in the order they happened.
  */
final class RecordingFk[F[_], Dom[_], Cod[_]] {
  private val recorded = ListBuffer.empty[RecordedWeave[F, Dom, Cod]]
  private val log = ListBuffer.empty[String]

  val fk: Aspect.Weave[F, Dom, Cod, *] ~> F =
    new (Aspect.Weave[F, Dom, Cod, *] ~> F) {
      def apply[A](w: Aspect.Weave[F, Dom, Cod, A]): F[A] = {
        val _ = recorded += RecordedWeave(w)
        val _ = log += s"weave:${w.algebraName}.${w.codomain.name}"
        w.codomain.target
      }
    }

  def record(event: String): Unit = {
    val _ = log += event
  }

  def weaves: List[RecordedWeave[F, Dom, Cod]] = recorded.toList

  def events: List[String] = log.toList
}
```

- [ ] **Step 7: Transpose `TestAlgReference` to the fused shape**

This is the differential oracle both macros are checked against, so it must
show exactly what they will generate. `mapK` is **unchanged**; only `weave`
becomes `intercept`. Every `pull(R)` becomes
`RaiseAspect.observing(R, onRaise)`, and the `Aspect.Weave` each method used to
*return* is now passed to `fk`.

Replace the imports and the `weave` method (the `mapK` method and the object's
scaladoc stay as they are):

```scala
import cats.Apply
import cats.mtl.Raise
import cats.tagless.aop.Aspect
import cats.~>
```

```scala
      def intercept[F[_]](af: TestAlg[F])(
          fk: Aspect.Weave[F, Dom, Cod, *] ~> F,
          onRaise: OnRaise[F, Err]
      )(implicit F: Apply[F]): TestAlg[F] =
        new TestAlg[F] {
          def a(i: Int)(implicit R: Raise[F, ErrA]): F[String] =
            fk(
              Aspect.Weave[F, Dom, Cod, String](
                "TestAlg",
                List(List(Aspect.Advice.byValue[Dom, Int]("i", i))),
                Aspect.Advice[F, Cod, String]("a", af.a(i)(RaiseAspect.observing(R, onRaise)))
              )
            )

          def b(x: String, y: => Int)(implicit R: Raise[F, ErrB]): F[Int] =
            fk(
              Aspect.Weave[F, Dom, Cod, Int](
                "TestAlg",
                List(
                  List(
                    Aspect.Advice.byValue[Dom, String]("x", x),
                    Aspect.Advice.byName[Dom, Int]("y", y)
                  )
                ),
                Aspect.Advice[F, Cod, Int]("b", af.b(x, y)(RaiseAspect.observing(R, onRaise)))
              )
            )

          def c(i: Int): F[Int] =
            fk(
              Aspect.Weave[F, Dom, Cod, Int](
                "TestAlg",
                List(List(Aspect.Advice.byValue[Dom, Int]("i", i))),
                Aspect.Advice[F, Cod, Int]("c", af.c(i))
              )
            )

          def d(i: Int)(j: Int)(implicit R: Raise[F, ErrA]): F[Int] =
            fk(
              Aspect.Weave[F, Dom, Cod, Int](
                "TestAlg",
                List(
                  List(Aspect.Advice.byValue[Dom, Int]("i", i)),
                  List(Aspect.Advice.byValue[Dom, Int]("j", j))
                ),
                Aspect.Advice[F, Cod, Int]("d", af.d(i)(j)(RaiseAspect.observing(R, onRaise)))
              )
            )

          // `e`'s only parameter clause holds nothing but capabilities, so it
          // contributes no clause to the domain at all.
          def e(implicit R1: Raise[F, ErrA], R2: Raise[F, ErrB]): F[Unit] =
            fk(
              Aspect.Weave[F, Dom, Cod, Unit](
                "TestAlg",
                Nil,
                Aspect.Advice[F, Cod, Unit](
                  "e",
                  af.e(RaiseAspect.observing(R1, onRaise), RaiseAspect.observing(R2, onRaise))
                )
              )
            )
        }
```

Note what did **not** change: the `Aspect.Weave`/`Aspect.Advice` construction,
the algebra name, the domain clauses, the advice names, `byValue` vs `byName`.
Woven content is identical; only its destination changed. Add a sentence to the
object's scaladoc saying exactly that, since the next reader will want to know
whether the oracle drifted.

- [ ] **Step 8: Transpose the reference's two specs**

`TestAlgReferenceSpec.scala`: delete the local `syntheticRender` and `raiseW`,
and replace the two helpers at the top with:

```scala
  private val raiseF: Raise[F, TestError] = Raise[F, TestError]

  private val ref: RaiseAspect[TestAlg, Render, Render, Render] =
    TestAlgReference.referenceRaiseAspect[Render, Render, Render]

  /** The fused analogue of the old `weave`-then-`mapK` pair: intercept with a
    * recording interpreter, then read the recorded weaves. `codomainTarget`'s
    * behaviour is what `RecordingFk` forwards, so the returned algebra is the
    * erased one and the recorder holds the structure.
    */
  private def instrumentedOf(eOutcome: Int): (TestAlg[F], RecordingFk[F, Render, Render]) = {
    val recorder = new RecordingFk[F, Render, Render]
    (ref.intercept(new EitherTestAlg(eOutcome))(recorder.fk, OnRaise.noop[F, Render]), recorder)
  }

  private def erasedOf(eOutcome: Int): TestAlg[F] = instrumentedOf(eOutcome)._1
```

The four erasure tests then keep their bodies verbatim — `erasedOf` still means
the same thing. The five structure tests change from inspecting a returned
weave to inspecting the recorded one; the pattern for each is:

```scala
  test("every woven method reports the algebra name") {
    val (w, recorder) = instrumentedOf(0)

    val _ = w.a(1)(raiseF)
    val _ = w.b("x", 1)(raiseF)
    val _ = w.c(1)
    val _ = w.d(1)(2)(raiseF)
    val _ = w.e(raiseF, raiseF)

    assertEquals(recorder.weaves.map(_.weave.algebraName), List.fill(5)("TestAlg"))
    assertEquals(recorder.weaves.map(_.weave.codomain.name), List("a", "b", "c", "d", "e"))
  }
```

which merges the old "algebra name" and "codomain advice named after the
method" tests and additionally pins the order. Do the same for the domain-shape
test, the capability-absent test, the strict/by-name capture test, and the
by-name-not-forced test — the last one becomes:

```scala
  test("weaving does not force a by-name argument") {
    val (w, recorder) = instrumentedOf(0)

    // x is empty, so the underlying implementation raises without touching y.
    val out = w.b("", throw new RuntimeException("by-name argument was forced"))(raiseF)

    assertEquals(out, EmptyInput("x").asLeft[Int].leftWiden[TestError])
    intercept[RuntimeException](recorder.weaves.head.weave.domain.head(1).target.value)
  }
```

The old "codomain target is the underlying call made with the pulled
capability" test becomes the stronger and simpler statement that the
instrumented method's *result* equals the underlying call:

```scala
  test("an instrumented method returns exactly what the underlying call returns") {
    val impl = new EitherTestAlg(0)
    val (w, _) = instrumentedOf(0)

    assertEquals(w.a(3)(raiseF), impl.a(3)(raiseF))
    assertEquals(w.a(-3)(raiseF), impl.a(-3)(raiseF))
    assertEquals(w.c(4), impl.c(4))
  }
```

`TestAlgReferencePropertySpec.scala`: delete `syntheticRender`, and replace
`erased`/the second property with the same pattern —

```scala
  private def erased(impl: TestAlg[F]): TestAlg[F] =
    ref.intercept(impl)(WeaveArrows.codomainTarget[F, Render, Render], OnRaise.noop[F, Render])
```

for the first property (which is L3′ in embryo), and for the second:

```scala
  property("weaving reports the algebra and method names for every input") {
    forAll { (i: Int, eOutcome: Int) =>
      val recorder = new RecordingFk[F, Render, Render]
      val w = ref.intercept(new EitherTestAlg(eOutcome))(recorder.fk, OnRaise.noop[F, Render])
      val _ = w.c(i)

      val weave = recorder.weaves.head.weave
      assertEquals(weave.algebraName, "TestAlg")
      assertEquals(weave.codomain.name, "c")
      assertEquals(weave.domain.map(_.map(_.name)), List(List("i")))
    }
  }
```

- [ ] **Step 9: Transpose the remaining core specs**

- `EvidenceThreadingSpec.scala` — delete `syntheticRender`; replace each
  `WeaveArrows.raiseLift[F, Render, Render, Render](hook).apply(Raise[F, ErrX])`
  with `RaiseAspect.observing[F, ErrX, Render](Raise[F, ErrX], hook)`. In the
  first test, the assertion on `out.codomain.target` becomes an assertion on
  `out` itself (there is no shell weave any more):
  `assertEquals(out, NegativeInput(-3).asLeft[Int].leftWiden[TestError])`. The
  spec's name and intent are unchanged — the `Err[E]` evidence still has to
  reach the hook, and this is still where that is proven.
- `WeaveInterpreterFixtures.scala` — `plainRaiseAspectPoison`'s `weave` becomes:

  ```scala
      def intercept[F[_]](af: PlainAlg[F])(
          fk: Weave[F, Render, Render, *] ~> F,
          onRaise: OnRaise[F, Render]
      )(implicit F: Apply[F]): PlainAlg[F] =
        throw new AssertionError("priority resolved to RaiseAspect instead of Aspect")
  ```

  with `cats.Apply` imported in place of `cats.Functor`. `plainAspect` and
  `mapK` are untouched.
- `WeaveInterpreterSpec.scala` — delete the local `syntheticRender` and its
  eight-line comment (the constraint it existed to satisfy is gone). Nothing
  else changes: the spec already calls
  `WeaveInterpreter[…].apply(alg)(erase, hook)`, which is now a direct call to
  `intercept`.

- [ ] **Step 10: Run the full core suite**

```bash
sbt "+raiseAspectCoreJVM/test"
```

Expected: PASS on 2.12, 2.13 and 3. `ObservingCapabilitySpec` adds 4 tests;
`TestAlgReferenceSpec` loses one to the merge in Step 8. Record the count.

- [ ] **Step 11: Verify the Scala.js linker**

```bash
sbt "+raiseAspectCoreJS/Test/scalaJSLinkerResult"
```

Expected: success on all three versions. (Test *execution* on JS is not
available locally — no Node — consistent with every prior milestone.)

- [ ] **Step 12: Commit**

```bash
git add raise-aspect-core/
git commit -m "feat!: fuse weave and mapK into RaiseAspect#intercept

The woven Aspect.Weave is now data handed to the interpreter's fk rather than
the algebra's effect type, so a method's Raise[F, E] is passed straight to the
underlying implementation, decorated with the hook at the same carrier. No
capability crosses a carrier boundary, so nothing needs a Functor[Weave]."
```

---

## Task 2: delete `Synthetic` and the `WeaveArrows` residue

Pure deletion, driven by the compiler. Nothing here is a behaviour change:
after Task 1 these members have no caller inside `raise-aspect-core`.

The order within the task matters. The replacement coverage is put in place and
**seen to pass** before anything is removed, so that no commit deletes a test
and the code it covered at the same time.

**Files:**
- Delete: `raise-aspect-core/src/main/scala/com/dwolla/tagless/mtl/Synthetic.scala`
- Modify: `raise-aspect-core/src/main/scala/com/dwolla/tagless/mtl/WeaveArrows.scala`
- Create: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/CarrierArrows.scala`
- Modify: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/WeaveArrowsSpec.scala`
- Delete: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/WeaveArrowsOnRaiseSpec.scala`
- Modify: `raise-aspect-core/src/main/scala/README.md`

**Interfaces:**
- Consumes: Task 1's `RaiseAspect.observing` (the new home for the hook
  coverage).
- Produces:
  - `WeaveArrows.codomainTarget[F[_], Dom[_], Cod[_]]: Aspect.Weave[F, Dom, Cod, *] ~> F` — the only surviving member.
  - `CarrierArrows.resultToLazily: RaiseArrow[CarrierArrows.Result, CarrierArrows.Lazily, Render]` — a genuine non-identity arrow between two different effects, replacing `eraseWeave` as the non-trivial `RaiseArrow` in tests and (Task 3) in laws L1/L2.
- Removes: `Synthetic`, `WeaveArrows.raisePull`, both `WeaveArrows.raiseLift`
  overloads, `WeaveArrows.eraseWeave`, the private `syntheticWeaveFunctor`, and
  the private `RaiseName`.

- [ ] **Step 1: Add the replacement arrow, and prove it before deleting anything**

Create `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/CarrierArrows.scala`:

```scala
package com.dwolla.tagless.mtl

import cats.Functor
import cats.data.EitherT
import cats.mtl.Raise
import cats.{Eval, ~>}

/** A genuine carrier change, for the tests and laws that need a `RaiseArrow`
  * which is not the identity.
  *
  * Before M12 that role was played by `WeaveArrows.eraseWeave`, an arrow from
  * the woven carrier back to `F`. Fusion deletes both the arrow and the
  * carrier, and testing `mapK` only at `RaiseArrow.id` would be a real loss:
  * L1 and L2 are about composition of arrows, and the identity arrow satisfies
  * them for reasons that have nothing to do with the derivation.
  *
  * `Eval` is total, so the pull can transport a `Raise[Lazily, E]` back to
  * `Raise[Result, E]` by running it — the canonical construction of a pull
  * from a `G ~> F`.
  */
object CarrierArrows {
  type Result[A] = Either[TestError, A]
  type Lazily[A] = EitherT[Eval, TestError, A]

  val resultToLazily: RaiseArrow[Result, Lazily, Render] =
    RaiseArrow(
      new (Result ~> Lazily) {
        def apply[A](fa: Result[A]): Lazily[A] = EitherT(Eval.now(fa))
      },
      new RaisePull[Lazily, Result, Render] {
        def apply[E](rg: Raise[Lazily, E])(implicit ev: Render[E]): Raise[Result, E] =
          new Raise[Result, E] {
            val functor: Functor[Result] = Functor[Result]

            def raise[E2 <: E, A](e: E2): Result[A] = rg.raise[E2, A](e).value.value
          }
      }
    )
}
```

In `WeaveArrowsSpec.scala`, delete the `syntheticRender`, `liftedRaise` and
`weaveOf` helpers and the five tests that exercise `raiseLift`, `raisePull`,
`eraseWeave` and `Synthetic`, keeping the `codomainTarget` test, the two
identity tests, and an `andThen` test rebuilt on the new arrow:

```scala
  test("RaiseArrow.andThen sends values forward and capabilities backward") {
    val arrow = CarrierArrows.resultToLazily.andThen(RaiseArrow.id[CarrierArrows.Lazily, Render])
    val err = NegativeInput(-4)

    assertEquals(arrow.fk(5.asRight[TestError]).value.value, 5.asRight[TestError])
    assertEquals(
      arrow.pull(Raise[CarrierArrows.Lazily, TestError]).raise[NegativeInput, Int](err),
      err.asLeft[Int].leftWiden[TestError]
    )
  }
```

- [ ] **Step 2: Run the surviving spec, with the old code still present**

```bash
sbt "raiseAspectCoreJVM/testOnly com.dwolla.tagless.mtl.WeaveArrowsSpec"
```

Expected: PASS. This is the point of the step: the replacement coverage is real
*before* the deletion, not after.

- [ ] **Step 3: Check the rescued-coverage inventory**

`WeaveArrowsOnRaiseSpec` is about to be deleted. Confirm each of its claims has
a live successor, and do not proceed until every line has one:

| Claim in `WeaveArrowsOnRaiseSpec` | Successor |
| --- | --- |
| the hook is invoked with the raised value | `ObservingCapabilitySpec`, test 3 |
| the hook renders through `Err[E]`, not `toString` | `ObservingCapabilitySpec` test 3, `EvidenceThreadingSpec` |
| the hook runs exactly once | `ObservingCapabilitySpec`, test 4 |
| the hook's effect is sequenced before the raise | `ObservingCapabilitySpec`, test 4 |
| nothing runs until the value is forced | `ObservingCapabilitySpec`, test 4 |
| the hook never fires on a success path | `WeaveInterpreterSpec` ("a RaiseAspect-only algebra … runs the hook", which asserts an empty log on the success call) |
| `raiseLift(noop)` agrees with `raiseLift` | vacuous — one `raiseLift` remains, and it is `observing` |
| woven-then-erased agrees with the underlying algebra, hook or not | `TestAlgReferencePropertySpec` and law L3′ (Task 3) |

- [ ] **Step 4: Delete**

```bash
git rm raise-aspect-core/src/main/scala/com/dwolla/tagless/mtl/Synthetic.scala \
       raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/WeaveArrowsOnRaiseSpec.scala
```

Reduce `WeaveArrows.scala` to:

```scala
package com.dwolla.tagless.mtl

import cats.arrow.FunctionK
import cats.tagless.aop.Aspect
import cats.~>

/** The forgetful arrow from a woven value back to the underlying effect.
  *
  * Before M12 this object also held the pair of capability transports between
  * `F` and the woven carrier. The fused `RaiseAspect#intercept` never puts a
  * capability on the woven carrier, so there is nothing left to transport, and
  * the `Synthetic[Cod]` those transports needed is gone with them.
  */
object WeaveArrows {

  /** Forget the metadata. Also the no-op interpreter, and the `fk` law L3′
    * erases with.
    */
  def codomainTarget[F[_], Dom[_], Cod[_]]: Aspect.Weave[F, Dom, Cod, *] ~> F =
    FunctionK.liftFunction[Aspect.Weave[F, Dom, Cod, *], F](_.codomain.target)
}
```

Update `raise-aspect-core/src/main/scala/README.md`: drop `Synthetic` from the
type list, and note that `WeaveArrows` is now a single arrow.

- [ ] **Step 5: Run the full core suite**

```bash
sbt "+raiseAspectCoreJVM/test"
```

Expected: PASS on all three versions.

- [ ] **Step 6: Confirm nothing in core still names the deleted machinery**

```bash
grep -rn "Synthetic\|raiseLift\|raisePull\|eraseWeave" raise-aspect-core/src
```

Expected: no output.

- [ ] **Step 7: Verify the Scala.js linker, then commit**

```bash
sbt "+raiseAspectCoreJS/Test/scalaJSLinkerResult"
```

```bash
git add raise-aspect-core/
git commit -m "feat!: delete Synthetic and the weave-carrier capability transports

The fused intercept never puts a Raise on the woven carrier, so raiseLift,
raisePull, eraseWeave and the synthesized weave Functor have no caller and no
reason to exist. WeaveArrows keeps codomainTarget. The unlawful-Functor finding
those scaladocs carried now lives in the M12 milestone document."
```

---

## Task 3: laws

The laws module is one compile unit and every piece of this references the
next, so it is one task: L3 becomes L3′, L5 and L6a–d go with the functions
they describe, L7 becomes an `eq` assertion, L8 and L9 re-anchor onto a
recording `fk`, and L1/L2 get a real arrow to replace `eraseWeave`.

**`raise-aspect-laws` is not frozen for this milestone** — decision D6, on
M10's D2 precedent: mechanically retyping a frozen file, forced by a ratified
design, is not a weakening. **But the meaning of every surviving law is
frozen.** If implementing a change requires editing either operand of a `<->`,
stop and report: that is an overview-level question, not a milestone-level fix.
Deletions are permitted only for the laws named here, each of which is a law
*about* a function that no longer exists.

**Files:**
- Modify: `raise-aspect-laws/src/main/scala/com/dwolla/tagless/mtl/laws/RaiseArrowLaws.scala`
- Modify: `raise-aspect-laws/src/main/scala/com/dwolla/tagless/mtl/laws/RaiseAspectLaws.scala`
- Modify: `raise-aspect-laws/src/main/scala/com/dwolla/tagless/mtl/laws/discipline/RaiseAspectTests.scala`
- Modify: `raise-aspect-laws/src/test/scala/com/dwolla/tagless/mtl/laws/LawsInstances.scala`
- Modify: `raise-aspect-laws/src/test/scala/com/dwolla/tagless/mtl/laws/PlainAlgReference.scala`
- Modify: `raise-aspect-laws/src/test/scala/com/dwolla/tagless/mtl/laws/RaiseAspectSuite.scala`
- Modify: `raise-aspect-laws/src/test/scala/com/dwolla/tagless/mtl/laws/ConservativeExtensionSuite.scala`
- Unchanged: `RaiseFunctorKLaws.scala`, `discipline/RaiseFunctorKTests.scala`,
  `WeaveRenderer.scala`, `ReferenceRaiseAspectSpec.scala`

**Interfaces:**
- Consumes: Task 1's `RaiseAspect#intercept`, `RaiseAspect.observing`,
  `RecordingFk`; Task 2's `WeaveArrows.codomainTarget` and
  `CarrierArrows.resultToLazily`.
- Produces:
  - `RaiseAspectLaws.instrumentErasure[A[_]](af: Alg[A])(implicit A: Applicative[A]): IsEq[Alg[A]]` — L3′
  - `RaiseAspectLaws.apply[Alg[_[_]], Dom[_], Cod[_], Err[_]](implicit ev: RaiseAspect[Alg, Dom, Cod, Err])` — no `syn`
  - `RaiseAspectTests[Alg, Dom, Cod, Err]#raiseAspect[A[_], B[_], C[_]]` taking `ApplicativeA: Applicative[A]` in place of `FunctorA: Functor[A]`
  - `LawsInstances.instrumented(instance, eOutcome)` and
    `LawsInstances.renderedWeaves(recorder)` helpers
- Removes: `RaiseArrowLaws.sectionRetraction` (L5),
  `liftedFunctorMapsTarget`/`liftedFunctorPreservesAlgebraName`/`liftedFunctorPreservesCodomainName`/`liftedFunctorPreservesDomain`
  (L6a–d), `pulledFunctorIsAmbient` (L7), the private `lifted`;
  `LawsInstances.Woven`, `eqWoven`, `raiseWoven`, `syntheticRender`.

- [ ] **Step 1: Write the failing test**

In `RaiseAspectSuite.scala`, replace the `// L4, L5, L6, L7` section's L7 unit
test with the fused one, which is what the design makes assertable:

```scala
  test("L7 the decorated capability reports the caller's own Functor instance") {
    val caller = raiseResult
    val decorated = RaiseAspect.observing[Result, TestError, Render](caller, OnRaise.noop[Result, Render])
    assert(decorated.functor eq caller.functor)
  }
```

- [ ] **Step 2: Run to verify it fails**

```bash
sbt "raiseAspectLawsJVM/test"
```

Expected: compile failure across the whole module — it still references
`weave`, `Synthetic`, `raiseLift`, `raisePull` and `eraseWeave`, none of which
exist. That is the expected red, and it is why this is one task.

- [ ] **Step 3: Reduce `RaiseArrowLaws.scala` to L4**

Delete `sectionRetraction`, the four `liftedFunctor*` laws,
`pulledFunctorIsAmbient` and the private `lifted`. Keep `arrowCoherence`
**byte-identical** — its `<->` operands do not change — and replace the
object's scaladoc:

```scala
/** Value-level laws about arrows: L4.
  *
  * L5, L6a–L6d and L7 lived here until M12. They were laws about
  * `WeaveArrows.raiseLift`/`raisePull` and the synthesized
  * `Functor[Aspect.Weave[F, Dom, Cod, *]]`, all of which the fused derivation
  * deletes: no capability is ever placed on the woven carrier, so there is
  * nothing to lift, pull, or synthesize a functor for. L7's content survives
  * as a one-line `eq` assertion in `RaiseAspectSuite` — `RaiseAspect.observing`
  * sets `functor = R.functor`, so it is true by construction rather than a
  * property to check. See
  * `docs/plans/raise-aspect/22-milestone-M12-fused-derivation.md`.
  */
```

- [ ] **Step 4: L3 becomes L3′ in `RaiseAspectLaws.scala`**

```scala
package com.dwolla.tagless.mtl
package laws

import cats.Applicative
import cats.laws._

/** Law L3′, the load-bearing one: intercepting an algebra with the forgetful
  * interpreter and no hook recovers the original algebra, including on inputs
  * that raise.
  */
trait RaiseAspectLaws[Alg[_[_]], Dom[_], Cod[_], Err[_]] extends RaiseFunctorKLaws[Alg, Err] {
  implicit def F: RaiseAspect[Alg, Dom, Cod, Err]

  /** L3′ — `intercept(af)(codomainTarget, OnRaise.noop) <-> af`.
    *
    * The analogue of upstream's Aspect-consistency law, and the successor to
    * M2's L3 (`mapK(weave(af))(eraseWeave) <-> af`). There is no longer a
    * second operation for the first to be inverse to, but the content L3
    * carried is exactly this: at `A = Either[TestError, *]` a raise must come
    * back as the identical `Left` through the instrumented path.
    *
    * `Applicative[A]` rather than `Functor[A]`: `intercept` needs `Apply` to
    * sequence the hook and `OnRaise.noop` needs `Applicative` to produce one.
    */
  def instrumentErasure[A[_]](af: Alg[A])(implicit A: Applicative[A]): IsEq[Alg[A]] =
    F.intercept(af)(WeaveArrows.codomainTarget[A, Dom, Cod], OnRaise.noop[A, Err]) <-> af
}

object RaiseAspectLaws {
  def apply[Alg[_[_]], Dom[_], Cod[_], Err[_]](implicit
      ev: RaiseAspect[Alg, Dom, Cod, Err]
  ): RaiseAspectLaws[Alg, Dom, Cod, Err] =
    new RaiseAspectLaws[Alg, Dom, Cod, Err] { val F = ev }
}
```

- [ ] **Step 5: Update the discipline rule set**

`discipline/RaiseAspectTests.scala` — the rule name changes with the law, the
`Synthetic` parameter goes, and the effect constraint strengthens:

```scala
trait RaiseAspectTests[Alg[_[_]], Dom[_], Cod[_], Err[_]] extends RaiseFunctorKTests[Alg, Err] {
  def laws: RaiseAspectLaws[Alg, Dom, Cod, Err]

  def raiseAspect[A[_], B[_], C[_]](implicit
      ArbAlgA: Arbitrary[Alg[A]],
      ArbArrowAB: Arbitrary[RaiseArrow[A, B, Err]],
      ArbArrowBC: Arbitrary[RaiseArrow[B, C, Err]],
      EqAlgA: Eq[Alg[A]],
      EqAlgC: Eq[Alg[C]],
      ApplicativeA: Applicative[A]
  ): RuleSet =
    new DefaultRuleSet(
      name = "raiseAspect",
      parent = Some(raiseFunctorK[A, B, C]),
      "intercept erasure" -> forAll((af: Alg[A]) => laws.instrumentErasure[A](af)(ApplicativeA))
    )
}

object RaiseAspectTests {
  def apply[Alg[_[_]], Dom[_], Cod[_], Err[_]](implicit
      ev: RaiseAspect[Alg, Dom, Cod, Err]
  ): RaiseAspectTests[Alg, Dom, Cod, Err] =
    new RaiseAspectTests[Alg, Dom, Cod, Err] { val laws = RaiseAspectLaws[Alg, Dom, Cod, Err] }
}
```

Change the import from `cats.{Eq, Functor}` to `cats.{Applicative, Eq}`.
`RaiseFunctorKTests` is untouched.

- [ ] **Step 6: Update `LawsInstances.scala`**

Delete `type Woven`, `eqWoven`, `raiseWoven` and `syntheticRender`. Keep
everything else verbatim. Add the two shared helpers the re-anchored L8 and the
macro axes' oracles both need:

```scala
  /** Instrument the algebra under test with a recording interpreter. The pair
    * is the fused replacement for `weave` — the algebra behaves as though it
    * had been woven and immediately erased, and the recorder holds the
    * structure that used to be inspectable on the returned value.
    */
  def instrumented(
      instance: RaiseAspect[TestAlg, Render, Render, Render],
      eOutcome: Int
  ): (TestAlg[Result], RecordingFk[Result, Render, Render]) = {
    val recorder = new RecordingFk[Result, Render, Render]
    (instance.intercept(new EitherTestAlg(eOutcome))(recorder.fk, OnRaise.noop[Result, Render]), recorder)
  }

  /** What the interpreter saw, rendered — the fused analogue of mapping
    * `WeaveRenderer.render` over a list of returned weaves, and stricter,
    * because the list is in arrival order.
    */
  def renderedWeaves(recorder: RecordingFk[Result, Render, Render]): List[RenderedWeave] =
    recorder.weaves.map(r => WeaveRenderer.render(r.weave))
```

Also add, for L1/L2's new instantiation, an `Arbitrary` for the arrow and the
`Eq` its target needs:

```scala
  implicit val arbResultToLazily: Arbitrary[RaiseArrow[Result, Lazily, Render]] =
    Arbitrary(Gen.const(CarrierArrows.resultToLazily))
```

(`Lazily` is already defined here and `eqEitherTEval` already supplies the
`Eq`s `eqTestAlg[Lazily]` needs; `Raise[Lazily, ErrA]`/`Raise[Lazily, ErrB]`
come from cats-mtl's `EitherT` instances by contravariance, as
`RaiseAspectSuite`'s existing `raiseLazily` already relies on. Add
`import org.scalacheck.{Arbitrary, Gen}`.)

- [ ] **Step 7: Transpose `PlainAlgReference`**

`weave` becomes `intercept`, mechanically — this fixture has no capability
parameters, so no `observing` call appears:

```scala
      def intercept[F[_]](af: PlainAlg[F])(
          fk: Aspect.Weave[F, Dom, Cod, *] ~> F,
          onRaise: OnRaise[F, Err]
      )(implicit F: Apply[F]): PlainAlg[F] =
        new PlainAlg[F] {
          def p(i: Int): F[String] =
            fk(
              Aspect.Weave[F, Dom, Cod, String](
                "PlainAlg",
                List(List(Aspect.Advice.byValue[Dom, Int]("i", i))),
                Aspect.Advice[F, Cod, String]("p", af.p(i))
              )
            )
        }
```

Imports become `cats.Apply`, `cats.tagless.aop.Aspect`, `cats.~>`. `mapK` is
unchanged, and so is the fact that this fixture needs no `Err` instances —
that is what L9's comparison depends on and it must stay true.

- [ ] **Step 8: Rewrite `RaiseAspectSuite`'s law sections**

Replace the fixtures at the top:

```scala
  private implicit val arbTestAlgResult: Arbitrary[TestAlg[Result]] =
    Arbitrary(Gen.oneOf(-1, 0, 1).map(new EitherTestAlg(_)))

  private implicit val arbIdArrow: Arbitrary[RaiseArrow[Result, Result, Render]] =
    Arbitrary(Gen.const(RaiseArrow.id[Result, Render]))

  private implicit val arbIdArrowLazily: Arbitrary[RaiseArrow[Lazily, Lazily, Render]] =
    Arbitrary(Gen.const(RaiseArrow.id[Lazily, Render]))
```

(`arbTestAlgWoven` and `arbEraseArrow` are deleted with their carrier.)

Then the two `checkAll`s:

```scala
  // L1, L2, L3′
  checkAll(
    "RaiseAspect[TestAlg, Render, Render, Render]",
    RaiseAspectTests[TestAlg, Render, Render, Render].raiseAspect[Result, Result, Result]
  )

  // ...and L1/L2 again over a genuinely non-trivial arrow. Before M12 that was
  // `eraseWeave`, between the woven carrier and `Result`; fusion deletes both
  // ends of it. `CarrierArrows.resultToLazily` is a real change of effect —
  // `Either[TestError, *]` to `EitherT[Eval, TestError, *]` — with a real pull
  // in the opposite direction. Testing mapK only at the identity arrow would
  // be a coverage loss disguised as a deletion.
  checkAll(
    "RaiseFunctorK[TestAlg] over a genuine carrier change",
    laws.discipline.RaiseFunctorKTests[TestAlg, Render].raiseFunctorK[Result, Lazily, Lazily]
  )
```

Delete: the `L5` property, both `L6` properties, the extensional `L7` property,
and the whole `Err = Trivial` block's L5/L6/L7 entries. **Keep** the two `L4`
`Err = Render` properties for `RaiseArrow.id` and — replacing the `eraseWeave`
one — for `CarrierArrows.resultToLazily` and its `andThen` composite, and keep
the `Err = Trivial` twin of L4 so the ∀`E` strength M10 preserved is still
covered. L4 at the new arrow reads:

```scala
  property("L4 arrow coherence for the carrier-change arrow") {
    forAllErrors { e =>
      val law = RaiseArrowLaws.arrowCoherence[Result, Lazily, Render, TestError, Int](
        CarrierArrows.resultToLazily,
        raiseLazily,
        e
      )
      assertEquals(law.lhs.value.value, law.rhs.value.value)
    }
  }
```

(`raiseLazily` already exists as a private val at the bottom of the suite;
promote it above its first use. `IsEq[Lazily[Int]]`'s operands are compared by
forcing, matching `eqEitherTEval`.)

- [ ] **Step 9: Re-anchor L8 and L10**

L8's five tests move from inspecting `wovenAlg(0)` to inspecting a recorder.
The first two collapse into one that also pins arrival order:

```scala
  test("L8 the interpreter receives one weave per call, naming the algebra and the method") {
    val (w, recorder) = LawsInstances.instrumented(instance, 0)

    val _ = w.a(1)(raiseResult)
    val _ = w.b("x", 1)(raiseResult)
    val _ = w.c(1)
    val _ = w.d(1)(2)(raiseResult)
    val _ = w.e(raiseResult, raiseResult)

    assertEquals(recorder.weaves.map(_.weave.algebraName), List.fill(5)("TestAlg"))
    assertEquals(LawsInstances.renderedWeaves(recorder).map(_.methodName), List("a", "b", "c", "d", "e"))
  }

  test("L8 the domain matches the declared parameter lists, capabilities absent") {
    val (w, recorder) = LawsInstances.instrumented(instance, 0)

    val _ = w.a(7)(raiseResult)
    val _ = w.b("ab", 2)(raiseResult)
    val _ = w.c(3)
    val _ = w.d(4)(5)(raiseResult)
    val _ = w.e(raiseResult, raiseResult)

    assertEquals(
      LawsInstances.renderedWeaves(recorder).map(_.domain),
      List(
        List(List("i" -> "7")),
        List(List("x" -> "ab", "y" -> "2")),
        List(List("i" -> "3")),
        List(List("i" -> "4"), List("j" -> "5")),
        // every parameter of `e` is a capability, so it contributes no clause
        List.empty[List[(String, String)]]
      )
    )
  }
```

The by-name test keeps its shape with `recorder.weaves.head.weave.domain`, and
"the codomain target is the underlying call with the capability pulled" becomes
"an instrumented method returns what the underlying call returns", as in Task 1
Step 8. **Delete** "L8 the synthesized Cod instance never appears in a woven
method's domain": there is no synthesized instance left for it to look for, and
`LawsInstances.syntheticRender` — the thing it grepped for — is gone.

L10's two tests keep their counting fixture and lose the weave indirection:

```scala
  test("L10 intercepting performs no effects until the result is run") {
    val counter = new java.util.concurrent.atomic.AtomicInteger(0)
    val recorder = new RecordingFk[Lazily, Render, Render]

    val inst = instance.intercept(countingAlg(counter))(recorder.fk, OnRaise.noop[Lazily, Render])
    val out = inst.a(1)(raiseLazily)
    assertEquals(counter.get(), 0, "intercepting must not run the underlying effect")

    val _ = out.value.value
    assertEquals(counter.get(), 1, "running the instrumented result must run the effect exactly once")
  }
```

with the parity test transposed the same way (`inst.a(i)(raiseLazily).value.value`
against `plain.a(i)(raiseLazily).value.value`).

Finally, in the Serializable block delete the `Synthetic[Trivial]` and
`WeaveArrows.eraseWeave` checks and keep the two identity ones; the arrows that
remain are `RaisePull.id` and `RaiseArrow.id`.

- [ ] **Step 10: Re-anchor L9 in `ConservativeExtensionSuite`**

Two of the three comparisons call `ours.weave(impl)`. L9's *content* is
unchanged — our derivation still has to agree with upstream `Derive.aspect` on
a capability-free algebra — but our side now goes through a recorder while
upstream's still returns a woven algebra:

```scala
  private def ourWeaves(inputs: List[Int]): List[Aspect.Weave[Result, Render, Render, String]] = {
    val recorder = new RecordingFk[Result, Render, Render]
    val instrumented = ours.intercept(impl)(recorder.fk, OnRaise.noop[Result, Render])
    inputs.foreach(i => { val _ = instrumented.p(i) })
    recorder.weaves.map(_.weave.asInstanceOf[Aspect.Weave[Result, Render, Render, String]])
  }
```

If that cast is unwelcome — and it should be — render inside the helper instead
and compare `RenderedWeave`s, which is what both tests actually assert on:

```scala
  private def ourRendered(inputs: List[Int]): List[RenderedWeave] = {
    val recorder = new RecordingFk[Result, Render, Render]
    val instrumented = ours.intercept(impl)(recorder.fk, OnRaise.noop[Result, Render])
    inputs.foreach(i => { val _ = instrumented.p(i) })
    recorder.weaves.map(r => WeaveRenderer.render(r.weave))
  }

  test("L9 our woven structure matches upstream's, rendered") {
    val theirWoven = upstream.weave(impl)
    val inputs = exhaustiveInt.allValues.toList

    assertEquals(ourRendered(inputs), inputs.map(i => WeaveRenderer.render(theirWoven.p(i))))
  }

  test("L9 our instrumented results match upstream's woven codomain targets") {
    val recorder = new RecordingFk[Result, Render, Render]
    val instrumented = ours.intercept(impl)(recorder.fk, OnRaise.noop[Result, Render])
    val theirWoven = upstream.weave(impl)

    exhaustiveInt.allValues.foreach { i =>
      assertEquals(instrumented.p(i), theirWoven.p(i).codomain.target)
    }
  }
```

Use the second form. The third test (mapK against upstream's `FunctorK.mapK`
for any pull) is **unchanged** — `mapK` and `RaisePull` both survive.

- [ ] **Step 11: Run the laws suite**

```bash
sbt "+raiseAspectLawsJVM/test"
```

Expected: PASS on all three versions. Record the test count and the delta; the
L5/L6/L7 deletions remove nine properties and the L4/L1/L2 re-anchoring adds
two. A *net* drop larger than that means something was dropped that should not
have been — check it against the list in Step 3 of this task's description
before continuing.

- [ ] **Step 12: Verify the JS linker, then commit**

```bash
sbt "+raiseAspectLawsJS/Test/scalaJSLinkerResult"
```

```bash
git add raise-aspect-laws/
git commit -m "feat!: L3 becomes L3', and the synthesized-functor laws go with the functor

intercept(af)(codomainTarget, noop) <-> af replaces mapK(weave(af))(eraseWeave)
<-> af; L5 and L6a-d are deleted because the functions they are laws about no
longer exist, and L7 becomes an eq assertion on the caller's own Functor. L8 and
L9 now observe what a recording interpreter receives, which additionally pins
arrival order. L1/L2's non-identity arrow is a real carrier change rather than
the deleted eraseWeave."
```

---

## Task 4: Scala 2 derivation

`raiseWeave` becomes `raiseInstrument`. This is the smaller of the two macro
changes: the fused generator *removes* the carrier-substitution machinery
rather than adding anything.

> **Corrected 2026-07-31, during Task 4.** This section originally said
> `substituteCapabilities` and `Method#transformedParamLists` "become provably
> dead" and instructed deleting them. That is **false**, and following it
> breaks the build. `raiseMapK` — which decision D2 keeps emitting unchanged —
> calls `substituteCapabilities` to retype each `Raise[F, E]` parameter to
> `Raise[G, E]`, because `mapK`'s per-method override must match `Alg[G]`'s
> abstract signature exactly. The "provably dead" claim came from the spike
> and holds only for the `intercept` path, not for a derivation that also
> emits `mapK`. Both helpers stay; Steps 3 and 4 below are superseded on that
> point only.

The code below is what the macro spike compiled and ran on 2.12.21 and 2.13.18,
with `RaiseInstrument` → `RaiseAspect` per decision D1. Take it as given rather
than re-deriving it.

**Files:**
- Modify: `raise-aspect-macros/src/main/scala-2/com/dwolla/tagless/mtl/DeriveRaiseMacros.scala` (`raiseWeave` at `:442`, `substituteCapabilities` at `:435`, `transformedParamLists` at `:63`, `aspect` at `:534`)
- Modify: `raise-aspect-macros/src/main/scala-2/com/dwolla/tagless/mtl/DeriveRaise.scala` (scaladoc only)
- Modify: `raise-aspect-macros/src/test/scala/com/dwolla/tagless/mtl/laws/ExpectedWeaves.scala` (`rendered` only — **not** `expected`)
- Modify: `raise-aspect-macros/src/test/scala-2/com/dwolla/tagless/mtl/laws/DifferentialOracleSpec.scala`
- Modify: `raise-aspect-macros/src/test/scala-2/com/dwolla/tagless/mtl/laws/CrossVersionAgreementSpec.scala`
- Modify: `raise-aspect-macros/src/test/scala-2/com/dwolla/tagless/mtl/laws/EdgeCaseDerivationSpec.scala`
- Modify: `raise-aspect-macros/src/test/scala-2/com/dwolla/tagless/mtl/laws/MethodLocalInstanceSpec.scala`
- Modify: `raise-aspect-macros/src/test/scala-2/com/dwolla/tagless/mtl/laws/DerivedConservativeExtensionSpec.scala`
- Unchanged: `DerivedRaiseAspectSpec.scala` (it only names the type and the
  entry point, both of which keep their spelling), `DerivationErrorSpec.scala`
  (every diagnostic is unchanged — if this file needs an edit, stop and report)

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces: `DeriveRaise.aspect[Alg[_[_]], Dom[_], Cod[_], Err[_]]: RaiseAspect[Alg, Dom, Cod, Err]`
  emitting **both** `intercept` and `mapK` from one derivation;
  `DeriveRaise.functorK[Alg[_[_]], Err[_]]` unchanged.
- Removes: `DeriveRaiseMacros.raiseWeave`, `substituteCapabilities`,
  `Method#transformedParamLists`.

- [ ] **Step 1: Write the failing test**

In `raise-aspect-macros/src/test/scala-2/.../CrossVersionAgreementSpec.scala`,
switch to the fused shape. `ExpectedWeaves.expected` is untouched; only how the
renderings are obtained changes:

```scala
class CrossVersionAgreementSpec extends FunSuite {
  test("the Scala 2 derivation matches the shared expected weave renderings") {
    val derived = DeriveRaise.aspect[TestAlg, Render, Render, Render]
    val recorder = new RecordingFk[Result, Render, Render]
    val instrumented = derived.intercept(new EitherTestAlg(0))(recorder.fk, OnRaise.noop[Result, Render])

    assertEquals(ExpectedWeaves.rendered(instrumented, recorder), ExpectedWeaves.expected)
  }
}
```

and change `ExpectedWeaves.rendered` — and nothing else in that file — to:

```scala
  /** The same calls, rendered from what the interpreter saw.
    *
    * `expected` is unchanged from M2: fusion changes who holds the weave, not
    * what a woven call produces. Only the way a test gets hold of the weaves
    * moved, from inspecting an `Alg[Weave[…]]` to reading a recording `fk`.
    */
  def rendered(
      instrumented: TestAlg[Result],
      recorder: RecordingFk[Result, Render, Render]
  ): List[RenderedWeave] = {
    val _ = instrumented.a(7)(Raise[Result, ErrA])
    val _ = instrumented.b("ab", 2)(Raise[Result, ErrB])
    val _ = instrumented.c(3)
    val _ = instrumented.d(4)(5)(Raise[Result, ErrA])
    val _ = instrumented.e(Raise[Result, ErrA], Raise[Result, ErrB])

    recorder.weaves.map(r => WeaveRenderer.render(r.weave))
  }
```

(adding `import cats.mtl.Raise`).

- [ ] **Step 2: Run to verify it fails**

```bash
sbt "++2.13.18 raiseAspectMacrosJVM/test"
```

Expected: compile failure — `value intercept is not a member of RaiseAspect`
is what the derived instance produces today, because the macro still generates
`weave`.

- [ ] **Step 3: Replace `raiseWeave` with `raiseInstrument`**

In `DeriveRaiseMacros.scala`, delete `raiseWeave` (`:441-494`) and
`substituteCapabilities` (`:431-439`) and put in their place:

```scala
  // def intercept[F[_]](af: Alg[F])(fk: Aspect.Weave[F, Dom, Cod, *] ~> F, onRaise: OnRaise[F, Err])
  //                     (implicit F: Apply[F]): Alg[F]
  def raiseInstrument(Dom: Type, Cod: Type, Err: Type)(algebra: Type): MethodDef = MethodDef("intercept") {
    case PolyType(List(f), MethodType(List(af), MethodType(List(fk, onRaise), MethodType(List(applyF), _)))) =>
      val F = f.asType.toTypeConstructor
      val Af = singleType(NoPrefix, af)
      val members = overridableMembersOf(Af)
      val types = delegateAbstractTypes(Af, members, Af)
      val algebraName = typeNameOf(algebra)

      val methods = delegateMethods(Af, members, af) {
        case method if returnsEffectDirectly(method, f) =>
          validateParams(method, f)

          val AspectAdvice = reify(Aspect.Advice)
          val RaiseAspectRef = reify(RaiseAspect)
          val typeArgs = method.returnType.typeArgs

          // The capability is decorated *in place*: same carrier, same declared
          // type, so there is no parameter-type substitution to do and the
          // generated method's parameter lists are the algebra's own.
          val args = method.transformedArgLists { case Parameter(pn, pt, _) if capabilityError(pt, f).isDefined =>
            val errorType = capabilityError(pt, f).get
            val errInstance = inferErrOrAbort(Err, errorType, method)
            q"$RaiseAspectRef.observing[$F, $errorType, $Err]($pn, $onRaise)($applyF, $errInstance)"
          }

          val codInstance = inferOrAbort(
            appliedType(Cod, typeArgs),
            s"for the result of method ${method.displayName}",
            method
          )
          val codomain =
            q"$AspectAdvice[$F, $Cod, ..$typeArgs](${method.displayName}, ${method.delegate(Ident(af), args)})($codInstance)"
          val weave =
            q"${reify(Aspect.Weave)}[$F, $Dom, $Cod, ..$typeArgs]($algebraName, ${domainOf(method, f, Dom)}, $codomain)"

          method.copy(body = q"$fk.apply[..$typeArgs]($weave)")
        case method if method.occursInReturn(f) =>
          abort(
            s"method ${method.displayName} returns ${method.returnType}; RaiseAspect supports F only as the " +
              "top-level return type, not nested inside another type."
          )
        case method if method.occursInSignature(f) =>
          abort(
            s"method ${method.displayName} mentions the effect type F but does not return F[?]; RaiseAspect " +
              "supports F only as the top-level return type and in Raise[F, E] parameters."
          )
      }

      implement(algebra)(f)(types ++ methods)
  }
```

Three things to notice, because they are the whole diff: the method now calls
`method.copy(body = …)` and nothing else — no `paramLists`, no `returnType`;
the body is `fk.apply(weave)` rather than the weave itself; and `implement`
targets `algebra` at `f` rather than a retyped `appliedType(algebra, WeaveF)`.
The two abort messages are **byte-identical to today's**, which is why
`DerivationErrorSpec` needs no edit.

Then change the `aspect` entry point's `MethodDef` list:

```scala
    instantiate[RaiseAspect[Alg, Dom, Cod, Err]](tag, Dom, Cod, Err)(
      raiseInstrument(Dom, Cod, Err),
      raiseMapK(Err)
    )
```

- [ ] **Step 4: Delete the now-dead `transformedParamLists`**

`Method#transformedParamLists` (`:63`) had exactly one caller,
`substituteCapabilities`, which Step 3 deleted. Delete it. If the compiler
reports another caller, stop and report — the spike's claim that it is provably
dead was checked, and a second caller would mean the tree has moved.

```bash
grep -n "transformedParamLists\|substituteCapabilities" raise-aspect-macros/src/main/scala-2/com/dwolla/tagless/mtl/DeriveRaiseMacros.scala
```

Expected after the deletion: no output.

- [ ] **Step 5: Update the Scala 2 macro tests**

Mechanical, and the same transformation everywhere: `derived.weave(impl)(Functor[Result])`
plus inspection of the returned weave becomes `derived.intercept(impl)(recorder.fk, hook)`
plus inspection of `recorder.weaves`.

- `DifferentialOracleSpec.scala` — see Task 6 for the audit; here, get it
  compiling and passing in the shape below.

  ```scala
  private def instrumented(
      instance: RaiseAspect[TestAlg, Render, Render, Render],
      outcome: Int
  ): (TestAlg[Result], RecordingFk[Result, Render, Render]) =
    LawsInstances.instrumented(instance, outcome)

  test("the derived instance is structurally identical to the reference, for every method and sample") {
    outcomes.foreach { outcome =>
      val (d, dRec) = instrumented(derived, outcome)
      val (r, rRec) = instrumented(reference, outcome)

      exhaustiveInt.allValues.foreach { i =>
        assertEquals(d.a(i)(raiseResult), r.a(i)(raiseResult))
        assertEquals(d.c(i), r.c(i))
        assertEquals(d.e(raiseResult, raiseResult), r.e(raiseResult, raiseResult))

        exhaustiveInt.allValues.foreach(j => assertEquals(d.d(i)(j)(raiseResult), r.d(i)(j)(raiseResult)))
        exhaustiveString.allValues.foreach(s => assertEquals(d.b(s, i)(raiseResult), r.b(s, i)(raiseResult)))
      }

      assertEquals(LawsInstances.renderedWeaves(dRec), LawsInstances.renderedWeaves(rRec))
      assertEquals(dRec.events, rRec.events)
    }
  }
  ```

  The two `mapK` tests are **unchanged**; the "erasure arrow" test becomes an
  `intercept`-with-`codomainTarget` comparison:

  ```scala
  test("the derived intercept agrees with the reference under the forgetful interpreter") {
    val eqAlg = eqTestAlg[Result]
    val erase = WeaveArrows.codomainTarget[Result, Render, Render]
    outcomes.foreach { outcome =>
      val impl = new EitherTestAlg(outcome)
      assert(
        eqAlg.eqv(
          derived.intercept(impl)(erase, OnRaise.noop[Result, Render]),
          reference.intercept(impl)(erase, OnRaise.noop[Result, Render])
        ),
        s"intercept under the forgetful interpreter differs for eOutcome $outcome"
      )
    }
  }
  ```

- `EdgeCaseDerivationSpec.scala` — `woven` becomes an instrumented `EdgeAlg`
  plus its recorder; each `WeaveRenderer.render(woven.m(…))` becomes a call
  followed by `WeaveRenderer.render(recorder.weaves.last.weave)`; and
  `derived.mapK(woven)(eraseWeave)` in "the inherited capability is
  transported" becomes a direct assertion on the instrumented algebra's result,
  which is what that test was checking through two hops.
- `MethodLocalInstanceSpec.scala` — the largest file (408 lines) and the most
  mechanical: every fixture's structural check reads the recorder instead of
  the returned weave. **The method-local `Err` cases get stronger here**: the
  hook now receives the resolved `Err[E]`, so a test can assert the rendering
  rather than only that the derivation compiled. Add that assertion where the
  file currently notes the gap.
- `DerivedConservativeExtensionSpec.scala` — the same L9 transposition as
  Task 3 Step 10, including the fourth test (derived versus the hand-written
  `PlainAlgReference`), which becomes a comparison of two recorders'
  renderings.

- [ ] **Step 6: Run the Scala 2 macro suites**

```bash
sbt "++2.13.18 raiseAspectMacrosJVM/test" && sbt "++2.12.21 raiseAspectMacrosJVM/test"
```

Expected: PASS on both. If the differential oracle disagrees, the macro is
generating something the reference does not, and **the reference is the
specification** — fix the macro, not `TestAlgReference`.

- [ ] **Step 7: Confirm `ExpectedWeaves.expected` was not touched**

```bash
git diff raise-aspect-macros/src/test/scala/com/dwolla/tagless/mtl/laws/ExpectedWeaves.scala
```

Expected: the diff shows changes to `rendered` and its scaladoc only. Any line
inside `val expected` means woven structure changed, which violates a global
constraint — stop and report.

- [ ] **Step 8: Confirm the diagnostics are untouched**

```bash
git diff --stat raise-aspect-macros/src/test/scala-2/com/dwolla/tagless/mtl/laws/DerivationErrorSpec.scala
```

Expected: empty output. Every rejection — `Handle`, effectful parameter,
`F[F[A]]`, buried `F`, capability without an `F[?]` return, missing
`Dom`/`Cod`/`Err`, the non-implicit-parameter hint, the method-local ambiguity,
the alias-dealiasing success — passes unchanged against the fused entry point.
That was demonstrated by the spike; this step is the check.

- [ ] **Step 9: Commit**

```bash
git add raise-aspect-macros/src/main/scala-2/ raise-aspect-macros/src/test/scala-2/ \
        raise-aspect-macros/src/test/scala/
git commit -m "feat!: generate the fused intercept in the Scala 2 derivation

raiseWeave becomes raiseInstrument: the generated method keeps the algebra's own
parameter lists and return type and simply hands its weave to fk, so
substituteCapabilities and Method#transformedParamLists have no caller and are
deleted. Every rejection diagnostic is unchanged."
```

---

## Task 5: Scala 3 derivation

`deriveWeave` becomes `deriveInstrument`. Three edits to the existing generator:
`Carrier` is `TypeRepr.of[F]` rather than the woven type lambda; the `args`
handler swaps the pull for `RaiseAspect.observing`; and the `body` handler wraps
its final expression in `fk.apply[t](…)`. `paramAdvice` is verbatim.

Two questions the design flagged were answered by the spike and do not need
re-litigating: `transformTo[Alg[F]]` with source and target carrier identical is
fine (nothing in `overridableMembers`/`newClassOf` depends on retyping, and the
body predicate `tpe.typeSymbol == Carrier.typeSymbol` is exactly `deriveMapK`'s
read at `G = F`); and the `body` handler can build the weave and apply `fk` in
one pass, which is a strictly *smaller* demand than `deriveWeave`'s, because
that one had to return a term of a different type than the delegate.

**Files:**
- Modify: `raise-aspect-macros/src/main/scala-3/com/dwolla/tagless/mtl/DeriveRaiseMacros.scala` (`aspect` at `:401`, `deriveWeave` at `:417`)
- Modify: `raise-aspect-macros/src/main/scala-3/com/dwolla/tagless/mtl/DeriveRaise.scala` (scaladoc only)
- Modify: `raise-aspect-macros/src/test/scala-3/com/dwolla/tagless/mtl/laws/DifferentialOracleSpec.scala`
- Modify: `raise-aspect-macros/src/test/scala-3/com/dwolla/tagless/mtl/laws/EdgeCaseDerivationSpec.scala`
- Modify: `raise-aspect-macros/src/test/scala-3/com/dwolla/tagless/mtl/laws/MethodLocalInstanceSpec.scala`
- Modify: `raise-aspect-macros/src/test/scala-3/com/dwolla/tagless/mtl/laws/DerivedConservativeExtensionSpec.scala`
- Modify: `raise-aspect-macros/src/test/scala-3/com/dwolla/tagless/mtl/laws/UsingAlgSpec.scala`
- Create: `raise-aspect-macros/src/test/scala-3/com/dwolla/tagless/mtl/laws/CrossVersionAgreementSpec.scala` **only if it does not already exist** — check first; Task 4's shared `ExpectedWeaves.rendered` is what both axes assert against.
- Unchanged: `DerivedRaiseAspectSpec.scala`, `DerivationErrorSpec.scala`

**Interfaces:**
- Consumes: Tasks 1–4 (Task 4 changed the shared `ExpectedWeaves.rendered`).
- Produces: the same two entry points as Task 4, `@experimental`-annotated.

`UsingAlgSpec` is the one that matters most here: its `m` method takes
`(using R1: Raise[F, ErrA])(using R2: Raise[F, ErrB])` — two capability clauses
with different error types on one method. If the decoration is wired to one
capability rather than per parameter, this is what catches it.

- [ ] **Step 1: Write the failing test**

In the Scala 3 `DifferentialOracleSpec.scala`, change the first test to the
fused comparison (same body as Task 4 Step 5's, with `using`-style call sites
where the file already uses them).

- [ ] **Step 2: Run to verify it fails**

```bash
sbt "++3.3.8 raiseAspectMacrosJVM/test"
```

Expected: compile failure — the derived instance has `weave`, not `intercept`.

- [ ] **Step 3: Change the Scala 3 entry point**

```scala
  def aspect[Alg[_[_]]: Type, Dom[_]: Type, Cod[_]: Type, Err[_]: Type](using Quotes)
      : Expr[RaiseAspect[Alg, Dom, Cod, Err]] = '{
    new RaiseAspect[Alg, Dom, Cod, Err]:
      def intercept[F[_]](af: Alg[F])(
          fk: FunctionK[[X] =>> Aspect.Weave[F, Dom, Cod, X], F],
          onRaise: OnRaise[F, Err]
      )(implicit F: Apply[F]): Alg[F] =
        ${ deriveInstrument[Alg, Dom, Cod, Err, F]('af, 'fk, 'onRaise, 'F) }

      def mapK[F[_], G[_]](af: Alg[F])(arrow: RaiseArrow[F, G, Err]): Alg[G] =
        ${ deriveMapK[Alg, F, G, Err]('af, 'arrow) }
  }
```

Inside the quote the `fk` parameter must be spelled
`FunctionK[[X] =>> Aspect.Weave[F, Dom, Cod, X], F]` rather than with `~>` and
`*`; that is cosmetic, and the public trait in `raise-aspect-core` keeps
`Aspect.Weave[F, Dom, Cod, *] ~> F` and compiles on all three versions. Add
`cats.Apply` and `cats.arrow.FunctionK` to the file's imports (it currently
imports `cats.{Eval, Functor}`); drop `Functor` if nothing else needs it.

- [ ] **Step 4: Replace `deriveWeave` with `deriveInstrument`**

```scala
  private def deriveInstrument[Alg[_[_]]: Type, Dom[_]: Type, Cod[_]: Type, Err[_]: Type, F[_]: Type](
      alg: Expr[Alg[F]],
      fk: Expr[FunctionK[[X] =>> Aspect.Weave[F, Dom, Cod, X], F]],
      onRaise: Expr[OnRaise[F, Err]],
      apply: Expr[Apply[F]]
  )(using q: Quotes): Expr[Alg[F]] =
    import quotes.reflect.*
    val macros = new DeriveRaiseMacros[q.type]
    import macros.*

    // The carrier never changes: source and target of the transform are both
    // `Alg[F]`, so this is the same `TypeRepr` the parameters already mention.
    val Carrier = TypeRepr.of[F]
    val Alg = TypeRepr.of[Alg]
    val algebraName = Expr(Alg.classSymbol.getOrElse(Alg.typeSymbol).name)

    macros.validate(TypeRepr.of[Alg[F]], TypeRepr.of[F], "RaiseAspect")

    def paramAdvice(param: ValDef)(using Quotes): Expr[Seq[Aspect.Advice[Eval, Dom]]] =
      val tpe = param.tpt.tpe
      tpe.widenParam.asType match
        case '[t] =>
          val name = Expr(param.name)
          val value = Ref(param.symbol)
          val dom = macros
            .summonOrAbort(
              TypeRepr.of[Dom].appliedTo(tpe.widenParam),
              s"for parameter ${param.name}",
              // the enclosing generated method, whose given parameters the advice may reference
              param.symbol.owner
            )
            .asExprOf[Dom[t]]
          if tpe.isByName then '{ Aspect.Advice.byName($name, ${ value.asExprOf[t] })(using $dom) :: Nil }
          else if tpe.isRepeated then '{ ${ value.asExprOf[Seq[t]] }.map(Aspect.Advice.byValue($name, _)(using $dom)) }
          else '{ Aspect.Advice.byValue($name, ${ value.asExprOf[t] })(using $dom) :: Nil }

    alg.transformTo[Alg[F]](
      args = {
        case (methodSym, tpe, arg) if macros.capabilityError(tpe, Carrier).isDefined =>
          tpe.dealias.typeArgs.last.asType match
            case '[e] =>
              val errEv = macros
                .summonErrOrAbort(TypeRepr.of[Err], tpe.dealias.typeArgs.last, methodSym)
                .asExprOf[Err[e]]
              '{
                RaiseAspect.observing[F, e, Err](${ arg.asExprOf[Raise[F, e]] }, $onRaise)(using $apply, $errEv)
              }.asTerm
      },
      body = {
        case (sym, tpe, body) if tpe.typeSymbol == Carrier.typeSymbol =>
          val clauses = sym.tree match
            case method: DefDef => method.termParamss.filterNot(c => c.isGiven || c.isImplicit)
            case _ => Nil

          tpe.typeArgs.last.asType match
            case '[t] =>
              given Quotes = sym.asQuotes
              val methodName = Expr(sym.name)
              val cod = macros
                .summonOrAbort(
                  TypeRepr.of[Cod].appliedTo(tpe.typeArgs.last),
                  s"for the result of method ${sym.name}",
                  sym
                )
                .asExprOf[Cod[t]]
              val domain = Expr.ofList(clauses.map { c =>
                val kept = c.params.collect {
                  case p: ValDef if macros.capabilityError(p.tpt.tpe.widenParam, Carrier).isEmpty => p
                }
                '{ List.concat(${ Varargs(kept.map(paramAdvice)) }*) }
              })
              val codomain = '{ Aspect.Advice($methodName, ${ body.asExprOf[F[t]] })(using $cod) }
              '{ $fk.apply[t](Aspect.Weave[F, Dom, Cod, t]($algebraName, $domain, $codomain)) }.asTerm
      }
    )
```

`deriveMapK` is untouched. So is `validate` — including its rejection of `F` in
a parameter position, which stays because the shared derivation still emits
`mapK` (decision D2).

- [ ] **Step 5: Update the Scala 3 macro tests**

The same mechanical transposition as Task 4 Step 5, adjusted for `using`
syntax and `@experimental`, plus:

- `UsingAlgSpec` — after intercepting, assert that a raise through `R1` and a
  raise through `R2` each reach the hook rendered through *their own* `Err`
  instance (`errA:` / `errB:` prefixes). This is newly observable: before M12
  the two capabilities were transported and the evidence was invisible from the
  test's side.
- Scala 3's `EdgeAlg` has a `val constant: F[String]` member. It fuses, but its
  `fk(Weave(…))` runs when the instrumented algebra is *constructed* rather than
  on access. Today's path is eager in the same way, so nothing changes — but a
  recorder-based test will see that weave already present before the `val` is
  read. Assert that deliberately rather than being surprised by it.

- [ ] **Step 6: Run the Scala 3 macro suite**

```bash
sbt "++3.3.8 raiseAspectMacrosJVM/test"
```

Expected: PASS, including `UsingAlgSpec` and the differential oracle.

- [ ] **Step 7: Run the whole cross-build so far**

```bash
sbt "+raiseAspectCoreJVM/test" "+raiseAspectLawsJVM/test" "+raiseAspectMacrosJVM/test"
```

Expected: green on all three versions, with `CrossVersionAgreementSpec` on each
axis reproducing `ExpectedWeaves.expected`.

- [ ] **Step 8: Commit**

```bash
git add raise-aspect-macros/src/main/scala-3/ raise-aspect-macros/src/test/scala-3/
git commit -m "feat!: generate the fused intercept in the Scala 3 derivation

deriveWeave becomes deriveInstrument: the carrier is TypeRepr.of[F] rather than
the woven type lambda, the args handler decorates the caller's Raise in place,
and the body handler hands its weave to fk. One derivation emits intercept and
mapK; every rejection diagnostic is unchanged."
```

---

## Task 6: differential-oracle audit

**No production code in this task.** It is the review gate decision D7 calls
for. The differential oracle is the single largest chunk of test rework in this
milestone and the easiest place to quietly lose coverage: the pre-M12 version
compared `Alg[Weave[…]]` values field by field, and the post-M12 version
compares what an interpreter received. Those are different observations, and
"it still passes" is not evidence that it still checks the same things.

Run this task with a fresh reading of the pre-M12 files from git history, not
from memory of Tasks 4 and 5.

**Files:**
- Modify (assertions only): `raise-aspect-macros/src/test/scala-2/com/dwolla/tagless/mtl/laws/DifferentialOracleSpec.scala`
- Modify (assertions only): `raise-aspect-macros/src/test/scala-3/com/dwolla/tagless/mtl/laws/DifferentialOracleSpec.scala`
- Modify (assertions only): both `CrossVersionAgreementSpec.scala`

**Interfaces:**
- Consumes: Tasks 4 and 5. Produces no new API.

- [ ] **Step 1: Build the inventory**

```bash
git show HEAD~3:raise-aspect-macros/src/test/scala-2/com/dwolla/tagless/mtl/laws/DifferentialOracleSpec.scala > /tmp/oracle-before-s2.scala
git show HEAD~2:raise-aspect-macros/src/test/scala-3/com/dwolla/tagless/mtl/laws/DifferentialOracleSpec.scala > /tmp/oracle-before-s3.scala
```

(adjust the revisions to the commits before Tasks 4 and 5). Write out, in the
task's report, a table with one row per assertion in the *old* files and the
assertion in the new file that subsumes it. The pre-M12 oracle asserted, per
method and per sample: equal `RenderedWeave` (algebra name, method name, domain
names, domain rendered values) and `Eq`-equal `codomain.target`; plus `mapK`
agreement under the identity arrow, under the erasure arrow, and between
`aspect`'s `mapK` and `functorK`'s.

- [ ] **Step 2: Check the four things a recording `fk` can lose**

For each, either point at the assertion that covers it or add one:

1. **Per-sample domain rendering.** The old oracle rendered a weave for *every*
   input in the exhaustive domains. A recorder-based test that only compares the
   final list still does — as long as the calls are driven over the same
   domains. Confirm the loops are intact and that
   `LawsInstances.renderedWeaves` is compared, not just method results.
2. **The codomain target.** The old oracle compared `d.codomain.target` against
   `r.codomain.target`. Under fusion the fk *returns* that value, so comparing
   the two instrumented algebras' results is the same comparison — but only if
   the interpreter is the forgetful one. Confirm the recorder forwards
   `codomain.target` unchanged and that the results are compared, not discarded.
3. **The `Cod` instance on the codomain.** Neither the old nor the new oracle
   compares it (`WeaveRenderer` deliberately renders only the domain). Say so
   explicitly in the report rather than leaving it as an unexamined gap; it is
   the field whose mishandling caused M12, and after M12 it is summoned at the
   derivation site and never rebuilt.
4. **Ordering and hook interleaving.** This is new. `RecordingFk.events`
   records weaves and hook firings in one buffer. Assert `dRec.events ==
   rRec.events` for every outcome — the derived instance and the reference must
   agree on *when* things happen, not only on what they produce. Do not
   hardcode an expected order; compare the two computed logs.

- [ ] **Step 3: Add cross-axis agreement on the log**

`CrossVersionAgreementSpec` compares each axis against
`ExpectedWeaves.expected`, which pins structure. Add to `ExpectedWeaves` a
second shared constant pinning the *arrival order* of the five weaves, and
assert it on both axes:

```scala
  /** The order in which the five calls above reach the interpreter. Weaves are
    * handed over one per call, so this is call order — but it is worth pinning,
    * because it is the property the pre-M12 oracle could not see at all and the
    * one a mis-fused generator (building all weaves eagerly, say) would break.
    */
  val expectedOrder: List[String] = List(
    "weave:TestAlg.a",
    "weave:TestAlg.b",
    "weave:TestAlg.c",
    "weave:TestAlg.d",
    "weave:TestAlg.e"
  )
```

with `assertEquals(recorder.events, ExpectedWeaves.expectedOrder)` in each
axis's `CrossVersionAgreementSpec`. This is additive: `expected` is untouched.

- [ ] **Step 4: Run both axes**

```bash
sbt "++2.13.18 raiseAspectMacrosJVM/test" && \
sbt "++2.12.21 raiseAspectMacrosJVM/test" && \
sbt "++3.3.8 raiseAspectMacrosJVM/test"
```

Expected: PASS on all three.

- [ ] **Step 5: Report and commit**

The commit message carries the audit's conclusion, because that is the artifact
a future reader needs:

```bash
git add raise-aspect-macros/src/test/
git commit -m "test: audit the differential oracle across the fused rewrite

Every assertion the pre-M12 oracle made has a successor: rendered weave
structure per sample, codomain targets (now the instrumented method's own
result), and the three mapK agreements. Two additions the value-level oracle
could not express: derived and reference must agree on the order weaves reach
the interpreter and on how hook firings interleave with them, and both axes now
pin that order against a shared constant. The codomain Cod instance is compared
by nothing, then or now — it is summoned at the derivation site and never
rebuilt, which is what M12 changed."
```

---

## Task 7: `natchez-tagless-mtl`

The payoff, and the proof that none of this is user-visible.
`syntheticTraceableValue` and its thirty-line caveat delete;
`traceWithInputs`/`traceWithInputsAndOutputs` keep their exact signatures; the
integration suites keep their exact expected command histories.

**Files:**
- Modify: `natchez-tagless-mtl/src/main/scala/com/dwolla/tracing/mtl/syntax/RaiseTraceWeaveOps.scala`
- Delete: `natchez-tagless-mtl/src/test/scala/com/dwolla/tracing/mtl/SyntheticTraceableValueSpec.scala`
- Modify: `natchez-tagless-mtl/src/test/scala/com/dwolla/tracing/mtl/syntax/AspectPriorityFixtures.scala`
- Modify: `natchez-tagless-mtl/src/main/scala/com/dwolla/tracing/mtl/package.scala` (only if it mentions `Synthetic` or `weave` — check)
- Unchanged and load-bearing: `RaiseRecorder.scala`, `RaiseTraceIntegrationSuite.scala`, `RaiseTraceValueSuite.scala`, both `RaiseTraceIntegrationSpec.scala`, `RaiseRecorderPriorityFixtures.scala`, `RaiseRecorderPrioritySpec.scala`, `AspectPrioritySpec.scala`, `Scala3UsageNote.scala`

**Interfaces:**
- Consumes: Tasks 1–6.
- Produces: `RaiseTraceWeaveOps#traceWithInputs[Cod]` and
  `#traceWithInputsAndOutputs` with **byte-identical signatures**;
  `ToRaiseTraceWeaveOps` with only the implicit conversion left on it.

- [ ] **Step 1: Write the failing test**

There is no new behaviour to test, so the test is the *absence* of the
extension point. Add to `AspectPrioritySpec.scala` (which already imports the
syntax package):

```scala
  test("the syntax package no longer supplies a Synthetic instance") {
    assert(
      compileErrors("com.dwolla.tagless.mtl.Synthetic").nonEmpty,
      "Synthetic must not exist: the fused derivation never places a capability on the woven carrier"
    )
  }
```

- [ ] **Step 2: Run to verify it fails**

```bash
sbt "natchezTaglessMtlJVM/test"
```

Expected: compile failure of the whole module — `AspectPriorityFixtures`
overrides `weave`, which no longer exists on `RaiseAspect`. The new test cannot
even run yet; that failure is the expected red.

- [ ] **Step 3: Delete the synthetic instance and its caveat**

In `RaiseTraceWeaveOps.scala`, delete `syntheticTraceableValue` and the whole
scaladoc block above it, leaving:

```scala
trait ToRaiseTraceWeaveOps {
  implicit def toRaiseTraceWeaveOps[Alg[_[_]], F[_]](alg: Alg[F]): RaiseTraceWeaveOps[Alg, F] =
    new RaiseTraceWeaveOps(alg)
}
```

and drop `Synthetic` and `TraceValue` from the imports (`TraceValue` was there
only for the sentinel). `RaiseTraceWeaveOps`' two methods are **not edited** —
confirm with `git diff` that the class body is unchanged.

Delete the spec:

```bash
git rm natchez-tagless-mtl/src/test/scala/com/dwolla/tracing/mtl/SyntheticTraceableValueSpec.scala
```

- [ ] **Step 4: Transpose the poison fixture**

In `AspectPriorityFixtures.scala`, `fooRaiseAspectPoison`'s `weave` becomes:

```scala
      def intercept[F[_]](af: Foo[F])(
          fk: Weave[F, TraceableValue, TraceableValue, *] ~> F,
          onRaise: OnRaise[F, TraceableValue]
      )(implicit F: Apply[F]): Foo[F] =
        throw new AssertionError("priority resolved to RaiseAspect instead of Aspect")
```

with `cats.Apply` imported in place of `cats.Functor` and
`com.dwolla.tagless.mtl.OnRaise` added to the existing import from that
package. `fooAspect` and `mapK` are untouched — the whole point of the fixture
is that the `Aspect` path still wins, and that property does not change.

- [ ] **Step 5: Run the natchez suite**

```bash
sbt "+natchezTaglessMtlJVM/test"
```

Expected: PASS on all three versions, with **no edits to any expected command
history**. `RaiseTraceIntegrationSuite` asserts, on the raise path, the input
`Put`, then
`Put(raise.error.type → …BarError$Negative, raise.error.value → "negative:-1")`,
then the `Submarine` `AttachError`, then release — all unchanged. The natchez
spike reproduced exactly these histories through the fused path with no
`Synthetic` in scope. If any of them needs editing, stop and report: that would
mean fusion changed observable tracing behaviour, which it must not.

While you are here, fix the stale comment at
`RaiseTraceIntegrationSuite.scala:107`, which explains the sequencing by
reference to `WeaveArrows.raiseLift(onRaise)`. It is now
`RaiseAspect.observing`.

- [ ] **Step 6: Verify the JS linker and the scaladoc**

```bash
sbt "+natchezTaglessMtlJS/Test/scalaJSLinkerResult" "+natchezTaglessMtlJVM/doc"
```

Expected: both succeed. The package object's worked example is a doctest, so a
stale snippet there fails the previous step rather than this one.

- [ ] **Step 7: Commit**

```bash
git add natchez-tagless-mtl/
git commit -m "feat!: drop Synthetic[TraceableValue] from the tracing syntax

The fused intercept never needs a Cod instance for a value that does not
exist, so the sentinel instance and the caveat explaining why it had to be a
constant both go. traceWithInputs and traceWithInputsAndOutputs keep their exact
signatures and the integration suites keep their exact span histories."
```

---

## Task 8: documentation

The overview actively states things this milestone makes false, and the
unlawful-`Functor` finding has just lost both of its homes in scaladoc. Leaving
either is worse than not having written them.

**Files:**
- Modify: `docs/plans/raise-aspect/01-overview-design-and-laws.md` §3.2, §3.3, §3.4, §4
- Modify: `docs/plans/raise-aspect/22-milestone-M12-fused-derivation.md` (status only)
- Modify: `docs/plans/raise-aspect/18-milestone-M8-capability-aspect.md` (a note, not a rewrite)

**Interfaces:**
- Consumes: Tasks 1–7. No code changes.

- [ ] **Step 1: Amend the overview's design sections**

`01-overview-design-and-laws.md` is the living design spec, so amend in place
and date the note, exactly as M10 did:

```markdown
> **Amended 2026-07-31 by M12.** `RaiseAspect`'s two operations are fused into
> one: `intercept[F](af)(fk, onRaise)(implicit F: Apply[F]): Alg[F]`.
> `Aspect.Weave` is no longer an effect type — it is data handed to `fk` — so
> no method receives a `Raise[Weave[F, Dom, Cod, *], E]`, nothing synthesizes a
> `Functor` for the woven carrier, and `Synthetic` is deleted. `RaiseFunctorK`,
> `RaiseArrow` and `RaisePull` are unchanged; `mapK` still does real transport.
> See `22-milestone-M12-fused-derivation.md`.
```

§3.3 ("Canonical arrows and synthetic instances") loses its synthetic half
entirely — `codomainTarget` is the only arrow left — and should say what
replaced it (`RaiseAspect.observing`, at the same carrier) rather than merely
deleting the paragraphs. §3.4's expansion specification must show the fused
expansion; take it from `TestAlgReference` after Task 1, which is the
specification both macros are held to.

- [ ] **Step 2: Amend §4, the law list**

- L3 becomes L3′ with its new statement and its `Applicative` constraint.
- L5, L6 and L7 are struck through with a one-line reason each, naming the
  function whose deletion justifies it. Do not silently remove them: the
  numbering is referenced from five other documents, and a reader who finds
  L4 followed by L8 needs to know why.
- L8 gains a sentence saying it is now asserted through a recording
  interpreter and additionally pins arrival order.
- L1/L2 gain a sentence naming their new non-identity arrow.

- [ ] **Step 3: Point at the durable home for the finding**

In §3.3, where the synthesized functor used to be specified, add:

```markdown
The synthesized `Functor[Aspect.Weave[F, Dom, Cod, *]]` that this section used
to specify was measured to fail the functor identity law, and the failure was
reachable through `Raise#functor` on a **successful** call — silently replacing
a real `Cod` rendering with a fabricated one, and, with a user-authored
rendering `Synthetic`, defeating a deliberate redaction. The measurements, the
blast radius and the proof that no lawful implementation exists for a
negative-only `Cod` are recorded in `22-milestone-M12-fused-derivation.md`.
That document is the only remaining home for the finding: the scaladoc that
carried it went with the code.
```

- [ ] **Step 4: Note the consequence for M8**

Append to `18-milestone-M8-capability-aspect.md`'s status section — a note, not
a reopening:

```markdown
### M12 changes what a resumed M8 would be deciding (2026-07-31)

The fused derivation means no capability is transported into a method, so the
producing-vs-consuming classification no longer governs which capabilities may
appear as method parameters — `Handle`, `Local`, `Listen`, `Censor` and
`Stateful` were all demonstrated working, requiring only `Apply[F]`. The
classification still governs `mapK`, which keeps real transport, so §1.1's
research does not expire; it relocates.

M12 deliberately kept `Raise`-only recognition and every rejection diagnostic.
Admitting more is still this milestone's decision and it is still paused. Two
hazards recorded in the fused-derivation spike would need answering first:
decorating a `Handle` with `RaiseAspect.observing` silently downcasts it to
`Raise` and drops `handleWith`, and the hook would fire for raises that never
escape.
```

- [ ] **Step 5: Update M12's status**

In `22-milestone-M12-fused-derivation.md`, replace the Status section's
"Planned, not started" with the outcome, following the shape M6 and M7 use:
what landed, what diverged from this plan and why, the verification actually
run with counts, and anything found along the way that a later milestone needs
to know.

- [ ] **Step 6: Full cross-build verification**

```bash
sbt "+test"
```

Expected: every module green on 2.12.21, 2.13.18 and 3.3.8, with zero warnings.

```bash
sbt "+raiseAspectCoreJS/Test/scalaJSLinkerResult" \
    "+raiseAspectLawsJS/Test/scalaJSLinkerResult" \
    "+raiseAspectMacrosJS/Test/scalaJSLinkerResult" \
    "+natchezTaglessMtlJS/Test/scalaJSLinkerResult"
```

Expected: all green. (Test execution on JS remains uncovered locally — no Node
— consistent with every prior milestone.)

- [ ] **Step 7: Commit**

```bash
git add docs/plans/raise-aspect/
git commit -m "docs: record the fused derivation and where the Functor finding now lives"
```

---

## Acceptance criteria

- [ ] `RaiseAspect` has exactly one weaving operation, `intercept`; `weave`
      does not exist anywhere in the tree.
- [ ] `Synthetic` does not exist; `WeaveArrows` contains `codomainTarget` and
      nothing else. `substituteCapabilities` and `transformedParamLists` **survive**, with
      `raiseMapK` as their sole caller — see the correction note below.
- [ ] `grep -rn "new Functor" raise-aspect-*/src/main` returns nothing — no
      `Functor` is constructed anywhere in the library; the only ones in play
      are read off capabilities the caller supplied.
- [ ] `git diff` on `ExpectedWeaves.scala` touches `rendered` and the additive
      `expectedOrder`, and no line inside `val expected`.
- [ ] Both `DerivationErrorSpec`s pass **unedited** on 2.12, 2.13 and 3.
- [ ] The natchez integration suites pass with their current expected command
      histories, unedited.
- [ ] The laws suite passes at both `Err = Trivial` and `Err = Render`; L1/L2
      are exercised over `CarrierArrows.resultToLazily`, not only at identity.
- [ ] Task 6's audit table exists in the milestone report, with a successor
      named for every pre-M12 oracle assertion.
- [ ] `sbt +test` green on all three versions; all four JS linkers green;
      `natchezTaglessMtlJVM/doc` succeeds; zero new warnings.

## Ground rules reminder

`raise-aspect-laws` may change (decision D6, on M10's D2 precedent), but no
surviving law's `<->` operands may. If a law change requires editing one, STOP
and report — that is an overview-level question for Brian, not a
milestone-level fix.

If implementing this reveals that the fused derivation changes observable
behaviour anywhere — a span history, a diagnostic, a rendered weave — STOP.
Three spikes demonstrated that it does not, on all three Scala versions and end
to end through natchez. A disagreement means either the transposition is wrong
or a spike claim was narrower than it read, and both are findings worth more
than a quick fix.
