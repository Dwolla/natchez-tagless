# RaiseAspect — shared design and laws (read first, every session)

You are implementing one milestone of a multi-milestone project. This
document is the shared context; the milestone document you were given
alongside it defines your scope. **Do not do work belonging to other
milestones.**

## Ground rules (apply to every milestone)

1. The laws and tests written in M2 are the executable specification. Once
   they exist, you must never weaken, skip, delete, or modify them to make a
   build green. You may add new tests. If you believe an existing law or test
   is wrong, STOP, explain why in your report, and wait for a human decision.
   This is the intended application of CLAUDE.md, not an exception to it: a
   suspected defect in the spec is an architectural question, and CLAUDE.md
   says those are discussed with Brian before implementation — "fix broken
   things you find immediately" is about bugs in code, not about a session
   adjudicating its own specification.
2. Brian's `~/.claude/CLAUDE.md` applies in full (TDD, honest reporting,
   smallest reasonable change, conventional commits, journaling). Within each
   milestone, work test-first; the M2 law suite is the *system-level* spec,
   and it does not replace red-green at the unit level.
3. Verify API shapes against the vendored sources in `reference/upstream/`
   (cats-tagless `v0.16.5`) instead of recalling them. Code snippets in this
   document are pseudocode at the constructor-call level; the vendored
   `cats/tagless/aop/Aspect.scala` is authoritative for `Weave`/`Advice`
   signatures. Never invent API details — research or say you don't know.
4. Match the repo's existing conventions (build structure, scalafmt, CI).
   Testing uses the repo's established stack — MUnit, munit-cats-effect,
   ScalaCheck (property-based wherever sensible), plus munit-discipline for
   law RuleSets.
5. House Scala style: Typelevel ecosystem, pure `F[_]`-parameterized code;
   lift values with postfix syntax via `cats.syntax.all._` (`x.pure[F]`,
   `e.raise[F, A]`, `either.liftTo[F]`, `x.some`) rather than companion
   constructors; no monad transformers in public APIs (test-internal use,
   e.g. `EitherT[Eval, TestError, *]` fixtures, is fine and intended).
6. Everything cross-builds on Scala 2.12, 2.13, and Scala 3 LTS unless the
   milestone says otherwise. 2.12 and 2.13 share the Scala 2 macro sources
   (version-guard only where the reflect APIs force it). If 2.12 turns out to
   be impossible somewhere, STOP and flag it — dropping 2.12 is Brian's call
   and must be called out, never silently skipped.

## 1. Problem

cats-tagless's `Aspect[Alg, Dom, Cod]` weaves `Alg[F]` into
`Alg[Aspect.Weave[F, Dom, Cod, *]]`, which natchez-tagless interprets back
into `Alg[F]` with tracing (`traceWithInputsAndOutputs`). `Aspect` extends
`FunctorK`, which requires the effect `F` to appear only as each method's
top-level return type. Algebras using cats-mtl submarine error handling
violate this:

```scala
trait Bar[F[_]] {
  def bar(i: Int)(implicit R: Raise[F, BarError]): F[String]
}
```

`FunctorK[Bar]` is uninhabited: `mapK` over `F ~> G` cannot turn the
`Raise[G, E]` a `G`-side method receives into the `Raise[F, E]` the
underlying method needs.

**Why a workaround exists:** `Raise[F, E]` never consumes `F` values —
`raise` only produces them — so the capability can be transported along
value-level mappings between effects:

- `Aspect.Weave[F, Dom, Cod, A]` exposes `codomain.target: F[A]`, so
  `_.codomain.target` is a natural transformation
  `Weave[F, Dom, Cod, *] ~> F`. A derived woven method can therefore convert
  the `Raise[Weave[F, Dom, Cod, *], E]` it receives into the `Raise[F, E]`
  the underlying implementation needs.
