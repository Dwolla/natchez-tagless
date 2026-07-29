# Milestone M1 — Runtime core + hand-written reference instance

Read `01-overview-design-and-laws.md` first. Prerequisite: M0 merged.

This milestone has outsized importance: the hand-written reference instance
you produce here is the **differential oracle** that the M2 laws validate and
that both macros (M3/M4) must match output-for-output. It is a permanent test
fixture, not scaffolding — it is never deleted.

## Tasks

1. In `raise-aspect-core`, implement exactly the types from overview §3.2 and
   §3.3: `RaisePull` (+ `id`), `RaiseArrow` (+ `id`, `andThen`),
   `RaiseFunctorK`, `RaiseAspect`, `Synthetic` (+ the `Trivial` instance),
   and `WeaveArrows` (`codomainTarget`, `raisePull`, `raiseLift`,
   `eraseWeave`, and the private `syntheticWeaveFunctor`).
   - Before writing `Weave`/`Advice` construction code, read the vendored
     `reference/upstream/.../aop/Aspect.scala` and use the real constructor
     signatures; the overview's snippets are pseudocode at that level.
   - `raisePull`'s produced `Raise[F, E]` uses the ambient `Functor[F]`, not
     `rw.functor` (overview law L7 depends on this).
   - `raiseLift`'s shell `Weave` uses the fixed advice/algebra name
     `"raise"` (decided).
   - Mind `Raise[F, -E]`'s contravariance and the `raise[E2 <: E, A]`
     signature shape in cats-mtl; compile against the pinned cats-mtl version
     and match its actual method signatures.
2. In the **test sources** of `raise-aspect-core` (or a shared testkit
   sub-module if the repo prefers — match conventions), define the fixture
   algebras from below and a **hand-written** `RaiseAspect` instance for
   `TestAlg`, written by mechanically following overview §3.4's expansion
   spec — as if you were the macro. Also hand-write the `RaiseFunctorK`
   `mapK`. Name it clearly, e.g. `TestAlg.referenceRaiseAspect`, and add a
   comment stating it is the permanent differential oracle for the derivation
   macros and must not be deleted or regenerated.

Fixture algebras (final; M2 depends on these exact shapes):

```scala
sealed trait ErrA extends Product with Serializable
sealed trait ErrB extends Product with Serializable
// concrete cases + a common supertype arrangement such that a single
// TestError type can serve as the Either left channel for both; design the
// hierarchy so cats-mtl's contravariance in E supplies Raise[F, ErrA] and
// Raise[F, ErrB] from Raise[F, TestError].

trait TestAlg[F[_]] {
  def a(i: Int)(implicit R: Raise[F, ErrA]): F[String]            // capability, strict param
  def b(x: String, y: => Int)(implicit R: Raise[F, ErrB]): F[Int] // by-name + different error type
  def c(i: Int): F[Int]                                           // no capability
  def d(i: Int)(j: Int)(implicit R: Raise[F, ErrA]): F[Int]       // multiple param lists
  def e(implicit R1: Raise[F, ErrA], R2: Raise[F, ErrB]): F[Unit] // two capabilities
}

trait PlainAlg[F[_]] {                                            // for law L9
  def p(i: Int): F[String]
}
```

3. Provide a concrete `TestAlg[Either[TestError, *]]` implementation in test
   sources whose methods raise on designated inputs (e.g. negative numbers)
   and succeed otherwise, so error paths are exercised. Provide the same for
   `PlainAlg`.
4. Smoke tests (not the law suite — that is M2): weave the reference instance
   at `F = Either[TestError, *]` with a simple test `Dom`/`Cod` typeclass
   (define a tiny `Render[A]` typeclass in test sources; do not depend on
   natchez here), then `mapK(_)(WeaveArrows.eraseWeave)` and assert behavior
   matches the unwoven implementation on both success and raise inputs.

## Acceptance criteria

- Both Scala versions compile; smoke tests pass on both.
- The reference instance matches the expansion spec **structurally**: correct
  `algebraName` (`"TestAlg"`), method/parameter advice names, capability
  params absent from `domain`, `Eval.now` vs `Eval.always` per parameter
  kind, parameter-list shape preserved, and `raisePull` applied at each
  underlying call site.
- No dependencies added to `raise-aspect-core` beyond M0's.
- Human review gate: report a side-by-side of the expansion spec and your
  reference instance for the reviewer.

## Ground rules reminder

Do not write the law traits or discipline suites (M2). Do not write any
macro code (M3/M4). If the cats-mtl or cats-tagless APIs differ from the
overview's pseudocode, follow the real APIs and note every divergence in your
report — those notes feed the macro milestones.
