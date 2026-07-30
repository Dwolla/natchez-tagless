# Milestone M10 — evidence-carrying `Raise` transport

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `RaisePull` a per-error-type evidence parameter `Err[_]`, so a
`TraceableValue[E]` reaches the point where a raised error is recorded, and
`RaiseRecorder`'s default stops bypassing `TraceableValue`.

**Architecture:** Transport stays uniform in the error type — `apply[E]` keeps
its type parameter, so a method with two distinct `Raise` parameters still
works. What changes is that every *application* of a pull carries an
`implicit ev: Err[E]`. `Err` is concrete at macro expansion, so both
derivations summon it per raise parameter with the machinery already used for
`Dom`/`Cod`.

**Tech Stack:** Scala 2.12 / 2.13 / 3.3 LTS cross-build, cats, cats-mtl,
cats-tagless, natchez, MUnit + ScalaCheck + discipline, sbt.

Read `03-evidence-carrying-transport-design.md` first — it is the ratified
design this plan implements. Read `01-overview-design-and-laws.md` for the
expansion specification the reference fixtures follow.

## Global Constraints

- Cross-compiles on 2.12.21, 2.13.18, and 3.3.8. Every task's verification
  runs under `+` unless a step says otherwise.
- No new dependencies in any module. `raise-aspect-core` must not gain a
  natchez dependency.
- Zero new compiler warnings. The build runs `-Xfatal-warnings`.
- No compatibility shims, deprecated overloads, or dual code paths
  (design decision D7). One way to do each thing.
- `ExpectedWeaves` (`raise-aspect-macros/src/test/scala/.../ExpectedWeaves.scala`)
  must not need editing. Evidence changes which code compiles, not what a
  woven call produces. If a task appears to require editing it, stop and
  report — something is wrong.
- There is no `scripts/check` in this repo. Canonical verification is the
  per-module sbt invocations named in each task.
- Never use `--no-verify` or any other hook-bypass flag.

**Type parameter order is fixed and load-bearing.** Everywhere all four
appear, the order is `Dom`, `Cod`, `Err`. Everywhere three appear on an arrow
type, it is `[F, G, Err]`. Later tasks depend on earlier tasks' exact
orderings.

---

## File Structure

**`raise-aspect-core` main** — the types themselves:

- `RaiseArrow.scala` — `RaisePull`, `RaiseArrow`. Gains `Err`.
- `RaiseAspect.scala` — `RaiseFunctorK`, `RaiseAspect`. Gains `Err`.
- `OnRaise.scala` — the hook. Gains `Err`; `apply` gains an implicit parameter.
- `WeaveArrows.scala` — all four members gain `Err`.
- `Synthetic.scala` — **unchanged.** It synthesizes a `Cod[A]` for an `A` that
  is never produced; that is a different job from `Err[E]`, which is real
  evidence for a real value.

**`raise-aspect-core` test** — fixtures and unit tests:

- `TestFixtures.scala` — gains three `Render` instances for the error types.
- `TestAlgReference.scala` — the differential oracle. Must mirror exactly what
  the macros will generate, so it changes in Task 2 and the macros follow it.
- `WeaveArrowsSpec.scala`, `WeaveArrowsOnRaiseSpec.scala`, `OnRaiseSpec.scala`,
  `TestAlgReferenceSpec.scala`, `TestAlgReferencePropertySpec.scala` — updated.

**`raise-aspect-laws`** — laws and discipline rule sets, all gaining `Err`.

**`raise-aspect-macros`** — both derivations summon `Err[E]` per raise
parameter and diagnose its absence.

**`natchez-tagless-mtl`** — `RaiseRecorder` renders via `TraceableValue`.

---

## Task 1: `Render` instances for the fixture error types

The law suite instantiates at `Err = Render` (Task 3) and the macro tests
derive at `Err = Render` (Tasks 4–5). Neither can compile without these. The
renderings are deliberately prefixed so a later test can prove the `Render`
instance ran rather than `toString`.

**Files:**
- Modify: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/TestFixtures.scala:30-36`
- Create: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/RenderErrorInstancesSpec.scala`

**Interfaces:**
- Produces: `Render.renderErrA: Render[ErrA]`, `Render.renderErrB: Render[ErrB]`,
  `Render.renderTestError: Render[TestError]`, rendering as `errA:…`,
  `errB:…`, `testError:…` respectively.

- [ ] **Step 1: Write the failing test**

Create `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/RenderErrorInstancesSpec.scala`:

```scala
package com.dwolla.tagless.mtl

import munit.FunSuite

import TestError._

/** Pins the distinctive prefixes the `Err = Render` law and macro
  * instantiations assert against. Without a prefix, a `Render` instance and a
  * bare `toString` are indistinguishable, and a test that meant to prove
  * evidence was used would pass either way.
  */
class RenderErrorInstancesSpec extends FunSuite {
  test("Render[ErrA] prefixes its rendering") {
    assertEquals(Render[ErrA].render(NegativeInput(-3)), "errA:NegativeInput(-3)")
  }

  test("Render[ErrB] prefixes its rendering") {
    assertEquals(Render[ErrB].render(EmptyInput("x")), "errB:EmptyInput(x)")
  }

  test("Render[TestError] prefixes its rendering") {
    assertEquals(Render[TestError].render(NegativeInput(-3)), "testError:NegativeInput(-3)")
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
sbt "raiseAspectCoreJVM/testOnly com.dwolla.tagless.mtl.RenderErrorInstancesSpec"
```

Expected: compile failure — `could not find implicit value for parameter ev: Render[ErrA]`.

- [ ] **Step 3: Add the instances**

In `TestFixtures.scala`, inside `object Render`, after `renderUnit`:

```scala
  /** The error-type instances. `Err = Render` is one of the two
    * instantiations the law suite runs (the other is `Trivial`), and the
    * macro test fixtures derive at `Err = Render`, so all three error types
    * that appear in `TestAlg`'s signatures need an instance. The prefixes
    * make it observable that these instances — rather than `toString` — are
    * what produced a rendering.
    */
  implicit val renderErrA: Render[ErrA] = (a: ErrA) => s"errA:$a"
  implicit val renderErrB: Render[ErrB] = (a: ErrB) => s"errB:$a"
  implicit val renderTestError: Render[TestError] = (a: TestError) => s"testError:$a"
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
sbt "+raiseAspectCoreJVM/testOnly com.dwolla.tagless.mtl.RenderErrorInstancesSpec"
```

Expected: PASS on all three Scala versions.

- [ ] **Step 5: Commit**

```bash
git add raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/TestFixtures.scala \
        raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/RenderErrorInstancesSpec.scala
git commit -m "test: add Render instances for the fixture error types"
```

---

## Task 2: `Err` on the core types

The atomic change. `RaisePull`, `RaiseArrow`, `RaiseFunctorK`, `RaiseAspect`,
`OnRaise`, and all four `WeaveArrows` members change together — each references
the next, so there is no smaller compiling step. The task's deliverable is
`raise-aspect-core` green on all three Scala versions.

**Files:**
- Modify: `raise-aspect-core/src/main/scala/com/dwolla/tagless/mtl/RaiseArrow.scala`
- Modify: `raise-aspect-core/src/main/scala/com/dwolla/tagless/mtl/RaiseAspect.scala`
- Modify: `raise-aspect-core/src/main/scala/com/dwolla/tagless/mtl/OnRaise.scala`
- Modify: `raise-aspect-core/src/main/scala/com/dwolla/tagless/mtl/WeaveArrows.scala`
- Modify: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/TestAlgReference.scala`
- Modify: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/WeaveArrowsSpec.scala`
- Modify: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/WeaveArrowsOnRaiseSpec.scala`
- Modify: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/OnRaiseSpec.scala`
- Modify: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/TestAlgReferenceSpec.scala`
- Modify: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/TestAlgReferencePropertySpec.scala`
- Create: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/EvidenceThreadingSpec.scala`

**Interfaces:**
- Consumes: `Render[ErrA]`, `Render[ErrB]`, `Render[TestError]` from Task 1.
- Produces:
  - `trait RaisePull[G[_], F[_], Err[_]] { def apply[E](rg: Raise[G, E])(implicit ev: Err[E]): Raise[F, E] }`
  - `RaisePull.id[F[_], Err[_]]: RaisePull[F, F, Err]`
  - `final case class RaiseArrow[F[_], G[_], Err[_]](fk: F ~> G, pull: RaisePull[G, F, Err])`
  - `RaiseArrow.id[F[_], Err[_]]: RaiseArrow[F, F, Err]`
  - `trait RaiseFunctorK[Alg[_[_]], Err[_]] { def mapK[F[_], G[_]](af: Alg[F])(arrow: RaiseArrow[F, G, Err]): Alg[G] }`
  - `trait RaiseAspect[Alg[_[_]], Dom[_], Cod[_], Err[_]] extends RaiseFunctorK[Alg, Err]`
  - `trait OnRaise[F[_], Err[_]] { def apply[E](e: E)(implicit ev: Err[E]): F[Unit] }`
  - `OnRaise.noop[F[_], Err[_]](implicit F: Applicative[F]): OnRaise[F, Err]`
  - `WeaveArrows.raisePull[F[_], Dom[_], Cod[_], Err[_]]`,
    `WeaveArrows.raiseLift[F[_], Dom[_], Cod[_], Err[_]]` (both overloads),
    `WeaveArrows.eraseWeave[F[_], Dom[_], Cod[_], Err[_]]`
  - `TestAlgReference.referenceRaiseAspect[Dom[_], Cod[_], Err[_]]`, taking
    `errA: Err[ErrA]` and `errB: Err[ErrB]` in addition to its existing
    `Dom`/`Cod` implicits.

- [ ] **Step 1: Write the failing test**

Create `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/EvidenceThreadingSpec.scala`:

```scala
package com.dwolla.tagless.mtl

