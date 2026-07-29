# Milestone M1 — Runtime core + hand-written reference instance

## Status

**Complete** (branch `milestone/m1-runtime-core`, stacked on
`milestone/m0-scaffolding`). Next: M2 laws.

Note the prerequisite "M0 merged" was not met — M0 is committed but unpushed
and unmerged, and its CI has never run. Brian chose to proceed anyway.

Built, all following overview §3.2/§3.3 exactly:
`RaisePull` (+`id`), `RaiseArrow` (+`id`, `andThen`), `RaiseFunctorK`,
`RaiseAspect`, `Synthetic` (+`trivial`), and `WeaveArrows`
(`codomainTarget`, `raisePull`, `raiseLift`, `eraseWeave`, and the private
`syntheticWeaveFunctor`). The reference instance is
`TestAlgReference.referenceRaiseAspect` in `raise-aspect-core` test sources,
marked do-not-delete.

### API divergences from the overview's pseudocode — these feed M3/M4

1. **`Aspect.Advice` has no `equals`, so woven values are not comparable with
   `==`.** `Advice` is a `trait` with an existential `type A`; it overrides
   `toString` but not `equals`, so it uses reference identity. `Weave` is a
   case class, but its `domain` and `codomain` fields are `Advice`s, so it
   inherits the problem. Verified empirically: two identically-constructed
   `Weave`s compare `false`, as do their `domain`s; only `codomain.target`
   (an `Either`) compares `true`. **M2 must hand-write a structural `Eq`**
   that walks `algebraName`, `domain` (advice name + forced `target.value`),
   and `codomain` (name + target) rather than deriving one or using `==`.
2. **`Advice.byValue`/`byName` are the real constructors** and are
   definitionally what §3.4's pseudocode spells out —
   `byValue(n, v) = Advice(n, Eval.now(v))` and
   `byName(n, t) = Advice(n, Eval.always(t))`. The reference instance uses
   them, which is also what upstream's macro emits, so M3/M4 should emit
   `byValue`/`byName` rather than open-coding `Eval.now`/`Eval.always`.
3. **`Raise` has exactly two abstract members** — `def functor: Functor[F]`
   and `def raise[E2 <: E, A](e: E2): F[A]`. `catchNonFatal`, `ensure`,
   `fromEither`, and `fromOption` are concrete defaults, so implementing
   `Raise` by hand means implementing only those two.
4. **`Trivial` is `final class Trivial[A] private`** with a cached
   `implicit def instance[A]` returning one casted singleton — so
   `Trivial.instance[Int] == Trivial.instance[String]` is reference-true.
   Harmless, but do not read anything into `Trivial` equality in M2.
5. **A parameter clause consisting entirely of capability parameters
   contributes no clause to `domain`** — not an empty inner list. This is what
   §3.4's expansion shows (`bar(i, s)(implicit R)` yields one inner list, not
   two), and the reference instance follows it: `e(implicit R1, R2)` has
   `domain == Nil`. **M3/M4 must match this.**

### Open question for M3/M4 — not decided here

Our fixtures contain no method with an *empty explicit* clause followed by a
capability clause (`def f()(implicit R: Raise[F, E]): F[A]`). Under rule 5
above it is ambiguous whether `f`'s `domain` should be `List(List())` (upstream
cats-tagless keeps the empty explicit clause, because it partitions out
implicit clauses rather than filtering individual parameters) or `Nil`. Upstream
would say `List(List())`. Flagging rather than inventing an answer; M3 should
pin it with a test and Brian should confirm the intent.

Also note derivation rule 1 ("implicitness is irrelevant to classification")
genuinely diverges from upstream, which drops *all* implicit/given clauses from
`domain`. Ours drops only `Raise` parameters, so a non-`Raise` implicit
parameter would be captured as an `Advice` and require a `Dom` instance.
No fixture covers this either.

### Deviations from the task list

- **Test dependencies were added to `raise-aspect-core`** — munit and
  munit-scalacheck 1.2.0, both `% Test`. Acceptance says "no dependencies
  beyond M0's"; read as being about the published/compile classpath, which is
  unchanged (still cats-core, cats-kernel, cats-mtl, cats-tagless-core,
  scala-library, scalac-compat-annotation). Task 4 requires smoke tests, and
  M0 gave the module no test framework, so this was unavoidable. Say the word
  if you want them in a separate testkit module instead.
- **No `RaiseAspect[PlainAlg, …]` reference instance.** `PlainAlg` and its
  `Either` implementation exist as task 3 requires, but law L9 needs a
  capability-free instance to compare against `cats.tagless.Derive.aspect`,
  and constructing that is M2's call. Not doing M2's work here.

### Verification

Clean `+clean +test` across 2.12.21 / 2.13.18 / 3.3.8: **24 raise-aspect-core
tests pass on each version** (11 `WeaveArrowsSpec`, 11 `TestAlgReferenceSpec`,
2 properties), alongside the 19 pre-existing core tests. Zero errors, zero new
warnings. Scala.js linking clean on all three. TDD throughout: every test was
watched failing with `NotImplementedError` against `???` skeletons before being
implemented.

Not covered: Scala.js *test execution* (no node locally, same as M0), and the
formal law suite (M2).

---

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
