# Raise-Aspect Effectful Test Fixtures Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove every mutable-state / raw-Java-primitive test double (`ListBuffer`, `AtomicInteger`, `collection.mutable.Buffer`, `var`) from the `raise-aspect-*`, `natchez-tagless-mtl`, and `otel4s-tagless-mtl` test suites, replacing them with genuine `cats.effect.kernel.Ref`-backed state, threaded through `F` via `flatMap`/`Applicative`/`Sync` — never `unsafeRunSync` inside a hand-written test assertion.

**Architecture:** Mutation is an effect capability. Any witness `F` a test uses for recording/counting must have `Sync[F]` so a real `Ref[F, _]` can back it. `Either[TestError, *]` (`Result`) and a bare `Either[WidgetError, *]` (`WidgetResult`) have no `Sync` instance and stay exactly as they are today for tests that need zero capability. Every test that currently mutates gets a *new*, `Sync`-capable witness instead: `Lazily[A] = EitherT[SyncIO, TestError, A]` (swapped from `EitherT[Eval, TestError, A]`) in `raise-aspect-core`/`raise-aspect-laws`/`raise-aspect-macros`, an analogous `WidgetLazily[A] = EitherT[SyncIO, WidgetError, A]` in the macros module's `MethodLocalInstanceSpec`, and a per-file `EitherT[SyncIO, <Error>, *]` swap in `natchez-tagless-mtl`/`otel4s-tagless-mtl` (those two files have no "pure law" tests to preserve, so their whole `F` moves). `RecordingFk` already has (uncommitted, in-flight) a `Ref[F, _]`-backed, `Monad[F]`-constrained design; this plan finishes it (adds a factory), and migrates every call site off the old zero-arg/synchronous-read API.

**Tech Stack:** Scala 2.12/2.13/3 cross-build, cats 2.13.0, cats-effect 3.7.0 (`SyncIO`, `Ref`), cats-effect-testkit (`Eq[SyncIO[_]]`, `SyncIO[Boolean] => Prop`), munit + munit-scalacheck + munit-cats-effect + discipline-munit.

## Global Constraints