- Conversely, a `Raise[F, E]` lifts to `Raise[Weave[F, Dom, Cod, *], E]` by
  wrapping the raised `F[A]` in a shell `Weave`. That needs a `Cod[A]` for
  arbitrary `A`; a constant *synthetic* instance is sound because the shell
  is immediately unwrapped via `.codomain.target` and a raised `F[A]` never
  yields an `A` for anything to render. The laws in §4 make this soundness
  claim testable.

Two mechanical obstacles: cats-mtl's `Raise` has an abstract
`def functor: Functor[F]` member (solved by a `Functor[F]` constraint on
`weave` — a decided, final design point), and the upstream derivation macros
reject `F` in parameter position (solved by our own derivation, M3/M4).

### Out of scope (all milestones)

- `Handle[F, E]` parameters — `Handle` consumes `F` (`handleWith` takes an
  `F[A]`), so it is genuinely not transportable. Derivation must reject it
  with a message directing users to take `Raise` in algebra methods and use
  `Handle.allow`/`rescue` at the boundary.
- Other capabilities (`Ask`, `Tell`, `Stateful` would admit the same
  treatment; deferred), method-level type parameters, varargs/defaults beyond
  what upstream `Derive.aspect` supports, and Scala 3 context-function return
  types (reject with a message suggesting a `using` parameter).

## 2. Decisions (final — do not relitigate)

- Modules live in the **natchez-tagless build** under `com.dwolla`
  coordinates; upstreaming to cats-tagless is deferred.
- cats-tagless pinned to **`v0.16.5`**. Scala 3 derivation reference:
  `cats.tagless.macros.MacroAspect` (vendored). cats-mtl test scope ≥ 1.4.0.
- Concrete names: `RaiseAspect`, `RaiseFunctorK`, `RaiseArrow`, `RaisePull`,
  `Synthetic`. Package: `com.dwolla.tagless.mtl`.
- Cross-build axes: Scala 2.12, 2.13, and 3 (LTS), matching the repo's
  published axes and cats-tagless 0.16.5's. Dropping 2.12 is a called-out
  decision reserved to Brian.
- `weave` keeps its `Functor[F]` constraint.
- Shell `Weave`s created by `raiseLift` use the fixed advice name `"raise"`.
- Scala 3 stays on the repo's LTS line (3.3.x). The derivation relies on
  `quotes.reflect` APIs that are experimental there (notably
  `Symbol.newClass`), so — matching upstream cats-tagless — the Scala 3
  `DeriveRaise` entry points are annotated `@experimental`, and call sites
  (including our own Scala 3 test sources) must be `@experimental`-annotated
  or compiled with `-experimental` (3.4+). Do not require a nightly compiler
  and do not raise the Scala 3 baseline to avoid this.

## 3. Design

### 3.1 Modules

```
modules/
  raise-aspect-core/        # typeclass, arrows, runtime helpers (no macros)
  raise-aspect-laws/        # laws + discipline test kit (FROZEN after M2)
  raise-aspect-macros/      # src/main/scala-2 and src/main/scala-3 derivation
  natchez-tagless-mtl/      # Synthetic[TraceableValue], tracing syntax
```

`raise-aspect-core` depends only on cats, cats-mtl, cats-tagless-core.

> **Amended 2026-07-30 by M10.** `RaisePull`, `RaiseArrow`, `RaiseFunctorK`,
> `RaiseAspect`, `OnRaise`, and the `WeaveArrows` members carry an `Err[_]`
> evidence parameter. The paragraph below about there being no `E` parameter
> still holds — transport remains uniform in the error type — but each
> application of a pull now carries `Err[E]`. See
> `03-evidence-carrying-transport-design.md`.

