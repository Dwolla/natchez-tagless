# Milestone M11 — a backend-agnostic weave interpreter

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collapse `WithInputsAndOutputsTracer`/`WithInputsTracer` and their
two `LowPriority` partners into one `WeaveInterpreter` typeclass in
`raise-aspect-core` that hard-codes neither `Dom`/`Cod`/`Err` nor the
`Weave ~> F` interpreter, so a future otel4s module reuses it unchanged.

**Architecture:** The four declarations in `TraceWeaveTracer.scala` answer one
question — does this algebra have an `Aspect` or only a `RaiseAspect`? — and
each answers it twice while hard-coding an interpreter. Lift the interpreter
and the type classes into parameters and the two typeclasses become one. The
tracing-specific parts (`Trace`, `TraceableValue`, `RaiseRecorder`) move to the
syntax methods, which keep exactly the signatures they have today.

**Tech Stack:** Scala 2.12 / 2.13 / 3.3 LTS cross-build, cats, cats-mtl,
cats-tagless, natchez, MUnit, sbt.

**Prerequisite: M10 must be merged.** `WeaveInterpreter`'s signature mentions
`Err` and `OnRaise[F, Err]`. Building it first means writing the file twice.

Read `03-evidence-carrying-transport-design.md` Part B first.

## Global Constraints

- Cross-compiles on 2.12.21, 2.13.18, and 3.3.8.
- No new dependencies. **`raise-aspect-core` must not gain a natchez
  dependency** — that is the whole point of this milestone, and the Task 3
  check enforces it mechanically.
- Zero new compiler warnings; the build runs `-Xfatal-warnings`.
- No compatibility shims (design decision D7). The old typeclasses are
  deleted, not deprecated.
- The two syntax methods keep the exact signatures `core`'s
  `com.dwolla.tracing.syntax.TraceWeaveOps` has: `traceWithInputs[Cod]` with a
  free `Cod`, `traceWithInputsAndOutputs` with none (decisions D3 and D6).
- Never use `--no-verify` or any other hook-bypass flag.

**One deviation from the ratified design, found while planning.** §B.2 of the
design document gives `fromAspect` a `Functor[F]` constraint. It does not need
one: `Aspect.weave[F[_]](af: Alg[F]): Alg[Weave[F, Dom, Cod, *]]`
(`reference/upstream/core/src/main/scala/cats/tagless/aop/Aspect.scala:39`)
takes no implicit, and `Aspect.mapK` takes only an `F ~> G`. The plan drops the
constraint. If it turns out to be needed, add it — but do not add it
speculatively.

---

## File Structure

**`raise-aspect-core` main:**

- `WeaveInterpreter.scala` — **new.** The sealed typeclass, its companion with
  the high-priority `Aspect` instance, and the `LowPriorityWeaveInterpreter`
  trait with the `RaiseAspect` instance. One file, because the low-priority
  trait only exists to order the two instances and splitting them would hide
  the mechanism.

**`raise-aspect-core` test:**

- `WeaveInterpreterFixtures.scala` — **new.** `PlainAlg` with both an `Aspect`
  and a poison `RaiseAspect`, mirroring how
  `natchez-tagless-mtl`'s `AspectPriorityFixtures` proves the same property
  today.
- `WeaveInterpreterSpec.scala` — **new.** Priority and behavior.

**`natchez-tagless-mtl` main:**

- `syntax/TraceWeaveTracer.scala` — **deleted.** Its four declarations are
  replaced by `WeaveInterpreter`.
- `syntax/RaiseTraceWeaveOps.scala` — the two methods now resolve a
  `WeaveInterpreter` and supply the interpreter and hook.
- `syntax/RaiseRecorder.scala` — **unchanged.** It stays backend-local; see
  design §B.2 for why it is not collapsed into `OnRaise`.

---

## Task 1: `WeaveInterpreter` in `raise-aspect-core`

**Files:**
- Create: `raise-aspect-core/src/main/scala/com/dwolla/tagless/mtl/WeaveInterpreter.scala`
- Create: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/WeaveInterpreterFixtures.scala`
- Create: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/WeaveInterpreterSpec.scala`

**Interfaces:**
- Consumes: `RaiseAspect[Alg, Dom, Cod, Err]`, `RaiseArrow[F, G, Err]`,
  `OnRaise[F, Err]`, `Synthetic[Cod]`, `WeaveArrows.raiseLift` — all from M10.