- No `var`, no `scala.collection.mutable.*`, no `java.util.concurrent.atomic.*` anywhere in test code touched by this plan.
- No `.unsafeRunSync()` (or equivalent) inside a hand-written test assertion body. The two sanctioned exceptions, both approved in design discussion and both used only inside shared fixture/law-checking *plumbing*, never inside an assertion: (1) `Eq[SyncIO[A]]`/`Eq[Lazily[A]]` instances used by discipline law-checking (mirrors `cats-effect-testkit`'s own `eqSyncIOA`), and (2) `CarrierArrows.resultToLazily`'s `RaisePull`, which transports a capability from a genuinely lazy `F` back to a genuinely strict one — forcing at that exact seam is what "transport back to strict" *means*, not a shortcut.
- Every test body that needs to run a `SyncIO`/`Lazily` action is rewritten to *return* `SyncIO[Unit]` from the `test(...)`/`property(...)` block, evaluated by `munit-cats-effect`'s registered `SyncIO` transform (`extends CatsEffectSuite`) — never popped out imperatively.
- `Result[A] = Either[TestError, A]` and `WidgetResult[A] = Either[WidgetError, A]` are untouched in signature and behavior; they keep testing that the laws/behavior hold for a zero-capability `F`. Only `Lazily`/`WidgetLazily` (and, once introduced, their construction) change.
- Preserve every existing assertion's intent and every doc comment's *reasoning* (rewrite comments that describe now-stale mechanics, e.g. "Eval is total" — keep comments that explain *why a test exists*).
- Cross-build correctness: every file under `src/test/scala-2` and `src/test/scala-3` gets the mirrored change; `raise-aspect-macros/src/test/scala/.../ExpectedWeaves.scala` must stay free of version-specific syntax (no `using`, no `@experimental`).

## File Structure

New files:
- `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/SyncIOTestSyntax.scala` — the one shared `EitherT[SyncIO, E, A] => SyncIO[A]` "run or fail the test" helper, generic in the error type `E`, used by every migrated test body across every module that depends on `raise-aspect-core`'s test sources.

Modified files, grouped by phase (see Tasks below for exact diffs):
- Phase 0 (foundation): `build.sbt`, `raise-aspect-core/.../RecordingFk.scala`, `raise-aspect-core/.../TestFixtures.scala`, `raise-aspect-core/.../CarrierArrows.scala`.
- Phase 1 (`raise-aspect-core` consumers): `EvidenceThreadingSpec.scala`, `TestAlgReferenceSpec.scala`, `WeaveInterpreterSpec.scala`, `RaiseRecorderSpec.scala`, `ObservingCapabilitySpec.scala`.
- Phase 2 (`raise-aspect-laws`): `LawsInstances.scala`, `RaiseAspectSuite.scala`, `ConservativeExtensionSuite.scala`.
- Phase 3 (`raise-aspect-macros`, shared + scala-3): `ExpectedWeaves.scala`, `MethodLocalInstanceSpec.scala`, `EdgeCaseDerivationSpec.scala`, `DifferentialOracleSpec.scala`, `UsingAlgSpec.scala`, `CrossVersionAgreementSpec.scala`, `DerivedConservativeExtensionSpec.scala`.
- Phase 4 (`raise-aspect-macros`, scala-2 mirrors): `MethodLocalInstanceSpec.scala`, `EdgeCaseDerivationSpec.scala`, `DifferentialOracleSpec.scala`, `CrossVersionAgreementSpec.scala`, `DerivedConservativeExtensionSpec.scala`.
- Phase 5 (`natchez-tagless-mtl` / `otel4s-tagless-mtl`): `TraceableRaiseAspectSpec.scala`, `DerivesFooRaiseSpec.scala`.
- Phase 6: full cross-build verification.

---

## Task 1: Foundation — `build.sbt`, `RecordingFk` factory, generic test algebras, `SyncIOTestSyntax`, `CarrierArrows`

**Files:**
- Modify: `build.sbt` (add `cats-effect-testkit` to `raiseAspectLaws`)
- Modify: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/RecordingFk.scala:1-56` (add companion factory)
- Modify: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/TestFixtures.scala` (add `GenericTestAlg`, `GenericPlainAlg`)
- Create: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/SyncIOTestSyntax.scala`
- Modify: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/CarrierArrows.scala:1-42`

**Interfaces:**
- Produces: `RecordingFk.apply[F[_]: Sync, Dom[_], Cod[_]]: F[RecordingFk[F, Dom, Cod]]` — every later task's `RecordingFk` construction goes through this, never `new RecordingFk(...)` directly.
- Produces: `GenericTestAlg[F[_]: Applicative](eOutcome: Int): TestAlg[F]` and `GenericPlainAlg[F[_]: Applicative]: PlainAlg[F]` — the `Lazily`-instantiable replacements for `EitherTestAlg`/`EitherPlainAlg` wherever a test needs `TestAlg[Lazily]`/`PlainAlg[Lazily]`. `EitherTestAlg`/`EitherPlainAlg` themselves are untouched (still used by every `Result`-typed call site).
- Produces: `implicit class RunOrFailSyncIOOps[E, A](fa: EitherT[SyncIO, E, A])` with `.runOrFail: SyncIO[A]` — the one place `Left` becomes a test failure.
- Produces: `CarrierArrows.Result`/`CarrierArrows.Lazily` (unchanged names, `Lazily`'s base now `SyncIO`).

- [ ] **Step 1: Add `cats-effect-testkit` to `raiseAspectLaws`**

In `build.sbt`, find the `raiseAspectLaws` project's `libraryDependencies` (already carries the uncommitted `cats-effect`/`munit-cats-effect` additions from an earlier session):

```scala
lazy val raiseAspectLaws = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("raise-aspect-laws"))
  .settings(
    name := "raise-aspect-laws",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-laws" % catsVersion,
      "org.typelevel" %%% "discipline-munit" % disciplineMunitVersion,
      "org.typelevel" %%% "cats-effect" % catsEffectVersion % Test,
      "org.typelevel" %%% "munit-cats-effect" % "2.2.0" % Test,
    ),
```

Add `cats-effect-testkit` (provides `Eq[SyncIO[A]]` and the `SyncIO[Boolean] => Prop` conversion used by Task 9):

```scala
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-laws" % catsVersion,
      "org.typelevel" %%% "discipline-munit" % disciplineMunitVersion,
      "org.typelevel" %%% "cats-effect" % catsEffectVersion % Test,
      "org.typelevel" %%% "cats-effect-testkit" % catsEffectVersion % Test,
      "org.typelevel" %%% "munit-cats-effect" % "2.2.0" % Test,
    ),
```

- [ ] **Step 2: Add the `RecordingFk` companion factory**

`RecordingFk.scala` already has (uncommitted) the `Ref`-backed class shape. Add a companion object with a factory that builds both `Ref`s and requires only `Sync[F]` (stronger than the class's own `Monad[F]`, needed only here to construct):

```scala
object RecordingFk {
  def apply[F[_] : Sync, Dom[_], Cod[_]]: F[RecordingFk[F, Dom, Cod]] =
    (
      Ref.of[F, Vector[RecordedWeave[F, Dom, Cod]]](Vector.empty),
      Ref.of[F, Vector[String]](Vector.empty)
    ).mapN(new RecordingFk[F, Dom, Cod](_, _))
}
```

Place this immediately after the `RecordedWeave`/`object RecordedWeave` block (line 26) and before `final class RecordingFk` (so the class stays the primary declaration). `cats.effect.*`/`cats.syntax.all.*` are already imported at the top of the file (per the uncommitted diff).

- [ ] **Step 3: Run existing tests to confirm the factory compiles**

`sbt raiseAspectCoreJVM/Test/compile`
Expected: still fails (call sites elsewhere aren't fixed yet) — this step only confirms `RecordingFk.scala` itself compiles in isolation. If it reports an error *inside* `RecordingFk.scala`, fix that before continuing; errors from other files are expected and addressed in later tasks.

- [ ] **Step 4: Add `GenericTestAlg`/`GenericPlainAlg` to `TestFixtures.scala`**

`EitherTestAlg`/`EitherPlainAlg` (lines 74-102 of `TestFixtures.scala`) are hardcoded to `Either[TestError, *]` and stay exactly as they are — every `Result`-typed call site keeps using them unchanged. Add two new, `F`-polymorphic siblings immediately after `EitherPlainAlg`'s closing brace (line 102), for the call sites that need `TestAlg[Lazily]`/`PlainAlg[Lazily]`. `PlainAlg`'s `p` returns a `Left` directly rather than through a `Raise` capability (see `EitherPlainAlg`'s own doc comment) — a polymorphic `F` has no built-in "return a Left", so `GenericPlainAlg` needs `ApplicativeError[F, TestError]`, not plain `Applicative[F]`; both `Result` (`Either[TestError, *]`) and `Lazily` (`EitherT[SyncIO, TestError, *]`, deriving it from `Sync[SyncIO]`) satisfy that:

```scala
/** Same behavior as `EitherTestAlg`, generic in `F` so it can be instantiated
  * at `Lazily` wherever a test needs genuine `Sync` capability (recording,
  * counting) alongside `EitherTestAlg`'s exact shape. `EitherTestAlg` itself
  * stays fixed to `Either` — this is additive, not a replacement.
  */
final class GenericTestAlg[F[_]](eOutcome: Int)(implicit F: Applicative[F]) extends TestAlg[F] {
  import TestError._

  def a(i: Int)(implicit R: Raise[F, ErrA]): F[String] =
    if (i < 0) R.raise(NegativeInput(i)) else s"a:$i".pure[F]

  def b(x: String, y: => Int)(implicit R: Raise[F, ErrB]): F[Int] =
    if (x.isEmpty) R.raise(EmptyInput("x")) else (x.length + y).pure[F]

  def c(i: Int): F[Int] = (i * 2).pure[F]

  def d(i: Int)(j: Int)(implicit R: Raise[F, ErrA]): F[Int] =
    if (i + j < 0) R.raise(NegativeInput(i + j)) else (i + j).pure[F]

  def e(implicit R1: Raise[F, ErrA], R2: Raise[F, ErrB]): F[Unit] =
    if (eOutcome < 0) R1.raise(NegativeInput(eOutcome))
    else if (eOutcome > 0) R2.raise(EmptyInput("e"))
    else ().pure[F]
}

/** Same behavior as `EitherPlainAlg`, generic in `F`. */
final class GenericPlainAlg[F[_]](implicit F: ApplicativeError[F, TestError]) extends PlainAlg[F] {
  def p(i: Int): F[String] =
    if (i < 0) TestError.NegativeInput(i).raiseError[F, String] else s"p:$i".pure[F]
}
```

Add `import cats.{Applicative, ApplicativeError}` and `import cats.syntax.all._` (for `.pure`/`.raiseError`) at the top of `TestFixtures.scala` if not already present (check the existing import list first — `cats.mtl.Raise` is almost certainly already imported since `EitherTestAlg` uses it).

- [ ] **Step 5: Create `SyncIOTestSyntax.scala`**

```scala
package com.dwolla.tagless.mtl

import cats.data.EitherT
import cats.effect.SyncIO
import cats.syntax.all._

/** Every migrated test body ends up with a `Lazily[Unit]` (or
  * `EitherT[SyncIO, WidgetError, Unit]`) describing the whole test —
  * `runOrFail` is the one place that turns "raised an error nobody expected"
  * into a failed `SyncIO`, so munit-cats-effect's registered `SyncIO`
  * transform reports it as a test failure with a real stack trace, instead of
  * silently succeeding on an unexamined `Left`.
  */
object SyncIOTestSyntax {
  implicit class RunOrFailSyncIOOps[E, A](private val fa: EitherT[SyncIO, E, A]) {
    def runOrFail: SyncIO[A] =
      fa.value.flatMap {
        case Right(a) => SyncIO.pure(a)
        case Left(e) => SyncIO.raiseError(new AssertionError(s"test raised unexpectedly: $e"))
      }
  }
}
```

- [ ] **Step 6: Rewrite `CarrierArrows.scala`**

Current (lines 1-42, quoted in full):

```scala
package com.dwolla.tagless.mtl

import cats.Functor
import cats.data.EitherT
import cats.mtl.Raise
import cats.{Eval, ~>}

/** A genuine carrier change, for the tests and laws that need a `RaiseArrow`
  * which is not the identity.
  *
  * `Eval` is total, so the pull can transport a `Raise[Lazily, E]` back to
  * `Raise[Result, E]` by running it — the canonical construction of a pull
  * from a `G ~> F`.
  *
  * Polymorphic in `Err` because the pull genuinely does not consult the
  * evidence: transport here is uniform in `E`. That is what lets the law suite
  * instantiate arrow coherence at `Err = Trivial` over a ''non-identity''
  * arrow, which is the only way that instantiation says anything — it is there
  * to show coherence does not secretly depend on having `Err[E]` in hand, and
  * at `RaiseArrow.id` both sides of the law are literally the same expression.
  * `eraseWeave` was parametric in `Err` for the same reason before M12 deleted
  * it.
  */
object CarrierArrows {
  type Result[A] = Either[TestError, A]
  type Lazily[A] = EitherT[Eval, TestError, A]

  def resultToLazily[Err[_]]: RaiseArrow[Result, Lazily, Err] =
    RaiseArrow(
      new (Result ~> Lazily) {
        def apply[A](fa: Result[A]): Lazily[A] = EitherT(Eval.now(fa))
      },
      new RaisePull[Lazily, Result, Err] {
        def apply[E](rg: Raise[Lazily, E])(implicit ev: Err[E]): Raise[Result, E] =
          new Raise[Result, E] {
            val functor: Functor[Result] = Functor[Result]

            def raise[E2 <: E, A](e: E2): Result[A] = rg.raise[E2, A](e).value.value
          }
      }
    )
}
```

Replace with:

```scala
package com.dwolla.tagless.mtl

import cats.Functor
import cats.data.EitherT
import cats.effect.SyncIO
import cats.mtl.Raise
import cats.~>

/** A genuine carrier change, for the tests and laws that need a `RaiseArrow`
  * which is not the identity.
  *
  * The pull transports a `Raise[Lazily, E]` back to `Raise[Result, E]` by
  * running the underlying `SyncIO` synchronously (`.unsafeRunSync()`) —
  * that's what "transport a capability from a suspended `F` back to a strict
  * one" means at the seam; there is no other way to produce a `Result[A]`
  * (an already-resolved `Either`) from a `Lazily[A]` (a suspended
  * computation) without running it. This is fixture/law-checking plumbing,
  * not a test assertion — the same category of forced evaluation as
  * `Eq[SyncIO[A]]`.
  *
  * Polymorphic in `Err` because the pull genuinely does not consult the
  * evidence: transport here is uniform in `E`. That is what lets the law suite
  * instantiate arrow coherence at `Err = Trivial` over a ''non-identity''
  * arrow, which is the only way that instantiation says anything — it is there
  * to show coherence does not secretly depend on having `Err[E]` in hand, and
  * at `RaiseArrow.id` both sides of the law are literally the same expression.
  * `eraseWeave` was parametric in `Err` for the same reason before M12 deleted
  * it.
  */
object CarrierArrows {
  type Result[A] = Either[TestError, A]
  type Lazily[A] = EitherT[SyncIO, TestError, A]

  def resultToLazily[Err[_]]: RaiseArrow[Result, Lazily, Err] =
    RaiseArrow(
      new (Result ~> Lazily) {
        def apply[A](fa: Result[A]): Lazily[A] = EitherT(SyncIO.pure(fa))
      },
      new RaisePull[Lazily, Result, Err] {
        def apply[E](rg: Raise[Lazily, E])(implicit ev: Err[E]): Raise[Result, E] =
          new Raise[Result, E] {
            val functor: Functor[Result] = Functor[Result]

            def raise[E2 <: E, A](e: E2): Result[A] = rg.raise[E2, A](e).value.unsafeRunSync()
          }
      }
    )
}
```

- [ ] **Step 7: Commit**

```bash
git add build.sbt raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/RecordingFk.scala raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/TestFixtures.scala raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/SyncIOTestSyntax.scala raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/CarrierArrows.scala
git commit -m "test: finish RecordingFk's Ref-based redesign; add Sync-capable Lazily witness"
```

---

## Task 2: `EvidenceThreadingSpec.scala`

**Files:**
- Modify: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/EvidenceThreadingSpec.scala:1-38`

**Interfaces:**
- Consumes: `CarrierArrows.Lazily`, `SyncIOTestSyntax.RunOrFailSyncIOOps`.

- [ ] **Step 1: Rewrite the file**

Current (full file):

```scala
package com.dwolla.tagless.mtl

import cats.mtl.Raise
import munit.FunSuite

import scala.collection.mutable.ListBuffer

import TestError._

class EvidenceThreadingSpec extends FunSuite {
  private type F[A] = Either[TestError, A]

  test("a second error type on the same carrier gets its own evidence") {
    val rendered = ListBuffer.empty[String]

    val hook: OnRaise[F, Render] = new OnRaise[F, Render] {
      def apply[E](e: E)(implicit ev: Render[E]): F[Unit] = {
        rendered += ev.render(e)
        Right(())
      }
    }

    val decoratedA = RaiseAspect.observing[F, ErrA, Render](Raise[F, ErrA], hook)
    val decoratedB = RaiseAspect.observing[F, ErrB, Render](Raise[F, ErrB], hook)

    decoratedA.raise[ErrA, Int](NegativeInput(-1))
    decoratedB.raise[ErrB, Int](EmptyInput("f"))

    assertEquals(rendered.toList, List("errA:NegativeInput(-1)", "errB:EmptyInput(f)"))
  }
}
```

Replace with:

```scala
package com.dwolla.tagless.mtl

import cats.effect.{Ref, SyncIO}
import cats.mtl.Raise
import cats.syntax.all._
import munit.CatsEffectSuite

import CarrierArrows.Lazily
import SyncIOTestSyntax._
import TestError._

class EvidenceThreadingSpec extends CatsEffectSuite {
  test("a second error type on the same carrier gets its own evidence") {
    (for {
      rendered <- Ref.of[Lazily, Vector[String]](Vector.empty)
      hook = new OnRaise[Lazily, Render] {
        def apply[E](e: E)(implicit ev: Render[E]): Lazily[Unit] = rendered.update(_ :+ ev.render(e))
      }
      decoratedA = RaiseAspect.observing[Lazily, ErrA, Render](Raise[Lazily, ErrA], hook)
      decoratedB = RaiseAspect.observing[Lazily, ErrB, Render](Raise[Lazily, ErrB], hook)
      _ <- decoratedA.raise[ErrA, Int](NegativeInput(-1))
      _ <- decoratedB.raise[ErrB, Int](EmptyInput("f"))
      seen <- rendered.get
    } yield assertEquals(seen.toList, List("errA:NegativeInput(-1)", "errB:EmptyInput(f)"))).runOrFail
  }
}
```

Note: `decoratedA.raise[ErrA, Int](NegativeInput(-1))` returns `Lazily[Int]` — a *raised* value, i.e. this call itself produces `EitherT(SyncIO.pure(Left(NegativeInput(-1))))` once run. Binding it with `<-` inside the `for`-comprehension over `Lazily` short-circuits the *rest of the `Lazily` chain* (matching `EitherT`'s monadic short-circuit on `Left`) — but the hook still fires because `RaiseAspect.observing` sequences the hook's effect *before* the raise (see `ObservingCapabilitySpec`'s "the hook's effect is sequenced before the raise" test in Task 6), so `rendered` is updated before `decoratedA.raise(...)`'s `Left` short-circuits the outer `for`. Since the outer `for` short-circuits after the first raise, `decoratedB.raise(...)` would never run if sequenced directly after — that's a change in behavior from the original (which called both regardless, since `Either`'s `Right(())` result was simply discarded, but nothing early-exits an imperative test body). Fix by running each raise's effect independently and discarding its (expected-to-be-`Left`) result, rather than binding it into the short-circuiting chain:

```scala
      _ <- decoratedA.raise[ErrA, Int](NegativeInput(-1)).value.void
      _ <- decoratedB.raise[ErrB, Int](EmptyInput("f")).value.void
```

`.value` strips to `SyncIO[Either[ErrA, Int]]`/`SyncIO[Either[ErrB, Int]]` (note: `decoratedA.raise[ErrA, Int]`'s `Lazily[Int]` is actually typed via `Raise[Lazily, ErrA]`'s carrier, which widens the error channel to `TestError` per `Raise`'s contravariance — same as today), `.void` discards the result without caring whether it's `Left`/`Right`, and critically this is a plain `SyncIO` step (not an `EitherT` bind), so it does **not** short-circuit the outer `for`. Final file body for the `for`-comprehension:

```scala
    (for {
      rendered <- Ref.of[Lazily, Vector[String]](Vector.empty)
      hook = new OnRaise[Lazily, Render] {
        def apply[E](e: E)(implicit ev: Render[E]): Lazily[Unit] = rendered.update(_ :+ ev.render(e))
      }
      decoratedA = RaiseAspect.observing[Lazily, ErrA, Render](Raise[Lazily, ErrA], hook)
      decoratedB = RaiseAspect.observing[Lazily, ErrB, Render](Raise[Lazily, ErrB], hook)
      _ <- decoratedA.raise[ErrA, Int](NegativeInput(-1)).value.void
      _ <- decoratedB.raise[ErrB, Int](EmptyInput("f")).value.void
      seen <- rendered.get
    } yield assertEquals(seen.toList, List("errA:NegativeInput(-1)", "errB:EmptyInput(f)"))).runOrFail
```

Here the outer `for` is now entirely built from `Lazily`-typed steps (`Ref.of[Lazily, _]`, `rendered.get`) except the two `.value.void` lines, which are `SyncIO`-typed — that's a type mismatch (`for` over `Lazily` can't bind a bare `SyncIO` step). Lift each back into `Lazily` with `EitherT.liftF`:

```scala
      _ <- EitherT.liftF[SyncIO, TestError, Unit](decoratedA.raise[ErrA, Int](NegativeInput(-1)).value.void)
      _ <- EitherT.liftF[SyncIO, TestError, Unit](decoratedB.raise[ErrB, Int](EmptyInput("f")).value.void)
```

Add `import cats.data.EitherT` to the import list. This is the final, correct form — copy the whole rewritten file exactly as follows:

```scala
package com.dwolla.tagless.mtl

import cats.data.EitherT
import cats.effect.{Ref, SyncIO}
import cats.mtl.Raise
import cats.syntax.all._
import munit.CatsEffectSuite

import CarrierArrows.Lazily
import SyncIOTestSyntax._
import TestError._

class EvidenceThreadingSpec extends CatsEffectSuite {
  test("a second error type on the same carrier gets its own evidence") {
    (for {
      rendered <- Ref.of[Lazily, Vector[String]](Vector.empty)
      hook = new OnRaise[Lazily, Render] {
        def apply[E](e: E)(implicit ev: Render[E]): Lazily[Unit] = rendered.update(_ :+ ev.render(e))
      }
      decoratedA = RaiseAspect.observing[Lazily, ErrA, Render](Raise[Lazily, ErrA], hook)
      decoratedB = RaiseAspect.observing[Lazily, ErrB, Render](Raise[Lazily, ErrB], hook)
      _ <- EitherT.liftF[SyncIO, TestError, Unit](decoratedA.raise[ErrA, Int](NegativeInput(-1)).value.void)
      _ <- EitherT.liftF[SyncIO, TestError, Unit](decoratedB.raise[ErrB, Int](EmptyInput("f")).value.void)
      seen <- rendered.get
    } yield assertEquals(seen.toList, List("errA:NegativeInput(-1)", "errB:EmptyInput(f)"))).runOrFail
  }
}
```

- [ ] **Step 2: Run the test**

`sbt raiseAspectCoreJVM/testOnly com.dwolla.tagless.mtl.EvidenceThreadingSpec`
Expected: PASS (once Task 1 compiles cleanly).

- [ ] **Step 3: Commit**

```bash
git add raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/EvidenceThreadingSpec.scala
git commit -m "test: migrate EvidenceThreadingSpec off ListBuffer onto Ref[Lazily, _]"
```

---

## Task 3: `TestAlgReferenceSpec.scala`

**Files:**
- Modify: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/TestAlgReferenceSpec.scala:1-50`

**Interfaces:**
- Consumes: `RecordingFk.apply`, `CarrierArrows.Lazily`, `GenericTestAlg`, `SyncIOTestSyntax.RunOrFailSyncIOOps`.

- [ ] **Step 1: Rewrite the file**

Current (full file, quoted above in research) uses `F = Either[TestError, A]`, `new EitherTestAlg(eOutcome)`, a `ListBuffer`-backed hook, and a zero-arg `RecordingFk[F, ...]`. Since this test both records (via the hook) and constructs a `RecordingFk`, its whole `F` moves to `Lazily`, and `new EitherTestAlg(eOutcome)` becomes `new GenericTestAlg[Lazily](eOutcome)`:

```scala
package com.dwolla.tagless.mtl

import cats.effect.Ref
import cats.mtl.Raise
import cats.syntax.all._
import munit.CatsEffectSuite

import CarrierArrows.Lazily
import SyncIOTestSyntax._
import TestError._

class TestAlgReferenceSpec extends CatsEffectSuite {
  private val raiseF: Raise[Lazily, TestError] = Raise[Lazily, TestError]

  private val ref: RaiseAspect[TestAlg, Render, Render, Render] =
    TestAlgReference.referenceRaiseAspect[Render, Render, Render]

  /** The differential oracle only ever compares this instance ''against'' a
    * derived one, so a defect the two shared would be invisible there. `e` is
    * where that matters most: it is the only method with two capabilities,
    * and a comparison against the derived instance's return value alone
    * cannot tell which one was decorated — that value is identical whether
    * or not `RaiseAspect.observing` was applied to either. A hook rendering
    * through `Err[E]` separates them: `errA:` can only come from `R1`'s
    * decoration and `errB:` only from `R2`'s.
    */
  test("intercept decorates both of e's capabilities, each with its own Err evidence") {
    def raisedThrough(eOutcome: Int, rendered: Ref[Lazily, Vector[String]]): Lazily[Unit] = {
      val hook: OnRaise[Lazily, Render] = new OnRaise[Lazily, Render] {
        def apply[E](e: E)(implicit ev: Render[E]): Lazily[Unit] = rendered.update(_ :+ ev.render(e))
      }

      for {
        recorder <- RecordingFk[Lazily, Render, Render]
        out <- ref.intercept(new GenericTestAlg[Lazily](eOutcome))(recorder.fk, hook).e(raiseF, raiseF)
      } yield out
    }

    (for {
      rendered <- Ref.of[Lazily, Vector[String]](Vector.empty)
      r1 <- raisedThrough(-1, rendered).value
      r2 <- raisedThrough(1, rendered).value
      r3 <- raisedThrough(0, rendered).value
      _ = assertEquals(r1, NegativeInput(-1).asLeft[Unit].leftWiden[TestError])
      _ = assertEquals(r2, EmptyInput("e").asLeft[Unit].leftWiden[TestError])
      _ = assertEquals(r3, ().asRight[TestError])
      seen <- rendered.get
    } yield assertEquals(seen.toList, List("errA:NegativeInput(-1)", "errB:EmptyInput(e)"))).runOrFail
  }
}
```

Note `raisedThrough`'s result is captured via `.value` (stripping to `SyncIO[Either[TestError, Unit]]`) rather than `.runOrFail`, because a `Left` here is the *expected* outcome for two of the three calls — this test's whole point is asserting on which side of the `Either` each call landed, so unlike other tests, we must not treat `Left` as a failure. Only the outermost `for` (over `Lazily`, built from `Ref.of`/`rendered.get`, which cannot themselves raise) uses `.runOrFail`.

- [ ] **Step 2: Run the test**

`sbt raiseAspectCoreJVM/testOnly com.dwolla.tagless.mtl.TestAlgReferenceSpec`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/TestAlgReferenceSpec.scala
git commit -m "test: migrate TestAlgReferenceSpec off ListBuffer/zero-arg RecordingFk onto Ref[Lazily, _]"
```

---

## Task 4: `WeaveInterpreterSpec.scala`

**Files:**
- Modify: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/WeaveInterpreterSpec.scala:1-55`

**Interfaces:**
- Consumes: `CarrierArrows.Lazily`, `GenericTestAlg`, `SyncIOTestSyntax.RunOrFailSyncIOOps`.

- [ ] **Step 1: Rewrite the file**

Only the second test (`"a RaiseAspect-only algebra resolves to the RaiseAspect instance and runs the hook"`) mutates (`ListBuffer`); the first (`"an Aspect instance outranks..."`) does not and stays exactly as-is on `F = Either[TestError, A]`/`EitherPlainAlg`. Only the second test's `F`/algebra moves to `Lazily`/`GenericTestAlg`:

```scala
package com.dwolla.tagless.mtl

import cats.effect.Ref
import cats.mtl.Raise
import cats.syntax.all._
import cats.tagless.aop.Aspect
import cats.~>
import munit.CatsEffectSuite

import CarrierArrows.Lazily
import SyncIOTestSyntax._
import TestError._

class WeaveInterpreterSpec extends CatsEffectSuite {
  private type F[A] = Either[TestError, A]
  private type W[A] = Aspect.Weave[F, Render, Render, A]

  // The library's own forgetful arrow, rather than a hand-rolled one — the
  // interpreter a caller supplies in production is this shape.
  private val erase: W ~> F = WeaveArrows.codomainTarget[F, Render, Render]

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

    type WLazily[A] = Aspect.Weave[Lazily, Render, Render, A]
    val eraseLazily: WLazily ~> Lazily = WeaveArrows.codomainTarget[Lazily, Render, Render]

    (for {
      rendered <- Ref.of[Lazily, Vector[String]](Vector.empty)
      hook = new OnRaise[Lazily, Render] {
        def apply[E](e: E)(implicit ev: Render[E]): Lazily[Unit] = rendered.update(_ :+ ev.render(e))
      }
      interpreted =
        WeaveInterpreter[TestAlg, Render, Render, Render, Lazily]
          .apply(new GenericTestAlg[Lazily](0))(eraseLazily, hook)
      r1 <- interpreted.a(3)(Raise[Lazily, ErrA]).value
      _ = assertEquals(r1, Right("a:3"))
      seen1 <- rendered.get
      _ = assertEquals(seen1.toList, Nil)
      r2 <- interpreted.a(-3)(Raise[Lazily, ErrA]).value
      _ = assertEquals(r2, Left(NegativeInput(-3)): F[String])
      seen2 <- rendered.get
      _ = assertEquals(seen2.toList, List("errA:NegativeInput(-3)"))
    } yield ()).runOrFail
  }
}
```

Note: `implicit val reference` is unused by the second test's body as rewritten (the original used `WeaveInterpreter[TestAlg, ...]`'s implicit resolution, which picks between an `Aspect`/`RaiseAspect` instance for `TestAlg` — check whether `WeaveInterpreter.apply` requires this as an *implicit* parameter it resolves internally, in which case keep the `implicit val` exactly as-is since removing it would change what's under test, or as an explicit argument, in which case thread it through explicitly). Read `WeaveInterpreter`'s signature in `raise-aspect-core/src/main/scala/.../WeaveInterpreter.scala` before finalizing this step, and preserve whichever resolution mechanism the original test exercised — this is the one place in this task where you must check the production signature rather than trust this plan's snippet verbatim.

- [ ] **Step 2: Run the test**

`sbt raiseAspectCoreJVM/testOnly com.dwolla.tagless.mtl.WeaveInterpreterSpec`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/WeaveInterpreterSpec.scala
git commit -m "test: migrate WeaveInterpreterSpec's hook-recording test off ListBuffer onto Ref[Lazily, _]"
```

---

## Task 5: `RaiseRecorderSpec.scala`

**Files:**
- Modify: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/RaiseRecorderSpec.scala:1-63`

**Interfaces:**
- Consumes: nothing from other tasks — self-contained (`Id` swaps to `SyncIO` directly, no error channel needed, so no `Lazily`/`EitherT` involved here).

- [ ] **Step 1: Rewrite the file**

Current (full file, quoted above in research) uses `cats.Id` and `collection.mutable.Buffer`. Swap `Id` for `SyncIO` (no error type is raised in this file — it's about implicit-priority resolution of `OnRaise`/`DefaultOnRaise`, not about `Raise`'s error channel — so no `EitherT` wrapper is needed) and the buffer for `Ref[SyncIO, Vector[String]]`:

```scala
package com.dwolla.tagless.mtl

import cats.effect.{Ref, SyncIO}
import cats.syntax.all._
import munit.CatsEffectSuite
import org.typelevel.scalaccompat.annotation.*

class RaiseRecorderSpec extends CatsEffectSuite {
  trait Rendered[A] { def render(a: A): String }
  object Rendered {
    implicit val intRendered: Rendered[Int] = (a: Int) => a.toString
  }

  // Distinguishable so the test observes WHICH instance resolved, not merely
  // that one did.
  private def recording(into: Ref[SyncIO, Vector[String]], label: String): OnRaise[SyncIO, Rendered] =
    new OnRaise[SyncIO, Rendered] {
      def apply[E](e: E)(implicit ev: Rendered[E]): SyncIO[Unit] = into.update(_ :+ s"$label:${ev.render(e)}")
    }

  test("with only a DefaultOnRaise in scope, the default resolves and runs") {
    for {
      log <- Ref.of[SyncIO, Vector[String]](Vector.empty)
      _ <- {
        implicit val default: DefaultOnRaise[SyncIO, Rendered] = new DefaultOnRaise[SyncIO, Rendered] {
          def onRaise: OnRaise[SyncIO, Rendered] = recording(log, "default")
        }
        implicitly[RaiseRecorder[SyncIO, Rendered]].onRaise(42)
      }
      seen <- log.get
    } yield assertEquals(seen.toList, List("default:42"))
  }

  test("a user OnRaise outranks the DefaultOnRaise, and it is the one that runs") {
    for {
      log <- Ref.of[SyncIO, Vector[String]](Vector.empty)
      _ <- {
        // Deliberately unreferenced: fromOnRaise outranks fromDefault whenever
        // both are in scope, so implicit search never touches this val — being
        // an unchosen candidate is exactly what this test is checking.
        @unused implicit val default: DefaultOnRaise[SyncIO, Rendered] = new DefaultOnRaise[SyncIO, Rendered] {
          def onRaise: OnRaise[SyncIO, Rendered] = recording(log, "default")
        }
        implicit val user: OnRaise[SyncIO, Rendered] = recording(log, "user")
        implicitly[RaiseRecorder[SyncIO, Rendered]].onRaise(42)
      }
      seen <- log.get
    } yield assertEquals(seen.toList, List("user:42"))
  }

  test("resolving with both in scope reports no ambiguous implicit") {
    // Guards the shape: if someone later gives fromDefault a different
    // type-parameter list from fromOnRaise, this fails on 2.13 (and
    // passes on 2.12 and 3).
    val errors: String = compileErrors(
      """import cats.effect.SyncIO
implicit val default: DefaultOnRaise[SyncIO, Rendered] = new DefaultOnRaise[SyncIO, Rendered] {
  def onRaise: OnRaise[SyncIO, Rendered] = new OnRaise[SyncIO, Rendered] {
    def apply[E](e: E)(implicit ev: Rendered[E]): SyncIO[Unit] = SyncIO.unit
  }
}
implicit val user: OnRaise[SyncIO, Rendered] = new OnRaise[SyncIO, Rendered] {
  def apply[E](e: E)(implicit ev: Rendered[E]): SyncIO[Unit] = SyncIO.unit
}
implicitly[RaiseRecorder[SyncIO, Rendered]]"""
    )
    assertNoDiff(errors, "")
  }
}
```

Note the first two tests wrap the `implicit val`/`implicitly[...]` block inside a nested block passed to `<-` — this is necessary because the `implicit val`s must be in scope exactly when `implicitly[RaiseRecorder[SyncIO, Rendered]]` resolves, and that resolution must happen *inside* the `for`-comprehension (not before it) so the `log` `Ref` it closes over is the one just constructed. `RaiseRecorder[SyncIO, Rendered].onRaise(42)` returns `SyncIO[Unit]`, which is what the `<-` binds.

- [ ] **Step 2: Run the tests**

`sbt raiseAspectCoreJVM/testOnly com.dwolla.tagless.mtl.RaiseRecorderSpec`
Expected: all 3 tests PASS.

- [ ] **Step 3: Commit**

```bash
git add raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/RaiseRecorderSpec.scala
git commit -m "test: migrate RaiseRecorderSpec off Id/mutable.Buffer onto SyncIO/Ref"
```

---

## Task 6: `ObservingCapabilitySpec.scala`

**Files:**
- Modify: `raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/ObservingCapabilitySpec.scala:1-219`

**Interfaces:**
- Consumes: `RecordingFk.apply`, `CarrierArrows.Lazily`, `GenericTestAlg`, `SyncIOTestSyntax.RunOrFailSyncIOOps`.

This file has five tests touching mutable state or `Lazily`/`Eval`, plus two untouched (the `Functor`-identity test and the `Serializable` test, both `F`/`Either`-only, no mutation — leave them exactly as they are).

- [ ] **Step 1: Change the base class and imports**

Replace:
```scala
import cats.data.EitherT
import cats.mtl.Raise
import cats.syntax.all._
import cats.{Eval, Functor}
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ListBuffer

import TestError._
```
with:
```scala
import cats.data.EitherT
import cats.effect.{Ref, SyncIO}
import cats.mtl.Raise
import cats.syntax.all._
import cats.Functor
import munit.{CatsEffectSuite, ScalaCheckSuite}
import org.scalacheck.Prop.forAll

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}

import CarrierArrows.Lazily
import SyncIOTestSyntax._
import TestError._
```

Change the class declaration:
```scala
class ObservingCapabilitySpec extends ScalaCheckSuite {
```
to:
```scala
class ObservingCapabilitySpec extends CatsEffectSuite with ScalaCheckSuite {
```

Remove the file-local aliases (now redundant / replaced):
```scala
  private type F[A] = Either[TestError, A]
  private type Lazily[A] = EitherT[Eval, TestError, A]
```
Keep only:
```scala
  private type F[A] = Either[TestError, A]
```
(`Lazily` now comes from the `CarrierArrows.Lazily` import.)

- [ ] **Step 2: Leave the two non-mutating tests untouched**

`"the decorated capability reports the caller's own Functor, never a synthesized one"` (lines 31-42) and `"decorating does not change the raised value"` (lines 44-52) touch neither `Lazily` nor mutable state — no changes.

- [ ] **Step 3: Migrate `"the hook renders the raised error through its Err evidence, exactly once"`**

Current:
```scala
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
```

Replace with:
```scala
  test("the hook renders the raised error through its Err evidence, exactly once") {
    (for {
      rendered <- Ref.of[Lazily, Vector[String]](Vector.empty)
      hook = new OnRaise[Lazily, Render] {
        def apply[E](e: E)(implicit ev: Render[E]): Lazily[Unit] = rendered.update(_ :+ ev.render(e))
      }
      decorated = RaiseAspect.observing[Lazily, ErrA, Render](Raise[Lazily, ErrA], hook)
      out <- decorated.raise[NegativeInput, Int](NegativeInput(-3)).value
      _ = assertEquals(out, NegativeInput(-3).asLeft[Int].leftWiden[TestError])
      seen <- rendered.get
      // `errA:` proves the Render instance ran; `toString` alone would give
      // "NegativeInput(-3)".
      _ = assertEquals(seen.toList, List("errA:NegativeInput(-3)"))
    } yield ()).runOrFail
  }
```

- [ ] **Step 4: Migrate `"the hook's effect is sequenced before the raise, and neither runs until the value is forced"`**

This is the laziness-under-test case. Current:
```scala
  test("the hook's effect is sequenced before the raise, and neither runs until the value is forced") {
    val counter = new AtomicInteger(0)
    val log = ListBuffer.empty[String]

    val hook: OnRaise[Lazily, Render] = new OnRaise[Lazily, Render] {
      def apply[E](e: E)(implicit ev: Render[E]): Lazily[Unit] =
        EitherT.liftF(Eval.always {
          counter.incrementAndGet()
          log += s"hook:${ev.render(e)}"
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
```

Replace with (both `counter` and `log` become `Ref[Lazily, _]`; `SyncIO`'s own laziness — nothing runs until `.unsafeRunSync()`/until the `for`-chain is forced by `runOrFail` — replaces `Eval.always`'s laziness one-for-one, so the "neither runs until forced" property transfers directly):

```scala
  test("the hook's effect is sequenced before the raise, and neither runs until the value is forced") {
    (for {
      counter <- Ref.of[Lazily, Int](0)
      log <- Ref.of[Lazily, Vector[String]](Vector.empty)
      hook = new OnRaise[Lazily, Render] {
        def apply[E](e: E)(implicit ev: Render[E]): Lazily[Unit] =
          counter.update(_ + 1) *> log.update(_ :+ s"hook:${ev.render(e)}")
      }
      caller = new Raise[Lazily, ErrA] {
        val functor: Functor[Lazily] = Functor[Lazily]
        def raise[E2 <: ErrA, A](e: E2): Lazily[A] =
          log.update(_ :+ "raise") *> EitherT.leftT[SyncIO, A](e: TestError)
      }
      decorated = RaiseAspect.observing[Lazily, ErrA, Render](caller, hook)
      // `raised` is built but not yet forced -- nothing has run.
      raised = decorated.raise[NegativeInput, Int](NegativeInput(-1))
      c0 <- counter.get
      l0 <- log.get
      _ = assertEquals(c0, 0, "building the raised value must run no effects")
      _ = assertEquals(l0.toList, List.empty[String])
      result <- raised.value
      _ = assertEquals(result, NegativeInput(-1).asLeft[Int].leftWiden[TestError])
      c1 <- counter.get
      _ = assertEquals(c1, 1, "the hook must run exactly once")
      l1 <- log.get
      _ = assertEquals(l1.toList, List("hook:errA:NegativeInput(-1)", "raise"))
    } yield ()).runOrFail
  }
```

Note: `raised = decorated.raise[...]` is a `Lazily[Int]` value bound with `=`, not `<-` — this is deliberate and load-bearing: it must *not* be forced by the surrounding `for`-comprehension at that point, only later at `result <- raised.value`. Building the whole outer `for`/`Lazily` chain up to this point (via `Ref.of`, `counter.get`) does not force `raised` — `raised` is just a value reference until something explicitly sequences it, which is exactly the same "must not run until forced" property the original `Eval`-based version had (constructing `Eval.always{...}` doesn't run it either; only `.value` does).

- [ ] **Step 5: Migrate `countingOnRaise`, `countingLazilyAlg`, `ambientRaise`, and the `property(...)` test**

Current:
```scala
  private def countingOnRaise(counter: AtomicInteger): OnRaise[Lazily, Render] =
    new OnRaise[Lazily, Render] {
      def apply[E](e: E)(implicit ev: Render[E]): Lazily[Unit] =
        EitherT(Eval.always {
          val _ = counter.incrementAndGet()
          ().asRight[TestError]
        })
    }

  private val ambientRaise: Raise[Lazily, TestError] =
    new Raise[Lazily, TestError] {
      val functor: Functor[Lazily] = Functor[Lazily]

      def raise[E2 <: TestError, A](e: E2): Lazily[A] =
        EitherT(Eval.always(e.asLeft[A].leftWiden[TestError]))
    }

  private val countingLazilyAlg: TestAlg[Lazily] =
    new TestAlg[Lazily] {
      def a(i: Int)(implicit R: Raise[Lazily, ErrA]): Lazily[String] =
        if (i < 0) R.raise(NegativeInput(i)) else EitherT(Eval.always(s"a:$i".asRight[TestError]))

      def b(x: String, y: => Int)(implicit R: Raise[Lazily, ErrB]): Lazily[Int] =
        if (x.isEmpty) R.raise(EmptyInput("x")) else EitherT(Eval.always((x.length + y).asRight[TestError]))

      def c(i: Int): Lazily[Int] = EitherT(Eval.always((i * 2).asRight[TestError]))

      def d(i: Int)(j: Int)(implicit R: Raise[Lazily, ErrA]): Lazily[Int] =
        if (i + j < 0) R.raise(NegativeInput(i + j)) else EitherT(Eval.always((i + j).asRight[TestError]))

      def e(implicit R1: Raise[Lazily, ErrA], R2: Raise[Lazily, ErrB]): Lazily[Unit] =
        EitherT(Eval.always(().asRight[TestError]))
    }

  property("the hook never runs on a success path, and runs exactly once per raise, through a full intercept round trip") {
    forAll { (i: Int) =>
      val counter = new AtomicInteger(0)

      val ref = TestAlgReference.referenceRaiseAspect[Render, Render, Render]
      val recorder = new RecordingFk[Lazily, Render, Render]
      val intercepted = ref.intercept(countingLazilyAlg)(recorder.fk, countingOnRaise(counter))

      val result = intercepted.a(i)(ambientRaise).value.value

      // The weave reaches the interpreter on both branches; only the hook is
      // conditional.
      assertEquals(recorder.events, List("weave:TestAlg.a"))

      if (i < 0) {
        assertEquals(counter.get(), 1, s"the hook must run exactly once when raising for i=$i")
        assertEquals(result, NegativeInput(i).asLeft[String].leftWiden[TestError])
      } else {
        assertEquals(counter.get(), 0, s"the hook must not run on the success path for i=$i")
        assertEquals(result, s"a:$i".asRight[TestError])
      }
    }
  }
```

`countingLazilyAlg`'s methods are always-eager `EitherT(Eval.always(...))` today — a plain, unconditional success/failure, no counting inside the algebra itself (the counting is entirely in the `OnRaise` hook, via `countingOnRaise`). Replace `countingLazilyAlg` with `new GenericTestAlg[Lazily](0)` (Task 1) — it has exactly this shape (raise on negative `i`, otherwise `.pure`), so drop the private `countingLazilyAlg` definition entirely. Rewrite `countingOnRaise`/`ambientRaise` to use `Ref`, and the property body to build/read that `Ref` inside `SyncIO` per generated `i` (since scalacheck's `forAll` needs a synchronous `Boolean`/`Prop`-convertible result — `cats-effect-testkit`'s `syncIoBooleanToProp: SyncIO[Boolean] => Prop` implicit conversion is the sanctioned way to let a property body run a `SyncIO`, so add the `cats-effect-testkit` dependency to `raiseAspectCore` too, matching Task 1 Step 1's addition to `raiseAspectLaws`):

Add to `build.sbt`'s `raiseAspectCore` project (alongside the `cats-effect`/`munit-cats-effect` Test deps already present from the earlier session):
```scala
      "org.typelevel" %%% "cats-effect-testkit" % catsEffectVersion % Test,
```

Then:

```scala
  private def countingOnRaise(counter: Ref[Lazily, Int]): OnRaise[Lazily, Render] =
    new OnRaise[Lazily, Render] {
      def apply[E](e: E)(implicit ev: Render[E]): Lazily[Unit] = counter.update(_ + 1)
    }

  private val ambientRaise: Raise[Lazily, TestError] =
    new Raise[Lazily, TestError] {
      val functor: Functor[Lazily] = Functor[Lazily]

      def raise[E2 <: TestError, A](e: E2): Lazily[A] = EitherT.leftT[SyncIO, A](e: TestError)
    }

  property("the hook never runs on a success path, and runs exactly once per raise, through a full intercept round trip") {
    forAll { (i: Int) =>
      (for {
        counter <- Ref.of[Lazily, Int](0)
        ref = TestAlgReference.referenceRaiseAspect[Render, Render, Render]
        recorder <- RecordingFk[Lazily, Render, Render]
        intercepted = ref.intercept(new GenericTestAlg[Lazily](0))(recorder.fk, countingOnRaise(counter))
        result <- intercepted.a(i)(ambientRaise).value
        events <- recorder.events
        // The weave reaches the interpreter on both branches; only the hook is
        // conditional.
        _ = assertEquals(events.toList, List("weave:TestAlg.a"))
        c <- counter.get
        _ =
          if (i < 0) {
            assertEquals(c, 1, s"the hook must run exactly once when raising for i=$i")
            assertEquals(result, NegativeInput(i).asLeft[String].leftWiden[TestError])
          } else {
            assertEquals(c, 0, s"the hook must not run on the success path for i=$i")
            assertEquals(result, s"a:$i".asRight[TestError])
          }
      } yield true).value.map(_.getOrElse(false))
    }
  }
```

Note the property body's final line: `raised.value` here is `SyncIO[Either[TestError, Boolean]]` — `.map(_.getOrElse(false))` turns an unexpected `Left` (from `GenericTestAlg`'s own raise path — shouldn't happen since `ambientRaise`, not `GenericTestAlg`'s own capability, is what raises here, but the `for`-comprehension is still `EitherT`-shaped so a `Left` is possible in principle) into a scalacheck-visible `false` rather than swallowing it silently, and `SyncIO[Boolean]` is picked up by the `cats-effect-testkit` `syncIoBooleanToProp` implicit. This changes the failure-reporting shape from `assertEquals`'s detailed diff to a plain scalacheck counterexample (the generated `i`) — acceptable for a property test, and scalacheck still reports which `i` failed.

- [ ] **Step 6: Leave `"the observing result is Serializable"` untouched**

Lines 189-217 use `F`/`Either` only, no mutation, no `Lazily` — no changes.

- [ ] **Step 7: Run the tests**

`sbt raiseAspectCoreJVM/testOnly com.dwolla.tagless.mtl.ObservingCapabilitySpec`
Expected: all tests PASS.

- [ ] **Step 8: Commit**

```bash
git add build.sbt raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/ObservingCapabilitySpec.scala
git commit -m "test: migrate ObservingCapabilitySpec's mutating tests off ListBuffer/AtomicInteger onto Ref[Lazily, _]"
```

---

## Task 7: `LawsInstances.scala`

**Files:**
- Modify: `raise-aspect-laws/src/test/scala/com/dwolla/tagless/mtl/laws/LawsInstances.scala:1-131`

**Interfaces:**
- Produces: `LawsInstances.Lazily` (base now `SyncIO`), `LawsInstances.instrumented`/`.observed` now return `Lazily[(TestAlg[Lazily], RecordingFk[Lazily, Render, Render])]` instead of a bare `(TestAlg[Result], RecordingFk[Result, Render, Render])` tuple, `LawsInstances.renderedWeaves` now returns `Lazily[List[RenderedWeave]]`.
- Consumes: `RecordingFk.apply`, `GenericTestAlg` (raise-aspect-core), `cats.effect.testkit.TestInstances` (for `Eq[SyncIO[A]]`).

- [ ] **Step 1: Swap `Lazily`'s base and delete the stale `Eq` instance**

Change:
```scala
  type Result[A] = Either[TestError, A]
  type Lazily[A] = EitherT[Eval, TestError, A]
```
to:
```scala
  type Result[A] = Either[TestError, A]
  type Lazily[A] = EitherT[SyncIO, TestError, A]
```

Delete:
```scala
  implicit def eqEitherTEval[A: Eq]: Eq[Lazily[A]] =
    Eq.by(_.value.value)
```

Add `import cats.effect.testkit.TestInstances._` (brings `eqSyncIOA[A: Eq]: Eq[SyncIO[A]]` into implicit scope) and `import cats.effect.SyncIO` at the top; remove the now-unused `import cats.Eval`. `Eq[Lazily[A]]` (i.e. `Eq[EitherT[SyncIO, TestError, A]]`) now resolves automatically via cats' own `EitherT.catsDataEqForEitherT[F, L, R](implicit F: Eq[F[Either[L, R]]])` combined with `eqSyncIOA` and the already-in-scope `Eq[Either[TestError, A]]` (itself derivable from `eqTestError` + whatever `Eq[A]` the call site supplies) — no replacement `def` needed.

- [ ] **Step 2: Rewrite `instrumented`/`observed`/`renderedWeaves` to move off `Result` onto `Lazily`**

Current:
```scala
  def instrumented(
      instance: RaiseAspect[TestAlg, Render, Render, Render],
      eOutcome: Int
  ): (TestAlg[Result], RecordingFk[Result, Render, Render]) = {
    val recorder = new RecordingFk[Result, Render, Render]
    (instance.intercept(new EitherTestAlg(eOutcome))(recorder.fk, OnRaise.noop[Result, Render]), recorder)
  }

  def observed(
      instance: RaiseAspect[TestAlg, Render, Render, Render],
      eOutcome: Int
  ): (TestAlg[Result], RecordingFk[Result, Render, Render]) = {
    val recorder = new RecordingFk[Result, Render, Render]

    val hook: OnRaise[Result, Render] = new OnRaise[Result, Render] {
      def apply[E](e: E)(implicit ev: Render[E]): Result[Unit] = {
        recorder.record(s"raise:${ev.render(e)}")
        Right(())
      }
    }

    (instance.intercept(new EitherTestAlg(eOutcome))(recorder.fk, hook), recorder)
  }

  def renderedWeaves(recorder: RecordingFk[Result, Render, Render]): List[RenderedWeave] =
    recorder.weaves.map(r => WeaveRenderer.render(r.weave))
```

Every caller of these three (Task 8's `RaiseAspectSuite`, Task 9's `DifferentialOracleSpec` in Phase 3) needs `TestAlg[Lazily]`/`RecordingFk[Lazily, ...]` now — they both already read `.weaves`/`.events` through them, so this migration is required, not optional. Replace with:

```scala
  def instrumented(
      instance: RaiseAspect[TestAlg, Render, Render, Render],
      eOutcome: Int
  ): Lazily[(TestAlg[Lazily], RecordingFk[Lazily, Render, Render])] =
    RecordingFk[Lazily, Render, Render].map { recorder =>
      (instance.intercept(new GenericTestAlg[Lazily](eOutcome))(recorder.fk, OnRaise.noop[Lazily, Render]), recorder)
    }

  def observed(
      instance: RaiseAspect[TestAlg, Render, Render, Render],
      eOutcome: Int
  ): Lazily[(TestAlg[Lazily], RecordingFk[Lazily, Render, Render])] =
    RecordingFk[Lazily, Render, Render].map { recorder =>
      val hook: OnRaise[Lazily, Render] = new OnRaise[Lazily, Render] {
        def apply[E](e: E)(implicit ev: Render[E]): Lazily[Unit] = recorder.record(s"raise:${ev.render(e)}")
      }

      (instance.intercept(new GenericTestAlg[Lazily](eOutcome))(recorder.fk, hook), recorder)
    }

  def renderedWeaves(recorder: RecordingFk[Result, Render, Render]): List[RenderedWeave] =
    recorder.weaves.map(r => WeaveRenderer.render(r.weave))

  def renderedWeaves(recorder: RecordingFk[Lazily, Render, Render]): Lazily[List[RenderedWeave]] =
    recorder.weaves.map(_.map(r => WeaveRenderer.render(r.weave)).toList)
```

`renderedWeaves` is overloaded: the `Result`-typed overload only exists to keep any *other*, non-mutating `Result`-based caller compiling — grep the codebase for `renderedWeaves(` call sites before finalizing this step, and delete the `Result`-typed overload if nothing calls it (per the Task 7/Phase-3 research, every current caller uses the `Result`-typed `RecordingFk` and will be migrated to `Lazily` in this same plan, so the `Result`-typed overload is very likely dead after Tasks 8-16 land — leave a `TODO` only if you find a genuine remaining caller, otherwise delete it).

- [ ] **Step 3: Run a compile check**

`sbt raiseAspectLawsJVM/Test/compile`
Expected: fails in `RaiseAspectSuite.scala`/`ConservativeExtensionSuite.scala` (not yet migrated) — confirms `LawsInstances.scala` itself compiles.

- [ ] **Step 4: Commit**

```bash
git add raise-aspect-laws/src/test/scala/com/dwolla/tagless/mtl/laws/LawsInstances.scala
git commit -m "test: swap LawsInstances.Lazily onto SyncIO; instrumented/observed return Lazily[...]"
```

---

## Task 8: `RaiseAspectSuite.scala`

**Files:**
- Modify: `raise-aspect-laws/src/test/scala/com/dwolla/tagless/mtl/laws/RaiseAspectSuite.scala:1-241`

**Interfaces:**
- Consumes: `LawsInstances.{Result, Lazily, instrumented, renderedWeaves, raiseResult}`, `RecordingFk.apply`, `GenericTestAlg`, `SyncIOTestSyntax.RunOrFailSyncIOOps`, `cats.effect.testkit.TestInstances` (`syncIoBooleanToProp`).

- [ ] **Step 1: Change the base class**

```scala
abstract class RaiseAspectSuite extends DisciplineSuite {
```
to:
```scala
abstract class RaiseAspectSuite extends munit.CatsEffectSuite with DisciplineSuite {
```

Both `CatsEffectSuite` (abstract class, extends `FunSuite`) and `DisciplineSuite` (a trait, per its `interface munit.DisciplineSuite extends munit.ScalaCheckSuite` bytecode signature — traits compose freely with a single base class) combine without conflict.

- [ ] **Step 2: Rewrite the three `forAllErrors`-based `.value.value` properties**

Current `forAllErrors`:
```scala
  private def forAllErrors(f: TestError => Unit): org.scalacheck.Prop =
    org.scalacheck.Prop.forAll(Gen.oneOf[TestError](NegativeInput(-1), EmptyInput("boom")))(e => {
      f(e); true
    })
```

Replace with:
```scala
  private def forAllErrors(f: TestError => SyncIO[Boolean]): org.scalacheck.Prop =
    org.scalacheck.Prop.forAll(Gen.oneOf[TestError](NegativeInput(-1), EmptyInput("boom")))(f)
```

(Relies on `cats-effect-testkit`'s `syncIoBooleanToProp` implicit to convert `SyncIO[Boolean]` to `Prop` inside `Prop.forAll`'s own `B => Prop` overload — add `import cats.effect.testkit.TestInstances._` to this file's imports.)

Current (three near-identical properties, lines 58-67, 80-89, 109-118):
```scala
  property("L4 arrow coherence for the carrier-change arrow") {
    forAllErrors { e =>
      val law = RaiseArrowLaws.arrowCoherence[Result, Lazily, Render, TestError, Int](
        CarrierArrows.resultToLazily[Render],
        raiseLazily,
        e
      )
      assertEquals(law.lhs.value.value, law.rhs.value.value)
    }
  }
```
```scala
  property("L4 arrow coherence for the carrier-change arrow andThen id") {
    forAllErrors { e =>
      val law = RaiseArrowLaws.arrowCoherence[Result, Lazily, Render, TestError, Int](
        CarrierArrows.resultToLazily[Render].andThen(RaiseArrow.id[Lazily, Render]),
        raiseLazily,
        e
      )
      assertEquals(law.lhs.value.value, law.rhs.value.value)
    }
  }
```
```scala
  property("L4 arrow coherence for the carrier-change arrow, at Err = Trivial") {
    forAllErrors { e =>
      val law = RaiseArrowLaws.arrowCoherence[Result, Lazily, Trivial, TestError, Int](
        CarrierArrows.resultToLazily[Trivial],
        raiseLazily,
        e
      )
      assertEquals(law.lhs.value.value, law.rhs.value.value)
    }
  }
```

Replace each `assertEquals(law.lhs.value.value, law.rhs.value.value)` line with a `SyncIO[Boolean]` comparison (`law.lhs`/`law.rhs` are `Lazily[Int]`, so `.value: SyncIO[Either[TestError, Int]]`):

```scala
  property("L4 arrow coherence for the carrier-change arrow") {
    forAllErrors { e =>
      val law = RaiseArrowLaws.arrowCoherence[Result, Lazily, Render, TestError, Int](
        CarrierArrows.resultToLazily[Render],
        raiseLazily,
        e
      )
      (law.lhs.value, law.rhs.value).mapN(_ == _)
    }
  }
```
```scala
  property("L4 arrow coherence for the carrier-change arrow andThen id") {
    forAllErrors { e =>
      val law = RaiseArrowLaws.arrowCoherence[Result, Lazily, Render, TestError, Int](
        CarrierArrows.resultToLazily[Render].andThen(RaiseArrow.id[Lazily, Render]),
        raiseLazily,
        e
      )
      (law.lhs.value, law.rhs.value).mapN(_ == _)
    }
  }
```
```scala
  property("L4 arrow coherence for the carrier-change arrow, at Err = Trivial") {
    forAllErrors { e =>
      val law = RaiseArrowLaws.arrowCoherence[Result, Lazily, Trivial, TestError, Int](
        CarrierArrows.resultToLazily[Trivial],
        raiseLazily,
        e
      )
      (law.lhs.value, law.rhs.value).mapN(_ == _)
    }
  }
```

The middle property (`"L4 arrow coherence for the identity arrow"`, lines 69-78) stays exactly as-is — it's `Result`-only (`assertEquals(law.lhs, law.rhs)`, no `.value.value`, no `Lazily`).

- [ ] **Step 3: Rewrite the four `LawsInstances.instrumented`-based L8 tests**

`instrumented` now returns `Lazily[(TestAlg[Lazily], RecordingFk[Lazily, Render, Render])]` (Task 7). Current:
```scala
  test("L8 the interpreter receives one weave per call, naming the algebra and the method") {
    val (w, recorder) = LawsInstances.instrumented(instance, 0)

    w.a(1)(raiseResult)
    w.b("x", 1)(raiseResult)
    w.c(1)
    w.d(1)(2)(raiseResult)
    w.e(raiseResult, raiseResult)

    assertEquals(recorder.weaves.map(_.weave.algebraName), List.fill(5)("TestAlg"))
    assertEquals(LawsInstances.renderedWeaves(recorder).map(_.methodName), List("a", "b", "c", "d", "e"))
  }
```

Replace with (`raiseResult` becomes `raiseLazily`, already defined in this file at line 25; every method call must be sequenced with `<-`, not fired as a bare statement, since building `w.a(1)(raiseLazily)` alone does not run anything):
```scala
  test("L8 the interpreter receives one weave per call, naming the algebra and the method") {
    (for {
      pair <- LawsInstances.instrumented(instance, 0)
      (w, recorder) = pair
      _ <- w.a(1)(raiseLazily)
      _ <- w.b("x", 1)(raiseLazily)
      _ <- w.c(1)
      _ <- w.d(1)(2)(raiseLazily)
      _ <- w.e(raiseLazily, raiseLazily)
      weaves <- recorder.weaves
      _ = assertEquals(weaves.map(_.weave.algebraName).toList, List.fill(5)("TestAlg"))
      rendered <- LawsInstances.renderedWeaves(recorder)
      _ = assertEquals(rendered.map(_.methodName), List("a", "b", "c", "d", "e"))
    } yield ()).runOrFail
  }
```

Current:
```scala
  test("L8 the domain matches the declared parameter lists, capabilities absent") {
    val (w, recorder) = LawsInstances.instrumented(instance, 0)

    w.a(7)(raiseResult)
    w.b("ab", 2)(raiseResult)
    w.c(3)
    w.d(4)(5)(raiseResult)
    w.e(raiseResult, raiseResult)

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

Replace with:
```scala
  test("L8 the domain matches the declared parameter lists, capabilities absent") {
    (for {
      pair <- LawsInstances.instrumented(instance, 0)
      (w, recorder) = pair
      _ <- w.a(7)(raiseLazily)
      _ <- w.b("ab", 2)(raiseLazily)
      _ <- w.c(3)
      _ <- w.d(4)(5)(raiseLazily)
      _ <- w.e(raiseLazily, raiseLazily)
      rendered <- LawsInstances.renderedWeaves(recorder)
      _ = assertEquals(
        rendered.map(_.domain),
        List(
          List(List("i" -> "7")),
          List(List("x" -> "ab", "y" -> "2")),
          List(List("i" -> "3")),
          List(List("i" -> "4"), List("j" -> "5")),
          // every parameter of `e` is a capability, so it contributes no clause
          List.empty[List[(String, String)]]
        )
      )
    } yield ()).runOrFail
  }
```

Current:
```scala
  test("L8 intercepting does not force a by-name argument") {
    val (w, recorder) = LawsInstances.instrumented(instance, 0)

    // the empty string makes the underlying implementation raise without
    // touching `y`, so nothing but the weaving itself could force it
    val out = w.b("", throw new RuntimeException("by-name argument was forced"))(raiseResult)

    assertEquals(out, EmptyInput("x").asLeft[Int].leftWiden[TestError])
    intercept[RuntimeException](recorder.weaves.head.weave.domain.head(1).target.value)
  }
```

`recorder.weaves.head.weave.domain.head(1).target` is a cats-tagless `Advice`'s captured by-name target, wrapped in `Eval` *by cats-tagless itself* (unrelated to `Lazily`'s own base) — `.target.value` there is forcing that `Eval`, not the outer `SyncIO`. Replace with:
```scala
  test("L8 intercepting does not force a by-name argument") {
    (for {
      pair <- LawsInstances.instrumented(instance, 0)
      (w, recorder) = pair
      // the empty string makes the underlying implementation raise without
      // touching `y`, so nothing but the weaving itself could force it
      out <- w.b("", throw new RuntimeException("by-name argument was forced"))(raiseLazily).value
      _ = assertEquals(out, EmptyInput("x").asLeft[Int].leftWiden[TestError])
      weaves <- recorder.weaves
      _ = intercept[RuntimeException](weaves.head.weave.domain.head(1).target.value)
    } yield ()).runOrFail
  }
```

Current:
```scala
  test("L8 an intercepted method returns what the underlying call returns") {
    val impl = new EitherTestAlg(0)
    val (w, _) = LawsInstances.instrumented(instance, 0)

    exhaustiveInt.allValues.foreach { i =>
      assertEquals(w.a(i)(raiseResult), impl.a(i)(raiseResult))
      assertEquals(w.c(i), impl.c(i))
    }
  }
```

Replace with (`impl` must also move to `GenericTestAlg[Lazily]` since it's compared against `w`, which is now `Lazily`-typed):
```scala
  test("L8 an intercepted method returns what the underlying call returns") {
    val impl = new GenericTestAlg[Lazily](0)

    (for {
      pair <- LawsInstances.instrumented(instance, 0)
      (w, _) = pair
      _ <- exhaustiveInt.allValues.toList.traverse_ { i =>
        for {
          wa <- w.a(i)(raiseLazily).value
          ia <- impl.a(i)(raiseLazily).value
          _ = assertEquals(wa, ia)
          wc <- w.c(i).value
          ic <- impl.c(i).value
          _ = assertEquals(wc, ic)
        } yield ()
      }
    } yield ()).runOrFail
  }
```

`.traverse_` needs `import cats.syntax.all._` (already present via `cats.syntax.all.*` — confirm the file's existing import list and add `import cats.effect.SyncIO` and `import cats.effect.testkit.TestInstances._` alongside it if not already there from Step 2).

- [ ] **Step 4: Rewrite the two L10 tests**

Current:
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

Replace with:
```scala
  test("L10 intercepting performs no effects until the result is run") {
    (for {
      counter <- Ref.of[Lazily, Int](0)
      recorder <- RecordingFk[Lazily, Render, Render]
      inst = instance.intercept(countingAlg(counter))(recorder.fk, OnRaise.noop[Lazily, Render])
      // built but not yet forced -- nothing has run.
      out = inst.a(1)(raiseLazily)
      c0 <- counter.get
      _ = assertEquals(c0, 0, "intercepting must not run the underlying effect")
      _ <- out
      c1 <- counter.get
      _ = assertEquals(c1, 1, "running the instrumented result must run the effect exactly once")
    } yield ()).runOrFail
  }
```

Current:
```scala
  test("L10 the intercepted path runs the same number of effects as the plain one") {
    val interceptedCounter = new java.util.concurrent.atomic.AtomicInteger(0)
    val plainCounter = new java.util.concurrent.atomic.AtomicInteger(0)

    val recorder = new RecordingFk[Lazily, Render, Render]
    val inst = instance.intercept(countingAlg(interceptedCounter))(recorder.fk, OnRaise.noop[Lazily, Render])
    val plain = countingAlg(plainCounter)

    exhaustiveInt.allValues.foreach { i =>
      val throughIntercepted = inst.a(i)(raiseLazily).value.value
      val throughPlain = plain.a(i)(raiseLazily).value.value
      assertEquals(throughIntercepted, throughPlain, s"intercepted and plain results differ for input $i")
    }

    assertEquals(interceptedCounter.get(), plainCounter.get())
  }
```

Replace with:
```scala
  test("L10 the intercepted path runs the same number of effects as the plain one") {
    (for {
      interceptedCounter <- Ref.of[Lazily, Int](0)
      plainCounter <- Ref.of[Lazily, Int](0)
      recorder <- RecordingFk[Lazily, Render, Render]
      inst = instance.intercept(countingAlg(interceptedCounter))(recorder.fk, OnRaise.noop[Lazily, Render])
      plain = countingAlg(plainCounter)
      _ <- exhaustiveInt.allValues.toList.traverse_ { i =>
        for {
          throughIntercepted <- inst.a(i)(raiseLazily).value
          throughPlain <- plain.a(i)(raiseLazily).value
        } yield assertEquals(throughIntercepted, throughPlain, s"intercepted and plain results differ for input $i")
      }
      ic <- interceptedCounter.get
      pc <- plainCounter.get
      _ = assertEquals(ic, pc)
    } yield ()).runOrFail
  }
```

- [ ] **Step 5: Rewrite `countingAlg`**

Current:
```scala
  private def countingAlg(counter: java.util.concurrent.atomic.AtomicInteger): TestAlg[Lazily] =
    new TestAlg[Lazily] {
      private def count[A](a: => A): Lazily[A] =
        EitherT(Eval.always { val _ = counter.incrementAndGet(); a.asRight[TestError] })

      def a(i: Int)(implicit R: Raise[Lazily, ErrA]): Lazily[String] =
        if (i < 0) EitherT(Eval.always { val _ = counter.incrementAndGet(); NegativeInput(i).asLeft[String].leftWiden[TestError] })
        else count(s"a:$i")

      def b(x: String, y: => Int)(implicit R: Raise[Lazily, ErrB]): Lazily[Int] =
        if (x.isEmpty) R.raise(EmptyInput("x")) else count(x.length + y)

      def c(i: Int): Lazily[Int] = count(i * 2)

      def d(i: Int)(j: Int)(implicit R: Raise[Lazily, ErrA]): Lazily[Int] =
        if (i + j < 0) R.raise(NegativeInput(i + j)) else count(i + j)

      def e(implicit R1: Raise[Lazily, ErrA], R2: Raise[Lazily, ErrB]): Lazily[Unit] =
        count(())
    }
```

`count`'s by-name parameter `a: => A` must stay unforced until the returned `Lazily[A]` runs — `Sync[Lazily].delay` (not `.pure`, which would force `a` immediately) preserves that:

```scala
  private def countingAlg(counter: Ref[Lazily, Int]): TestAlg[Lazily] =
    new TestAlg[Lazily] {
      private def count[A](a: => A): Lazily[A] = counter.update(_ + 1) *> Sync[Lazily].delay(a)

      def a(i: Int)(implicit R: Raise[Lazily, ErrA]): Lazily[String] =
        if (i < 0) counter.update(_ + 1) *> EitherT.leftT[SyncIO, String](NegativeInput(i): TestError)
        else count(s"a:$i")

      def b(x: String, y: => Int)(implicit R: Raise[Lazily, ErrB]): Lazily[Int] =
        if (x.isEmpty) R.raise(EmptyInput("x")) else count(x.length + y)

      def c(i: Int): Lazily[Int] = count(i * 2)

      def d(i: Int)(j: Int)(implicit R: Raise[Lazily, ErrA]): Lazily[Int] =
        if (i + j < 0) R.raise(NegativeInput(i + j)) else count(i + j)

      def e(implicit R1: Raise[Lazily, ErrA], R2: Raise[Lazily, ErrB]): Lazily[Unit] =
        count(())
    }
```

Add `import cats.effect.{Ref, Sync, SyncIO}` to the file's imports.

- [ ] **Step 6: Run the full suite**

`sbt raiseAspectLawsJVM/testOnly com.dwolla.tagless.mtl.laws.ReferenceRaiseAspectSpec`
Expected: every test PASSES (this concrete spec runs the full `RaiseAspectSuite` against `TestAlgReference`'s hand-written instance).

- [ ] **Step 7: Commit**

```bash
git add build.sbt raise-aspect-laws/src/test/scala/com/dwolla/tagless/mtl/laws/RaiseAspectSuite.scala
git commit -m "test: migrate RaiseAspectSuite's L8/L10 tests and forAllErrors properties onto SyncIO/Ref"
```

---

## Task 9: `ConservativeExtensionSuite.scala`

**Files:**
- Modify: `raise-aspect-laws/src/test/scala/com/dwolla/tagless/mtl/laws/ConservativeExtensionSuite.scala:1-75`

**Interfaces:**
- Consumes: `RecordingFk.apply`, `GenericPlainAlg`, `LawsInstances.Lazily`, `SyncIOTestSyntax.RunOrFailSyncIOOps`.

- [ ] **Step 1: Change the base class, and move `impl`/`ourRendered` onto `Lazily`**

```scala
abstract class ConservativeExtensionSuite extends FunSuite {
```
to:
```scala
abstract class ConservativeExtensionSuite extends munit.CatsEffectSuite {
```

`private val impl: PlainAlg[Result] = EitherPlainAlg` (line 31) — every use of `impl` in this file is inside a `RecordingFk`-touching test (both `ourRendered` at line 39-44 and the direct construction at line 53-61), so `impl` moves entirely to `Lazily`/`GenericPlainAlg`:
```scala
  private val impl: PlainAlg[Lazily] = new GenericPlainAlg[Lazily]
```

- [ ] **Step 2: Rewrite `ourRendered` and the two `RecordingFk`-touching tests**

Current:
```scala
  private def ourRendered(inputs: List[Int]): List[RenderedWeave] = {
    val recorder = new RecordingFk[Result, Render, Render]
    val instrumented = ours.intercept(impl)(recorder.fk, OnRaise.noop[Result, Render])
    inputs.foreach(i => { val _ = instrumented.p(i) })
    recorder.weaves.map(r => WeaveRenderer.render(r.weave))
  }
```
Replace with:
```scala
  private def ourRendered(inputs: List[Int]): Lazily[List[RenderedWeave]] =
    for {
      recorder <- RecordingFk[Lazily, Render, Render]
      instrumented = ours.intercept(impl)(recorder.fk, OnRaise.noop[Lazily, Render])
      _ <- inputs.traverse_(i => instrumented.p(i).value.void)
      weaves <- recorder.weaves
    } yield weaves.map(r => WeaveRenderer.render(r.weave)).toList
```

(`instrumented.p(i).value.void` discards the `p(i)` result without short-circuiting — matching the original's `inputs.foreach(i => { val _ = instrumented.p(i) })`, which also discarded the result. `.value` is `SyncIO[Either[...]]`; wrap with `EitherT.liftF` to fold back into the `Lazily`-typed `for`, exactly as in Task 2 Step 1's `EvidenceThreadingSpec` rewrite — add `import cats.data.EitherT`:
```scala
      _ <- inputs.traverse_(i => EitherT.liftF[SyncIO, TestError, Unit](instrumented.p(i).value.void))
```
)

Current:
```scala
  test("L9 our woven structure matches upstream's, rendered") {
    assertEquals(ourRendered(exhaustiveInt.allValues.toList), upstream.weave(impl).map(WeaveRenderer.render))
  }
```
(Note: the research catalog's line numbers put this body slightly differently — re-read `ConservativeExtensionSuite.scala:46-51` directly before editing to confirm the exact right-hand side, since `upstream.weave(impl)` and its `.map` shape must match `impl`'s new `Lazily` type; the mechanical change is the same regardless: wrap in a `for`, compare inside it.)
Replace with:
```scala
  test("L9 our woven structure matches upstream's, rendered") {
    (for {
      ours <- ourRendered(exhaustiveInt.allValues.toList)
    } yield assertEquals(ours, upstream.weave(impl).map(WeaveRenderer.render))).runOrFail
  }
```

Current:
```scala
  test("L9 our intercepted results match upstream's woven codomain targets") {
    val recorder = new RecordingFk[Result, Render, Render]
    val instrumented = ours.intercept(impl)(recorder.fk, OnRaise.noop[Result, Render])
    val theirWoven = upstream.weave(impl)

    exhaustiveInt.allValues.foreach { i =>
      assertEquals(instrumented.p(i), theirWoven.p(i).codomain.target)
    }
  }
```
Replace with:
```scala
  test("L9 our intercepted results match upstream's woven codomain targets") {
    (for {
      recorder <- RecordingFk[Lazily, Render, Render]
      instrumented = ours.intercept(impl)(recorder.fk, OnRaise.noop[Lazily, Render])
      theirWoven = upstream.weave(impl)
      _ <- exhaustiveInt.allValues.toList.traverse_ { i =>
        for {
          ours <- instrumented.p(i).value
          theirs <- theirWoven.p(i).codomain.target.value
        } yield assertEquals(ours, theirs)
      }
    } yield ()).runOrFail
  }
```

- [ ] **Step 3: Leave `"L9 our mapK agrees with upstream's FunctorK.mapK for any pull"` untouched**

This test (lines 63-75 per the earlier catalog) touches no `RecordingFk`/mutation — no changes.

- [ ] **Step 4: Run the suite**

`sbt raiseAspectLawsJVM/testOnly com.dwolla.tagless.mtl.laws.ConservativeExtensionSpec`
Expected: all tests PASS (both the scala-2 and scala-3 `ConservativeExtensionSpec` variants run this shared suite — run whichever cross-version this session's `scalaVersion` is set to; run the other via `+testOnly` or `++ <version>` before moving on).

- [ ] **Step 5: Commit**

```bash
git add raise-aspect-laws/src/test/scala/com/dwolla/tagless/mtl/laws/ConservativeExtensionSuite.scala
git commit -m "test: migrate ConservativeExtensionSuite's RecordingFk usage onto Ref[Lazily, _]"
```

---

## Task 10: `raise-aspect-macros` shared fixture — `ExpectedWeaves.scala`

**Files:**
- Modify: `raise-aspect-macros/src/test/scala/com/dwolla/tagless/mtl/laws/ExpectedWeaves.scala:1-65`

**Interfaces:**
- Produces: `ExpectedWeaves.rendered(instrumented: TestAlg[Lazily], recorder: RecordingFk[Lazily, Render, Render]): Lazily[List[RenderedWeave]]` (was `(TestAlg[Result], RecordingFk[Result, Render, Render]) => List[RenderedWeave]`).
- Consumes: `LawsInstances.Lazily`, `LawsInstances.raiseResult`-equivalent for `Lazily` (`Raise[Lazily, ErrA]`/`Raise[Lazily, ErrB]`, summoned directly — no shared `val` for these exists yet; summon via `Raise[Lazily, ErrA]` at each call site, matching how `RaiseAspectSuite` already does for `raiseLazily`).

- [ ] **Step 1: Rewrite `rendered`**

Current:
```scala
  def rendered(
      instrumented: TestAlg[Result],
      recorder: RecordingFk[Result, Render, Render]
  ): List[RenderedWeave] = {
    // Bare calls rather than `val _ = ...`: 2.12 treats `_` as a real value
    // name, so only one `val _` may appear per block (see `RecordingFk`).
    // These are method calls performed for effect, not pure expressions in
    // statement position, so they warn under neither axis.
    instrumented.a(7)(Raise[Result, ErrA])
    instrumented.b("ab", 2)(Raise[Result, ErrB])
    instrumented.c(3)
    instrumented.d(4)(5)(Raise[Result, ErrA])
    instrumented.e(Raise[Result, ErrA], Raise[Result, ErrB])

    recorder.weaves.map(r => WeaveRenderer.render(r.weave))
  }
```

Replace with (this file must stay free of version-specific syntax — no `using`, no `@experimental` — the replacement below uses only cross-version `for`/`yield`, matching that constraint):
```scala
  def rendered(
      instrumented: TestAlg[Lazily],
      recorder: RecordingFk[Lazily, Render, Render]
  ): Lazily[List[RenderedWeave]] =
    for {
      _ <- instrumented.a(7)(Raise[Lazily, ErrA])
      _ <- instrumented.b("ab", 2)(Raise[Lazily, ErrB])
      _ <- instrumented.c(3)
      _ <- instrumented.d(4)(5)(Raise[Lazily, ErrA])
      _ <- instrumented.e(Raise[Lazily, ErrA], Raise[Lazily, ErrB])
      weaves <- recorder.weaves
    } yield weaves.map(r => WeaveRenderer.render(r.weave)).toList
```

This preserves the doc comment's "arrival-order pin, not just a content pin" property exactly: each call is sequenced via `<-` before the next is even built, and `recorder.weaves` is read only after all five have run — the same strict left-to-right ordering the original imperative version had, now made explicit by the monadic `for` rather than implicit in statement order.

- [ ] **Step 2: Commit**

```bash
git add raise-aspect-macros/src/test/scala/com/dwolla/tagless/mtl/laws/ExpectedWeaves.scala
git commit -m "test: move ExpectedWeaves.rendered onto Lazily, preserving arrival-order guarantee"
```

(Deferred verification: this file has no tests of its own; Tasks 12 and 16 exercise it. Do not mark this task's tests "passing" until those land — track it as a dependency.)

---

## Task 11: `raise-aspect-macros` scala-3 — `MethodLocalInstanceSpec.scala`

**Files:**
- Modify: `raise-aspect-macros/src/test/scala-3/com/dwolla/tagless/mtl/laws/MethodLocalInstanceSpec.scala:1-450`

**Interfaces:**
- Produces: `MethodLocal.WidgetLazily[A] = EitherT[SyncIO, WidgetError, A]`; `widgets`/`poly`/`bounded`/`variations`/`multiUsing`/`contra`/`precedence` become `F`-polymorphic functions (`def widgets[F[_]: Applicative]: WidgetAlg[F]`, etc.) instead of fixed `val`s, instantiated at `WidgetResult` where no recording is needed and at `WidgetLazily` where it is; `recordingArrow` becomes `Ref`-backed and `F`-polymorphic.
- Consumes: `RecordingFk.apply`, `SyncIOTestSyntax.RunOrFailSyncIOOps` (generic in error type, so it applies to `WidgetError` too).

This is the largest single file in the plan (12 `RecordingFk` sites + 3 `recordingArrow` sites + 1 direct-hook site). Work through it in the following six sub-steps.

- [ ] **Step 1: Add `WidgetLazily` and imports**

In `object MethodLocal`, change:
```scala
object MethodLocal:
  type WidgetResult[A] = Either[WidgetError, A]
```
to:
```scala
object MethodLocal:
  type WidgetResult[A] = Either[WidgetError, A]
  type WidgetLazily[A] = EitherT[SyncIO, WidgetError, A]
```

Add to the top of the file: `import cats.data.EitherT`, `import cats.effect.{Ref, SyncIO}`, `import cats.{Applicative, ApplicativeError}`, and `import com.dwolla.tagless.mtl.SyncIOTestSyntax._`. Change the `import scala.collection.mutable.ListBuffer` line — delete it (no longer used after Step 2).

- [ ] **Step 2: Make the seven fixture algebras `F`-polymorphic**

Current (lines 153-179, quoted in full above in research):
```scala
  val widgets: WidgetAlg[WidgetResult] = new WidgetAlg[WidgetResult]:
    def show(w: Widget)(using R: Render[Widget]): WidgetResult[String] = Right(R.render(w))
    def make(i: Int)(using R: Render[Widget]): WidgetResult[Widget] = Right(Widget(i))
    def risky(i: Int)(using RE: Render[WidgetError], R: Raise[WidgetResult, WidgetError]): WidgetResult[String] =
      if i < 0 then R.raise(WidgetError(s"negative:$i")) else Right(s"ok:$i")

  val poly: WidgetPolyAlg[WidgetResult] = new WidgetPolyAlg[WidgetResult]:
    def poly[A](a: A)(using R: Render[A]): WidgetResult[A] = Right(a)

  val bounded: WidgetBoundedAlg[WidgetResult] = new WidgetBoundedAlg[WidgetResult]:
    def bounded[A: Render](a: A): WidgetResult[A] = Right(a)

  val variations: WidgetVariationsAlg[WidgetResult] = new WidgetVariationsAlg[WidgetResult]:
    def sub(w: Widget)(using R: WidgetRender): WidgetResult[String] = Right(R.render(w))
    def aliased(w: Widget)(using R: AliasedRender): WidgetResult[String] = Right(R.render(w))
    def several(w: Widget)(using S: Render[WidgetError], R: Render[Widget]): WidgetResult[String] =
      Right(R.render(w))

  val multiUsing: WidgetMultiUsingAlg[WidgetResult] = new WidgetMultiUsingAlg[WidgetResult]:
    def multi(w: Widget)(using R: Render[Widget])(using i: Render[Int]): WidgetResult[String] =
      Right(R.render(w))

  val contra: ContraAlg[WidgetResult] = new ContraAlg[WidgetResult]:
    def sub(s: SubThing)(using C: Contra[Thing]): WidgetResult[String] = Right(C.describe(s))

  val precedence: PrecedenceAlg[WidgetResult] = new PrecedenceAlg[WidgetResult]:
    def pick(i: Int)(using R: Render[Int]): WidgetResult[String] = Right(R.render(i))
```

Replace with (`Right(x)` becomes `x.pure[F]`; `risky`'s `R.raise(...)` already takes its `Raise[F, WidgetError]` capability generically, unchanged in shape):
```scala
  def widgets[F[_]: Applicative]: WidgetAlg[F] = new WidgetAlg[F]:
    def show(w: Widget)(using R: Render[Widget]): F[String] = R.render(w).pure[F]
    def make(i: Int)(using R: Render[Widget]): F[Widget] = Widget(i).pure[F]
    def risky(i: Int)(using RE: Render[WidgetError], R: Raise[F, WidgetError]): F[String] =
      if i < 0 then R.raise(WidgetError(s"negative:$i")) else s"ok:$i".pure[F]

  def poly[F[_]: Applicative]: WidgetPolyAlg[F] = new WidgetPolyAlg[F]:
    def poly[A](a: A)(using R: Render[A]): F[A] = a.pure[F]

  def bounded[F[_]: Applicative]: WidgetBoundedAlg[F] = new WidgetBoundedAlg[F]:
    def bounded[A: Render](a: A): F[A] = a.pure[F]

  def variations[F[_]: Applicative]: WidgetVariationsAlg[F] = new WidgetVariationsAlg[F]:
    def sub(w: Widget)(using R: WidgetRender): F[String] = R.render(w).pure[F]
    def aliased(w: Widget)(using R: AliasedRender): F[String] = R.render(w).pure[F]
    def several(w: Widget)(using S: Render[WidgetError], R: Render[Widget]): F[String] =
      R.render(w).pure[F]

  def multiUsing[F[_]: Applicative]: WidgetMultiUsingAlg[F] = new WidgetMultiUsingAlg[F]:
    def multi(w: Widget)(using R: Render[Widget])(using i: Render[Int]): F[String] =
      R.render(w).pure[F]

  def contra[F[_]: Applicative]: ContraAlg[F] = new ContraAlg[F]:
    def sub(s: SubThing)(using C: Contra[Thing]): F[String] = C.describe(s).pure[F]

  def precedence[F[_]: Applicative]: PrecedenceAlg[F] = new PrecedenceAlg[F]:
    def pick(i: Int)(using R: Render[Int]): F[String] = R.render(i).pure[F]
```

`import cats.syntax.all.*` must already be present (check the top of the file; add if missing) for `.pure[F]`.

- [ ] **Step 3: Rewrite `recordingArrow`**

Current (lines 185-195):
```scala
  def recordingArrow(recorded: ListBuffer[String]): RaiseArrow[WidgetResult, WidgetResult, Render] =
    RaiseArrow(
      FunctionK.id[WidgetResult],
      new RaisePull[WidgetResult, WidgetResult, Render]:
        def apply[E](rg: Raise[WidgetResult, E])(implicit ev: Render[E]): Raise[WidgetResult, E] =
          new Raise[WidgetResult, E]:
            val functor: cats.Functor[WidgetResult] = rg.functor
            def raise[E2 <: E, A](e: E2): WidgetResult[A] =
              recorded += ev.render(e)
              rg.raise[E2, A](e)
    )
```

Replace with (`F`-polymorphic, needs `Apply[F]` to sequence `recorded.update(...)` before `rg.raise(...)` — `Applicative[F]` is stronger and already in scope at every call site):
```scala
  def recordingArrow[F[_]: Applicative](recorded: Ref[F, Vector[String]]): RaiseArrow[F, F, Render] =
    RaiseArrow(
      FunctionK.id[F],
      new RaisePull[F, F, Render]:
        def apply[E](rg: Raise[F, E])(implicit ev: Render[E]): Raise[F, E] =
          new Raise[F, E]:
            val functor: cats.Functor[F] = rg.functor
            def raise[E2 <: E, A](e: E2): F[A] =
              recorded.update(_ :+ ev.render(e)) *> rg.raise[E2, A](e)
    )
```

- [ ] **Step 4: Migrate the three `recordingArrow` call sites (all currently on `WidgetResult`; all mutate, so all move to `WidgetLazily`)**

Current, test `"the Err evidence transported with the capability is the one the method was handed"` (line ~262):
```scala
    val recorded = ListBuffer.empty[String]
    val mapped = riskyAspect.mapK(widgets)(recordingArrow(recorded))

    assertEquals(
      mapped.risky(-1)(using loudError, raiseWidget),
      Left(WidgetError("negative:-1")): WidgetResult[String]
    )
    assertEquals(
      mapped.risky(-2)(using quietError, raiseWidget),
      Left(WidgetError("negative:-2")): WidgetResult[String]
    )
    assertEquals(recorded.toList, List("loudError:negative:-1", "quietError:negative:-2"))
```

Replace with:
```scala
    (for {
      recorded <- Ref.of[WidgetLazily, Vector[String]](Vector.empty)
      mapped = riskyAspect.mapK(widgets[WidgetLazily])(recordingArrow(recorded))
      r1 <- mapped.risky(-1)(using loudError, Raise[WidgetLazily, WidgetError]).value
      _ = assertEquals(r1, Left(WidgetError("negative:-1")): WidgetResult[String])
      r2 <- mapped.risky(-2)(using quietError, Raise[WidgetLazily, WidgetError]).value
      _ = assertEquals(r2, Left(WidgetError("negative:-2")): WidgetResult[String])
      seen <- recorded.get
      _ = assertEquals(seen.toList, List("loudError:negative:-1", "quietError:negative:-2"))
    } yield ()).runOrFail
```

Current, test `"the functorK path resolves Err from the method's own using clause too"` (line ~300):
```scala
    val recorded = ListBuffer.empty[String]
    val mapped = riskyFunctorK.mapK(widgets)(recordingArrow(recorded))

    assertEquals(mapped.risky(3)(using loudError, raiseWidget), Right("ok:3"): WidgetResult[String])
    assertEquals(recorded.toList, Nil)

    mapped.risky(-3)(using loudError, raiseWidget)
    assertEquals(recorded.toList, List("loudError:negative:-3"))
```

Replace with:
```scala
    (for {
      recorded <- Ref.of[WidgetLazily, Vector[String]](Vector.empty)
      mapped = riskyFunctorK.mapK(widgets[WidgetLazily])(recordingArrow(recorded))
      r1 <- mapped.risky(3)(using loudError, Raise[WidgetLazily, WidgetError]).value
      _ = assertEquals(r1, Right("ok:3"): WidgetResult[String])
      seen1 <- recorded.get
      _ = assertEquals(seen1.toList, Nil)
      _ <- mapped.risky(-3)(using loudError, Raise[WidgetLazily, WidgetError]).value.void
      seen2 <- recorded.get
      _ = assertEquals(seen2.toList, List("loudError:negative:-3"))
    } yield ()).runOrFail
```

Current, test `"all three instance kinds resolve on one algebra"`, second half (line ~321):
```scala
    val recorded = ListBuffer.empty[String]
    val mapped = widgetAspect.mapK(widgets)(recordingArrow(recorded))
    mapped.risky(-6)(using loudError, raiseWidget)
    assertEquals(recorded.toList, List("loudError:negative:-6"))
```

Replace with (this half joins the first half of the same test, migrated in Step 5 below — both halves share one `for`):
```scala
      recorded <- Ref.of[WidgetLazily, Vector[String]](Vector.empty)
      mapped = widgetAspect.mapK(widgets[WidgetLazily])(recordingArrow(recorded))
      _ <- mapped.risky(-6)(using loudError, Raise[WidgetLazily, WidgetError]).value.void
      seen <- recorded.get
      _ = assertEquals(seen.toList, List("loudError:negative:-6"))
```

- [ ] **Step 5: Migrate every `RecordingFk[WidgetResult, ...]` test to `RecordingFk[WidgetLazily, ...]`**

Every one of the 12 `RecordingFk` sites follows the identical shape: build the recorder via the factory inside a `for`, instantiate the relevant fixture at `WidgetLazily` (via the now-generic `widgets[WidgetLazily]`/`poly[WidgetLazily]`/etc. from Step 2), sequence each call with `<-`, read `recorder.weaves` with `<-`, assert inside the `yield`, wrap the whole thing in `.runOrFail`. Two full worked examples, then the exhaustive remaining list.

Worked example 1 — `"the Dom advice carries the Render the method itself was handed"` (current, lines 227-239):
```scala
  test("the Dom advice carries the Render the method itself was handed") {
    val recorder = new RecordingFk[WidgetResult, Render, Render]
    val instrumented = showAspect.intercept(widgets)(recorder.fk, OnRaise.noop[WidgetResult, Render])

    instrumented.show(Widget(1))(using loud)
    val rendered = WeaveRenderer.render(recorder.weaves.last.weave)
    assertEquals(rendered.algebraName, "WidgetShowAlg")
    assertEquals(rendered.methodName, "show")
    assertEquals(rendered.domain, List(List("w" -> "loud:1")))

    instrumented.show(Widget(1))(using quiet)
    assertEquals(WeaveRenderer.render(recorder.weaves.last.weave).domain, List(List("w" -> "quiet:1")))
  }
```
becomes:
```scala
  test("the Dom advice carries the Render the method itself was handed") {
    (for {
      recorder <- RecordingFk[WidgetLazily, Render, Render]
      instrumented = showAspect.intercept(widgets[WidgetLazily])(recorder.fk, OnRaise.noop[WidgetLazily, Render])
      _ <- instrumented.show(Widget(1))(using loud)
      w1 <- recorder.weaves
      rendered1 = WeaveRenderer.render(w1.last.weave)
      _ = assertEquals(rendered1.algebraName, "WidgetShowAlg")
      _ = assertEquals(rendered1.methodName, "show")
      _ = assertEquals(rendered1.domain, List(List("w" -> "loud:1")))
      _ <- instrumented.show(Widget(1))(using quiet)
      w2 <- recorder.weaves
      _ = assertEquals(WeaveRenderer.render(w2.last.weave).domain, List(List("w" -> "quiet:1")))
    } yield ()).runOrFail
  }
```

Worked example 2 — `"the Cod advice carries the Render the method itself was handed"` (current, lines 241-259):
```scala
  test("the Cod advice carries the Render the method itself was handed") {
    val recorder = new RecordingFk[WidgetResult, Render, Render]
    val instrumented = makeAspect.intercept(widgets)(recorder.fk, OnRaise.noop[WidgetResult, Render])

    instrumented.make(2)(using loud)
    val loudly = recorder.weaves.last.weave
    assertEquals(loudly.codomain.name, "make")
    assertEquals(
      loudly.codomain.target.map(loudly.codomain.instance.render),
      Right("loud:2"): WidgetResult[String]
    )

    instrumented.make(2)(using quiet)
    val quietly = recorder.weaves.last.weave
    assertEquals(
      quietly.codomain.target.map(quietly.codomain.instance.render),
      Right("quiet:2"): WidgetResult[String]
    )
  }
```
becomes:
```scala
  test("the Cod advice carries the Render the method itself was handed") {
    (for {
      recorder <- RecordingFk[WidgetLazily, Render, Render]
      instrumented = makeAspect.intercept(widgets[WidgetLazily])(recorder.fk, OnRaise.noop[WidgetLazily, Render])
      _ <- instrumented.make(2)(using loud)
      w1 <- recorder.weaves
      loudly = w1.last.weave
      _ = assertEquals(loudly.codomain.name, "make")
      loudTarget <- loudly.codomain.target.value
      _ = assertEquals(loudTarget.map(loudly.codomain.instance.render), Right("loud:2"): WidgetResult[String])
      _ <- instrumented.make(2)(using quiet)
      w2 <- recorder.weaves
      quietly = w2.last.weave
      quietTarget <- quietly.codomain.target.value
      _ = assertEquals(quietTarget.map(quietly.codomain.instance.render), Right("quiet:2"): WidgetResult[String])
    } yield ()).runOrFail
  }
```

Apply the identical pattern (recorder via factory, fixture instantiated at `[WidgetLazily]`, each call sequenced with `<-`, `.weaves` read with `<-`, assertions moved into `_ = assertEquals(...)` lines inside the `for`, whole thing wrapped `(for {...} yield ()).runOrFail`) to every remaining `RecordingFk`-touching test in this file, at these exact line ranges (line numbers per the original file, before this task's edits):

| Lines | Test name | Fixture to instantiate at `[WidgetLazily]` |
|---|---|---|
| 289-298 | "the intercept hook renders a raise through the method-local Err instance" | `widgets` — **also** rewrite the local `ListBuffer`-backed `hook` the same way as `EvidenceThreadingSpec` (Task 2): `Ref[WidgetLazily, Vector[String]]`, `hook.apply` becomes `rendered.update(_ :+ ev.render(e))`, both `instrumented.risky(-7)(...)`/`instrumented.risky(-8)(...)` calls sequenced with `<-` via `.value.void` (they're expected to raise, and the test only cares about the log, not the returned value) |
| 311-320 (first half) | "all three instance kinds resolve on one algebra" | `widgetAspect`/`widgets` — this test's second half was already migrated in Step 4; merge both halves into one `for` block |
| 328-335 | "a polymorphic method resolves both Dom and Cod from its own given parameter" | `polyAspect`/`poly` |
| 340-347 | "a context-bound method resolves from its synthetic evidence parameter" | `boundedAspect`/`bounded` |
| 351-362 | "a subtype, an alias, and one conforming instance among several all resolve" | `variationsAspect`/`variations` |
| 366-373 | "a conforming given in a non-final using clause resolves" | `multiUsingAspect`/`multiUsing` |
| 377-384 | "a wider contravariant instance stands in for the narrower one the derivation needs" | `contraAspect`/`contra` — note `RecordingFk[WidgetLazily, Contra, Render]`, **not** `Render` for `Dom` |
| 388-395 | "resolution is derivation-site first..." | `precedenceAspect`/`precedence` |

- [ ] **Step 6: Run the file's tests**

`sbt raiseAspectMacrosJVM/testOnly com.dwolla.tagless.mtl.laws.MethodLocalInstanceSpec`
Expected: all tests PASS (the `compileErrors`-based rejection tests at the bottom of the file are untouched and should already pass).

- [ ] **Step 7: Commit**

```bash
git add raise-aspect-macros/src/test/scala-3/com/dwolla/tagless/mtl/laws/MethodLocalInstanceSpec.scala
git commit -m "test(scala-3): migrate MethodLocalInstanceSpec off ListBuffer/zero-arg RecordingFk onto WidgetLazily/Ref"
```

---

## Task 12: `raise-aspect-macros` scala-3 — `EdgeCaseDerivationSpec.scala`

**Files:**
- Modify: `raise-aspect-macros/src/test/scala-3/com/dwolla/tagless/mtl/laws/EdgeCaseDerivationSpec.scala:1-125`

**Interfaces:**
- Consumes: `LawsInstances.Lazily`, `RecordingFk.apply`, `SyncIOTestSyntax.RunOrFailSyncIOOps`. `EdgeAlg.either: EdgeAlg[Result]` (line 30-ish) needs an `EdgeAlg[Lazily]` counterpart — check its body; if it's `Right(...)`-shaped like the `MethodLocal` fixtures, generalize it to `def instance[F[_]: Applicative]: EdgeAlg[F]` following the Task 11 Step 2 pattern, since every test in this file touches `RecordingFk`.

- [ ] **Step 1: Generalize the `EdgeAlg` fixture**

Read `EdgeCaseDerivationSpec.scala:19-37` directly (not fully quoted in this plan's research pass) and apply the Task 11 Step 2 transformation: turn `object EdgeAlg { def either: EdgeAlg[LawsInstances.Result] = new EdgeAlg[LawsInstances.Result] { ... } }`'s body from `Either`-literal (`Right(...)`, or `Raise`-based raises) into an `F[_]: Applicative`-polymorphic `def instance[F[_]: Applicative]: EdgeAlg[F]`, mirroring every `Right(x)` to `x.pure[F]`.

- [ ] **Step 2: Migrate all six `RecordingFk[Result, ...]` tests to `RecordingFk[Lazily, ...]`**

Every test in this file follows exactly the Task 11 Step 5 pattern (`instrumented = derived.intercept(impl)(recorder.fk, OnRaise.noop[...])`, one or more calls sequenced with `<-`, `recorder.weaves` read with `<-`). Apply it at these line ranges:

| Lines | Test name | Notes |
|---|---|---|
| 45-56 | "a capability method inherited from a parent trait is woven" | `impl` = `EdgeAlg.instance[Lazily]` |
| 57-64 | "the inherited capability's raise survives intercept unchanged" | asserts on the raised `Left`, not on `.weaves` — use `.value` (not `.runOrFail`) for the final comparison, same as Task 3's `raisedThrough`, since `Left` is the expected outcome here |
| 65-73 | "a nullary def returning F[A] is woven with an empty domain" | |
| 76-89 | "overloads are woven independently, each keeping its own parameter type" | two sequential `instrumented.overloaded(...)` calls, each followed by its own `recorder.weaves` read — bind each with its own `<-` pair, don't reuse one binding for both |
| 96-114 | "an abstract val returning F[A] is woven eagerly, at construction, unlike on Scala 2" | **read carefully**: this test asserts `recorder.weaves` has exactly one entry *before* `instrumented.constant` is ever read, and still exactly one after re-reading it. Since `recorder.weaves` is now `Lazily[Vector[...]]`, both reads must be sequenced (`<-`) at the right points in the `for` — do not collapse them into one read, or the "before/after, still one entry" assertion becomes vacuous |
| 117-124 | "a capability-free method on the same algebra is woven unchanged" | |

- [ ] **Step 3: Run the tests**

`sbt raiseAspectMacrosJVM/testOnly com.dwolla.tagless.mtl.laws.EdgeCaseDerivationSpec`
Expected: all PASS.

- [ ] **Step 4: Commit**

```bash
git add raise-aspect-macros/src/test/scala-3/com/dwolla/tagless/mtl/laws/EdgeCaseDerivationSpec.scala
git commit -m "test(scala-3): migrate EdgeCaseDerivationSpec's RecordingFk usage onto Ref[Lazily, _]"
```

---

## Task 13: `raise-aspect-macros` scala-3 — `DifferentialOracleSpec.scala`

**Files:**
- Modify: `raise-aspect-macros/src/test/scala-3/com/dwolla/tagless/mtl/laws/DifferentialOracleSpec.scala:1-131`

**Interfaces:**
- Consumes: `LawsInstances.{observed, renderedWeaves, Result, Lazily}` (both now `Lazily`-returning per Task 7).

- [ ] **Step 1: Migrate the one `observed`-based test**

Current (lines 35-73, per research catalog):
```scala
  private def observed(
      instance: RaiseAspect[TestAlg, Render, Render, Render],
      outcome: Int
  ): (TestAlg[Result], RecordingFk[Result, Render, Render]) =
    LawsInstances.observed(instance, outcome)
```
`LawsInstances.observed` now returns `Lazily[(TestAlg[Lazily], RecordingFk[Lazily, Render, Render])]` (Task 7) — this thin wrapper's signature must follow:
```scala
  private def observed(
      instance: RaiseAspect[TestAlg, Render, Render, Render],
      outcome: Int
  ): Lazily[(TestAlg[Lazily], RecordingFk[Lazily, Render, Render])] =
    LawsInstances.observed(instance, outcome)
```

Then the test body (current, lines 41-73):
```scala
test("the derived instance is structurally identical to the reference, for every method and sample") {
  outcomes.foreach { outcome =>
    val (d, dRec) = observed(derived, outcome)
    val (r, rRec) = observed(reference, outcome)
    // ... per-method comparisons of d vs r, using eqTestAlg-style sampling ...
    assertEquals(LawsInstances.renderedWeaves(dRec), LawsInstances.renderedWeaves(rRec))
    assertEquals(dRec.events, rRec.events)
    assert(
      rRec.events.exists(_.startsWith("raise:")),
      s"no raise reached the hook for eOutcome $outcome — the events comparison above is vacuous"
    )
  }
}
```
Read the omitted per-method comparison lines directly from the file (they weren't fully quoted in this plan's research pass) before editing — every `d.<method>(...)`/`r.<method>(...)` call in that block must become a `Lazily`-sequenced `<-` bind, exactly like Task 8 Step 3's `"L8 an intercepted method returns what the underlying call returns"` rewrite, and the whole `outcomes.foreach { outcome => ... }` becomes `outcomes.toList.traverse_ { outcome => (for { ... } yield ()) }` folded into one outer `.runOrFail`:
```scala
test("the derived instance is structurally identical to the reference, for every method and sample") {
  outcomes.toList.traverse_ { outcome =>
    for {
      dPair <- observed(derived, outcome)
      (d, dRec) = dPair
      rPair <- observed(reference, outcome)
      (r, rRec) = rPair
      // ... per-method comparisons of d vs r, each call sequenced with <-,
      // each comparison as a `_ = assertEquals(...)` line — port every
      // comparison from the original block here without changing what is
      // compared, only how each side's value is obtained ...
      dRendered <- LawsInstances.renderedWeaves(dRec)
      rRendered <- LawsInstances.renderedWeaves(rRec)
      _ = assertEquals(dRendered, rRendered)
      dEvents <- dRec.events
      rEvents <- rRec.events
      _ = assertEquals(dEvents.toList, rEvents.toList)
      _ = assert(
        rEvents.exists(_.startsWith("raise:")),
        s"no raise reached the hook for eOutcome $outcome — the events comparison above is vacuous"
      )
    } yield ()
  }.runOrFail
}
```

- [ ] **Step 2: Leave the remaining four tests untouched**

Per the research catalog, `"the derived mapK agrees with the reference under the identity arrow"`, `"...under a genuine carrier change"`, `"...under the forgetful interpreter"`, and `"the derived functorK agrees with the derived aspect's mapK"` use `eqTestAlg[Result]`/`eqTestAlg[Lazily]`-based comparisons, not `RecordingFk` — no code changes needed; they pick up the new `Eq[Lazily[A]]` (Task 7 Step 1) automatically via implicit resolution.

- [ ] **Step 3: Run the tests**

`sbt raiseAspectMacrosJVM/testOnly com.dwolla.tagless.mtl.laws.DifferentialOracleSpec`
Expected: all PASS.

- [ ] **Step 4: Commit**

```bash
git add raise-aspect-macros/src/test/scala-3/com/dwolla/tagless/mtl/laws/DifferentialOracleSpec.scala
git commit -m "test(scala-3): migrate DifferentialOracleSpec's observed-based test onto Lazily"
```

---

## Task 14: `raise-aspect-macros` scala-3 — `UsingAlgSpec.scala`

**Files:**
- Modify: `raise-aspect-macros/src/test/scala-3/com/dwolla/tagless/mtl/laws/UsingAlgSpec.scala:1-140`

**Interfaces:**
- Consumes: `LawsInstances.Lazily`, `RecordingFk.apply`, `ExpectedWeaves.expected` (Task 10), `SyncIOTestSyntax.RunOrFailSyncIOOps`.

`UsingAlg.either`/`MultiUsingAlg.either` (referenced at lines 35, 49 per research) need the same `F`-polymorphic treatment as Task 11 Step 2 and Task 12 Step 1 — read their bodies directly and generalize.

- [ ] **Step 1: Generalize `UsingAlg`/`MultiUsingAlg`**

Same pattern as prior tasks: `def either: UsingAlg[Result] = ...` becomes `def instance[F[_]: Applicative]: UsingAlg[F] = ...` (rename `either` to `instance` throughout this file, or keep the name `either` if you prefer — just make it generic; check for other call sites of `UsingAlg.either`/`MultiUsingAlg.either` outside this file before renaming, there should be none since these types are file-local per the research catalog).

- [ ] **Step 2: Migrate the four `RecordingFk`-touching tests**

| Lines | Test name | Notes |
|---|---|---|
| 62-77 | "a using-based algebra renders exactly like the implicit-based TestAlg" | `recorder.weaves.map(...)` becomes `recorder.weaves.map(_.map(...))` sequenced via `<-`; compares against `ExpectedWeaves.expected` (unchanged — that's a plain `List[RenderedWeave]` literal, not effectful) |
| 78-90 | "a using-based algebra transports every capability, raising included" | both sides (`instrumented.a(3)(...)`, `impl.a(3)(...)`) become `.value`-compared, `impl` = `UsingAlg.instance[Lazily]` |
| 92-104 | "two separate using clauses are both dropped from the domain" | |
| 105-115 | "both capabilities from separate using clauses are transported" | |

- [ ] **Step 3: Migrate the `ListBuffer`-based hook test**

Current (lines 116-131, per research):
```scala
val log = ListBuffer.empty[String]
val hook: OnRaise[Result, Render] = new OnRaise[Result, Render]:
  def apply[E](e: E)(implicit ev: Render[E]): Result[Unit] =
    log += ev.render(e)
    Right(())

val recorderA = new RecordingFk[Result, Render, Render]
val viaR1 = derived.intercept(UsingAlg.either(-9))(recorderA.fk, hook)
viaR1.e(using raiseResult, raiseResult)
assertEquals(log.toList, List("errA:NegativeInput(-9)"))

val recorderB = new RecordingFk[Result, Render, Render]
val viaR2 = derived.intercept(UsingAlg.either(9))(recorderB.fk, hook)
viaR2.e(using raiseResult, raiseResult)
assertEquals(log.toList, List("errA:NegativeInput(-9)", "errB:EmptyInput(e)"))
```
Replace with (same `Ref`-backed hook shape as every prior task; `UsingAlg.either(-9)`/`UsingAlg.either(9)` — read whether `either` takes an outcome parameter like `EitherTestAlg`'s constructor does, and generalize accordingly, likely `UsingAlg.instance[Lazily](-9)` if so):
```scala
(for {
  log <- Ref.of[Lazily, Vector[String]](Vector.empty)
  hook = new OnRaise[Lazily, Render] {
    def apply[E](e: E)(implicit ev: Render[E]): Lazily[Unit] = log.update(_ :+ ev.render(e))
  }
  recorderA <- RecordingFk[Lazily, Render, Render]
  viaR1 = derived.intercept(UsingAlg.instance[Lazily](-9))(recorderA.fk, hook)
  _ <- viaR1.e(using Raise[Lazily, ErrA], Raise[Lazily, ErrB])
  seen1 <- log.get
  _ = assertEquals(seen1.toList, List("errA:NegativeInput(-9)"))
  recorderB <- RecordingFk[Lazily, Render, Render]
  viaR2 = derived.intercept(UsingAlg.instance[Lazily](9))(recorderB.fk, hook)
  _ <- viaR2.e(using Raise[Lazily, ErrA], Raise[Lazily, ErrB])
  seen2 <- log.get
  _ = assertEquals(seen2.toList, List("errA:NegativeInput(-9)", "errB:EmptyInput(e)"))
} yield ()).runOrFail
```

- [ ] **Step 4: Run the tests**

`sbt raiseAspectMacrosJVM/testOnly com.dwolla.tagless.mtl.laws.UsingAlgSpec`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add raise-aspect-macros/src/test/scala-3/com/dwolla/tagless/mtl/laws/UsingAlgSpec.scala
git commit -m "test(scala-3): migrate UsingAlgSpec's RecordingFk/hook usage onto Ref[Lazily, _]"
```

---

## Task 15: `raise-aspect-macros` scala-3 — `CrossVersionAgreementSpec.scala` and `DerivedConservativeExtensionSpec.scala`

**Files:**
- Modify: `raise-aspect-macros/src/test/scala-3/com/dwolla/tagless/mtl/laws/CrossVersionAgreementSpec.scala:1-20`
- Modify: `raise-aspect-macros/src/test/scala-3/com/dwolla/tagless/mtl/laws/DerivedConservativeExtensionSpec.scala:1-90`

**Interfaces:**
- Consumes: `RecordingFk.apply`, `LawsInstances.Lazily`, `ExpectedWeaves.rendered` (Task 10), `GenericPlainAlg`, `SyncIOTestSyntax.RunOrFailSyncIOOps`.

- [ ] **Step 1: `CrossVersionAgreementSpec.scala`**

Current (whole file):
```scala
class CrossVersionAgreementSpec extends FunSuite {
  test("the Scala 3 derivation matches the shared expected weave renderings") {
    val derived = DeriveRaise.aspect[TestAlg, Render, Render, Render]
    val recorder = new RecordingFk[Result, Render, Render]
    val instrumented = derived.intercept(new EitherTestAlg(0))(recorder.fk, OnRaise.noop[Result, Render])

    assertEquals(ExpectedWeaves.rendered(instrumented, recorder), ExpectedWeaves.expected)
  }
}
```
Replace with:
```scala
class CrossVersionAgreementSpec extends munit.CatsEffectSuite {
  test("the Scala 3 derivation matches the shared expected weave renderings") {
    val derived = DeriveRaise.aspect[TestAlg, Render, Render, Render]

    (for {
      recorder <- RecordingFk[Lazily, Render, Render]
      instrumented = derived.intercept(new GenericTestAlg[Lazily](0))(recorder.fk, OnRaise.noop[Lazily, Render])
      rendered <- ExpectedWeaves.rendered(instrumented, recorder)
    } yield assertEquals(rendered, ExpectedWeaves.expected)).runOrFail
  }
}
```

- [ ] **Step 2: `DerivedConservativeExtensionSpec.scala`**

Apply the identical transformation Task 9 (`ConservativeExtensionSuite.scala`) applied — `ourRendered` becomes `Lazily`-returning, both `RecordingFk`-touching tests get the `for`/`runOrFail` treatment, `impl: PlainAlg[Result] = EitherPlainAlg` (or equivalent local fixture) becomes `impl: PlainAlg[Lazily] = new GenericPlainAlg[Lazily]`, the third test (`"L9 the derived mapK agrees with upstream's FunctorK.mapK for any pull"`, which asserts the pull is never consulted via `fail(...)`) stays untouched since it constructs no `RecordingFk` and mutates nothing.

- [ ] **Step 3: Run the tests**

```
sbt raiseAspectMacrosJVM/testOnly com.dwolla.tagless.mtl.laws.CrossVersionAgreementSpec
sbt raiseAspectMacrosJVM/testOnly com.dwolla.tagless.mtl.laws.DerivedConservativeExtensionSpec
```
Expected: all PASS.

- [ ] **Step 4: Commit**

```bash
git add raise-aspect-macros/src/test/scala-3/com/dwolla/tagless/mtl/laws/CrossVersionAgreementSpec.scala raise-aspect-macros/src/test/scala-3/com/dwolla/tagless/mtl/laws/DerivedConservativeExtensionSpec.scala
git commit -m "test(scala-3): migrate CrossVersionAgreementSpec and DerivedConservativeExtensionSpec onto Ref[Lazily, _]"
```

---

## Task 16: `raise-aspect-macros` scala-2 mirrors

**Files:**
- Modify: `raise-aspect-macros/src/test/scala-2/com/dwolla/tagless/mtl/laws/MethodLocalInstanceSpec.scala`
- Modify: `raise-aspect-macros/src/test/scala-2/com/dwolla/tagless/mtl/laws/EdgeCaseDerivationSpec.scala`
- Modify: `raise-aspect-macros/src/test/scala-2/com/dwolla/tagless/mtl/laws/DifferentialOracleSpec.scala`
- Modify: `raise-aspect-macros/src/test/scala-2/com/dwolla/tagless/mtl/laws/CrossVersionAgreementSpec.scala`
- Modify: `raise-aspect-macros/src/test/scala-2/com/dwolla/tagless/mtl/laws/DerivedConservativeExtensionSpec.scala`

**Interfaces:** identical to Tasks 11-13 and 15 — same target types, same `RecordingFk`/`Ref` shapes.

- [ ] **Step 1: Port Task 11's transformation to the scala-2 `MethodLocalInstanceSpec.scala`**

Same six sub-steps (add `WidgetLazily`, generalize the seven fixtures, rewrite `recordingArrow`, migrate its three call sites, migrate all 11 `RecordingFk` sites — the scala-2 file has one fewer than scala-3's 12, per the research catalog's line list: 240, 254, 297, 319, 336, 348, 359, 374, 385), only with Scala 2 syntax: `def widgets[F[_]](implicit F: Applicative[F]): WidgetAlg[F] = new WidgetAlg[F] { ... }` (braces, not `:`/indentation), `implicit` parameters spelled out (no `using`), no `@experimental` needed (this file doesn't use `Derive`'s experimental macro path the way the scala-3 `ConservativeExtensionSpec` variant does — confirm by checking whether the existing scala-2 file has an `@experimental` annotation; per the research catalog it does not).

- [ ] **Step 2: Port Task 12's transformation to the scala-2 `EdgeCaseDerivationSpec.scala`**

Same line-range table, same `EdgeAlg` generalization, Scala 2 syntax.

- [ ] **Step 3: Port Task 13's transformation to the scala-2 `DifferentialOracleSpec.scala`**

Same `observed` rewrite and `outcomes.toList.traverse_` restructuring. Note the research catalog flagged this file's `import com.dwolla.tagless.mtl.laws.LawsInstances.*` (line 5) as using Scala-3-style `*`-wildcard-import syntax inside a `scala-2` source directory — while touching this file's imports for the `Ref`/`SyncIO` additions, change that import to the classic `_`-wildcard (`import com.dwolla.tagless.mtl.laws.LawsInstances._`) to match every sibling file in this directory, unless the build's Scala 2.13 settings already accept `*` imports (check `scalacOptions`/Scala version in `build.sbt` — 2.13.9+ with no special flag needed accepts it, but consistency with siblings is the more important reason to fix it while you're in the file).

- [ ] **Step 4: Port Task 15's transformation to the scala-2 `CrossVersionAgreementSpec.scala` and `DerivedConservativeExtensionSpec.scala`**

Same shapes.

- [ ] **Step 5: Run the tests under Scala 2.13 and 2.12**

```
sbt ++2.13.18 raiseAspectMacrosJVM/test
sbt ++2.12.21 raiseAspectMacrosJVM/test
```
Expected: all PASS on both versions.

- [ ] **Step 6: Commit**

```bash
git add raise-aspect-macros/src/test/scala-2/com/dwolla/tagless/mtl/laws/
git commit -m "test(scala-2): mirror the scala-3 RecordingFk/Ref migration across MethodLocalInstanceSpec, EdgeCaseDerivationSpec, DifferentialOracleSpec, CrossVersionAgreementSpec, DerivedConservativeExtensionSpec"
```

---

## Task 17: `natchez-tagless-mtl` — `TraceableRaiseAspectSpec.scala`

**Files:**
- Modify: `natchez-tagless-mtl/src/test/scala-3/com/dwolla/tracing/mtl/TraceableRaiseAspectSpec.scala:1-204`

**Interfaces:**
- Produces: this file's `F`/`G` move from `Either[BarError, *]`/`EitherT[Eval, BarError, *]` to `EitherT[SyncIO, BarError, *]` uniformly — there is no "pure law" test in this file to preserve a zero-capability witness for, so the whole file's effect type changes, not just the mutating tests.
- Consumes: nothing from `raise-aspect-core`/`raise-aspect-laws` (`natchez-tagless-mtl` does not depend on their test sources — see Task 1's note on cross-module test dependencies); define a local `RunOrFailSyncIOOps`-equivalent inline in this file, or promote `SyncIOTestSyntax` into `core`'s test sources if `natchezTaglessMtl.dependsOn(core % "compile->compile;test->test", ...)` already gives access to `core`'s test classpath (check `build.sbt`; if so, put `SyncIOTestSyntax` there instead of duplicating it — confirm before choosing).

- [ ] **Step 1: Swap `F` to `EitherT[SyncIO, BarError, *]` and change the base class**

```scala
class TraceableRaiseAspectSpec extends FunSuite {
  private type F[A] = Either[BarError, A]
```
to:
```scala
class TraceableRaiseAspectSpec extends munit.CatsEffectSuite {
  private type F[A] = EitherT[SyncIO, BarError, A]
```
Add `import cats.data.EitherT`, `import cats.effect.{Ref, SyncIO}`, `import cats.syntax.all._`. Every existing `Bar[F]`/`DerivesBar[F]` construction (these are presumably `object Bar`/`object DerivesBar` with a generic `apply[F[_]: ...]` factory, or fixed like `EitherTestAlg` — read `Bar`'s definition in this module's test/main sources before this step to confirm whether it's already `F`-polymorphic; if it's fixed to `Either[BarError, *]` like `EitherTestAlg`, generalize it the same way as Task 1 Step 4) needs to resolve at the new `F`.

- [ ] **Step 2: Rewrite `Recorder`**

Current (lines 46-56):
```scala
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
```
Replace with a `Ref`-backed class built via a factory (mirroring `RecordingFk`'s shape exactly, but local to this module since it's a different, simpler recorder tracking only a flat `Vector[String]`, not `RecordedWeave`s):
```scala
  private final class Recorder(seenRef: Ref[F, Vector[String]]) {
    def seen: F[Vector[String]] = seenRef.get

    val fk: Aspect.Weave[F, TraceableValue, TraceableValue, *] ~> F =
      new (Aspect.Weave[F, TraceableValue, TraceableValue, *] ~> F) {
        def apply[A](w: Aspect.Weave[F, TraceableValue, TraceableValue, A]): F[A] =
          seenRef.update(_ :+ s"${w.algebraName}.${w.codomain.name}(${w.domain.flatten.map(_.name).mkString(",")})") *>
            w.codomain.target
      }
  }

  private object Recorder {
    def apply(): F[Recorder] = Ref.of[F, Vector[String]](Vector.empty).map(new Recorder(_))
  }
```

- [ ] **Step 3: Migrate the two `Recorder`-reading tests**

Current (lines 58-69):
```scala
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
```
Replace with:
```scala
  test("intercept forwards to the underlying instance, weave for weave") {
    (for {
      wideRec <- Recorder()
      narrowRec <- Recorder()
      viaWide = wide.intercept(Bar[F])(wideRec.fk, OnRaise.noop[F, TraceableValue])
      viaNarrow = narrow.intercept(Bar[F])(narrowRec.fk, OnRaise.noop[F, TraceableValue])
      r1 <- viaNarrow.bar(5)(raiseF).value
      w1 <- viaWide.bar(5)(raiseF).value
      _ = assertEquals(r1, w1)
      r2 <- viaNarrow.bar(-1)(raiseF).value
      w2 <- viaWide.bar(-1)(raiseF).value
      _ = assertEquals(r2, w2)
      narrowSeen <- narrowRec.seen
      wideSeen <- wideRec.seen
      _ = assertEquals(narrowSeen.toList, wideSeen.toList)
      _ = assertEquals(narrowSeen.toList, List("Bar.bar(i)", "Bar.bar(i)"))
    } yield ()).value.flatMap {
      case Right(_) => SyncIO.unit
      case Left(e) => SyncIO.raiseError(new AssertionError(s"test raised unexpectedly: $e"))
    }
  }
```

(Inlining `runOrFail`'s body directly here, per Step 0's open question about where `SyncIOTestSyntax` should live for this module — if you determined `core`'s test classpath is reachable, replace the trailing `.value.flatMap { ... }` with `.runOrFail` and import it instead.)

Apply the identical transformation to `"the derived instance agrees with a hand-written one on intercept"` (lines 129-150), replacing `new Recorder` with `Recorder()` sequenced via `<-`, and `derivedRec.seen`/`handRec.seen` reads via `<-`.

- [ ] **Step 4: Migrate the local-`ListBuffer` hook test**

Current (lines 71-90):
```scala
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
```
Replace with:
```scala
  test("intercept forwards the hook, so a raise is still observed exactly once") {
    (for {
      rendered <- Ref.of[F, Vector[String]](Vector.empty)
      hook = new OnRaise[F, TraceableValue] {
        def apply[E](e: E)(implicit ev: TraceableValue[E]): F[Unit] = rendered.update(_ :+ ev.toTraceValue(e).toString)
      }
      rec <- Recorder()
      intercepted = narrow.intercept(Bar[F])(rec.fk, hook)
      r1 <- intercepted.bar(5)(raiseF).value
      _ = assertEquals(r1, "bar:5".asRight[BarError])
      seen1 <- rendered.get
      _ = assertEquals(seen1.toList, List.empty[String], "no raise, so no hook firing")
      r2 <- intercepted.bar(-1)(raiseF).value
      _ = assertEquals(r2, BarError.Negative(-1).asLeft[String])
      seen2 <- rendered.get
      _ = assertEquals(seen2.size, 1, "the hook must fire exactly once per raise")
      _ = assert(seen2.head.contains("negative:-1"), s"rendered through TraceableValue, got ${seen2.head}")
    } yield ()).value.flatMap {
      case Right(_) => SyncIO.unit
      case Left(e) => SyncIO.raiseError(new AssertionError(s"test raised unexpectedly: $e"))
    }
  }
```

- [ ] **Step 5: Leave the remaining six tests' behavior untouched, but re-check their types**

`"mapK forwards to the underlying instance"` (uses a local `type G[A] = EitherT[Eval, BarError, A]` at line 93 — this can stay `Eval`-based since it's a *third*, separate, non-`F` witness used only for a carrier-change test, not for the module's main `F`; leave it exactly as-is unless it turns out to also depend on `F`'s old `Either` shape — check for compile errors here after Step 1 and fix only if the compiler flags something), `"the narrow instance is accepted wherever the wide one is"`, `"the derives clause produces an instance, and it is the narrow type"`, `"a wide RaiseAspect does not satisfy a demand for the narrow type"`, `"a companion's narrow derived instance silently outranks a wide one beside it..."`, `"...and fromRaiseAspect is how you get one anyway"` — these use `F` only as an ordinary type parameter (no direct `Either`-pattern-matching), so they most likely compile unchanged against the new `F`; verify with a full test run in Step 6 and fix only genuine compile errors, not speculative ones.

- [ ] **Step 6: Run the tests**

`sbt natchezTaglessMtlJVM/testOnly com.dwolla.tracing.mtl.TraceableRaiseAspectSpec`
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add natchez-tagless-mtl/src/test/scala-3/com/dwolla/tracing/mtl/TraceableRaiseAspectSpec.scala
git commit -m "test: migrate TraceableRaiseAspectSpec off ListBuffer, F onto EitherT[SyncIO, BarError, *]"
```

---

## Task 18: `otel4s-tagless-mtl` — `DerivesFooRaiseSpec.scala`

**Files:**
- Modify: `otel4s-tagless-mtl/src/test/scala-3/com/dwolla/tracing/otel4s/mtl/DerivesFooRaiseSpec.scala:1-170`

**Interfaces:** identical to Task 17, with `Foo`/`FooError`/`ToAnyValue`/`AnyValueRaiseAspect` in place of `Bar`/`BarError`/`TraceableValue`/`TraceableRaiseAspect`.

- [ ] **Step 1: Apply Task 17's transformation verbatim, substituting names**

`F[A] = Either[FooError, A]` → `F[A] = EitherT[SyncIO, FooError, A]`; `Recorder` (lines 38-48) → `Ref`-backed factory exactly as Task 17 Step 2; the two `Recorder`-reading tests (`"intercept forwards to the underlying instance, weave for weave"` lines 50-61, `"the derived instance agrees with a hand-written one on intercept"` lines 121-143) → Task 17 Step 3's shape; the local-`ListBuffer` hook test (`"intercept forwards the hook, so a raise is still observed exactly once"` lines 63-82) → Task 17 Step 4's shape (`ev.toAnyValue(e)` in place of `ev.toTraceValue(e)`, `FooError.Negative` in place of `BarError.Negative`); the remaining five tests (`"mapK forwards to the underlying instance"` with its local `type G[A] = EitherT[Eval, FooError, A]`, `"the narrow instance is accepted wherever the wide one is"`, `"the derives clause produces an instance, and it is the narrow type"`, `"...and fromRaiseAspect is how you get one anyway"`, `"a wide RaiseAspect does not satisfy a demand for the narrow type"`) → leave untouched, verify by compiling.

- [ ] **Step 2: Run the tests**

`sbt otel4sTaglessMtlJVM/testOnly com.dwolla.tracing.otel4s.mtl.DerivesFooRaiseSpec`
Expected: all PASS.

- [ ] **Step 3: Commit**

```bash
git add otel4s-tagless-mtl/src/test/scala-3/com/dwolla/tracing/otel4s/mtl/DerivesFooRaiseSpec.scala
git commit -m "test: migrate DerivesFooRaiseSpec off ListBuffer, F onto EitherT[SyncIO, FooError, *]"
```

---

## Task 19: Full cross-build verification

**Files:** none (verification only).

- [ ] **Step 1: Full JVM test run, default Scala version**

`sbt test`
Expected: PASS, zero new warnings versus the pre-plan baseline (compare against `git stash`'d output if anything looks suspicious).

- [ ] **Step 2: Cross-build JVM**

```
sbt ++2.12.21 test
sbt ++3.3.8 test
```
Expected: PASS on both.

- [ ] **Step 3: JS build (raise-aspect-core/laws/macros are `crossProject(JVMPlatform, JSPlatform)`)**

`sbt raiseAspectCoreJS/test raiseAspectLawsJS/test raiseAspectMacrosJS/test`
Expected: PASS. `SyncIO`/`Ref` cross-build to JS without changes; if `cats-effect-testkit`'s `syncIoBooleanToProp`/`eqSyncIOA` are unavailable on the JS artifact for any resolved version, fall back to a hand-written `Eq.by(_.unsafeRunSync())` instance local to `LawsInstances.scala`/`RaiseAspectSuite.scala` instead of importing `cats.effect.testkit.TestInstances._` — check `cats-effect-testkit`'s published JS artifacts before assuming this fallback is needed.

- [ ] **Step 4: MiMa / binary compatibility**

These are all `Test`-scoped changes in non-published (or test-only) sources — confirm none of `RecordingFk`, `TestFixtures`, `CarrierArrows`, `LawsInstances`, `ExpectedWeaves` are part of a published artifact's `main` sources (they're all under `src/test`, so MiMa does not apply), then run the canonical check command (`sbt +test` plus whatever `scripts/check`/`sbt ci` alias this repo defines — check `build.sbt`/`project/` for the canonical check task name before assuming `sbt test` is sufficient) to confirm no other regression.

- [ ] **Step 5: Final review pass**

Re-read every file this plan touched (`git diff main...HEAD --stat`) and confirm: zero `ListBuffer`/`AtomicInteger`/`mutable.Buffer`/`var` remain (`grep -rn 'ListBuffer\|AtomicInteger\|AtomicLong\|AtomicBoolean\|AtomicReference\|mutable\.' --include='*.scala' raise-aspect-core raise-aspect-laws raise-aspect-macros natchez-tagless-mtl otel4s-tagless-mtl` returns nothing outside the sanctioned two exceptions this plan documented, and even those two contain no mutable *collections/primitives*, only a single `.unsafeRunSync()` call each), and zero `.unsafeRunSync()`/`.unsafeRunAsync()` calls exist outside `CarrierArrows.resultToLazily` and the `Eq[SyncIO[_]]`/`syncIoBooleanToProp` machinery from `cats-effect-testkit`.

- [ ] **Step 6: Commit any final cleanup**

```bash
git add -A
git commit -m "test: final cleanup pass after Ref-based test fixture migration"
```