import cats.mtl.Raise
import munit.FunSuite

import scala.collection.mutable.ListBuffer

import TestError._

/** The point of M10: the `Err` evidence for the raised error type reaches the
  * `OnRaise` hook, so a hook can render the error through a type class rather
  * than falling back to `toString`.
  */
class EvidenceThreadingSpec extends FunSuite {
  private type F[A] = Either[TestError, A]

  test("the OnRaise hook renders the raised error through its Err evidence") {
    val rendered = ListBuffer.empty[String]

    val hook: OnRaise[F, Render] = new OnRaise[F, Render] {
      def apply[E](e: E)(implicit ev: Render[E]): F[Unit] = {
        rendered += ev.render(e)
        Right(())
      }
    }

    val lifted =
      WeaveArrows.raiseLift[F, Render, Render, Render](hook).apply(Raise[F, ErrA])

    val out = lifted.raise[ErrA, Int](NegativeInput(-3))

    assertEquals(out.codomain.target, Left(NegativeInput(-3)): F[Int])
    assertEquals(rendered.toList, List("errA:NegativeInput(-3)"))
  }

  test("a second error type on the same carrier gets its own evidence") {
    val rendered = ListBuffer.empty[String]

    val hook: OnRaise[F, Render] = new OnRaise[F, Render] {
      def apply[E](e: E)(implicit ev: Render[E]): F[Unit] = {
        rendered += ev.render(e)
        Right(())
      }
    }

    val liftA = WeaveArrows.raiseLift[F, Render, Render, Render](hook).apply(Raise[F, ErrA])
    val liftB = WeaveArrows.raiseLift[F, Render, Render, Render](hook).apply(Raise[F, ErrB])

    liftA.raise[ErrA, Int](NegativeInput(-1))
    liftB.raise[ErrB, Int](EmptyInput("f"))

    assertEquals(rendered.toList, List("errA:NegativeInput(-1)", "errB:EmptyInput(f)"))
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
sbt "raiseAspectCoreJVM/testOnly com.dwolla.tagless.mtl.EvidenceThreadingSpec"
```

Expected: compile failure — `OnRaise` takes one type parameter, and
`raiseLift` takes three.

- [ ] **Step 3: Change `RaiseArrow.scala`**

Replace the body of `RaiseArrow.scala` below the imports with:

```scala
/** Transports a `Raise` capability backward along a mapping of effects:
  * `∀E. Err[E] ?=> Raise[G, E] => Raise[F, E]`.
  *
  * This is possible because `Raise` only ever ''produces'' `F` values — `raise`
  * has no `F[A]` parameter — so the capability can follow a value-level mapping
  * in the opposite direction. `Handle` is not transportable for exactly this
  * reason: `handleWith` consumes an `F[A]`.
  *
  * Transport is uniform in `E` — `apply` keeps its own type parameter, so one
  * pull serves a method declaring several `Raise` parameters with different
  * error types. `Err` supplies per-error-type evidence at each application,
  * which is what lets an interception point render the error through a type
  * class instead of `toString`. Use `cats.tagless.Trivial` for `Err` when no
  * evidence is wanted; its universal instance makes the constraint vacuous.
  */
trait RaisePull[G[_], F[_], Err[_]] extends Serializable {
  def apply[E](rg: Raise[G, E])(implicit ev: Err[E]): Raise[F, E]
}

object RaisePull {
  def id[F[_], Err[_]]: RaisePull[F, F, Err] =
    new RaisePull[F, F, Err] {
      def apply[E](rg: Raise[F, E])(implicit ev: Err[E]): Raise[F, E] = rg
    }
}

/** A morphism `F ⇒ G` in the category algebras with `Raise` parameters are
  * functorial over: values travel forward along `fk`, `Raise` capabilities
  * travel backward along `pull`.
  */
final case class RaiseArrow[F[_], G[_], Err[_]](fk: F ~> G, pull: RaisePull[G, F, Err]) {
  def andThen[H[_]](that: RaiseArrow[G, H, Err]): RaiseArrow[F, H, Err] =
    RaiseArrow(
      that.fk.compose(fk),
      new RaisePull[H, F, Err] {
        def apply[E](rh: Raise[H, E])(implicit ev: Err[E]): Raise[F, E] =
          pull(that.pull(rh))
      }
    )
}

object RaiseArrow {
  def id[F[_], Err[_]]: RaiseArrow[F, F, Err] =
    RaiseArrow(FunctionK.id[F], RaisePull.id[F, Err])
}
```

- [ ] **Step 4: Change `RaiseAspect.scala`**

```scala
trait RaiseFunctorK[Alg[_[_]], Err[_]] extends Serializable {
  def mapK[F[_], G[_]](af: Alg[F])(arrow: RaiseArrow[F, G, Err]): Alg[G]
}

trait RaiseAspect[Alg[_[_]], Dom[_], Cod[_], Err[_]] extends RaiseFunctorK[Alg, Err] {
  def weave[F[_]](af: Alg[F])(implicit F: Functor[F]): Alg[Aspect.Weave[F, Dom, Cod, *]]
}
```

Update the scaladoc on `RaiseAspect` where it currently reads "There is
deliberately no `E` parameter": that statement is still true and must stay,
but add that `Err` is a per-error-type *evidence* parameter and is not the
same thing as an `E` parameter — transport remains uniform.

- [ ] **Step 5: Change `OnRaise.scala`**

```scala
/** A hook invoked with the typed value at the moment a `Raise[F, E].raise`
  * crosses a `raiseLift` interception point (see [[WeaveArrows.raiseLift]]).
  *
  * Universally quantified in `E`, with per-`E` evidence supplied by `Err`: a
  * hook can render the error through `Err[E]` rather than matching on the
  * value. Behavior that genuinely depends on the concrete error type still
  * lives inside the hook implementation.
  */
trait OnRaise[F[_], Err[_]] extends Serializable {
  def apply[E](e: E)(implicit ev: Err[E]): F[Unit]
}

object OnRaise {
  /** Does nothing. The default for callers who don't want observation. */
  def noop[F[_], Err[_]](implicit F: Applicative[F]): OnRaise[F, Err] =
    new OnRaise[F, Err] {
      def apply[E](e: E)(implicit ev: Err[E]): F[Unit] = ().pure[F]
    }
}
```

- [ ] **Step 6: Change `WeaveArrows.scala`**

Add `Err[_]` to all four members and thread the evidence. The four signatures,
with the bodies that change:

```scala
  def codomainTarget[F[_], Dom[_], Cod[_]]: Aspect.Weave[F, Dom, Cod, *] ~> F =
    FunctionK.liftFunction[Aspect.Weave[F, Dom, Cod, *], F](_.codomain.target)

  def raisePull[F[_], Dom[_], Cod[_], Err[_]](implicit
      F: Functor[F]
  ): RaisePull[Aspect.Weave[F, Dom, Cod, *], F, Err] =
    new RaisePull[Aspect.Weave[F, Dom, Cod, *], F, Err] {
      def apply[E](rw: Raise[Aspect.Weave[F, Dom, Cod, *], E])(implicit ev: Err[E]): Raise[F, E] =
        new Raise[F, E] {
          val functor: Functor[F] = F

          def raise[E2 <: E, A](e: E2): F[A] =
            rw.raise[E2, A](e).codomain.target
        }
    }

  def raiseLift[F[_], Dom[_], Cod[_], Err[_]](implicit
      F: Functor[F],
      syn: Synthetic[Cod]
  ): RaisePull[F, Aspect.Weave[F, Dom, Cod, *], Err] =
    new RaisePull[F, Aspect.Weave[F, Dom, Cod, *], Err] {
      def apply[E](rf: Raise[F, E])(implicit ev: Err[E]): Raise[Aspect.Weave[F, Dom, Cod, *], E] =
        new Raise[Aspect.Weave[F, Dom, Cod, *], E] {
          val functor: Functor[Aspect.Weave[F, Dom, Cod, *]] =
            syntheticWeaveFunctor[F, Dom, Cod]

          def raise[E2 <: E, A](e: E2): Aspect.Weave[F, Dom, Cod, A] =
            Aspect.Weave[F, Dom, Cod, A](
              RaiseName,
              Nil,
              Aspect.Advice[F, Cod, A](RaiseName, rf.raise[E2, A](e))(syn.apply[A])
            )
        }
    }

  def raiseLift[F[_], Dom[_], Cod[_], Err[_]](onRaise: OnRaise[F, Err])(implicit
      F: Apply[F],
      syn: Synthetic[Cod]
  ): RaisePull[F, Aspect.Weave[F, Dom, Cod, *], Err] =
    new RaisePull[F, Aspect.Weave[F, Dom, Cod, *], Err] {
      def apply[E](rf: Raise[F, E])(implicit ev: Err[E]): Raise[Aspect.Weave[F, Dom, Cod, *], E] =
        new Raise[Aspect.Weave[F, Dom, Cod, *], E] {
          val functor: Functor[Aspect.Weave[F, Dom, Cod, *]] =
            syntheticWeaveFunctor[F, Dom, Cod]

          def raise[E2 <: E, A](e: E2): Aspect.Weave[F, Dom, Cod, A] =
            Aspect.Weave[F, Dom, Cod, A](
              RaiseName,
              Nil,
              Aspect.Advice[F, Cod, A](
                RaiseName,
                // `e` is an `E2 <: E` and the evidence in scope is `Err[E]`,
                // so the hook is invoked at `E` and `e` widens. This is why
                // `Err` needs no variance annotation.
                onRaise.apply[E](e)(ev) *> rf.raise[E2, A](e)
              )(syn.apply[A])
            )
        }
    }

  def eraseWeave[F[_], Dom[_], Cod[_], Err[_]](implicit
      F: Functor[F],
      syn: Synthetic[Cod]
  ): RaiseArrow[Aspect.Weave[F, Dom, Cod, *], F, Err] =
    RaiseArrow(codomainTarget[F, Dom, Cod], raiseLift[F, Dom, Cod, Err])
```

`codomainTarget` and the private `syntheticWeaveFunctor` are unchanged —
neither touches a capability. Add a sentence to `raiseLift`'s scaladoc noting
that the genuine `Err[E]` and the synthesized `Cod[A]` mean different things:
the error value is real and rendered; the success value never exists.

- [ ] **Step 7: Run the new test to verify it passes**

```bash
sbt "raiseAspectCoreJVM/testOnly com.dwolla.tagless.mtl.EvidenceThreadingSpec"
```

Expected: PASS. The rest of the module's tests will not compile yet — that is
the next step.

- [ ] **Step 8: Update `TestAlgReference.scala`**

This is the differential oracle both macros are checked against, so it must
show exactly what they will generate. Change the signature and the two
capability-transport sites:

```scala
  def referenceRaiseAspect[Dom[_], Cod[_], Err[_]](implicit
      domInt: Dom[Int],
      domString: Dom[String],
      codString: Cod[String],
      codInt: Cod[Int],
      codUnit: Cod[Unit],
      errA: Err[ErrA],
      errB: Err[ErrB]
  ): RaiseAspect[TestAlg, Dom, Cod, Err] =
    new RaiseAspect[TestAlg, Dom, Cod, Err] {

      def weave[F[_]](af: TestAlg[F])(implicit F: Functor[F]): TestAlg[Aspect.Weave[F, Dom, Cod, *]] = {
        type WF[A] = Aspect.Weave[F, Dom, Cod, A]

        // The `Err[E]` a macro resolves at the derivation site arrives here as
        // an implicit parameter, exactly as `Dom`/`Cod` instances already do.
        def pull[E](rw: Raise[WF, E])(implicit ev: Err[E]): Raise[F, E] =
          WeaveArrows.raisePull[F, Dom, Cod, Err].apply(rw)

        // ... method bodies unchanged: `pull(R)`, `pull(R1)`, `pull(R2)` all
        // resolve `errA`/`errB` from the enclosing implicit parameter list.
      }

      def mapK[F[_], G[_]](af: TestAlg[F])(arrow: RaiseArrow[F, G, Err]): TestAlg[G] =
        // ... bodies unchanged: `arrow.pull(R)` resolves the same implicits.
    }
```

Nothing else in the file changes. The `Aspect.Weave`/`Aspect.Advice`
construction is untouched — woven structure does not depend on evidence.

- [ ] **Step 9: Update the remaining core tests**

Mechanical. In each file, add the `Err` type argument in the position after
`Cod`, and the `Err` type argument to arrow types after `G`:

- `WeaveArrowsSpec.scala` — `raiseLift[F, Render, Render]` becomes
  `raiseLift[F, Render, Render, Render]`; `RaisePull.id[F]` becomes
  `RaisePull.id[F, Render]`; `RaiseArrow.id[F]` becomes
  `RaiseArrow.id[F, Render]`; `eraseWeave[F, Render, Render]` becomes
  `eraseWeave[F, Render, Render, Render]`. The explicit-implicit-argument call
  at line 25 becomes `raiseLift[F, Render, Render, Render](F, syntheticRender)`.
- `WeaveArrowsOnRaiseSpec.scala` — same, plus every `OnRaise[F]` /
  `OnRaise[Lazily]` becomes `OnRaise[F, Render]` / `OnRaise[Lazily, Render]`,
  every `OnRaise.noop[F]` becomes `OnRaise.noop[F, Render]`, and each hook's
  `def apply[E](e: E): F[Unit]` becomes
  `def apply[E](e: E)(implicit ev: Render[E]): F[Unit]`.
- `OnRaiseSpec.scala` — `OnRaise.noop[F]` becomes `OnRaise.noop[F, Render]`;
  `noop.apply[ErrA](err)` still compiles (the evidence resolves implicitly);
  the deserialization cast `asInstanceOf[OnRaise[F]]` becomes
  `asInstanceOf[OnRaise[F, Render]]`.
- `TestAlgReferenceSpec.scala` and `TestAlgReferencePropertySpec.scala` —
  `referenceRaiseAspect[Render, Render]` becomes
  `referenceRaiseAspect[Render, Render, Render]`; arrow types gain `Render`.

- [ ] **Step 10: Run the full core suite**

```bash
sbt "+raiseAspectCoreJVM/test"
```

Expected: all tests pass on 2.12, 2.13, and 3. Note the count — M6 recorded
34 tests per version; this task adds `RenderErrorInstancesSpec` (3) and
`EvidenceThreadingSpec` (2).

- [ ] **Step 11: Verify the Scala.js linker**

```bash
sbt "+raiseAspectCoreJS/Test/scalaJSLinkerResult"
```

Expected: success on all three versions. (Test *execution* on JS is not
available locally — no Node — consistent with every prior milestone.)

- [ ] **Step 12: Commit**

```bash
git add raise-aspect-core/src/main/scala/com/dwolla/tagless/mtl/ \
        raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/
git commit -m "feat!: thread per-error-type evidence through Raise transport

RaisePull, RaiseArrow, RaiseFunctorK, RaiseAspect, OnRaise and all four
WeaveArrows members gain an Err[_] parameter. Transport stays uniform in E;
each application of a pull now carries Err[E], so an interception point can
render the raised error through a type class."
```

---

## Task 3: laws

**Files:**
- Modify: `raise-aspect-laws/src/main/scala/com/dwolla/tagless/mtl/laws/RaiseArrowLaws.scala`
- Modify: `raise-aspect-laws/src/main/scala/com/dwolla/tagless/mtl/laws/RaiseFunctorKLaws.scala`
- Modify: `raise-aspect-laws/src/main/scala/com/dwolla/tagless/mtl/laws/RaiseAspectLaws.scala`
- Modify: `raise-aspect-laws/src/main/scala/com/dwolla/tagless/mtl/laws/discipline/RaiseFunctorKTests.scala`
- Modify: `raise-aspect-laws/src/main/scala/com/dwolla/tagless/mtl/laws/discipline/RaiseAspectTests.scala`
- Modify: `raise-aspect-laws/src/test/scala/com/dwolla/tagless/mtl/laws/PlainAlgReference.scala`
- Modify: `raise-aspect-laws/src/test/scala/com/dwolla/tagless/mtl/laws/LawsInstances.scala`
- Modify: `raise-aspect-laws/src/test/scala/com/dwolla/tagless/mtl/laws/RaiseAspectSuite.scala`
- Modify: `raise-aspect-laws/src/test/scala/com/dwolla/tagless/mtl/laws/ReferenceRaiseAspectSpec.scala`
- Modify: `raise-aspect-laws/src/test/scala/com/dwolla/tagless/mtl/laws/ConservativeExtensionSuite.scala`

**Interfaces:**
- Consumes: everything Task 2 produces.
- Produces:
  - `RaiseArrowLaws.arrowCoherence[F[_], G[_], Err[_], E, A](arrow: RaiseArrow[F, G, Err], rg: Raise[G, E], e: E)(implicit ev: Err[E]): IsEq[G[A]]`
  - `RaiseArrowLaws.sectionRetraction[F[_], Dom[_], Cod[_], Err[_], E, A](rf, e)(implicit F, syn, ev)`
  - the four L6 laws and L7, each gaining `Err[_]` after `Cod[_]` and
    `implicit ev: Err[E]`
  - `RaiseFunctorKLaws[Alg[_[_]], Err[_]]`, `RaiseAspectLaws[Alg[_[_]], Dom[_], Cod[_], Err[_]]`
  - `RaiseFunctorKTests[Alg[_[_]], Err[_]]`, `RaiseAspectTests[Alg[_[_]], Dom[_], Cod[_], Err[_]]`
  - `PlainAlgReference.referenceRaiseAspect[Dom[_], Cod[_], Err[_]]`

**The strength-preserving instantiation is the substance of this task.** Adding
`implicit ev: Err[E]` narrows every law from "∀`E`" to "∀`E` with `Err[E]`".
`cats.tagless.Trivial.instance[A]` is implicit and universal, so instantiating
at `Err = Trivial` restores the original statement exactly. The suite runs
both: `Trivial` for the unweakened law, `Render` for the path that carries
real evidence. Do not drop the `Trivial` instantiation as redundant — it is
the only thing keeping the frozen laws as strong as they were.

- [ ] **Step 1: Write the failing test**

In `RaiseAspectSuite.scala`, add — after the existing "L4 arrow coherence for
the identity arrow" property — the strength-preserving block:

```scala
  // ------------------------- every value-level law at Err = Trivial (∀E)

  // Adding `implicit ev: Err[E]` to the laws narrows them from "for all E" to
  // "for all E for which Err[E] exists". `Trivial`'s instance is universal, so
  // this instantiation restores the original quantifier. The `Render`
  // instantiations above cover the evidence-carrying path; these cover the
  // strength the laws had before M10.
  //
  // This block covers ALL SEVEN value-level laws — L4, L5, L6a, L6b, L6c, L6d
  // and L7 — not just L4/L5. Every one of them now takes `implicit ev: Err[E]`,
  // so every one of them was narrowed: L6a-d reach the evidence through the
  // private `lifted` helper (`WeaveArrows.raiseLift[F, Dom, Cod, Err].apply`)
  // and L7 through `WeaveArrows.raisePull[F, Dom, Cod, Err].apply`. Covering
  // only two of the seven would leave the "unweakened" claim true of 2/7 laws
  // while reading as though it were true of all of them.
  property("L4 arrow coherence for eraseWeave, at Err = Trivial") {
    forAllErrors { e =>
      val law = RaiseArrowLaws.arrowCoherence[Woven, Result, Trivial, TestError, Int](
        WeaveArrows.eraseWeave[Result, Render, Render, Trivial],
        raiseResult,
        e
      )
      assertEquals(law.lhs, law.rhs)
    }
  }

  property("L5 section/retraction, at Err = Trivial") {
    forAllErrors { e =>
      val law = RaiseArrowLaws.sectionRetraction[Result, Render, Render, Trivial, TestError, Int](
        raiseResult,
        e
      )
      assertEquals(law.lhs, law.rhs)
    }
  }
```

Add `import cats.tagless.Trivial` to the suite's imports.

- [ ] **Step 2: Run to verify it fails**

```bash
sbt "raiseAspectLawsJVM/test"
```

Expected: compile failure — the laws module still references the pre-M10
signatures, so the whole module fails to compile. That is the expected red.

- [ ] **Step 3: Add `Err` to the law traits**

`RaiseArrowLaws.scala` — every method gains `Err[_]` immediately after `Cod[_]`
(or after `G[_]` where there is no `Cod`), and an `implicit ev: Err[E]`. Two
representative signatures; apply the same shape to L6a–L6d and L7:

```scala
  def arrowCoherence[F[_], G[_], Err[_], E, A](
      arrow: RaiseArrow[F, G, Err],
      rg: Raise[G, E],
      e: E
  )(implicit ev: Err[E]): IsEq[G[A]] =
    arrow.fk(arrow.pull(rg).raise[E, A](e)) <-> rg.raise[E, A](e)

  def sectionRetraction[F[_], Dom[_], Cod[_], Err[_], E, A](rf: Raise[F, E], e: E)(implicit
      F: Functor[F],
      syn: Synthetic[Cod],
      ev: Err[E]
  ): IsEq[F[A]] = {
    val there = WeaveArrows.raiseLift[F, Dom, Cod, Err].apply(rf)
    val back = WeaveArrows.raisePull[F, Dom, Cod, Err].apply(there)
    back.raise[E, A](e) <-> rf.raise[E, A](e)
  }
```

The private `lifted` helper gains `Err[_]` and `implicit ev: Err[E]` the same
way. **No `<->` changes on either side** — if a law's left or right operand
needs editing, stop and report: that would mean the change altered a law's
meaning, which the design says it must not.

`RaiseFunctorKLaws.scala`:

```scala
trait RaiseFunctorKLaws[Alg[_[_]], Err[_]] {
  implicit def F: RaiseFunctorK[Alg, Err]

  def mapKIdentity[A[_]](af: Alg[A]): IsEq[Alg[A]] =
    F.mapK(af)(RaiseArrow.id[A, Err]) <-> af

  def mapKComposition[A[_], B[_], C[_]](
      af: Alg[A],
      f: RaiseArrow[A, B, Err],
      g: RaiseArrow[B, C, Err]
  ): IsEq[Alg[C]] =
    F.mapK(F.mapK(af)(f))(g) <-> F.mapK(af)(f.andThen(g))
}

object RaiseFunctorKLaws {
  def apply[Alg[_[_]], Err[_]](implicit ev: RaiseFunctorK[Alg, Err]): RaiseFunctorKLaws[Alg, Err] =
    new RaiseFunctorKLaws[Alg, Err] { val F = ev }
}
```

`RaiseAspectLaws.scala`:

```scala
trait RaiseAspectLaws[Alg[_[_]], Dom[_], Cod[_], Err[_]] extends RaiseFunctorKLaws[Alg, Err] {
  implicit def F: RaiseAspect[Alg, Dom, Cod, Err]
  implicit def synthetic: Synthetic[Cod]

  def weaveErasure[A[_]](af: Alg[A])(implicit A: Functor[A]): IsEq[Alg[A]] =
    F.mapK(F.weave(af))(WeaveArrows.eraseWeave[A, Dom, Cod, Err]) <-> af
}

object RaiseAspectLaws {
  def apply[Alg[_[_]], Dom[_], Cod[_], Err[_]](implicit
      ev: RaiseAspect[Alg, Dom, Cod, Err],
      syn: Synthetic[Cod]
  ): RaiseAspectLaws[Alg, Dom, Cod, Err] =
    new RaiseAspectLaws[Alg, Dom, Cod, Err] {
      val F = ev
      val synthetic = syn
    }
}
```

- [ ] **Step 4: Add `Err` to the discipline rule sets**

`RaiseFunctorKTests[Alg[_[_]], Err[_]]` with `def laws: RaiseFunctorKLaws[Alg, Err]`
and `ArbArrowAB: Arbitrary[RaiseArrow[A, B, Err]]`,
`ArbArrowBC: Arbitrary[RaiseArrow[B, C, Err]]`. Its companion `apply` gains
`Err[_]` and takes `RaiseFunctorK[Alg, Err]`.

`RaiseAspectTests[Alg[_[_]], Dom[_], Cod[_], Err[_]] extends RaiseFunctorKTests[Alg, Err]`,
`def laws: RaiseAspectLaws[Alg, Dom, Cod, Err]`, same `Arbitrary` changes,
companion `apply` gains `Err[_]`.

- [ ] **Step 5: Update the laws test fixtures and suites**

- `PlainAlgReference.referenceRaiseAspect[Dom[_], Cod[_], Err[_]]` returning
  `RaiseAspect[PlainAlg, Dom, Cod, Err]`, `mapK` taking
  `RaiseArrow[F, G, Err]`. `PlainAlg` has no capability parameters, so it needs
  no `Err` instances in its implicit list — that is the point of L9's
  conservative-extension comparison and must stay true.
- `LawsInstances.scala` — no signature changes; it defines `Eq`,
  `ExhaustiveCheck`, `Renderable`, `Synthetic[Render]`, and the non-implicit
  `raiseResult`. Verify it still compiles unchanged; if a type ascription
  needs `Err`, add it there rather than restructuring.
- `RaiseAspectSuite.scala` — `def instance: RaiseAspect[TestAlg, Render, Render, Render]`;
  `Arbitrary[RaiseArrow[Woven, Result, Render]]` from
  `WeaveArrows.eraseWeave[Result, Render, Render, Render]`;
  `Arbitrary[RaiseArrow[Result, Result, Render]]` from `RaiseArrow.id[Result, Render]`;
  `RaiseAspectTests[TestAlg, Render, Render, Render]`;
  `RaiseFunctorKTests[TestAlg, Render]`; every `RaiseArrowLaws.*` call gains
  its `Err` type argument.
- `ReferenceRaiseAspectSpec.scala` — supplies
  `TestAlgReference.referenceRaiseAspect[Render, Render, Render]`.
- `ConservativeExtensionSuite.scala` — `PlainAlgReference.referenceRaiseAspect`
  gains its third type argument; arrow types gain `Err`.

- [ ] **Step 6: Run the laws suite**

```bash
sbt "+raiseAspectLawsJVM/test"
```

Expected: PASS on all three versions, including the two new `Err = Trivial`
properties.

- [ ] **Step 7: Verify the JS linker**

```bash
sbt "+raiseAspectLawsJS/Test/scalaJSLinkerResult"
```

Expected: success.

- [ ] **Step 8: Commit**

```bash
git add raise-aspect-laws/
git commit -m "feat!: thread evidence through the laws, preserving their strength

Every law gains an Err[_] parameter and an implicit ev: Err[E]; no <-> operand
changes. The value-level laws are now instantiated twice — at Err = Trivial,
whose universal instance restores the original 'for all E' quantifier, and at
Err = Render, which exercises the evidence-carrying path."
```

---

## Task 4: Scala 2 derivation

**Files:**
- Modify: `raise-aspect-macros/src/main/scala-2/com/dwolla/tagless/mtl/DeriveRaise.scala`
- Modify: `raise-aspect-macros/src/main/scala-2/com/dwolla/tagless/mtl/DeriveRaiseMacros.scala:367` (`raiseWeave`), `:419` (`raiseMapK`), `:454` (`aspect`), `:464` (`functorK`)
- Modify: `raise-aspect-macros/src/test/scala-2/com/dwolla/tagless/mtl/laws/DerivedRaiseAspectSpec.scala`
- Modify: `raise-aspect-macros/src/test/scala-2/com/dwolla/tagless/mtl/laws/DifferentialOracleSpec.scala`
- Modify: `raise-aspect-macros/src/test/scala-2/com/dwolla/tagless/mtl/laws/DerivedConservativeExtensionSpec.scala`
- Modify: `raise-aspect-macros/src/test/scala-2/com/dwolla/tagless/mtl/laws/EdgeCaseDerivationSpec.scala`
- Modify: `raise-aspect-macros/src/test/scala-2/com/dwolla/tagless/mtl/laws/CrossVersionAgreementSpec.scala`

**Interfaces:**
- Consumes: Task 2's core types, Task 3's law suites.
- Produces: `DeriveRaise.aspect[Alg[_[_]], Dom[_], Cod[_], Err[_]]: RaiseAspect[Alg, Dom, Cod, Err]`
  and `DeriveRaise.functorK[Alg[_[_]], Err[_]]: RaiseFunctorK[Alg, Err]`.

- [ ] **Step 1: Write the failing test**

In `DerivedRaiseAspectSpec.scala`, change the derived instance to the
four-parameter form:

```scala
  implicit val derived: RaiseAspect[TestAlg, Render, Render, Render] =
    DeriveRaise.aspect[TestAlg, Render, Render, Render]
```

- [ ] **Step 2: Run to verify it fails**

```bash
sbt "++2.13.18 raiseAspectMacrosJVM/test"
```

Expected: compile failure — `DeriveRaise.aspect` takes three type parameters.

- [ ] **Step 3: Change the Scala 2 entry points**

`DeriveRaise.scala`:

```scala
  /** Derive a [[RaiseAspect]], supporting both `weave` and `mapK`. */
  def aspect[Alg[_[_]], Dom[_], Cod[_], Err[_]]: RaiseAspect[Alg, Dom, Cod, Err] =
    macro DeriveRaiseMacros.aspect[Alg, Dom, Cod, Err]

  /** Derive just a [[RaiseFunctorK]], when no weaving is needed. */
  def functorK[Alg[_[_]], Err[_]]: RaiseFunctorK[Alg, Err] =
    macro DeriveRaiseMacros.functorK[Alg, Err]
```

Update the scaladoc example to
`DeriveRaise.aspect[Bar, TraceableValue, TraceableValue, TraceableValue]`, and
add a sentence naming `Err` as the per-error-type evidence type class, with
`cats.tagless.Trivial` as the opt-out.

- [ ] **Step 4: Thread `Err` through the macro**

`DeriveRaiseMacros.scala` — four changes.

`raiseWeave` gains `Err` in its parameter list, and its capability-parameter
transform (currently line 386, `q"$WeaveArrowsRef.raisePull[$F, $Dom, $Cod].apply($pn)"`)
becomes:

```scala
  def raiseWeave(Dom: Type, Cod: Type, Err: Type)(algebra: Type): MethodDef = MethodDef("weave") {
    // ... everything down to the capability transform is unchanged ...
          val args = method.transformedArgLists { case Parameter(pn, pt, _) if capabilityError(pt, f).isDefined =>
            val errorType = capabilityError(pt, f).get
            val errInstance = inferOrAbort(appliedType(Err, errorType), s"for the error type of parameter $pn")
            q"$WeaveArrowsRef.raisePull[$F, $Dom, $Cod, $Err].apply($pn)($errInstance)"
          }
    // ... the rest is unchanged ...
  }
```

`raiseMapK` gains `Err` — it currently takes only the algebra type and has no
access to any type class. Its transform (currently line 431,
`q"$arrow.pull($pn)"`) becomes:

```scala
  def raiseMapK(Err: Type)(algebra: Type): MethodDef = MethodDef("mapK") {
    // ... unchanged down to the capability transform ...
          val args = method.transformedArgLists { case Parameter(pn, pt, _) if capabilityError(pt, f).isDefined =>
            val errorType = capabilityError(pt, f).get
            val errInstance = inferOrAbort(appliedType(Err, errorType), s"for the error type of parameter $pn")
            q"$arrow.pull($pn)($errInstance)"
          }
    // ... the rest is unchanged ...
  }
```

Both `describe` strings are replaced in Task 6 by a dedicated diagnostic; they
exist here only so this task compiles.

The two entry points:

```scala
  def aspect[Alg[_[_]], Dom[_], Cod[_], Err[_]](implicit
      tag: WeakTypeTag[Alg[Any]],
      dom: WeakTypeTag[Dom[Any]],
      cod: WeakTypeTag[Cod[Any]],
      err: WeakTypeTag[Err[Any]]
  ): Tree = {
    val Dom = typeConstructorOf(dom)
    val Cod = typeConstructorOf(cod)
    val Err = typeConstructorOf(err)
    instantiate[RaiseAspect[Alg, Dom, Cod, Err]](tag, Dom, Cod, Err)(
      raiseWeave(Dom, Cod, Err),
      raiseMapK(Err)
    )
  }

  def functorK[Alg[_[_]], Err[_]](implicit
      tag: WeakTypeTag[Alg[Any]],
      err: WeakTypeTag[Err[Any]]
  ): Tree = {
    val Err = typeConstructorOf(err)
    instantiate[RaiseFunctorK[Alg, Err]](tag, Err)(raiseMapK(Err))
  }
```

`instantiate` already takes `typeArgs: Type*` and appends them after the
algebra, so passing `Err` last matches the declaration order
`RaiseAspect[Alg, Dom, Cod, Err]` and `RaiseFunctorK[Alg, Err]`.

The error type for a capability parameter comes from the existing
`capabilityError(pt, f)` helper (line 263) — it already returns
`Option[Type]` holding exactly the `E` in `Raise[F, E]`.

- [ ] **Step 5: Update the remaining Scala 2 macro tests**

Add the fourth type argument at every `DeriveRaise.aspect` call and the second
at every `DeriveRaise.functorK` call; add `Err` to every `RaiseAspect`,
`RaiseFunctorK`, and `RaiseArrow` ascription. `EdgeCaseDerivationSpec`'s
`inherited` algebra raises `ErrA`, so `Render[ErrA]` from Task 1 covers it.

- [ ] **Step 6: Run the Scala 2 macro suites**

```bash
sbt "++2.13.18 raiseAspectMacrosJVM/test" && sbt "++2.12.21 raiseAspectMacrosJVM/test"
```

Expected: PASS on both. The differential oracle must agree with
`TestAlgReference` output-for-output — if it disagrees, the macro is
generating something the reference does not, and the reference is the
specification.

- [ ] **Step 7: Confirm `ExpectedWeaves` was not touched**

```bash
git diff --stat raise-aspect-macros/src/test/scala/com/dwolla/tagless/mtl/laws/ExpectedWeaves.scala
```

Expected: empty output. A non-empty diff means woven structure changed, which
violates a global constraint — stop and report.

- [ ] **Step 8: Commit**

```bash
git add raise-aspect-macros/src/main/scala-2/ raise-aspect-macros/src/test/scala-2/
git commit -m "feat!: summon Err evidence per raise parameter in the Scala 2 derivation"
```

---

## Task 5: Scala 3 derivation

**Files:**
- Modify: `raise-aspect-macros/src/main/scala-3/com/dwolla/tagless/mtl/DeriveRaise.scala`
- Modify: `raise-aspect-macros/src/main/scala-3/com/dwolla/tagless/mtl/DeriveRaiseMacros.scala:329` (`aspect`), `:338` (`functorK`), `:344` (`deriveWeave`), `:407` (`deriveMapK`)
- Modify: `raise-aspect-macros/src/test/scala-3/com/dwolla/tagless/mtl/laws/DerivedRaiseAspectSpec.scala`
- Modify: `raise-aspect-macros/src/test/scala-3/com/dwolla/tagless/mtl/laws/DifferentialOracleSpec.scala`
- Modify: `raise-aspect-macros/src/test/scala-3/com/dwolla/tagless/mtl/laws/DerivedConservativeExtensionSpec.scala`
- Modify: `raise-aspect-macros/src/test/scala-3/com/dwolla/tagless/mtl/laws/EdgeCaseDerivationSpec.scala`
- Modify: `raise-aspect-macros/src/test/scala-3/com/dwolla/tagless/mtl/laws/UsingAlgSpec.scala`

**Interfaces:**
- Consumes: Task 2's core types, Task 3's law suites.
- Produces: the same two entry points as Task 4, `@experimental`-annotated.

`UsingAlgSpec` is the one that matters most here: its `m` method takes
`(using R1: Raise[F, ErrA])(using R2: Raise[F, ErrB])` — two capability
clauses with different error types on one method. If the evidence summon is
wired to a single error type rather than per parameter, this is what catches it.

- [ ] **Step 1: Write the failing test**

In the Scala 3 `DerivedRaiseAspectSpec.scala`:

```scala
  @experimental
  implicit val derived: RaiseAspect[TestAlg, Render, Render, Render] =
    DeriveRaise.aspect[TestAlg, Render, Render, Render]
```

- [ ] **Step 2: Run to verify it fails**

```bash
sbt "++3.3.8 raiseAspectMacrosJVM/test"
```

Expected: compile failure — wrong number of type arguments.

- [ ] **Step 3: Change the Scala 3 entry points**

```scala
  @experimental
  inline def aspect[Alg[_[_]], Dom[_], Cod[_], Err[_]]: RaiseAspect[Alg, Dom, Cod, Err] =
    ${ RaiseAspectMacros.aspect[Alg, Dom, Cod, Err] }

  @experimental
  inline def functorK[Alg[_[_]], Err[_]]: RaiseFunctorK[Alg, Err] =
    ${ RaiseAspectMacros.functorK[Alg, Err] }
```

Update the scaladoc example to the four-argument form, matching Task 4's.

- [ ] **Step 4: Thread `Err` through the macro**

```scala
  def aspect[Alg[_[_]]: Type, Dom[_]: Type, Cod[_]: Type, Err[_]: Type](using Quotes)
      : Expr[RaiseAspect[Alg, Dom, Cod, Err]] = '{
    new RaiseAspect[Alg, Dom, Cod, Err]:
      def weave[F[_]](af: Alg[F])(implicit F: Functor[F]): Alg[[X] =>> Aspect.Weave[F, Dom, Cod, X]] =
        ${ deriveWeave[Alg, Dom, Cod, Err, F]('af, 'F) }

      def mapK[F[_], G[_]](af: Alg[F])(arrow: RaiseArrow[F, G, Err]): Alg[G] =
        ${ deriveMapK[Alg, F, G, Err]('af, 'arrow) }
  }
```

In `deriveWeave`, the capability-parameter transform becomes:

```scala
        case (_, tpe, arg) if macros.capabilityError(tpe, Carrier).isDefined =>
          tpe.dealias.typeArgs.last.asType match
            case '[e] =>
              val errEv = macros
                .summonOrAbort(TypeRepr.of[Err].appliedTo(tpe.dealias.typeArgs.last),
                               s"for the error type of a Raise parameter")
                .asExprOf[Err[e]]
              '{
                WeaveArrows
                  .raisePull[F, Dom, Cod, Err](using $functor)
                  .apply(${ arg.asExprOf[Raise[[X] =>> Aspect.Weave[F, Dom, Cod, X], e]] })(using $errEv)
              }.asTerm