- Produces:
  - `sealed trait WeaveInterpreter[Alg[_[_]], Dom[_], Cod[_], Err[_], F[_]] { def apply(alg: Alg[F])(fk: Aspect.Weave[F, Dom, Cod, *] ~> F, onRaise: OnRaise[F, Err]): Alg[F] }`
  - `WeaveInterpreter.apply[Alg[_[_]], Dom[_], Cod[_], Err[_], F[_]](implicit ev): WeaveInterpreter[Alg, Dom, Cod, Err, F]` — the summoner
  - `WeaveInterpreter.fromAspect[Alg[_[_]], Dom[_], Cod[_], Err[_], F[_]](implicit A: Aspect[Alg, Dom, Cod])`
  - `LowPriorityWeaveInterpreter.fromRaiseAspect[Alg[_[_]], Dom[_], Cod[_], Err[_], F[_]](implicit F: Apply[F], A: RaiseAspect[Alg, Dom, Cod, Err], syn: Synthetic[Cod])`

- [ ] **Step 1: Write the fixtures**

Create `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/WeaveInterpreterFixtures.scala`:

```scala
package com.dwolla.tagless.mtl

import cats.Functor
import cats.tagless.aop.Aspect
import cats.tagless.aop.Aspect.Weave
import cats.~>

/** `PlainAlg` has no capability parameters, so it can carry both an `Aspect`
  * and a `RaiseAspect` — the situation `WeaveInterpreter`'s implicit priority
  * exists to resolve. The `RaiseAspect` here is a poison instance: it throws
  * if it is ever invoked, so priority resolving the wrong way fails loudly
  * rather than producing plausible-looking wrong output. Same technique as
  * `natchez-tagless-mtl`'s `AspectPriorityFixtures`.
  */
object WeaveInterpreterFixtures {

  /** The correct instance — the one priority must select. */
  implicit val plainAspect: Aspect[PlainAlg, Render, Render] =
    new Aspect[PlainAlg, Render, Render] {
      def weave[F[_]](af: PlainAlg[F]): PlainAlg[Weave[F, Render, Render, *]] =
        new PlainAlg[Weave[F, Render, Render, *]] {
          def p(i: Int): Weave[F, Render, Render, String] =
            Weave("PlainAlg", List(List(Aspect.Advice.byValue("i", i))), Aspect.Advice("p", af.p(i)))
        }

      def mapK[F[_], G[_]](af: PlainAlg[F])(fk: F ~> G): PlainAlg[G] =
        new PlainAlg[G] {
          def p(i: Int): G[String] = fk(af.p(i))
        }
    }

  implicit val plainRaiseAspectPoison: RaiseAspect[PlainAlg, Render, Render, Render] =
    new RaiseAspect[PlainAlg, Render, Render, Render] {
      def weave[F[_]](af: PlainAlg[F])(implicit F: Functor[F]): PlainAlg[Weave[F, Render, Render, *]] =
        throw new AssertionError("priority resolved to RaiseAspect instead of Aspect")

      def mapK[F[_], G[_]](af: PlainAlg[F])(arrow: RaiseArrow[F, G, Render]): PlainAlg[G] =
        throw new AssertionError("priority resolved to RaiseAspect instead of Aspect")
    }
}
```

- [ ] **Step 2: Write the failing test**

Create `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/WeaveInterpreterSpec.scala`:

```scala
package com.dwolla.tagless.mtl

import cats.mtl.Raise
import cats.tagless.aop.Aspect
import cats.~>
import munit.FunSuite

import scala.collection.mutable.ListBuffer

import TestError._

class WeaveInterpreterSpec extends FunSuite {
  private type F[A] = Either[TestError, A]
  private type W[A] = Aspect.Weave[F, Render, Render, A]

  // The library's own forgetful arrow, rather than a hand-rolled one — the
  // interpreter a caller supplies in production is this shape.
  private val erase: W ~> F = WeaveArrows.codomainTarget[F, Render, Render]

  // `Synthetic`'s companion supplies only `Synthetic[Trivial]`, so `Cod = Render`
  // needs a local instance for `WeaveInterpreter.fromRaiseAspect` to resolve.
  // The module's other specs each define this same private val; match them.
  // (M10's Task 2 lost time to this exact omission in a spec source — the
  // constraint is on the instance, so without it the test fails to compile
  // rather than failing to pass.)
  private implicit val syntheticRender: Synthetic[Render] =
    new Synthetic[Render] {
      def apply[A]: Render[A] = (_: A) => "<synthetic>"
    }

  test("an Aspect instance outranks a RaiseAspect instance for the same algebra") {
    import WeaveInterpreterFixtures._

    val interpreted =
      WeaveInterpreter[PlainAlg, Render, Render, Render, F]
        .apply(EitherPlainAlg)(erase, OnRaise.noop[F, Render])

    // The poison RaiseAspect throws on any use, so reaching a result at all
    // proves the Aspect path ran.
    assertEquals(interpreted.p(2), Right("p:2"))
  }

  test("a RaiseAspect-only algebra resolves to the RaiseAspect instance and runs the hook") {
    implicit val reference: RaiseAspect[TestAlg, Render, Render, Render] =
      TestAlgReference.referenceRaiseAspect[Render, Render, Render]

    val rendered = ListBuffer.empty[String]
    val hook: OnRaise[F, Render] = new OnRaise[F, Render] {
      def apply[E](e: E)(implicit ev: Render[E]): F[Unit] = {
        rendered += ev.render(e)
        Right(())
      }
    }

    val interpreted =
      WeaveInterpreter[TestAlg, Render, Render, Render, F]
        .apply(new EitherTestAlg(0))(erase, hook)

    assertEquals(interpreted.a(3)(Raise[F, ErrA]), Right("a:3"))
    assertEquals(rendered.toList, Nil)

    assertEquals(interpreted.a(-3)(Raise[F, ErrA]), Left(NegativeInput(-3)): F[String])
    assertEquals(rendered.toList, List("errA:NegativeInput(-3)"))
  }
}
```

- [ ] **Step 3: Run to verify it fails**

```bash
sbt "raiseAspectCoreJVM/testOnly com.dwolla.tagless.mtl.WeaveInterpreterSpec"
```

Expected: compile failure — `WeaveInterpreter` does not exist.

- [ ] **Step 4: Write `WeaveInterpreter.scala`**

```scala
package com.dwolla.tagless.mtl

import cats.Apply
import cats.tagless.aop.Aspect
import cats.~>

/** Resolves which of the two available strategies interprets an `Alg[F]`
  * whose methods have been woven: cats-tagless's own `Aspect`, or this
  * library's `RaiseAspect`, when only the latter exists.
  *
  * Both strategies are expressed as instances of this one sealed type class so
  * that the usual low-priority-trait mechanism can order them. Expressing them
  * as two separate conversions would not work: there is no supertype
  * relationship between `Aspect` and `RaiseAspect` to make one win.
  *
  * Nothing here is specific to tracing, or to any particular backend. `Dom`,
  * `Cod`, `Err`, the `Weave ~> F` interpreter, and the `OnRaise` hook are all
  * supplied by the caller, so a natchez module and an otel4s module — which
  * share no rendering type class — use the same instance resolution.
  */
sealed trait WeaveInterpreter[Alg[_[_]], Dom[_], Cod[_], Err[_], F[_]] {
  def apply(alg: Alg[F])(
      fk: Aspect.Weave[F, Dom, Cod, *] ~> F,
      onRaise: OnRaise[F, Err]
  ): Alg[F]
}

object WeaveInterpreter extends LowPriorityWeaveInterpreter {
  def apply[Alg[_[_]], Dom[_], Cod[_], Err[_], F[_]](implicit
      ev: WeaveInterpreter[Alg, Dom, Cod, Err, F]
  ): WeaveInterpreter[Alg, Dom, Cod, Err, F] = ev

  /** Higher priority: the `Aspect`-based strategy wins whenever both an
    * `Aspect` and a `RaiseAspect` exist for the same algebra.
    *
    * `onRaise` is ignored, and that is correct rather than a gap: an algebra
    * with an `Aspect` instance has no `Raise` capability parameters, so the
    * hook's domain is empty and it can never fire. Note the ordering is
    * consistent with this — the only algebras for which this instance
    * outranks [[LowPriorityWeaveInterpreter.fromRaiseAspect]] are exactly the
    * ones with no raise to intercept.
    *
    * No effect constraint: `Aspect.weave` takes no implicit and `Aspect.mapK`
    * takes only a `FunctionK`.
    */
  implicit def fromAspect[Alg[_[_]], Dom[_], Cod[_], Err[_], F[_]](implicit
      A: Aspect[Alg, Dom, Cod]
  ): WeaveInterpreter[Alg, Dom, Cod, Err, F] =
    new WeaveInterpreter[Alg, Dom, Cod, Err, F] {
      def apply(alg: Alg[F])(
          fk: Aspect.Weave[F, Dom, Cod, *] ~> F,
          onRaise: OnRaise[F, Err]
      ): Alg[F] = A.mapK(A.weave(alg))(fk)
    }
}

trait LowPriorityWeaveInterpreter {

  /** Lower priority: used only when no `Aspect` instance is available. Pairs
    * the caller's interpreter with `WeaveArrows.raiseLift`, so the hook runs
    * at the interception point where a raised value crosses back into `F`.
    *
    * `Apply[F]` rather than `Functor[F]` because `raiseLift`'s hook overload
    * sequences the hook's effect before the raise; `Apply` extends `Functor`,
    * which is what `weave` needs.
    */
  implicit def fromRaiseAspect[Alg[_[_]], Dom[_], Cod[_], Err[_], F[_]](implicit
      F: Apply[F],
      A: RaiseAspect[Alg, Dom, Cod, Err],
      syn: Synthetic[Cod]
  ): WeaveInterpreter[Alg, Dom, Cod, Err, F] =
    new WeaveInterpreter[Alg, Dom, Cod, Err, F] {
      def apply(alg: Alg[F])(
          fk: Aspect.Weave[F, Dom, Cod, *] ~> F,
          onRaise: OnRaise[F, Err]
      ): Alg[F] =
        A.mapK(A.weave(alg))(
          RaiseArrow(fk, WeaveArrows.raiseLift[F, Dom, Cod, Err](onRaise))
        )
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
sbt "+raiseAspectCoreJVM/test"
```