> **Amended 2026-07-30 by M11.** `WeaveInterpreter` (M11) is backend-agnostic:
> `Dom`, `Cod`, `Err`, the `Weave ~> F` interpreter, and the `OnRaise` hook are
> all caller-supplied. A second backend — otel4s is the motivating case, and
> shares no rendering type class with natchez except `cats.tagless.Trivial` —
> needs only its own `Weave ~> F` interpreters, its own `Synthetic` instance
> for its rendering type class, and its own `RaiseRecorder`-equivalent
> resolving the default hook. The resolution mechanism, the arrows, and
> `OnRaise` are reused unchanged. That `Synthetic` instance must live
> somewhere its own syntax import will carry it — a package object is in the
> implicit scope of neither `Synthetic` nor the backend's rendering type
> class, so `Synthetic[Cod]`, resolved from `raise-aspect-core` rather than a
> lexically-enclosing scope, won't find it there; natchez-tagless hit this
> directly and moved its instance onto its syntax trait to fix it. See
> `03-evidence-carrying-transport-design.md` §B.2 for the discovery and the
> fix.

### 3.2 Core types

Algebras with method-level `Raise` parameters are functorial over a category
whose morphisms are pairs: values forward, capability backward.

```scala
package com.dwolla.tagless.mtl

/** ∀E. Err[E] ?=> Raise[G, E] => Raise[F, E] — pulls the capability backward. */
trait RaisePull[G[_], F[_], Err[_]] extends Serializable {
  def apply[E](rg: Raise[G, E])(implicit ev: Err[E]): Raise[F, E]
}
object RaisePull {
  def id[F[_], Err[_]]: RaisePull[F, F, Err] = new RaisePull[F, F, Err] {
    def apply[E](r: Raise[F, E])(implicit ev: Err[E]): Raise[F, E] = r
  }
}

/** A morphism F ⇒ G: values go forward, Raise capabilities come backward. */
final case class RaiseArrow[F[_], G[_], Err[_]](fk: F ~> G, pull: RaisePull[G, F, Err]) {
  def andThen[H[_]](that: RaiseArrow[G, H, Err]): RaiseArrow[F, H, Err] =
    RaiseArrow(that.fk.compose(fk), new RaisePull[H, F, Err] {
      def apply[E](rh: Raise[H, E])(implicit ev: Err[E]): Raise[F, E] = pull(that.pull(rh))
    })
}
object RaiseArrow {
  def id[F[_], Err[_]]: RaiseArrow[F, F, Err] = RaiseArrow(FunctionK.id, RaisePull.id)
}

trait RaiseFunctorK[Alg[_[_]], Err[_]] extends Serializable {
  def mapK[F[_], G[_]](af: Alg[F])(arrow: RaiseArrow[F, G, Err]): Alg[G]
}

trait RaiseAspect[Alg[_[_]], Dom[_], Cod[_], Err[_]] extends RaiseFunctorK[Alg, Err] {
  def weave[F[_]](af: Alg[F])(implicit F: Functor[F]): Alg[Aspect.Weave[F, Dom, Cod, *]]
}
```

There is deliberately **no `E` parameter on the typeclass**: transport is
uniform in the error type, so each method is handled with whatever error
type(s) it declares, including multiple `Raise` parameters per method.
`Err[_]` is not that `E` parameter — it is a per-error-type *evidence* type
class that `RaisePull#apply` demands afresh at each application, so transport
stays uniform in `E` while an interception point gains something better than
`toString` to render a raised error with; `cats.tagless.Trivial` is the `Err`
to use when no evidence is wanted. `Aspect.Weave`/`Aspect.Advice` are reused
from cats-tagless so natchez-tagless's existing `Weave ~> F` interpreters
work unchanged. `weave` is not expressible via `mapK` (it injects per-call
metadata and needs `Dom`/`Cod` instances); both are derived.

### 3.3 Canonical arrows and synthetic instances