```

In `deriveMapK`:

```scala
        case (_, tpe, arg) if macros.capabilityError(tpe, G).isDefined =>
          tpe.dealias.typeArgs.last.asType match
            case '[e] =>
              val errEv = macros
                .summonOrAbort(TypeRepr.of[Err].appliedTo(tpe.dealias.typeArgs.last),
                               s"for the error type of a Raise parameter")
                .asExprOf[Err[e]]
              '{ $arrow.pull(${ arg.asExprOf[Raise[G, e]] })(using $errEv) }.asTerm
```

`deriveMapK`'s signature becomes
`deriveMapK[Alg[_[_]]: Type, F[_]: Type, G[_]: Type, Err[_]: Type](alg: Expr[Alg[F]], arrow: Expr[RaiseArrow[F, G, Err]])`,
and `functorK[Alg[_[_]]: Type, Err[_]: Type]` follows the same pattern.

The diagnostic text above is a placeholder for Task 6, which replaces it with
a message naming the method and the type constructor.

- [ ] **Step 5: Update the remaining Scala 3 macro tests**

Same mechanical change as Task 4 Step 5, plus `UsingAlgSpec`.

- [ ] **Step 6: Run the Scala 3 macro suite**

```bash
sbt "++3.3.8 raiseAspectMacrosJVM/test"
```

Expected: PASS, including `UsingAlgSpec`'s two-capability-clause method and
the differential oracle against `TestAlgReference`.

- [ ] **Step 7: Run the whole cross-build so far**

```bash
sbt "+raiseAspectCoreJVM/test" "+raiseAspectLawsJVM/test" "+raiseAspectMacrosJVM/test"
```

Expected: all green on all three versions. `CrossVersionAgreementSpec` proves
the two axes generate equivalent instances.

- [ ] **Step 8: Commit**

```bash
git add raise-aspect-macros/src/main/scala-3/ raise-aspect-macros/src/test/scala-3/
git commit -m "feat!: summon Err evidence per raise parameter in the Scala 3 derivation"
```

---

## Task 6: missing-evidence diagnostics

A missing `Err[E]` currently produces whichever generic message
`summonOrAbort`/`inferOrAbort` emits. The existing `Dom`/`Cod` diagnostics set
a quality bar — they name the parameter or method — and this one must match on
both axes, with the same wording, because `CrossVersionAgreementSpec`'s
premise is that the axes behave alike.

**Files:**
- Modify: `raise-aspect-macros/src/main/scala-2/com/dwolla/tagless/mtl/DeriveRaiseMacros.scala`
- Modify: `raise-aspect-macros/src/main/scala-3/com/dwolla/tagless/mtl/DeriveRaiseMacros.scala`
- Modify: `raise-aspect-macros/src/test/scala-2/com/dwolla/tagless/mtl/laws/DerivationErrorSpec.scala`
- Modify: `raise-aspect-macros/src/test/scala-3/com/dwolla/tagless/mtl/laws/DerivationErrorSpec.scala`

**Interfaces:**
- Consumes: Tasks 4 and 5.
- Produces: an error message containing the substring
  `no evidence for the error type` plus the method name and the error type's
  name, on both axes.

- [ ] **Step 1: Write the failing test**

Add to *both* `DerivationErrorSpec` files (adjusting `implicit`/`using` and
`@experimental` per axis). The Scala 2 form:

```scala
  test("deriving with an Err type class that has no instance for the error type names the method and the type") {
    val errors: String = compileErrors(
      """import cats.mtl.Raise
import com.dwolla.tagless.mtl._
trait Unrenderable
trait NoEvidenceAlg[F[_]] {
  def go(i: Int)(implicit R: Raise[F, Unrenderable]): F[String]
}
DeriveRaise.aspect[NoEvidenceAlg, Render, Render, Render]"""
    )
    assert(errors.contains("no evidence for the error type"), errors)
    assert(errors.contains("go"), errors)
    assert(errors.contains("Unrenderable"), errors)
  }