Expected: PASS on all three versions, including both new tests and everything
M10 left green.

- [ ] **Step 6: Verify the JS linker**

```bash
sbt "+raiseAspectCoreJS/Test/scalaJSLinkerResult"
```

Expected: success.

- [ ] **Step 7: Commit**

```bash
git add raise-aspect-core/src/main/scala/com/dwolla/tagless/mtl/WeaveInterpreter.scala \
        raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/WeaveInterpreterFixtures.scala \
        raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/WeaveInterpreterSpec.scala
git commit -m "feat: add a backend-agnostic WeaveInterpreter to raise-aspect-core

One sealed type class resolves Aspect-vs-RaiseAspect with the interpreter,
Dom, Cod, Err and the OnRaise hook all supplied by the caller, so no natchez
type appears in the resolution mechanism."
```

---

## Task 2: rewire the natchez syntax

**Files:**
- Delete: `natchez-tagless-mtl/src/main/scala/com/dwolla/tracing/mtl/syntax/TraceWeaveTracer.scala`
- Modify: `natchez-tagless-mtl/src/main/scala/com/dwolla/tracing/mtl/syntax/RaiseTraceWeaveOps.scala`
- Modify: `natchez-tagless-mtl/src/test/scala/com/dwolla/tracing/mtl/syntax/AspectPrioritySpec.scala`
- Modify: `natchez-tagless-mtl/src/main/scala/com/dwolla/tracing/mtl/package.scala`

**Interfaces:**
- Consumes: Task 1's `WeaveInterpreter`; `RaiseRecorder[F, TraceableValue]` from M10.
- Produces: `RaiseTraceWeaveOps#traceWithInputs[Cod]` and
  `#traceWithInputsAndOutputs`, signature-identical to
  `com.dwolla.tracing.syntax.TraceWeaveOps`'s.

`AspectPrioritySpec` and `RaiseRecorderPrioritySpec` are the regression net
here. Both must keep passing **in substance** — `Aspect` still outranks
`RaiseAspect`, a user `OnRaise` still outranks the `Trace` default — with no
weakening of what they assert.

- [ ] **Step 1: Extend the safety net (this test passes before the change)**

This task is a refactor, not a new behavior, so there is no honest red step:
the behavior it must preserve is already asserted. What it needs is a *wider*
net before the change lands. `AspectPrioritySpec` proves priority only through
`traceWithInputsAndOutputs`; `traceWithInputs` goes through a different
typeclass today and the same one afterwards, so pin it too:

```scala
  test("the Aspect path is used by traceWithInputs too, not the poison RaiseAspect instance") {
    val traced = Foo.io.traceWithInputs[TraceableValue]
    traced.foo(3).assertEquals("foo:3")
  }
```

Add `import natchez.TraceableValue` to the spec.

- [ ] **Step 2: Run it and confirm it passes on the *unchanged* code**

```bash
sbt "natchezTaglessMtlJVM/testOnly com.dwolla.tracing.mtl.syntax.AspectPrioritySpec"
```