```scala
/** Produces a Cod instance for any type. Used only inside the raise-lift
  * shell, which is unwrapped immediately; its value is never observable
  * (a raised F[A] contains no A). Laws L5/L6 pin this down. */
trait Synthetic[Cod[_]] extends Serializable { def apply[A]: Cod[A] }
object Synthetic {
  implicit val trivial: Synthetic[cats.tagless.Trivial] = ...
}

/** Invoked with the typed value at the moment a raise crosses a `raiseLift`
  * interception point. Universally quantified in `E`, with per-`E` evidence
  * supplied by `Err`. */
trait OnRaise[F[_], Err[_]] extends Serializable {
  def apply[E](e: E)(implicit ev: Err[E]): F[Unit]
}
object OnRaise {
  def noop[F[_], Err[_]](implicit F: Applicative[F]): OnRaise[F, Err] = ...
}
```

In a `WeaveArrows` object (names final):

```scala
/** Forward: forget the metadata. */
def codomainTarget[F[_], Dom[_], Cod[_]]: Aspect.Weave[F, Dom, Cod, *] ~> F
  // = _.codomain.target

/** Used INSIDE derived weave methods. */
def raisePull[F[_], Dom[_], Cod[_], Err[_]](implicit F: Functor[F]): RaisePull[Aspect.Weave[F, Dom, Cod, *], F, Err] =
  // apply[E](rw)(implicit ev: Err[E]) = new Raise[F, E] {
  //   val functor = F                                  // NOT rw.functor
  //   def raise[E2 <: E, A](e: E2) = rw.raise[E2, A](e).codomain.target
  // }

/** Used on the interpretation side. Shell advice name is "raise". */
def raiseLift[F[_], Dom[_], Cod[_], Err[_]](implicit F: Functor[F], syn: Synthetic[Cod]): RaisePull[F, Aspect.Weave[F, Dom, Cod, *], Err] =
  // apply[E](r)(implicit ev: Err[E]) = new Raise[Weave[F, Dom, Cod, *], E] {
  //   val functor = syntheticWeaveFunctor(F, syn)      // see below
  //   def raise[E2 <: E, A](e: E2) =
  //     Aspect.Weave("raise", Nil, Aspect.Advice("raise", r.raise[E2, A](e))(syn[A]))
  // }

/** As `raiseLift` above, but sequences an `OnRaise[F, Err]` hook's effect
  * before the raised value crosses the interception point (`Apply[F]`, not
  * `Functor[F]`, is what sequencing needs) — this is M6/M10's typed-error
  * span recording. */
def raiseLift[F[_], Dom[_], Cod[_], Err[_]](onRaise: OnRaise[F, Err])(implicit F: Apply[F], syn: Synthetic[Cod]): RaisePull[F, Aspect.Weave[F, Dom, Cod, *], Err] =
  // apply[E](r)(implicit ev: Err[E]) = new Raise[Weave[F, Dom, Cod, *], E] {
  //   val functor = syntheticWeaveFunctor(F, syn)
  //   def raise[E2 <: E, A](e: E2) =
  //     Aspect.Weave("raise", Nil,
  //       Aspect.Advice("raise", onRaise.apply[E](e) *> r.raise[E2, A](e))(syn[A]))
  // }

/** The full erasure morphism Weave[F, Dom, Cod, *] ⇒ F. */
def eraseWeave[F[_]: Functor, Dom[_], Cod[_], Err[_]](implicit syn: Synthetic[Cod]): RaiseArrow[Aspect.Weave[F, Dom, Cod, *], F, Err] =
  RaiseArrow(codomainTarget, raiseLift)
```

`syntheticWeaveFunctor` maps `codomain.target` with `Functor[F]`, substitutes
`syn[B]` for the `Cod` instance, and preserves `algebraName`, `domain`, and
`codomain.name`. It and `Synthetic` exist only to satisfy `Raise`'s structure
on shell values in the raise channel; laws L5–L7 verify they are never
observable through the public API.

### 3.4 Expansion specification for derivation

Given:

```scala
trait Bar[F[_]] {
  def bar(i: Int, s: => String)(implicit R: Raise[F, BarError]): F[String]
  def baz(k: Int): F[Int]
}
```