```

The Scala 3 form is the same snippet with `using` instead of `implicit` and
the `DeriveRaise.aspect` call wrapped so the `@experimental` requirement is
satisfied the way the neighbouring cases in that file already do it.

- [ ] **Step 2: Run to verify it fails**

```bash
sbt "++2.13.18 raiseAspectMacrosJVM/testOnly *DerivationErrorSpec" && \
sbt "++3.3.8 raiseAspectMacrosJVM/testOnly *DerivationErrorSpec"
```

Expected: FAIL on both — the generic missing-implicit message does not contain
the required substrings.

- [ ] **Step 3: Add the diagnostic on the Scala 2 axis**

Replace the placeholder `describe` argument from Task 4 with a dedicated
abort. In `DeriveRaiseMacros.scala`, add a helper next to `inferOrAbort`:

```scala
  /** The `Err[E]` for a capability parameter's error type, or an abort naming
    * both. Distinct from `inferOrAbort` so the message can say "error type"
    * rather than "parameter" — the parameter is the `Raise`, not the error.
    */
  private def inferErrOrAbort(Err: Type, errorType: Type, method: Method): Tree =
    c.inferImplicitValue(appliedType(Err, errorType)) match {
      case EmptyTree =>
        abort(
          s"no evidence for the error type $errorType raised by method ${method.displayName}: " +
            s"an implicit ${Err.typeSymbol.name}[$errorType] is required at the derivation site. " +
            s"Supply one, or derive at Err = cats.tagless.Trivial to opt out of error evidence."
        )
      case tree => tree
    }