Expected: PASS. `Foo`'s poison `RaiseAspect` throws on any use, so a green
result proves the old `WithInputsTracer.fromAspect` outranks
`fromRaiseAspect`. That is the property the rest of this task must not break.

If it *fails*, stop: the existing priority ordering is not what the design
assumed, and that is a design-level finding rather than something to fix
inside this task.

- [ ] **Step 3: Rewrite `RaiseTraceWeaveOps.scala`**

```scala
package com.dwolla.tracing.mtl
package syntax

import cats.{Apply, FlatMap}
import com.dwolla.tagless.mtl.WeaveInterpreter
import com.dwolla.tracing.{TraceWeaveCapturingInputs, TraceWeaveCapturingInputsAndOutputs}
import natchez.{Trace, TraceableValue}

/** Mirrors `com.dwolla.tracing.syntax.ToTraceWeaveOps`/`TraceWeaveOps`, but
  * resolves either an `Aspect` or a `RaiseAspect` instance for the algebra via
  * `WeaveInterpreter` — see that type class for why a single sealed type class
  * is what makes one strategy take priority over the other.
  *
  * The signatures are deliberately identical to the non-mtl `TraceWeaveOps`'s.
  * The two syntax packages cannot be imported into the same scope (that
  * reintroduces exactly the ambiguity this module exists to avoid), so
  * switching an import between them must not break call sites.
  *
  * Import `com.dwolla.tracing.mtl.syntax._` in place of
  * `com.dwolla.tracing.syntax._` to get both capabilities under the same call
  * syntax.
  */
trait ToRaiseTraceWeaveOps {
  implicit def toRaiseTraceWeaveOps[Alg[_[_]], F[_]](alg: Alg[F]): RaiseTraceWeaveOps[Alg, F] =
    new RaiseTraceWeaveOps(alg)
}

class RaiseTraceWeaveOps[Alg[_[_]], F[_]](val alg: Alg[F]) extends AnyVal {

  /** `Err` is pinned to `TraceableValue` independently of `Cod`, so opting out
    * of return-value rendering does not silently disable typed error
    * recording.
    */
  def traceWithInputs[Cod[_]](implicit
      F: Apply[F],
      T: Trace[F],
      R: RaiseRecorder[F, TraceableValue],
      ev: WeaveInterpreter[Alg, TraceableValue, Cod, TraceableValue, F]
  ): Alg[F] =
    ev(alg)(TraceWeaveCapturingInputs[F, Cod], R.onRaise)

  def traceWithInputsAndOutputs(implicit
      F: FlatMap[F],
      T: Trace[F],
      R: RaiseRecorder[F, TraceableValue],
      ev: WeaveInterpreter[Alg, TraceableValue, TraceableValue, TraceableValue, F]
  ): Alg[F] =
    ev(alg)(TraceWeaveCapturingInputsAndOutputs[F], R.onRaise)
}
```

Note `Synthetic[Cod]` is no longer named at this level — it is a constraint on
`WeaveInterpreter.fromRaiseAspect` and resolves along with `ev`.

- [ ] **Step 4: Delete `TraceWeaveTracer.scala`**

```bash
git rm natchez-tagless-mtl/src/main/scala/com/dwolla/tracing/mtl/syntax/TraceWeaveTracer.scala
```

All four declarations are replaced by `WeaveInterpreter`. Do not leave a
deprecated alias behind (decision D7).

- [ ] **Step 5: Update the syntax package object if it re-exports the deleted names**

```bash
grep -rn "WithInputsTracer\|WithInputsAndOutputsTracer" natchez-tagless-mtl/ docs/
```

Fix every hit. The scaladoc in
`natchez-tagless-mtl/src/main/scala/com/dwolla/tracing/mtl/package.scala`
names both types in its Submarine section — replace with `WeaveInterpreter`.

- [ ] **Step 6: Run the natchez suite**

```bash
sbt "+natchezTaglessMtlJVM/test"
```

Expected: PASS on all three versions. Specifically confirm:
- `AspectPrioritySpec` — both the original test and the new
  `traceWithInputs` one.
- `RaiseRecorderPrioritySpec` — all three tests, unmodified.
- `RaiseTraceIntegrationSuite` — the span fields, including
  `RaiseRecorder.ErrorValueKey`, are unchanged from M10.

- [ ] **Step 7: Verify the JS linker and scaladoc**

```bash
sbt "+natchezTaglessMtlJS/Test/scalaJSLinkerResult" "+natchezTaglessMtlJVM/doc"
```

Expected: both succeed.

- [ ] **Step 8: Commit**