`DeriveRaise.aspect[Bar, Dom, Cod, Err]` must produce, for `weave` (modulo
hygiene; confirm `Weave`/`Advice` constructor shapes against the vendored
`Aspect.scala`):

```scala
def weave[F[_]](af: Bar[F])(implicit F: Functor[F]): Bar[Aspect.Weave[F, Dom, Cod, *]] =
  new Bar[Aspect.Weave[F, Dom, Cod, *]] {
    def bar(i: Int, s: => String)(
        implicit R: Raise[Aspect.Weave[F, Dom, Cod, *], BarError]
    ): Aspect.Weave[F, Dom, Cod, String] =
      Aspect.Weave(
        "Bar",
        List(List(
          Aspect.Advice("i", cats.Eval.now(i))(Dom[Int]),
          Aspect.Advice("s", cats.Eval.always(s))(Dom[String])
        )),
        Aspect.Advice("bar", af.bar(i, s)(WeaveArrows.raisePull[F, Dom, Cod, Err].apply(R)(errBarError)))(Cod[String])
      )

    def baz(k: Int): Aspect.Weave[F, Dom, Cod, Int] =
      Aspect.Weave("Bar",
        List(List(Aspect.Advice("k", cats.Eval.now(k))(Dom[Int]))),
        Aspect.Advice("baz", af.baz(k))(Cod[Int]))
  }
```

where `errBarError` is the `Err[BarError]` the derivation summons implicitly
for `bar`'s `Raise` parameter — one summon per capability parameter, at the
derivation site, per Task 6's macro work; a missing instance is a derivation-
time diagnostic naming the method, the error type, and `Err`, not a runtime
failure.

and for `mapK`:

```scala
def mapK[F[_], G[_]](af: Bar[F])(arrow: RaiseArrow[F, G, Err]): Bar[G] =
  new Bar[G] {
    def bar(i: Int, s: => String)(implicit R: Raise[G, BarError]): G[String] =
      arrow.fk(af.bar(i, s)(arrow.pull(R)(errBarError)))
    def baz(k: Int): G[Int] = arrow.fk(af.baz(k))
  }
```

Derivation rules:

1. A parameter is a **capability parameter** iff its type, after dealiasing,
   is `cats.mtl.Raise[F, e]` where `F` is the algebra's effect parameter and
   `e` does not mention `F`. Implicitness is irrelevant to classification;
   generated signatures preserve parameter-list shape and `implicit`/`using`
   flags exactly.
2. Capability parameters are transported, never captured in `domain`. All
   other parameters must not mention `F` and are captured as `Advice`s with
   `Dom` instances (strict → `Eval.now`, by-name → `Eval.always`), exactly
   like upstream `Derive.aspect`.
3. `F` may otherwise appear only as the top-level return type; anything else,
   including `Handle[F, e]`, is a compile error (messages in the M3/M4 docs).
4. `algebraName` = simple name of `Alg`; advice names = parameter/method
   names (upstream conventions, so span naming is unchanged).
5. Multiple capability parameters on one method are each transported
   independently, each summoning its own `Err[e]` instance.
6. Abstract vals / nullary defs returning `F[A]`: parity with upstream.
7. Use the declared parameter type verbatim post-dealias (`Raise` is
   contravariant in `E`; do not reconstruct types and risk variance drift).
8. Each capability parameter's `Err[e]` instance is summoned implicitly at
   the derivation site, exactly like a `Dom`/`Cod` instance; a missing
   instance is a derivation-time diagnostic naming the method, the error
   type, and `Err` (M10, Task 6).

## 4. Laws

Notation: `<->` is extensional equality under the test-kit `Eq` instances.

Adapted from existing law sets:

- **L1 mapK identity:** `mapK(af)(RaiseArrow.id) <-> af`
- **L2 mapK composition:** `mapK(mapK(af)(f))(g) <-> mapK(af)(f andThen g)`
- **L3 weave erasure** (the Aspect-consistency analogue and the load-bearing
  law): `mapK(weave(af))(WeaveArrows.eraseWeave) <-> af`, tested including
  inputs that make the underlying implementation raise — at
  `F = Either[TestError, *]` a raise must return the identical `Left`
  through the woven path.
- **Serializable** tests for all typeclass instances (cats convention).

(cats-mtl's `Raise` is itself essentially lawless — its laws live on
`Handle` — so beyond functor coherence there is nothing upstream to
preserve; that is what L6/L7 pin down.)

Novel laws:

- **L4 arrow coherence** (property of a `RaiseArrow`; check for `id`,
  `eraseWeave`, and their compositions):
  `arrow.fk(arrow.pull(rg).raise[A](e)) <-> rg.raise[A](e)`
- **L5 section/retraction** on the canonical pair:
  `raisePull(raiseLift(r)).raise[A](e) <-> r.raise[A](e)`, i.e.
  `raiseLift(r).raise[A](e).codomain.target <-> r.raise[A](e)`
- **L6 synthesized-functor coherence (lift side):**
  `lifted.functor.map(w)(f).codomain.target <-> Functor[F].map(w.codomain.target)(f)`
  and `algebraName`/`domain`/`codomain.name` preserved.
- **L7 synthesized-functor coherence (pull side):** the `Raise[F, E]` handed
  to the underlying implementation has `functor` extensionally equal to the
  ambient `Functor[F]` (unit test; guards against routing through
  `rw.functor`).
- **L8 weave structure fidelity / capability erasure** (structural, per
  method/argument tuple): `algebraName` and method name correct; `domain`
  matches declared parameter lists in order with capability parameters
  absent, advice names = parameter names, strict args `Eval.now`-like,
  by-name args not forced by weaving (throwing-thunk test);
  `codomain.target` equals invoking the underlying method with the pulled
  capability.
- **L9 conservative extension:** on capability-free algebras,
  `DeriveRaise.aspect` agrees with `cats.tagless.Derive.aspect` (structurally
  equal rendered `Weave`s, `Eq`-equal codomain targets), and
  `mapK(af)(RaiseArrow(fk, pull))` agrees with `FunctorK.mapK(af)(fk)` for
  any `pull`.
- **L10 laziness parity (semi-law):** weaving itself performs no `F`
  effects; effects run exactly when they would under upstream
  `Derive.aspect` (tested with a `Writer`/`State` effect counter).

## 5. Milestone map

- M0 scaffolding → `10-milestone-M0-scaffolding.md`
- M1 runtime core + hand-written reference instance → `11-…`
- M2 laws + discipline test kit (validated against M1) → `12-…`
- M3 Scala 2 macro → `13-…`
- M4 Scala 3 macro → `14-…`
- M5 natchez-tagless integration + docs → `15-…`

Second round (planned 2026-07-29; M0–M5 are complete):

- M6 typed-error span recording at the `raiseLift` interception point →
  `16-milestone-M6-typed-error-span-recording.md`
- M7 method-local `Dom`/`Cod` instances in derivation (resolves the
  appendix below) → `17-milestone-M7-method-local-dom-cod-instances.md`
- M8 generalizing to `CapabilityAspect` over `Ask`/`Tell`/`Stateful` —
  design-gated and need-gated; see its doc →
  `18-milestone-M8-capability-aspect.md`
- M9 upstreaming to cats-tagless — gated on Brian and on maintainer
  buy-in → `19-milestone-M9-upstreaming.md`

M6 and M7 are independent of each other; M8 and M9 carry explicit gates
recorded in their documents. As ever: do not do work belonging to milestones
other than your own.

Third round (2026-07-31):

- M12 fused derivation — replaces `weave` + `mapK` with a single `intercept`,
  removing the `Weave`-as-effect-type carrier and with it the unlawful
  synthesized `Functor` →
  `22-milestone-M12-fused-derivation.md`