```

Use it at both call sites from Task 4 (`raiseWeave` and `raiseMapK`).

- [ ] **Step 4: Add the same diagnostic on the Scala 3 axis**

In the Scala 3 `DeriveRaiseMacros`, add alongside `summonOrAbort`:

```scala
  /** As [[summonOrAbort]], but worded for a capability parameter's error type.
    * Kept textually identical to the Scala 2 axis's message — the two axes are
    * held to behavioral agreement.
    */
  def summonErrOrAbort(Err: TypeRepr, errorType: TypeRepr, methodName: String): Term =
    Implicits.search(Err.appliedTo(errorType)) match
      case success: ImplicitSearchSuccess => success.tree
      case _ =>
        report.errorAndAbort(
          s"no evidence for the error type ${errorType.show} raised by method $methodName: " +
            s"an implicit ${Err.typeSymbol.name}[${errorType.show}] is required at the derivation site. " +
            s"Supply one, or derive at Err = cats.tagless.Trivial to opt out of error evidence."
        )
```

Thread the enclosing method's name into both `deriveWeave`'s and
`deriveMapK`'s transforms so the message can name it. In `deriveWeave` the
`Transform` partial function receives the method `Symbol` as its first tuple
element; in `deriveMapK` the same is true, and the current code discards it
with `_` — bind it instead.

- [ ] **Step 5: Run to verify both pass**

```bash
sbt "++2.13.18 raiseAspectMacrosJVM/testOnly *DerivationErrorSpec" && \
sbt "++2.12.21 raiseAspectMacrosJVM/testOnly *DerivationErrorSpec" && \
sbt "++3.3.8 raiseAspectMacrosJVM/testOnly *DerivationErrorSpec"
```

Expected: PASS on all three.

- [ ] **Step 6: Run the full macro suites to confirm nothing regressed**

```bash
sbt "+raiseAspectMacrosJVM/test"
```

Expected: PASS. The pre-existing `Dom`/`Cod` diagnostics are pinned by the
same spec and must still match their original wording.

- [ ] **Step 7: Commit**

```bash
git add raise-aspect-macros/
git commit -m "feat: diagnose a missing Err instance by method and error type on both axes"
```

---

## Task 7: natchez module renders errors through `TraceableValue`

The payoff. `RaiseRecorder.fromTrace` stops calling `toString`.

**Files:**
- Modify: `natchez-tagless-mtl/src/main/scala/com/dwolla/tracing/mtl/syntax/RaiseRecorder.scala`
- Modify: `natchez-tagless-mtl/src/main/scala/com/dwolla/tracing/mtl/syntax/TraceWeaveTracer.scala`
- Modify: `natchez-tagless-mtl/src/test/scala/com/dwolla/tracing/mtl/BarFixture.scala`
- Modify: `natchez-tagless-mtl/src/test/scala/com/dwolla/tracing/mtl/RaiseTraceIntegrationSuite.scala:98-99`
- Modify: `natchez-tagless-mtl/src/test/scala/com/dwolla/tracing/mtl/RaiseTraceValueSuite.scala`
- Modify: `natchez-tagless-mtl/src/test/scala/com/dwolla/tracing/mtl/syntax/RaiseRecorderPriorityFixtures.scala`
- Modify: `natchez-tagless-mtl/src/test/scala/com/dwolla/tracing/mtl/syntax/RaiseRecorderPrioritySpec.scala`
- Modify: `natchez-tagless-mtl/src/test/scala/com/dwolla/tracing/mtl/syntax/AspectPriorityFixtures.scala`
- Modify: `natchez-tagless-mtl/src/test/scala-2/com/dwolla/tracing/mtl/RaiseTraceIntegrationSpec.scala`
- Modify: `natchez-tagless-mtl/src/test/scala-3/com/dwolla/tracing/mtl/RaiseTraceIntegrationSpec.scala`
- Modify: `natchez-tagless-mtl/src/main/scala-3/com/dwolla/tracing/mtl/Scala3UsageNote.scala`

**Interfaces:**
- Consumes: Tasks 2–6.
- Produces:
  - `sealed trait RaiseRecorder[F[_], Err[_]] { def onRaise: OnRaise[F, Err] }`
  - `RaiseRecorder.ErrorTypeKey: String` (unchanged, `"raise.error.type"`)
  - `RaiseRecorder.ErrorValueKey: String` — **new name**, `"raise.error.value"`,
    replacing `ErrorMessageKey` / `"raise.error.message"`
  - `RaiseRecorder.fromOnRaise[F[_], Err[_]](implicit or: OnRaise[F, Err]): RaiseRecorder[F, Err]`
  - `LowPriorityRaiseRecorder.fromTrace[F[_], Err[_]](implicit T: Trace[F], ev: RaiseRecorder.IsTraceableValue[Err]): RaiseRecorder[F, Err]`
    — note the witness. The simpler `fromTrace[F[_]](implicit T: Trace[F]): RaiseRecorder[F, TraceableValue]`
    does not compile on Scala 2.13.18 (only there): fixing `Err` in the return
    type makes it strictly more specific than `fromOnRaise`, offsetting
    `fromOnRaise`'s owner-derivation advantage, and 2.13 reports ambiguity
    instead of applying the low-priority ordering. A type bound
    `Err[x] <: TraceableValue[x]` fails the same way. See the design doc's
    amendment note in Part A §A.8.
  - `BarFixture` gains `implicit val traceableValueBarError: TraceableValue[BarError]`

- [ ] **Step 1: Write the failing test**

In `RaiseTraceIntegrationSuite.scala`, replace the two expected span fields
(currently lines 98-99) with the `TraceableValue`-rendered form:

```scala
        RaiseRecorder.ErrorTypeKey -> StringValue(classOf[BarError.Negative].getName),
        RaiseRecorder.ErrorValueKey -> StringValue("negative:-1")