```bash
git add -A natchez-tagless-mtl/
git commit -m "refactor!: resolve tracing strategy through WeaveInterpreter

Deletes WithInputsTracer, WithInputsAndOutputsTracer and their two LowPriority
partners; the syntax methods now resolve one WeaveInterpreter and supply the
interpreter and the RaiseRecorder's hook. Signatures are unchanged and still
mirror core's TraceWeaveOps."
```

---

## Task 3: prove backend-agnosticism, and document it

The design's acceptance criterion is that no natchez type appears in any
signature in `raise-aspect-core`. That is checkable rather than assertable, so
check it.

**Files:**
- Modify: `docs/plans/raise-aspect/01-overview-design-and-laws.md`
- Modify: `natchez-tagless-mtl/src/main/scala/com/dwolla/tracing/mtl/package.scala`

**Interfaces:**
- Consumes: Tasks 1 and 2. No production code changes.

- [ ] **Step 1: Check for natchez leakage into core**

```bash
grep -rn "natchez" raise-aspect-core/src/main/ ; echo "exit: $?"
```

Expected: no matches (`exit: 1` from grep). A match means a natchez type
reached the backend-agnostic module — stop and report.

Also confirm the module's declared dependencies still exclude natchez:

```bash
grep -n -A 10 "lazy val raiseAspectCore" build.sbt
```

Expected: `cats-core`, `cats-mtl`, `cats-tagless-core` only.

- [ ] **Step 2: Record the otel4s finding**

Add to `01-overview-design-and-laws.md`, in the module-layout section near
line 124, a note recording what a second backend needs:

```markdown
`WeaveInterpreter` (M11) is backend-agnostic: `Dom`, `Cod`, `Err`, the
`Weave ~> F` interpreter, and the `OnRaise` hook are all caller-supplied. A
second backend — otel4s is the motivating case, and shares no rendering type
class with natchez except `cats.tagless.Trivial` — needs only its own
`Weave ~> F` interpreters, its own `Synthetic` instance for its rendering type
class, and its own `RaiseRecorder`-equivalent resolving the default hook. The
resolution mechanism, the arrows, and `OnRaise` are reused unchanged.
```

- [ ] **Step 3: Update the module scaladoc**

In `natchez-tagless-mtl/src/main/scala/com/dwolla/tracing/mtl/package.scala`,
the Submarine section currently says
"`WithInputsAndOutputsTracer`/`WithInputsTracer` resolve a `RaiseRecorder[F]`
and sequence its `OnRaise[F]` hook". Replace with:

```
  * This happens with no action required from the caller: both syntax methods
  * resolve a `RaiseRecorder[F, TraceableValue]` and hand its
  * `OnRaise[F, TraceableValue]` hook to `WeaveInterpreter`, which sequences it
  * at the `raiseLift` interception point — falling back to this `Trace`-based
  * recording whenever no more specific hook is in scope.
```

- [ ] **Step 4: Full cross-build verification**

```bash
sbt "+test"
```

Expected: every module green on 2.12, 2.13, and 3, zero warnings.

- [ ] **Step 5: Commit**

```bash
git add docs/plans/raise-aspect/01-overview-design-and-laws.md \
        natchez-tagless-mtl/src/main/scala/com/dwolla/tracing/mtl/package.scala
git commit -m "docs: record WeaveInterpreter's backend-agnostic contract"
```

---

## Acceptance criteria

From `03-evidence-carrying-transport-design.md` §E, Part B:

- [ ] One `WeaveInterpreter` type class in `raise-aspect-core` replaces all
      four declarations in `TraceWeaveTracer.scala`; that file is deleted.
- [ ] `grep -rn natchez raise-aspect-core/src/main/` returns nothing.
- [ ] `AspectPrioritySpec` and `RaiseRecorderPrioritySpec` pass unmodified in
      substance — `Aspect` still outranks `RaiseAspect`, a user `OnRaise` still
      outranks the `Trace` default.
- [ ] Both syntax methods keep the signatures `core`'s `TraceWeaveOps` has, and
      produce spans identical to M10's.
- [ ] A written note records that the `Dom`/`Cod`/`Err`/interpreter quadruple
      suffices for an otel4s module.
- [ ] `sbt +test` green on all three versions; both JS linkers green;
      `natchezTaglessMtlJVM/doc` succeeds; zero new warnings.

## Ground rules reminder

If `fromAspect` turns out to need an effect constraint after all, add it and
say so — but the vendored `Aspect.scala` says it does not, and adding one
speculatively would put a constraint on every caller for no reason.