**Why M12 exists.** §3.2's decision to make `Weave` the woven algebra's effect
type forces the woven methods to take `Raise[Weave[F, Dom, Cod, *], E]`, and
`cats.mtl.Raise` declares an abstract `functor`, so a `Functor[Weave[…]]` must
be synthesized. That instance was demonstrated to **fail the functor identity
law**, and `Raise#functor` is public precisely so external generic code can
recover the bundled algebra — so a generic `R.functor.map(fa)(f)` on a real
weave silently substitutes a fabricated `Cod`, corrupting a *successful*
call's rendering. With a user-authored rendering `Synthetic` it becomes a
redaction hole.

Fusing the two operations removes the cause rather than documenting the
symptom: the caller's `Raise[F, E]` is handed straight to the underlying
implementation, so no capability ever crosses a carrier boundary, no
`Functor[Weave]` is needed, and `Synthetic` deletes. `Weave` survives as
*data* passed to the interpreter, so `TraceWeaveCapturingInputs(AndOutputs)`
keep their exact types.

Three spikes established this, each reverting its code: behavioural
equivalence including end-to-end natchez span histories; the macro merge on
both axes and all three Scala versions, with `ExpectedWeaves.expected`
reproduced byte for byte; and the negative result that no lawful
`Functor[Weave[F, Dom, TraceableValue, *]]` exists, because a lawful one needs
`Functor[Cod]` and `TraceableValue`'s parameter is negative-only.

**Consequence for M8, which stays paused.** Because nothing crosses a boundary
in the fused form, the four-of-nine transportable classification ceases to
govern method parameters — `Handle`, `Local`, `Listen`, `Censor` and
`Stateful` were all demonstrated working. That classification still governs
`mapK`, which keeps real transport. Whether to admit further capabilities is a
separate decision the owner has not made; M12 keeps `Raise`-only recognition.

### Method-local `Dom`/`Cod`/`Err` instances (found in M4, resolved in M7)

Originally, `Dom` and `Cod` instances (later joined by `Err`, added in M10) were
summoned only at the *derivation* site. A method that supplied its own instance
through its own `using`/`implicit` clause could not be woven at all, even though
the instance was in scope everywhere it was needed:

```scala
trait Widget
trait WidgetAlg[F[_]] {
  // Render[Widget] is supplied by the method, not by the derivation site
  def show(w: Widget)(using Render[Widget]): F[String]
}

// Before M7, DeriveRaise.aspect[WidgetAlg, Render, Render] failed with:
//   Not found: given Render[Widget] for parameter w of method show
```

Capturing `w` in `domain` needs a `Render[Widget]`, and the only one available
lived inside `show`'s own `using` clause — invisible to the derivation, which
resolved instances before generating the method body.

Upstream cats-tagless works around this in `MacroAspect` with an
`addToGivenScope` block (commented "This is a hack") that reflects into
`dotty.tools.dotc` internals to inject the method's given parameters into the
implicit cache. M4 deliberately omitted it, and **M7 confirmed that ruling
permanently**: it is reflection into compiler internals that silently degrades
to a no-op on failure, no fixture exercised it, and shipping it in a published
library means a future compiler change surfaces as a confusing
implicit-not-found rather than an obvious break. Option (a) stays rejected; do
not revisit it.

**M7 resolved this limitation on both macro axes by pursuing option (b), but not
by the mechanism this appendix originally proposed.** The mechanism that
shipped, and the mechanism this appendix speculated about, are different enough
to be worth keeping straight:

> An instance resolves iff the declared type of one of the generated method's
> own implicit/given parameters is a **subtype** (`<:<`) of the needed
> `Dom[T]` / `Cod[T]` / `Err[E]`. No implicit search of any kind is performed:
> no derivation, no companion scope, no chaining.