```

And add, in the same suite, a test proving the rendering came from the
type class rather than `toString` — `BarError.Negative(-1).toString` is
`"Negative(-1)"`, so the two are distinguishable:

```scala
  test("the default recorder renders the error through TraceableValue, not toString") {
    assertNotEquals("negative:-1", BarError.Negative(-1).toString)
  }
```

- [ ] **Step 2: Run to verify it fails**

```bash
sbt "natchezTaglessMtlJVM/test"
```

Expected: compile failure — `ErrorValueKey` does not exist, and the module
still references pre-M10 signatures.

- [ ] **Step 3: Add a `TraceableValue[BarError]` to the fixture**

In `BarFixture.scala`, inside `object BarError`. Write it against
`TraceableValue`'s one abstract method, which is the form that is certain to
compile on natchez 0.3.10 — `toTraceValue(a: A): TraceValue`, as used at
`core/shared/src/main/scala/com/dwolla/tracing/ToTraceValue.scala:17`:

```scala
  /** Deliberately not `toString`: the integration suite asserts on this exact
    * string to prove the default recorder renders through `TraceableValue`
    * rather than falling back to the error's own `toString`.
    */
  implicit val traceableValueBarError: TraceableValue[BarError] =
    new TraceableValue[BarError] {
      def toTraceValue(a: BarError): TraceValue = a match {
        case Negative(i) => TraceValue.StringValue(s"negative:$i")
      }
    }
```

Add `import natchez.{TraceValue, TraceableValue}`. If natchez 0.3.10 turns out
to offer a `contramap` or a SAM-friendlier constructor, using it is fine — but
do not reach for one on the assumption it exists.

- [ ] **Step 4: Change `RaiseRecorder.scala`**

```scala
sealed trait RaiseRecorder[F[_], Err[_]] {
  def onRaise: OnRaise[F, Err]
}

object RaiseRecorder extends LowPriorityRaiseRecorder {
  val ErrorTypeKey: String = "raise.error.type"
  val ErrorValueKey: String = "raise.error.value"

  /** Higher priority: a user-supplied `OnRaise[F, Err]` wins. */
  implicit def fromOnRaise[F[_], Err[_]](implicit or: OnRaise[F, Err]): RaiseRecorder[F, Err] =
    new RaiseRecorder[F, Err] {
      def onRaise: OnRaise[F, Err] = or
    }
}

trait LowPriorityRaiseRecorder {

  /** Lower priority: used only when no `OnRaise[F, TraceableValue]` is available.
    * Records the typed error's runtime class name and its `TraceableValue`
    * rendering as span fields, under a `raise.*` key prefix deliberately
    * distinct from the `exception.*` fields a backend derives from
    * `attachError` — those still receive cats-mtl's opaque `Submarine` wrapper
    * on an unhandled raise.
    *
    * Pinned at `Err = TraceableValue`: it is the only evidence type class this
    * default can render through.
    */
  implicit def fromTrace[F[_], Err[_]](implicit
      T: Trace[F],
      isTV: RaiseRecorder.IsTraceableValue[Err]
  ): RaiseRecorder[F, Err] =
    new RaiseRecorder[F, Err] {
      def onRaise: OnRaise[F, Err] = new OnRaise[F, Err] {
        def apply[E](e: E)(implicit ev: Err[E]): F[Unit] =
          T.put(
            RaiseRecorder.ErrorTypeKey -> e.getClass.getName,
            RaiseRecorder.ErrorValueKey -> isTV.widen(ev).toTraceValue(e)
          )
      }
    }
}
```

- [ ] **Step 5: Thread `Err = TraceableValue` through `TraceWeaveTracer.scala`**

Four instances change. `WithInputsAndOutputsTracer`:

```scala
  implicit def fromAspect[Alg[_[_]], F[_]](implicit
      F: FlatMap[F],
      T: Trace[F],
      A: Aspect[Alg, TraceableValue, TraceableValue]
  ): WithInputsAndOutputsTracer[Alg, F]   // body unchanged — Aspect has no Err

  implicit def fromRaiseAspect[Alg[_[_]], F[_]](implicit
      F: FlatMap[F],
      T: Trace[F],
      A: RaiseAspect[Alg, TraceableValue, TraceableValue, TraceableValue],
      R: RaiseRecorder[F, TraceableValue]
  ): WithInputsAndOutputsTracer[Alg, F] =
    new WithInputsAndOutputsTracer[Alg, F] {
      def apply(alg: Alg[F]): Alg[F] =
        A.mapK(A.weave(alg))(
          RaiseArrow(
            TraceWeaveCapturingInputsAndOutputs[F],
            WeaveArrows.raiseLift[F, TraceableValue, TraceableValue, TraceableValue](R.onRaise)
          )
        )
    }