Concretely: derivation-site resolution runs first, exactly as before — it
changes nothing when it succeeds, including the existing missing-instance
diagnostics. Only when it fails does the macro look for exactly one of the
generated method's own implicit/given parameters whose declared type conforms
to the needed one, and splice a direct reference to that parameter —
`Ref(paramSymbol)` on Scala 3, `Ident(name)` on Scala 2. This newly resolves,
among other shapes:
an exact match; a candidate that is merely a subtype of the needed type; the
needed type hidden behind a type alias; contravariant widening; several
method-local candidates with exactly one conforming; polymorphic and
context-bound methods (`def poly[A: Render](a: A)`), which could never resolve
at the derivation site at all, since no concrete type exists there to summon
against; a conforming parameter in a non-final `using` clause on Scala 3 (an
axis-asymmetric shape: Scala 2's grammar permits at most one implicit clause
and requires it to be last, so it cannot even be expressed there); and the
`functorK`/`mapK` path. Two conforming candidates abort with an
ambiguity error naming both, rather than picking one arbitrarily; a conforming
parameter sitting in an ordinary, non-implicit clause gets a hint naming it
rather than the plain missing-instance message.

Because the fallback only fires when derivation-site resolution fails,
derivation-site precedence is silent: adding an instance at the derivation site
later — e.g. a companion gains an `implicit val` that conforms — stops the
weave from using the method-local instance it previously used, with no
diagnostic marking the switch. This is intended (Decision 1, pinned by the
`PrecedenceAlg` test) and preserves the pre-M7 contract, but it is worth
naming: it is the same class of surprise this milestone otherwise eliminated.

**This appendix's own option-(b) hypothesis — emit an in-body
`scala.compiletime.summonInline` and let post-macro inlining resolve it in the
generated body's scope — turned out not to work, and is worth recording
precisely so nobody re-attempts it.** It is not merely inert. A `summonInline`
spliced into a macro expansion is reduced against the **macro call site's**
implicit scope, not the generated method's — so with a conforming `given`
sitting at the call site, the woven code silently used *that* instance and
ignored the one the method was actually handed. That is a silent-wrong-instance
hazard, not a no-op, which makes it more dangerous than the plain "not found"
error it was meant to replace. `scala.compiletime.summonFrom` cannot even be
emitted from this position ("can only be used in an inline method"). Do not
revisit either.

**What remains unresolved.** By Brian's explicit ruling, the derivation case
itself is out of scope: deriving an instance from a method-local one (e.g.
needing `Render[List[Widget]]` when only `Render[Widget]` is method-local)
would require actual implicit search, which the `<:<` rule deliberately does
not perform. Three more shapes go unresolved not by ruling but as a structural
consequence of the same `<:<`-only rule: a needed type that is a subtype of the
handed one under an *invariant* type class (the reverse of the contravariant
case that does resolve); an instance reachable only *through* a parameter
rather than being one directly; and an instance handed as an ordinary,
non-implicit parameter (the best it gets is the hint mentioned above) — none of
these are declared-type subtype relations, so nothing short of real implicit
search would reach them. Scala 2 has a superficially more powerful
alternative — splicing an untyped `implicitly[T]` into the generated body,
which the typer then resolves with the method's implicit parameters in
scope — and it was rejected even though it compiles: it only
*derives* an instance when the derivation rule is in lexical scope **at the
derivation site**, because the generated body's lexical scope *is* the macro
call site, not some independent scope nested inside the method. So "full
implicit search in the body" was never going to deliver what the phrase
suggests, on either axis, short of the permanently-rejected option (a). It was
also rejected on independent grounds: it regresses all three existing
missing-instance diagnostics into raw `TypecheckException` stack traces, and it
would hand Scala 2 a capability Scala 3 cannot match.

See `17-milestone-M7-method-local-dom-cod-instances.md` — its Status section
records the spike in full, including the independently-reproduced Scala 3
behavior above and the exact fixtures verified against 2.12.21, 2.13.18, and
3.3.8.