```

`WithInputsTracer.fromRaiseAspect` the same, with `Cod` free and
`Err = TraceableValue`:

```scala
  implicit def fromRaiseAspect[Alg[_[_]], Cod[_], F[_]](implicit
      F: Apply[F],
      T: Trace[F],
      A: RaiseAspect[Alg, TraceableValue, Cod, TraceableValue],
      syn: Synthetic[Cod],
      R: RaiseRecorder[F, TraceableValue]
  ): WithInputsTracer[Alg, Cod, F] =
    new WithInputsTracer[Alg, Cod, F] {
      def apply(alg: Alg[F]): Alg[F] =
        A.mapK(A.weave(alg))(
          RaiseArrow(
            TraceWeaveCapturingInputs[F, Cod],
            WeaveArrows.raiseLift[F, TraceableValue, Cod, TraceableValue](R.onRaise)
          )
        )
    }
```

M11 replaces this whole file; this task only keeps it compiling and correct.

- [ ] **Step 6: Update the test fixtures**

- `RaiseRecorderPriorityFixtures.poisonOnRaise: OnRaise[IO, TraceableValue]`,
  with `def apply[E](e: E)(implicit ev: TraceableValue[E]): IO[Unit]`.
- `RaiseRecorderPrioritySpec` — `implicitly[RaiseRecorder[IO, TraceableValue]]`
  in both the `compileErrors` snippet and the two behavioral tests.
  `recorder.onRaise(42)` still compiles: natchez supplies `TraceableValue[Int]`.
- `AspectPriorityFixtures.fooRaiseAspectPoison: RaiseAspect[Foo, TraceableValue, TraceableValue, TraceableValue]`,
  `mapK` taking `RaiseArrow[F, G, TraceableValue]`.
- `RaiseTraceValueSuite` and both `RaiseTraceIntegrationSpec` files —
  `RaiseAspect[Bar, TraceableValue, TraceableValue, TraceableValue]` and
  `DeriveRaise.aspect[Bar, TraceableValue, TraceableValue, TraceableValue]`.
- `Scala3UsageNote.scala` — the compiled Scala 3 declaration gains its fourth
  type argument.

- [ ] **Step 7: Run the natchez suite**

```bash
sbt "+natchezTaglessMtlJVM/test"
```

Expected: PASS on all three versions, including both priority specs
unmodified in substance — `Aspect` still outranks `RaiseAspect`, a user
`OnRaise` still outranks the `Trace` default.

- [ ] **Step 8: Verify the JS linker and scaladoc**

```bash
sbt "+natchezTaglessMtlJS/Test/scalaJSLinkerResult" "+natchezTaglessMtlJVM/doc"
```

Expected: both succeed.

- [ ] **Step 9: Commit**

```bash
git add natchez-tagless-mtl/
git commit -m "feat!: render raised errors through TraceableValue

RaiseRecorder's Trace-based default no longer calls toString on the domain
error. The raise.error.message span field becomes raise.error.value, since it
now holds whatever TraceableValue renders rather than a message."
```

---

## Task 8: documentation

The M6 docs actively state things this milestone makes false. Leaving them is
worse than not writing them.

**Files:**
- Modify: `natchez-tagless-mtl/src/main/scala/com/dwolla/tracing/mtl/package.scala`
- Modify: `docs/plans/raise-aspect/01-overview-design-and-laws.md` §3.2, §3.3, §3.4

**Interfaces:**
- Consumes: Tasks 2–7. No code changes.

- [ ] **Step 1: Invert the redaction paragraph in `package.scala`**

The paragraph beginning "This default rendering is not redaction-aware" is now
false. Replace it with:

```
  * This default rendering ''is'' redaction-aware, like the rest of this
  * library: `raise.error.value` is the error's `TraceableValue[E]` rendering,
  * so the newtype-plus-custom-`TraceableValue` pattern documented on
  * `TraceWeaveCapturingInputs`/`TraceWeaveCapturingInputsAndOutputs` applies
  * to error values too. An error ADT carrying a token or a card number should
  * declare a `TraceableValue` that omits or masks it, exactly as a sensitive
  * parameter type would. Note that `raise.error.type` still records the
  * error's runtime class name unconditionally.
```

Also update, in the same file:
- the `RaiseRecorder.ErrorMessageKey` reference in the Submarine section to
  `ErrorValueKey` / `raise.error.value`;
- the worked example's instance declaration to the four-argument
  `DeriveRaise.aspect[Validator, TraceableValue, TraceableValue, TraceableValue]`
  and both `RaiseAspect[Validator, …]` ascriptions;
- the "Overriding the default recording" example's `OnRaise[F]` to
  `OnRaise[F, TraceableValue]` with the `(implicit ev: TraceableValue[E])`
  parameter, showing `ev.toTraceValue(e)` being used in the fall-through case
  so the example demonstrates the new capability rather than ignoring it.

- [ ] **Step 2: Amend the overview**

`01-overview-design-and-laws.md` is the living design spec, so amend §3.2,
§3.3, and §3.4 in place to the M10 signatures, and add a dated note at the top
of §3.2:

```markdown
> **Amended 2026-07-30 by M10.** `RaisePull`, `RaiseArrow`, `RaiseFunctorK`,
> `RaiseAspect`, `OnRaise`, and the `WeaveArrows` members carry an `Err[_]`
> evidence parameter. The paragraph below about there being no `E` parameter
> still holds — transport remains uniform in the error type — but each
> application of a pull now carries `Err[E]`. See
> `03-evidence-carrying-transport-design.md`.
```

Keep the "no `E` parameter on the typeclass" paragraph. It is still true and
§A.2 of the design document depends on it being on record.

- [ ] **Step 3: Verify the scaladoc examples compile**

The `package.scala` examples are doctests
(`doctestSettings` in `build.sbt:40`).

```bash
sbt "+natchezTaglessMtlJVM/test"
```

Expected: PASS, with the doctest-generated tests included.

- [ ] **Step 4: Full cross-build verification**

```bash
sbt "+test"
```

Expected: every module green on 2.12, 2.13, and 3, with zero warnings.

- [ ] **Step 5: Commit**

```bash
git add natchez-tagless-mtl/src/main/scala/com/dwolla/tracing/mtl/package.scala \
        docs/plans/raise-aspect/01-overview-design-and-laws.md
git commit -m "docs: record that error recording now goes through TraceableValue"
```

---

## Acceptance criteria

From `03-evidence-carrying-transport-design.md` §E, Part A:

- [ ] `RaisePull`, `RaiseArrow`, `RaiseFunctorK`, `RaiseAspect`, `OnRaise`, and
      all four `WeaveArrows` members carry `Err`, with evidence threaded at
      every application site.
- [ ] Both macro axes summon `Err[E]` per raise parameter and emit an
      actionable diagnostic naming the method, error type, and type class when
      it is missing; `DerivationErrorSpec` pins the message on 2.12, 2.13, and 3.
- [ ] `git diff --stat` on `ExpectedWeaves.scala` is empty.
- [ ] The law suite passes at both `Err = Trivial` and `Err = Render`; the
      differential oracle, cross-version agreement, and edge-case suites pass
      with type-ascription changes only.
- [ ] `RaiseRecorder.fromTrace` renders via `TraceableValue[E]`, with a test
      proving a custom instance reaches the span field, and the `package.scala`
      redaction warning replaced by its inverse.
- [ ] `sbt +test` green on all three versions; both JS linkers green;
      `natchezTaglessMtlJVM/doc` succeeds; zero new warnings.

## Ground rules reminder

`raise-aspect-laws` is no longer frozen for this milestone — the design
ratified changing it — but the *meaning* of every law is. If implementing a
law change requires editing either operand of a `<->`, stop and report: that
is an overview-level question, not a milestone-level fix.

**M7 is affected but out of scope here.** M7 resolves method-local `Dom`/`Cod`
instances; after this milestone, `Err[E]` has the identical problem — a method
supplying its own `Render[ErrA]`/`TraceableValue[ErrA]` through its own
`implicit`/`using` clause. M7's hybrid must cover `Err` as a third case. Do
not attempt it here; note it in M7's document if it is not already recorded
(design §A.6 records it).
