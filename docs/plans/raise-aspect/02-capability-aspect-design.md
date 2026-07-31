# `CapabilityAspect` — generalizing capability transport beyond `Raise`

Design document, 2026-07-30. Phase 1 deliverable of M8
(`18-milestone-M8-capability-aspect.md`). Plays the same role for this work
that `01-overview-design-and-laws.md` plays for M0–M5: final decisions, core
types at the constructor-call level, an expansion specification, laws, and a
milestone map for Phase 2.

Read `01-overview-design-and-laws.md` first, then
`03-evidence-carrying-transport-design.md` (M10/M11), whose `Err` evidence
parameter and `WeaveInterpreter` are live inputs here.

## Status

**Not ratified. This is the design gate.** Phase 2 does not begin until Brian
ratifies this document. It resolves M8's six numbered points; each is marked
**DECIDED** (with rationale) or **QUESTION FOR BRIAN**. Three of the six carry
a subsidiary question even where the main point is decided; §12 collects them
in one table.

**Five assumptions in this design have never been put in front of a compiler.**
They are marked `[SPIKE-n]` where they appear and collected in §11. Each is a
Phase 2 task that must be discharged *before* the code depending on it is
written. This project has repeatedly had confident claims about Scala
semantics falsified by contact with the compiler — most sharply in M7, where a
ratified design's `summonInline` mechanism turned out to bind the *wrong*
instance while compiling cleanly — so anything unverified is labelled rather
than asserted.

Every cats-mtl signature cited below was read from the vendored v1.7.0 sources
under `reference/upstream/cats-mtl/` (tag `v1.7.0`, commit
`931556e44938a47eaa91af4d907b61a4a0bb4cab`). File and line are given at each
citation. Nothing is recalled.

---

## 1. What is already settled

Carried from the milestone document, the Q2 spike (`.superpowers/sdd/
m8-q2-spike-report.md`) and its independent verification
(`.superpowers/sdd/m8-q2-verification.md`). **Where spike and verification
disagree the verification wins**; it reproduced both load-bearing claims from
scratch and corrected several overstatements.

### 1.1 The transportable set: four of nine, verified

cats-mtl 1.7.0 defines **nine** capability type classes. A capability is
transportable iff no *abstract* member takes an `F[_]` value in a parameter
position.

| Capability | Abstract members | Verdict | Disqualifier |
| --- | --- | --- | --- |
| `Raise[F[_], -E]` | `functor` (Raise.scala:67), `raise[E2 <: E, A](e: E2): F[A]` (:69) | transportable | — |
| `Ask[F[_], +E]` | `applicative` (Ask.scala:49), `ask[E2 >: E]: F[E2]` (:51) | transportable | — |
| `Tell[F[_], -L]` | `functor` (Tell.scala:43), `tell(l: L): F[Unit]` (:45) | transportable | — |
| `Stateful[F[_], S]` | `monad` (:58), `get: F[S]` (:64), `set(s: S): F[Unit]` (:66) | transportable, **deferred** — §1.2 | — |
| `Local[F[_], E] extends Ask` | `local[A](fa: F[A])(f: E => E): F[A]` (Local.scala:53) | excluded | `local` |
| `Listen[F[_], L] extends Tell` | `listen[A](fa: F[A]): F[(A, L)]` (Listen.scala:52) | excluded | `listen` |
| `Censor[F[_], L] extends Listen` | `applicative` (:38), `monoid: Monoid[L]` (:39), `censor[A](fa: F[A])(f: L => L): F[A]` (:42) | excluded | `censor`, inherited `listen` |
| `Handle[F[_], E] extends Raise` | `applicative` (Handle.scala:31), `handleWith[A](fa: F[A])(f: E => F[A]): F[A]` (:35) | excluded | `handleWith` |
| `Chronicle[F[_], E]` | `monad` (:29), `dictate`, `confess`, `materialize[A](fa: F[A]): F[E Ior A]` (Chronicle.scala:37) | excluded | **`materialize`**, not `confess`/`dictate` |

Two corrections to the milestone document's original list, both carried:
`Listen` was missing entirely (it sits between `Tell` and `Censor`, so any
reasoning about `Censor` must account for it), and `Chronicle`'s disqualifier
is `materialize`, not the produce-only `confess`/`dictate`.

**`Local.liftTo` is not a counterexample.** `Local` has a `liftTo`
(Local.scala:58–59) despite `local` consuming an `F[A]`, but it is typed
`LiftKind[F, G]`, whose `limitedMapK[A](ga: G[A])(scope: F ~> F): G[A]`
(LiftKind.scala:38) lifts an *endomorphism* `F ~> F`, and whose only instances
are canonical transformer stacks (`EitherT`/`IorT`/`Kleisli`/`OptionT`/
`WriterT`). This library transports along an *arbitrary* `F ~> G` — a tracing
interpreter. Different problem, same method name. Do not build on `LiftKind`.

**Design language.** State the rule as "excluded if *any* abstract member has
an `F` value in a parameter position", not "excluded if a member does not
produce": `local`, `listen`, `censor`, `handleWith` and `materialize` all both
consume and produce, and the consuming parameter is what disqualifies them.

### 1.2 Why `Stateful` is out, and exactly how far out

**Scope, ruled by Brian 2026-07-30: `Tell` and `Ask`. `Stateful` deferred.**

`Stateful`'s evidence member is `monad: Monad[F]` (Stateful.scala:58), so
transporting it onto the woven carrier requires a `Monad[Aspect.Weave[F, Dom,
Cod, *]]`. That cannot be lawfully constructed. The argument (the
verification's, which is stronger than the spike's sampling of three merge
policies):

`Weave[F, Dom, Cod, A] ≅ M × F[A] × Cod[A]`, where
`M = (algebraName, domain, codomain.name)` is **strict, non-effectful metadata
independent of `A`**. A `flatMap[A, B](w)(f)` has `w`'s components and an
opaque `f` it can only apply to an `A` — and there is no `A` in hand; the only
`A`s live inside `F[A]`, and `Monad[F]` cannot project one back out into a
strict `String`. Therefore `flatMap(w)(f)`'s metadata is `g(m_w)` for some
fixed `g`, uniform in `f`. Left identity, `flatMap(pure(a))(f) <-> f(a)`, then
demands that a single value `g(m₀)` be identified with `metadata(f(a))` for
*every* `f` — i.e. that the equivalence identify all metadata, and (by the same
argument on the third component) all `Cod` instances. So:

> **Any equivalence at which a synthesized `Monad[Weave]` satisfies left
> identity must factor through `WeaveArrows.codomainTarget`** — which is
> exactly the statement that the metadata and the redaction instance are
> unspecified, i.e. that `Weave` has no reason to exist.

This rules out **every** `flatMap`, not merely every merge policy, and no
useful coarser equivalence exists: the lattice collapses to target-only.
`ap` escapes because it takes two *already-built* weaves, so both metadata
values are visible and a policy is an honest function `M × M → M`; that is the
entire difference between `Ask` and `Stateful`.

Two things not to rediscover:

- **The `null` probe is not an escape hatch.** Scala is not parametric, so
  `flatMap` could evaluate `f(null.asInstanceOf[A])` and read the metadata off
  the probe. It silently yields `0`/`false` for primitive `A`; the derived
  expansion puts arguments into `domain` via `Advice.byValue` = `Eval.now`,
  strictly, so the probe's domain values are `null` and the structural
  equivalence still fails; and it throws for any `f` that touches `a`.
- **The impossibility is relative to reusing cats-tagless's `Weave`
  verbatim.** Metadata of type `F[M]` would make `flatMap` sequence it and
  recover left identity. Overview §3.2 forfeited that deliberately, so that
  natchez-tagless's existing `Weave ~> F` interpreters keep working
  (`RaiseAspect`'s own scaladoc says so). **"`Stateful` deferred" therefore
  means "out unless `Weave` interop is abandoned" — not "out until someone
  tries harder."**

Two corroborating costs, both from the spike: `Stateful` is the only one of
the four that forces `Monad[F]` onto `weave` for every algebra in the module,
and its unlawfulness is reachable through documented members — `inspect`
(Stateful.scala:60), `modify` (:62), `fromStateT` (:74, which needs only
`monad` and so *is* reachable on a lifted instance), and `monad` itself, a
public abstract member of a public trait.

This design gives that ruling teeth in the type system rather than only in an
allowlist: see `WeaveEvidence` (§2.3), which has instances at `Functor` and
`Applicative` and deliberately none at `Monad`. A hand-written
`CapabilityK[Stateful, Monad]` still could not form an erasure arrow.

### 1.3 What the spike and its verification settled

- **One arrow suffices** (M8 question 1, settled favourably). `raisePull` and
  `raiseLift` are the *same operation* applied to two different natural
  transformations: `raisePull == transport(rw)(codomainTarget)` and
  `raiseLift == transport(rf)(shellK("raise"))`, demonstrated equal on the
  shipped code including the ambient-`Functor` identity that law L7 pins.
- **The `OnRaise` hook factors as a pre-transport capability decorator**, so
  hooks do not force a per-capability pull trait; the spike's caveat there was
  over-stated and the verification corrected it with a working decorator.
  §2.4 records the one residue of that caveat which the verification did not
  test — it constrains the arrow's *representation*, not the uniformity of
  transport.
- **`CapabilityK[C, Ev]` is indexed at the exact evidence class**, so a
  mixed-capability arrow needs a **subsumption witness**. §2.2 designs it.
- **Question 2's stated premise is dead.** The section/retraction argument
  cannot carry the soundness claim alone: a synthesized `Cod` is observable
  through a capability's *public evidence member*, and `Ask#reader`
  (Ask.scala:53, `applicative.map(ask)(f)`), `Tell#writer` (Tell.scala:47,
  `functor.as(tell(l), a)`) and `Stateful#inspect` (Stateful.scala:60) are all
  defaults routed through it. The claim that survives is about *the
  derivation*. §3 states it.
- **A law comparing `codomain.instance` is unsatisfiable**, so the
  substitution must be stated *as* a law rather than forbidden by one.
- **Extensible, user-registered capabilities are out of scope (YAGNI).** The
  allowlist is closed. Beyond YAGNI there is a soundness reason: a
  user-supplied `CapabilityK` would be trusted by the derivation with nothing
  checking that it transports only producing members.
- **Never reintroduce** upstream cats-tagless's `addToGivenScope` reflection
  into `dotty.tools.dotc` internals. Permanently rejected (overview appendix,
  confirmed by M7).

### 1.4 What M8 has instead of a use case

The need gate was opened on the goal of extending the transport concept, not
on a driving algebra. The fixture algebra in §9 therefore *is* the driving use
case, and it is the only thing anchoring the mixed-capability shape. That is
worth remembering when weighing §12's questions: nothing external will tell us
we got the ergonomics wrong.

---

## 2. Point 1 — Representation — **DECIDED**, with one question in §5

### 2.1 `CapabilityK` — uniform transport

```scala
package com.dwolla.tagless.mtl

/** Transports a cats-mtl capability along a value-level mapping of effects,
  * in the direction opposite to the values. Possible exactly for capabilities
  * whose abstract members only ''produce'' `F` values.
  *
  * `Ev` is the type class of the capability's evidence member: `Functor` for
  * `Raise` and `Tell`, `Applicative` for `Ask`.
  */
trait CapabilityK[C[_[_], _], Ev[_[_]]] extends Serializable {
  def transport[G[_], F[_], X](cg: C[G, X])(gf: G ~> F)(implicit F: Ev[F]): C[F, X]

  /** Decorates a capability with an observation hook ''before'' transport.
    * The default is the identity: a capability with no payload travelling out
    * of the algebra has nothing to observe. Only `Raise` overrides it. §7.1.
    */
  def observe[G[_], X, Err[_]](cg: C[G, X])(hook: OnRaise[G, Err])(implicit
      G: Apply[G],
      ev: Err[X]
  ): C[G, X] = cg
}

object CapabilityK {
  def apply[C[_[_], _], Ev[_[_]]](implicit ev: CapabilityK[C, Ev]): CapabilityK[C, Ev] = ev

  implicit val raiseK: CapabilityK[Raise, Functor] = new CapabilityK[Raise, Functor] {
    def transport[G[_], F[_], X](cg: Raise[G, X])(gf: G ~> F)(implicit F: Functor[F]): Raise[F, X] =
      new Raise[F, X] {
        val functor: Functor[F] = F
        def raise[E2 <: X, A](e: E2): F[A] = gf(cg.raise[E2, A](e))
      }

    override def observe[G[_], X, Err[_]](cg: Raise[G, X])(hook: OnRaise[G, Err])(implicit
        G: Apply[G],
        ev: Err[X]
    ): Raise[G, X] = new Raise[G, X] {
      val functor: Functor[G] = G
      def raise[E2 <: X, A](e: E2): G[A] = hook.apply[X](e)(ev) *> cg.raise[E2, A](e)
    }
  }

  implicit val askK: CapabilityK[Ask, Applicative] = new CapabilityK[Ask, Applicative] {
    def transport[G[_], F[_], X](cg: Ask[G, X])(gf: G ~> F)(implicit F: Applicative[F]): Ask[F, X] =
      new Ask[F, X] {
        val applicative: Applicative[F] = F
        def ask[E2 >: X]: F[E2] = gf(cg.ask[E2])
      }
  }

  implicit val tellK: CapabilityK[Tell, Functor] = new CapabilityK[Tell, Functor] {
    def transport[G[_], F[_], X](cg: Tell[G, X])(gf: G ~> F)(implicit F: Functor[F]): Tell[F, X] =
      new Tell[F, X] {
        val functor: Functor[F] = F
        def tell(l: X): F[Unit] = gf(cg.tell(l))
      }
  }

  // deliberately no `statefulK`: see §1.2
}
```

`transport`'s three instances are the spike's, verbatim, and were demonstrated
to reproduce the shipped `raisePull`/`raiseLift`/`andThen` behaviour exactly on
2.13.18 and 3.3.8. `observe` is the verification's `hookDecorator`, moved from
a free function onto the capability so that a capability-polymorphic pull can
apply it without dispatching on `C` — see §2.4 and §7.1.

`Raise[F, -E]`, `Ask[F, +E]` and `Tell[F, -L]` have three different variance
annotations on their second parameter, and `C[_[_], _]` is invariant. They
conform on 2.13 and 3 (demonstrated). **On 2.12 this is unverified
`[SPIKE-1]`.**

### 2.2 `Subsumes` — the subsumption witness

`CapabilityK` is indexed at the exact evidence class. An algebra whose methods
mention both `Ask` and `Raise` needs one arrow, carrying one evidence
constraint — the **join**, `Applicative`. But `CapabilityK[Raise, Functor]` is
not a `CapabilityK[Raise, Applicative]`: `Ev[_[_]]` sits in an invariant
higher-kinded slot. Making it contravariant would only help if Scala derived
`Applicative <: Functor` as *type constructors*, which is precisely the kind
of claim this project does not make without a compiler. So the witness is
explicit and first-class:

```scala
/** `Ev` is at least as strong as `Ev0`: every `Ev[F]` is an `Ev0[F]`. */
trait Subsumes[Ev[_[_]], Ev0[_[_]]] extends Serializable {
  def apply[F[_]](ev: Ev[F]): Ev0[F]
}

object Subsumes {
  def apply[Ev[_[_]], Ev0[_[_]]](implicit ev: Subsumes[Ev, Ev0]): Subsumes[Ev, Ev0] = ev

  implicit def refl[Ev[_[_]]]: Subsumes[Ev, Ev] =
    new Subsumes[Ev, Ev] { def apply[F[_]](ev: Ev[F]): Ev[F] = ev }

  implicit val applicativeFunctor: Subsumes[Applicative, Functor] =
    new Subsumes[Applicative, Functor] { def apply[F[_]](ev: Applicative[F]): Functor[F] = ev }

  implicit val applicativeApply: Subsumes[Applicative, Apply] =
    new Subsumes[Applicative, Apply] { def apply[F[_]](ev: Applicative[F]): Apply[F] = ev }
}
```

Each instance's body is a plain upcast; the type class exists only to make the
relation available where `Ev` and `Ev0` are *abstract*, which is inside the
generic pull implementations. In macro-generated code both are concrete and the
upcast would typecheck on its own — but the pull's signature demands the
witness, and summoning it explicitly at the derivation site is what turns a
too-weak `Ev` into a diagnostic naming the method and the capability (§4)
rather than a raw inference failure.

Three instances is the whole set M8 needs (`Functor`, `Applicative`; `Apply`
only for the hook path). No `Monad` instances: `Stateful` is out.

**Rejected alternative: fix `Ev = Applicative` everywhere and drop
`Subsumes`.** It over-constrains every existing user — today's `weave` needs
only `Functor[F]`, and law L3 is exercised at `Functor`-only effects. Paying
`Applicative` for a `Raise`-only algebra is a regression, not a
simplification.

**Rejected alternative: make `Ev` a type member of `CapabilityAspect` rather
than a parameter.** It removes a user-visible type argument, but pushes every
`weave` call site onto a path-dependent implicit (`implicit F: A.Ev[F]`),
which is exactly the sort of resolution this repo has been burned by three
times (M7's `summonInline`, M10's `IsTraceableValue`, M11's package-object
`Synthetic`). Named here so it can be reconsidered if `[SPIKE-3]` fails.

### 2.3 `WeaveEvidence` — where `Synthetic` fits

The lift direction needs the capability's evidence *on the woven carrier*. For
`Raise` and `Tell` that is the existing `syntheticWeaveFunctor`; for `Ask` it
is a synthesized `Applicative`. Rather than scatter these, index them:

```scala
/** Synthesizes the evidence a lifted capability must report on the woven
  * carrier. Instances exist exactly where a lawful one can be built:
  * `Functor` and `Applicative`. There is deliberately no `Monad` instance —
  * see §1.2 — which is what makes `Stateful`'s deferral structural rather
  * than merely a rule in the derivation's allowlist.
  */
trait WeaveEvidence[Ev[_[_]]] extends Serializable {
  def apply[F[_], Dom[_], Cod[_]](implicit F: Ev[F], syn: Synthetic[Cod]):
    Ev[Aspect.Weave[F, Dom, Cod, *]]
}

object WeaveEvidence {
  def apply[Ev[_[_]]](implicit ev: WeaveEvidence[Ev]): WeaveEvidence[Ev] = ev

  implicit val functor: WeaveEvidence[Functor]         // today's syntheticWeaveFunctor
  implicit val applicative: WeaveEvidence[Applicative] // §3.3, sentinel monoid
}
```

Two properties worth stating explicitly, because they are the safety story:

- The synthesized instances are **never in implicit scope as
  `Applicative[Weave[...]]`**. Summoning `Applicative[Aspect.Weave[F, Dom,
  Cod, *]]` finds nothing; the instance exists only behind
  `WeaveEvidence#apply`. This matters — the spike demonstrated (Q2g) that with
  a `Monad[Weave]` in implicit scope, a for-comprehension over a woven algebra
  type-checks, looks entirely normal, and silently discards both metadata and
  redaction.
- It is still *reachable*, as a field of a lifted capability
  (`liftedAsk.applicative`) and as a field of the erasure arrow. The
  indirection reduces the blast radius; it does not close the hole. §3.2 says
  so plainly rather than repeating the claim that was already corrected once.

`WeaveEvidence[Applicative]#apply(...).map` must be extensionally equal to
`WeaveEvidence[Functor]#apply(...).map` — law C10 (§3.5) — so that the two
instances cannot drift.

### 2.4 `CapabilityPull` and `CapabilityArrow`

```scala
/** The backward leg of a morphism of effects, polymorphic in the capability.
  *
  * `Ev` is the arrow's evidence constraint — the join over the capabilities
  * the algebra mentions. `Ev0` is the capability's own, weaker requirement;
  * `sub` bridges them. `Err[X]` is M10's per-payload evidence, demanded at
  * each application site so that transport stays uniform in `X`.
  */
trait CapabilityPull[G[_], F[_], Err[_], Ev[_[_]]] extends Serializable {
  def apply[C[_[_], _], Ev0[_[_]], X](cg: C[G, X])(implicit
      K: CapabilityK[C, Ev0],
      sub: Subsumes[Ev, Ev0],
      ev: Err[X]
  ): C[F, X]
}

object CapabilityPull {
  def id[F[_], Err[_], Ev[_[_]]]: CapabilityPull[F, F, Err, Ev] =
    new CapabilityPull[F, F, Err, Ev] {
      def apply[C[_[_], _], Ev0[_[_]], X](cg: C[F, X])(implicit
          K: CapabilityK[C, Ev0], sub: Subsumes[Ev, Ev0], ev: Err[X]): C[F, X] = cg
    }

  /** Every canonical pull is `CapabilityK.transport` along one `FunctionK`,
    * against one source-carrier evidence value. This is the spike's
    * unification, kept as the ''construction'' of pulls.
    */
  def transporting[G[_], F[_], Err[_], Ev[_[_]]](gf: G ~> F, evF: Ev[F]):
      CapabilityPull[G, F, Err, Ev] =
    new CapabilityPull[G, F, Err, Ev] {
      def apply[C[_[_], _], Ev0[_[_]], X](cg: C[G, X])(implicit
          K: CapabilityK[C, Ev0], sub: Subsumes[Ev, Ev0], ev: Err[X]): C[F, X] =
        K.transport(cg)(gf)(sub(evF))
    }
}

/** A morphism `F ⇒ G`: values travel forward along `fk`, capabilities travel
  * backward along `pull`.
  */
final case class CapabilityArrow[F[_], G[_], Err[_], Ev[_[_]]](
    fk: F ~> G,
    pull: CapabilityPull[G, F, Err, Ev]
) {
  def andThen[H[_]](that: CapabilityArrow[G, H, Err, Ev]): CapabilityArrow[F, H, Err, Ev] =
    CapabilityArrow(
      that.fk.compose(fk),
      new CapabilityPull[H, F, Err, Ev] {
        def apply[C[_[_], _], Ev0[_[_]], X](ch: C[H, X])(implicit
            K: CapabilityK[C, Ev0], sub: Subsumes[Ev, Ev0], ev: Err[X]): C[F, X] =
          pull(that.pull(ch))
      }
    )
}

object CapabilityArrow {
  def id[F[_], Err[_], Ev[_[_]]]: CapabilityArrow[F, F, Err, Ev] =
    CapabilityArrow(FunctionK.id[F], CapabilityPull.id[F, Err, Ev])
}
```

#### Why not the flat `(fk, gf, evF)` triple

The spike proposed `CapabilityArrow(fk, gf, evF)`, with `andThen` collapsing to
`FunctionK` composition in both directions. The verification demonstrated that
collapse for `eraseWeave.andThen(id)`. **The collapse is real for the canonical
transport pulls, and this design keeps it — as the construction
(`CapabilityPull.transporting`), not as the representation.** The
representation cannot be flattened, for a reason neither document tested:

A hook decorates the *target-side* capability before transport. For
`f: F ⇒ G` carrying decorator `d_f` (acting on `C[G, ·]`) and `g: G ⇒ H`
carrying `d_g` (acting on `C[H, ·]`), law L2 requires the composite to act as

```
c_H  ↦  transport_{f.gf}( d_f( transport_{g.gf}( d_g(c_H) ) ) )
```

which is not of the form `transport_{gf}(d(c_H))` for any single `d` and any
single `gf`: pushing `d_f` back through `transport_{g.gf}` would require the
hook's effect to factor through `g.gf`, and a `G ~> F` never sees the raised
value. A flat arrow with one decorator slot therefore either loses a hook or
breaks **L2 (mapK composition)** on hooked arrows — and today's `RaiseArrow`
satisfies L2 *definitionally*, because `andThen` nests. Weakening a frozen
law's generalization to buy a flatter case class is a bad trade, and it would
be a silent weakening: the shipped `Arbitrary[RaiseArrow]` generators
(`RaiseAspectSuite.scala:46-50`) produce only `eraseWeave` and `id`, both
hook-free, so no test would have caught it.

So: **the arrow is `(fk, pull)`, `pull` is capability-polymorphic, and
`andThen` nests.** The uniformity the spike found is preserved where it
belongs — one pull serves every capability, and every canonical pull is one
`transport` along one `FunctionK`.

### 2.5 `CapabilityFunctorK` and `CapabilityAspect`

```scala
trait CapabilityFunctorK[Alg[_[_]], Err[_], Ev[_[_]]] extends Serializable {
  def mapK[F[_], G[_]](af: Alg[F])(arrow: CapabilityArrow[F, G, Err, Ev]): Alg[G]
}

trait CapabilityAspect[Alg[_[_]], Dom[_], Cod[_], Err[_], Ev[_[_]]]
    extends CapabilityFunctorK[Alg, Err, Ev] {
  def weave[F[_]](af: Alg[F])(implicit F: Ev[F]): Alg[Aspect.Weave[F, Dom, Cod, *]]
}
```

`weave`'s constraint is `Ev[F]`, not `Functor[F]`. This is forced, and the
spike demonstrated it: `RaiseAspect.weave[F](af)(implicit F: Functor[F])` is
not strong enough for an `Ask`-taking algebra, and a `compileErrors` test
confirmed the too-weak case fails. `Ev` is the **join** of the evidence
requirements of the capabilities the algebra mentions — `Functor` for a
`Raise`/`Tell`-only algebra, `Applicative` as soon as one method takes `Ask`.

`Ev` is a type parameter the user writes, and the derivation *checks* it (§4):
`DeriveCapability.aspect[CatalogAlg, Dom, Cod, Err, Applicative]`.

### 2.6 `WeaveArrows`, generalized

```scala
object WeaveArrows {
  /** Unchanged. */
  def codomainTarget[F[_], Dom[_], Cod[_]]: Aspect.Weave[F, Dom, Cod, *] ~> F

  /** The shell constructor `raiseLift` used inline today, now named. Advice
    * name is fixed at "raise" for every capability: one backward
    * `FunctionK` means one shell name. Observable only if a shell escapes,
    * which §3.1 says it does not along the derived path.
    */
  def shellK[F[_], Dom[_], Cod[_]](name: String)(implicit syn: Synthetic[Cod]):
    F ~> Aspect.Weave[F, Dom, Cod, *]

  /** Used INSIDE derived `weave` methods. Generalizes `raisePull`. */
  def capabilityPull[F[_], Dom[_], Cod[_], Err[_], Ev[_[_]]](implicit F: Ev[F]):
      CapabilityPull[Aspect.Weave[F, Dom, Cod, *], F, Err, Ev] =
    CapabilityPull.transporting(codomainTarget[F, Dom, Cod], F)

  /** Used on the interpretation side. Generalizes `raiseLift`. */
  def capabilityLift[F[_], Dom[_], Cod[_], Err[_], Ev[_[_]]](implicit
      F: Ev[F], we: WeaveEvidence[Ev], syn: Synthetic[Cod]):
      CapabilityPull[F, Aspect.Weave[F, Dom, Cod, *], Err, Ev] =
    CapabilityPull.transporting(shellK[F, Dom, Cod]("raise"), we[F, Dom, Cod])

  /** As above, but decorating with an observation hook before transport. */
  def capabilityLift[F[_], Dom[_], Cod[_], Err[_], Ev[_[_]]](onRaise: OnRaise[F, Err])(implicit
      F: Ev[F], FA: Apply[F], we: WeaveEvidence[Ev], syn: Synthetic[Cod]):
      CapabilityPull[F, Aspect.Weave[F, Dom, Cod, *], Err, Ev] =
    new CapabilityPull[F, Aspect.Weave[F, Dom, Cod, *], Err, Ev] {
      def apply[C[_[_], _], Ev0[_[_]], X](cf: C[F, X])(implicit
          K: CapabilityK[C, Ev0], sub: Subsumes[Ev, Ev0], ev: Err[X]) =
        K.transport(K.observe(cf)(onRaise))(shellK("raise"))(sub(we[F, Dom, Cod]))
    }

  /** The full erasure morphism. */
  def eraseWeave[F[_], Dom[_], Cod[_], Err[_], Ev[_[_]]](implicit
      F: Ev[F], we: WeaveEvidence[Ev], syn: Synthetic[Cod]):
      CapabilityArrow[Aspect.Weave[F, Dom, Cod, *], F, Err, Ev] =
    CapabilityArrow(codomainTarget, capabilityLift)
}
```

Note that `evF` on the erasure arrow's pull is the **synthesized** evidence —
that is the one-sentence answer to "where does `Synthetic` fit": it is
resolved by `WeaveEvidence` into the source-carrier evidence of the erasure
arrow, and nowhere else.

`Apply[F]` on the hooked overload is separate from `Ev[F]`, matching today's
shape exactly (`WeaveInterpreter.fromRaiseAspect` already asks for `Apply[F]`
while `weave` asks for `Functor[F]`). At `Ev = Applicative` a caller will
normally have both resolve to the same instance, but nothing enforces it. This
is a pre-existing wart, not a new one; deriving `Apply` from `Ev` via
`Subsumes[Ev, Apply]` would fix it but would make the hooked path
unavailable at `Ev = Functor`, which is today's most common case.

### 2.7 `Err` is demanded uniformly, including for `Ask` and `Tell` — **DECIDED**

`CapabilityPull#apply` demands `Err[X]` for every capability, where `X` is the
capability's payload type: the error type for `Raise`, the environment type for
`Ask`, the log type for `Tell`. This follows M10's precedent exactly —
transport uniform in the type, evidence per application site — and it is what
makes `observe` (§7.1) implementable for any capability with an outbound
payload.

**The cost, stated plainly:** under the tracing syntax, `Err` is pinned to
`natchez.TraceableValue` (`RaiseTraceWeaveOps.scala`), so an algebra method
taking `Ask[F, Region]` will require a `TraceableValue[Region]` at the
derivation site, even though nothing renders the environment today. That is a
real tax on a capability whose only justification is conceptual completeness.

Rejected alternative: make the evidence requirement a *type member* of
`CapabilityK` (`type Payload[Err[_], X]`, `= Err[X]` for `Raise`/`Tell`,
`= Unit` for `Ask`) so the tax falls only where a payload actually travels
out. It works on paper and costs an `Aux` encoding plus path-dependent
implicits in macro-generated code. Rejected as disproportionate for a
one-implicit tax with a one-line workaround — but it is the thing to reach for
if §9's fixture makes the tax feel wrong in practice.

### 2.8 What happens to `RaiseArrow`, `RaisePull`, `RaiseAspect`

They are **replaced**, not aliased. See §5 for the full compatibility
argument and the one question it leaves for Brian. In outline:

- `RaisePull` → `CapabilityPull`. Not aliasable: `apply` gains two
  higher-kinded type parameters and two implicit witnesses.
- `RaiseArrow` → `CapabilityArrow`. Not aliasable: its `pull` field changes
  type, so every `RaiseArrow(fk, pull)` construction changes anyway.
- `RaiseFunctorK` / `RaiseAspect` → `CapabilityFunctorK` /
  `CapabilityAspect`, at `Ev = Functor` for today's algebras. These two
  *could* be type aliases pinned at `Functor`; §5 asks whether they should be.
- `WeaveArrows.raisePull` / `raiseLift` → `capabilityPull` / `capabilityLift`.
- `DeriveRaise.aspect[Alg, Dom, Cod, Err]` →
  `DeriveCapability.aspect[Alg, Dom, Cod, Err, Ev]`.

---

## 3. Point 2 — Synthetic evidence on shells — **DECIDED**

### 3.1 The claim that holds

> **Derivation-scoped soundness.** In every method body the derivation
> generates, the only members invoked on a transported capability are that
> capability's **abstract producing members** — `Raise#raise` (Raise.scala:69),
> `Ask#ask` (Ask.scala:51), `Tell#tell` (Tell.scala:45) — and each such
> invocation's result is consumed by `codomain.target` inside the same
> expansion. No synthesized evidence instance and no shell `Weave` therefore
> reaches an interpreter along the derived path.

This is a property of *the derivation*, not of the capability and not of the
carrier. The spike demonstrated it directly: an algebra taking `Ask`, `Tell`,
`Stateful` and one mixed `Ask` + `Raise` method, woven and `mapK`'d through a
recording interpreter that rendered every weave with that weave's own `Cod`
instance, produced no line containing the synthetic sentinel, no line carrying
a shell's `algebraName`, and rendered a `Secret` through the *real*
`Render[Secret]`. Same result through a composite arrow.

It also demonstrated something sharper, which is why the claim is worded this
way: law L3 passes even under a *deliberately unlawful* merge policy. The
shell's non-escape is not what makes L3 pass. What makes it pass is that the
derived body touches nothing but the abstract producing members, and the pull
unwraps exactly those.

### 3.2 What is **not** claimed

The section/retraction argument covers the shell. It does not cover the
capability's **evidence member**, which is public on every cats-mtl capability
and is handed out with the lifted instance:

| Reachable through | Vendored source | Nature |
| --- | --- | --- |
| `Ask#reader(f)` | Ask.scala:53 — `applicative.map(ask)(f)` | ordinary documented API; the caller never mentions an evidence member |
| `Tell#writer(a, l)` / `Tell#tuple` | Tell.scala:47, :49 — `functor.as(tell(l), a)` | same |
| `Ask#applicative`, `Tell#functor`, `Raise#functor` | Ask.scala:49, Tell.scala:43, Raise.scala:67 | public members of public traits |

The first two are the ones that matter: nobody has to misuse anything. A
`reader` call on a lifted `Ask` produces a weave carrying a **real**
environment-derived value under a **fabricated** `Cod`. (Three of the spike's
six demonstrations are of this kind; the other three are evidence-member
projections. The spike said "five of six through documented cats-mtl API";
the verification corrected it to three on the strict reading, and the strict
reading is the one that matters.)

`Ask#fromKleisli` (Ask.scala:57) takes `implicit F: FlatMap[F]` and is
therefore unreachable on a lifted instance today — which stops being true the
day a `Monad[Weave]` exists. One more reason there is no `WeaveEvidence[Monad]`.

**The design's position:** these routes are unsupported and unsound, they are
documented as such on `Synthetic` and on each `CapabilityK` instance, and law
C11 (§3.5) pins the property that *is* guaranteed. The claim "a synthesized
instance is never observable through the public API" is false and was already
removed from `Synthetic`'s scaladoc (commit 6c77d74); it must not come back in
any generalized form.

One inherited mitigation, worth restating because it bounds the damage: the
shipped `Synthetic[TraceableValue]` is a constant sentinel
(`«raised»`), so even a successful exploit records a wrong span attribute
rather than disclosing a value. That holds only because *this* `Synthetic` is
constant. The constraint on implementors — **a `Synthetic` instance must not
reveal anything about the value it stands in for** — becomes load-bearing once
`Ask`'s shells wrap real environment values instead of raises.

### 3.3 The synthesized `Applicative`, and why the sentinel is forced

`Ask`'s evidence member is `applicative: Applicative[F]` (Ask.scala:49), so a
lifted `Ask` must report an `Applicative[Aspect.Weave[F, Dom, Cod, *]]`.
`pure` must invent `algebraName`, `domain` and `codomain.name`; `ap` must
choose whose metadata survives. Three equivalences matter:

- **target** — compare `codomain.target` only. Everything is lawful here, for
  every policy, because `codomainTarget` is an applicative homomorphism by
  construction. It is also the weakest possible statement.
- **structural** — target plus `algebraName`, `codomain.name`, and domain
  names and rendered domain values. This is what `WeaveRenderer` compares and
  what the shipped L3/L6 suites use.
- **observing** — structural plus the codomain rendered with the weave's own
  `Cod` instance. What a tracing interpreter sees.

At the structural equivalence, exactly one policy is lawful: **treat the
manufactured shell as the two-sided identity of the merge**.

- **Left-biased** (`ff`'s metadata always wins) breaks `applicativeIdentity`
  (`ap(pure(id))(fa)` carries `pure`'s metadata, not `fa`'s), breaks
  `interchange`, and — worst — breaks `applicativeMap`, i.e. `ap`-derived
  `map` disagrees with the metadata-preserving `map` that L6b–L6d require.
- **Right-biased** breaks `interchange`: `ap(ff)(pure(x))` keeps `pure`'s
  metadata while `ap(pure(f => f(x)))(ff)` keeps `ff`'s.
- **Sentinel** passes composition, homomorphism, identity, interchange and map
  at the structural equivalence (demonstrated).

The sentinel is **forced, not lucky** — the verification's correction to the
spike's framing. The applicative laws require an identity element; `pure` must
produce it; `Weave` gives nowhere to put a tag; so the identity must be
encoded as a distinguished `String` and tested by comparison. Its fragility is
a consequence of the fixed `Weave` shape, which is a constraint the project
has already accepted, not of a sloppy choice.

```scala
private val Manufactured: String = "<manufactured>"

// identity test, tightened relative to the spike: all three metadata
// components must match, not just the name and an empty domain
private def isManufactured[F[_], Dom[_], Cod[_], A](w: Aspect.Weave[F, Dom, Cod, A]) =
  w.algebraName == Manufactured && w.domain.isEmpty && w.codomain.name == Manufactured

def pure[A](a: A): Aspect.Weave[F, Dom, Cod, A] =
  Aspect.Weave(Manufactured, Nil,
    Aspect.Advice[F, Cod, A](Manufactured, F.pure(a))(syn.apply[A]))

override def map[A, B](w: ...)(f: A => B) =            // metadata-preserving, as L6 requires
  Aspect.Weave(w.algebraName, w.domain,
    Aspect.Advice[F, Cod, B](w.codomain.name, F.map(w.codomain.target)(f))(syn.apply[B]))

def ap[A, B](ff: ...)(fa: ...) = {
  val src = if (isManufactured(ff)) fa else ff
  Aspect.Weave(src.algebraName, src.domain,
    Aspect.Advice[F, Cod, B](src.codomain.name,
      F.ap(ff.codomain.target)(fa.codomain.target))(syn.apply[B]))
}
```

**No policy is lawful at the observing equivalence** — `ap(pure(identity))(fa)`
substitutes `syn[B]` for the result's `Cod`. That is already true of the
shipped `syntheticWeaveFunctor` and is not fixable; see §3.4.

**Sentinel collision.** A real algebra named `<manufactured>` with an empty
domain and a method named `<manufactured>` would be treated as the identity
and lose its metadata. The tightened three-component test above makes that
astronomically unlikely; whether it is *impossible* depends on whether a Scala
class simple name can contain `<` (backquoted identifiers permit unusual
characters; JVM binary class names do not permit `<`). **Unverified
`[SPIKE-4]`.** Until it is verified, the design treats the sentinel as
"documented and vanishingly improbable", not "impossible".

### 3.4 The unsatisfiable law, stated positively instead

The verification established that the natural fifth L6 clause —

> `raiseLift(rf).functor.map(w)(identity).codomain.instance` renders the same
> as `w.codomain.instance`

— **cannot be satisfied**. For a general `f: A => B` there is no way to produce
a real `Cod[B]` from a `Cod[A]`: `Cod` is an arbitrary type constructor, and
even the contravariant case (`TraceableValue[A] = A => TraceValue`) would need
a `B => A`. At `A = B` a `Functor#map` cannot detect `f == identity`. So the
spike's suggestion — "decide whether that is a bug in `syntheticWeaveFunctor`
or an accepted limitation" — offers a choice that does not exist.

**Decision: state the substitution as a law (C9), positively.** The
synthesized evidence's `map`/`ap`/`pure` put `syn.apply[B]` at the codomain,
always. Pinning it means the property is specified rather than accidental, and
a future change that made it conditional would break a law instead of silently
altering what interpreters see.

### 3.5 Laws

New files (§6), numbered `C_n` to mirror `L_n` where there is an analogue.
Notation as in overview §4: `<->` is extensional equality under the test-kit
`Eq` instances.

| Law | Statement | Mirrors |
| --- | --- | --- |
| **C1** | `mapK(af)(CapabilityArrow.id) <-> af` | L1 |
| **C2** | `mapK(mapK(af)(f))(g) <-> mapK(af)(f andThen g)` | L2 |
| **C3** | `mapK(weave(af))(WeaveArrows.eraseWeave) <-> af`, exercised at inputs that raise, that ask, and that tell | L3 |
| **C4** | transport coherence, per capability: `arrow.fk(arrow.pull(cg).<produce>) <-> cg.<produce>` — Raise: `raise[E, A](e)`; Ask: `ask[X]`; Tell: `tell(l)` | L4 |
| **C5** | section/retraction, per capability: `capabilityPull(capabilityLift(c)).<produce> <-> c.<produce>` | L5 |
| **C6a–d** | lifted `Functor` coherence (Raise, Tell): maps the codomain target with the ambient `Functor[F]`; preserves `algebraName`, `codomain.name`, `domain` | L6a–L6d |
| **C6e–h** | lifted `Applicative` coherence (Ask): `pure` produces the manufactured shell; `map` agrees with C6a–d; `ap` keeps the first non-manufactured operand's metadata and `F.ap`s the targets; plus cats-laws' `ApplicativeTests` at the **structural** `Eq` | new |
| **C7** | the capability handed to the underlying implementation reports the **ambient** evidence, not a synthesized one — Raise/Tell: `functor eq Functor[F]`; Ask: `applicative eq Applicative[F]` | L7 |
| **C8** | weave structure fidelity: capability parameters absent from `domain` for all three capabilities; advice names, ordering, `Eval.now`/`Eval.always` behaviour as today | L8 |
| **C9** | codomain-instance substitution, stated positively: the synthesized evidence's `map`, `ap` and `pure` all carry `syn.apply[B]` as the result's `codomain.instance` (§3.4) | new |
| **C10** | evidence coherence: `WeaveEvidence[Applicative].apply(...).map` <-> `WeaveEvidence[Functor].apply(...).map` | new |
| **C11** | **no-synthetic-escape** (the §3.1 claim, made executable): interpreting a woven-and-`mapK`'d algebra through a recording `Weave ~> F` that renders every weave with *that weave's own* `Cod` instance produces no rendering equal to the `Synthetic` sentinel, no `algebraName` equal to a shell or manufactured name, and renders real values through their real instances | new |
| **C12** | conservative extension: on `Raise`-only algebras, `CapabilityAspect` at `Ev = Functor` agrees with today's `RaiseAspect` (structurally equal rendered weaves, `Eq`-equal codomain targets); on capability-free algebras it agrees with `cats.tagless.Derive.aspect` | L9 |
| **C13** | laziness parity: weaving performs no `F` effects; by-name arguments are not forced by weaving | L10 |

C11 is the law that carries §3.1. Like L8 it is structural and per-algebra
rather than a value-level law over arbitrary inputs, which is the honest shape
for a claim about generated code. It is exactly the spike's Q2a, promoted from
a throwaway assertion to a rule set clause.

C6e–h asks discipline's `ApplicativeTests` to run against the synthesized
instance at the structural `Eq`. The spike checked five equations by hand;
the upstream rule set checks more (`apProductConsistent`,
`monoidalLeftIdentity`, and friends). **Whether the full upstream rule set
passes is unverified `[SPIKE-5]`.** If some clause fails, that is a finding
about the sentinel policy, not a licence to weaken the rule set.

---

## 4. Point 3 — Derivation recognition — **DECIDED**

Generalizes overview §3.4 rule 1.

### 4.1 The allowlist

A parameter is a **capability parameter** iff its type, after dealiasing, has a
type-constructor symbol **exactly equal** to one of

```
cats.mtl.Raise    cats.mtl.Ask    cats.mtl.Tell
```

with first type argument `=:=` the algebra's effect parameter and second type
argument not mentioning it. Exact symbol equality, **never a conformance
test** — the existing Scala 3 macro already documents why
(`DeriveRaiseMacros.scala:229-241`): `Handle[F, E] <: Raise[F, E]` and `Handle`
consumes `F`, so a subtype test would generate unsound code. The same trap now
exists twice more: `Local <: Ask` and `Listen <: Tell` (and `Censor <: Listen
<: Tell`). Getting this wrong is silent, not a compile error.

The allowlist is closed. **User-registered capabilities are out of scope
(YAGNI)**, and would additionally require the derivation to trust an
unverified `CapabilityK`.

### 4.2 Multiple and mixed capability parameters

Unchanged in principle from overview §3.4 rules 2, 5 and 7, now across
capabilities:

- Each capability parameter is transported independently, in declaration
  order, and is never captured in `domain`.
- A method may take several capability parameters of the same type
  constructor with different payload types (today's `def e(implicit R1:
  Raise[F, ErrA], R2: Raise[F, ErrB])`), or of different type constructors
  (`Ask` + `Raise`), or both.
- Each parameter summons, at the derivation site: its `Err[x]`, its
  `CapabilityK[C, Ev0]`, and its `Subsumes[Ev, Ev0]`.
- Parameter types are used verbatim post-dealias. The three capabilities carry
  three different variance annotations (`Raise[F, -E]`, `Ask[F, +E]`,
  `Tell[F, -L]`); reconstructing types risks variance drift.
- M7's method-local instance fallback applies to `Err[x]` exactly as it does to
  `Dom`/`Cod` (already true post-M10) and now must also cover the
  `CapabilityK`/`Subsumes` summons — though in practice both live in companion
  objects, so the derivation-site search finds them.

### 4.3 The `Ev` check

`Ev` is supplied by the user at the derivation call. For each capability
parameter the macro summons `Subsumes[Ev, Ev0]` where `Ev0` is fixed by the
capability. A missing witness means the declared `Ev` is too weak, and the
diagnostic must say so in those terms:

```
method `lookup` takes cats.mtl.Ask[F, Region], whose evidence member is
Applicative[F], but this derivation was asked for Ev = Functor. Derive with
Ev = Applicative (the join of the evidence requirements of the capabilities
this algebra uses: Functor for Raise/Tell, Applicative for Ask).
```

### 4.4 Rejection diagnostics: three buckets, three messages

**Deferred and impossible are not the same thing to a user**, so `Stateful`
gets its own message:

1. **`Stateful` — deferred.**
   ```
   method `tally` takes cats.mtl.Stateful[F, Count]. Stateful transport is
   deferred, not rejected: its evidence member is Monad[F], and a lawful
   Monad on Aspect.Weave cannot be built while Weave's metadata is strict
   (flatMap cannot see the continuation's metadata without running F, so left
   identity fails at every equivalence above pure target erasure). Take
   Ask/Tell/Raise in algebra methods, or thread state at the boundary. See
   docs/plans/raise-aspect/02-capability-aspect-design.md §1.2.
   ```
2. **`Handle`, `Local`, `Listen`, `Censor`, `Chronicle` — permanently
   excluded**, each naming its own disqualifying member and its own remedy,
   in the register the shipped `Handle` message already uses
   (`DeriveRaiseMacros.scala:371-375`). For example:
   ```
   method `scoped` takes cats.mtl.Local[F, Region]; Local#local consumes an
   F[A] and cannot be woven. Take Ask[F, Region] in algebra methods and apply
   Local at the boundary.
   ```
   ```
   method `audit` takes cats.mtl.Censor[F, Log]; Censor#censor and the
   inherited Listen#listen both consume an F[A] and cannot be woven. Take
   Tell[F, Log] in algebra methods.
   ```
   `Chronicle`'s message must name **`materialize`** — not `confess`, which is
   produce-only. Getting the member wrong here is exactly the mistake the
   capability verification caught in the milestone document's prose.
3. **Anything else mentioning `F` in a parameter position** — today's message,
   with "and in `Raise[F, E]` parameters" widened to "and in `Raise[F, E]`,
   `Ask[F, E]` or `Tell[F, L]` parameters".

All three are pinned by `DerivationErrorSpec` cases on 2.12, 2.13 and 3, the
bar M10 set.

---

## 5. Point 4 — Compatibility — **DECIDED** in substance, one **QUESTION**

### 5.1 Decided: no shims, no dual code paths

M10's decision D7 already broke `RaiseAspect`, `RaiseFunctorK`, `RaiseArrow`,
`RaisePull` and `OnRaise` without shims, deliberately, on unpublished-in-anger
API. The precedent is established and this design follows it: **one derivation,
one macro code path per axis, one set of runtime types.** Anything that keeps a
second way to do the same thing needs Brian's explicit approval per CLAUDE.md,
and nothing here proposes one.

Aliasing is not available for most of the family regardless of preference:
`RaisePull#apply` gains two higher-kinded type parameters and two witnesses;
`RaiseArrow`'s `pull` field changes type. A type alias cannot preserve source
compatibility for either, so offering one would advertise a compatibility that
does not exist.

Breaking-change inventory (all of it published API as of M5, all of it already
broken once by M10):

| Type | Change |
| --- | --- |
| `RaisePull[G, F, Err]` | replaced by `CapabilityPull[G, F, Err, Ev]` |
| `RaiseArrow[F, G, Err]` | replaced by `CapabilityArrow[F, G, Err, Ev]` |
| `RaiseFunctorK[Alg, Err]` | replaced by `CapabilityFunctorK[Alg, Err, Ev]` |
| `RaiseAspect[Alg, Dom, Cod, Err]` | replaced by `CapabilityAspect[Alg, Dom, Cod, Err, Ev]`; `weave`'s constraint becomes `Ev[F]` |
| `WeaveArrows.raisePull` / `raiseLift` | renamed `capabilityPull` / `capabilityLift`, gain `Ev` |
| `WeaveArrows.eraseWeave` | gains `Ev`, `WeaveEvidence[Ev]` |
| `DeriveRaise.aspect` / `.functorK` | become `DeriveCapability.*`, gain `Ev` |
| `WeaveInterpreter` | signature unchanged if `[SPIKE-3]` holds; see §7.2 |
| `OnRaise[F, Err]` | unchanged |
| span field names | unchanged |

### 5.2 Question Q1 for Brian: do the `Raise` names survive as specializations?

The two type classes — and only those two — *could* survive as pinned aliases
with no dual code path:

```scala
type RaiseFunctorK[Alg[_[_]], Err[_]] = CapabilityFunctorK[Alg, Err, Functor]
type RaiseAspect[Alg[_[_]], Dom[_], Cod[_], Err[_]] =
  CapabilityAspect[Alg, Dom, Cod, Err, Functor]
```

plus, optionally, a `DeriveRaise.aspect[Alg, Dom, Cod, Err]` entry point that
forwards to `DeriveCapability.aspect[Alg, Dom, Cod, Err, Functor]`.

**For:** today's users — the majority case, `Raise`-only algebras — keep a
four-argument ascription instead of a five-argument one and keep the name that
matches what they are doing. The library keeps a name for the concept that M9
might upstream on its own.

It does **not** rescue the frozen law files. `RaiseFunctorKLaws` calls
`RaiseArrow.id`, `RaiseArrowLaws` calls `WeaveArrows.raiseLift`/`raisePull` and
applies `arrow.pull(rg)` directly, and `RaiseAspectLaws` calls
`WeaveArrows.eraseWeave` — none of which a type alias supplies, since
`CapabilityPull#apply` takes different arguments. Keeping those compiling would
need companions and retained `WeaveArrows` members, which is a dual code path.
So Q3 (§6) stands whichever way Q1 goes.

**Against:** two documented spellings of one concept is the thing CLAUDE.md
reserves for Brian, even when it is one code path. `RaiseAspect` would no
longer be able to hold a `Tell`-only algebra's instance despite `Tell` needing
only `Functor`, so the name would be actively misleading the moment `Tell`
ships. And M9 is easier to propose with one concept than with two.

**Recommendation: one name (`CapabilityAspect`), no aliases** — the alias buys
a shorter ascription and costs a second name that becomes wrong for `Tell`,
and it does not buy the frozen-laws reprieve that would have justified it.
This is still Brian's call under CLAUDE.md, so it is a question, not a
decision.

---

## 6. Point 5 — Laws placement — **DECIDED**, with one flag for Brian

`raise-aspect-laws` is frozen: the generalized laws of §3.5 are **new files in
the existing module**, never edits.

```
raise-aspect-laws/src/main/scala/com/dwolla/tagless/mtl/laws/
  CapabilityFunctorKLaws.scala     C1, C2
  CapabilityAspectLaws.scala       C3, C8, C11, C12, C13
  CapabilityTransportLaws.scala    C4, C5, C7 — one object per capability
  SyntheticEvidenceLaws.scala      C6, C9, C10
  ObservingWeaveRenderer.scala     the codomain-observing projection C11 needs
  discipline/CapabilityFunctorKTests.scala
  discipline/CapabilityAspectTests.scala
```

`ObservingWeaveRenderer` is a new file rather than a clause added to
`WeaveRenderer.scala`, precisely because that file is frozen. It reuses the
existing `Renderable[G]`.

**No generalized law contradicts a frozen one.** Checked clause by clause:
C1–C8 are the frozen laws with the capability quantifier widened; C9 pins a
component no frozen law compares (`WeaveRenderer` renders `advice.instance`
for *domain* advices only — verified, and demonstrated blind by the
verification's `LeakDemo`); C10 and C11 are about types L1–L10 do not mention.

**One collision to flag, which is not between laws.** If point 4 replaces
`RaiseArrow`/`RaisePull` (§5), the frozen law files stop *compiling* —
`RaiseAspectLaws.weaveErasure` names `WeaveArrows.eraseWeave` and
`RaiseFunctorKLaws` names `RaiseArrow.id`. Mechanically retyping them is an
edit to frozen files. There is a direct precedent: **M10's decision D2 did
exactly that**, giving `RaiseArrowLaws`, `RaiseFunctorKLaws` and
`RaiseAspectLaws` an `Err[_]` parameter, on the reasoning that a signature
change forced by a ratified design is not a weakening. This design recommends
the same treatment and records it as **Question Q3** (§12) rather than
assuming it: the freeze rule as written says "never edits", and it is Brian's
rule to interpret. Q1's aliases would not avoid it — see §5.2 — so this
question stands on its own.

---

## 7. Point 6 — Interactions — **DECIDED**

### 7.1 What `OnRaise` becomes

**`OnRaise[F, Err]` is unchanged in shape and stays the only hook shipped.**
M8's default position — observation hooks stay `Raise`-specific until a need
exists — is honoured. What changes is *where* it plugs in: through
`CapabilityK#observe`, whose default is the identity and which only `Raise`
overrides (§2.1).

That reconciles the default position with the verification's finding. The
verification showed the hook is a clean pre-transport decorator, so the
*mechanism* has to be uniform — a capability-polymorphic pull cannot dispatch
on `C` at the application site, so the decoration has to be a member of the
per-capability instance. But making the mechanism uniform is not the same as
shipping hooks for every capability, and this design ships exactly one
non-identity `observe`.

`OnTell` is a one-instance change when a need appears: override `observe` on
`tellK` as `hook(l) *> cg.tell(l)`, which is why §2.7's uniform `Err[X]`
matters. `OnAsk` is incoherent in this frame and will stay unimplemented:
nothing travels *out* of the algebra through `ask`, so there is no payload to
observe at the interception point.

Naming debt, recorded rather than fixed: `observe`'s hook parameter is typed
`OnRaise[G, Err]`, a `Raise`-flavoured name in a capability-generic signature.
Renaming a published type for a hypothetical second implementation is churn;
if `OnTell` ever ships, both should become instances of a shared supertype
then.

### 7.2 What `WeaveInterpreter` becomes

M11 made `WeaveInterpreter[Alg, Dom, Cod, Err, F]` the backend-agnostic
resolution point, and decision D6 makes signature parity between the two
syntax packages a design goal. Adding `Ev` as a sixth type parameter would
break that: `Ev` appears in no position the syntax method could infer it from,
and Scala has no default type arguments, so `alg.traceWithInputs[Cod]` would
have to become `alg.traceWithInputs[Cod, Ev]`.

**Decision: `WeaveInterpreter`'s signature does not change.** `Ev` becomes a
type parameter of the low-priority *instance method*, inferred from the
`CapabilityAspect` instance found in implicit scope:

```scala
trait LowPriorityWeaveInterpreter {
  implicit def fromCapabilityAspect[Alg[_[_]], Dom[_], Cod[_], Err[_], Ev[_[_]], F[_]](implicit
      FA: Apply[F],
      F: Ev[F],
      A: CapabilityAspect[Alg, Dom, Cod, Err, Ev],
      we: WeaveEvidence[Ev],
      syn: Synthetic[Cod]
  ): WeaveInterpreter[Alg, Dom, Cod, Err, F] =
    new WeaveInterpreter[Alg, Dom, Cod, Err, F] {
      def apply(alg: Alg[F])(fk: Aspect.Weave[F, Dom, Cod, *] ~> F, onRaise: OnRaise[F, Err]): Alg[F] =
        A.mapK(A.weave(alg))(
          CapabilityArrow(fk, WeaveArrows.capabilityLift[F, Dom, Cod, Err, Ev](onRaise))
        )
    }
}
```

`fromAspect` is untouched, and the `Aspect`-outranks-`CapabilityAspect`
priority is unchanged.

**This turns on an implicit search with an undetermined higher-kinded type
parameter** — the compiler must solve `CapabilityAspect[Alg, Dom, Cod, Err,
?Ev]` and then use the solution to drive the `Ev[F]` and `WeaveEvidence[Ev]`
searches, and the order in which those are attempted matters. **Unverified
`[SPIKE-3]`, and it is the highest-risk assumption in this document.** If it
fails, the fallback is `Ev` as a sixth parameter on `WeaveInterpreter` plus a
second type argument on both syntax methods, which breaks D6's parity goal and
should be brought back to Brian rather than absorbed silently.

Note also that the syntax methods supply `Apply[F]` / `FlatMap[F]` while
`fromCapabilityAspect` resolves `Ev[F]` separately; at `Ev = Applicative`
those can in principle be different instances. Same wart as §2.6, same
pre-existing status.

### 7.3 M9

M8's question 6 says to check whether upstream discussion is open, because
maintainer appetite for a general versus a `Raise`-specific shape is direct
input here. **It is not open.** `19-milestone-M9-upstreaming.md` records M9 as
Not started, with Task 1 (drift survey) and Task 2 (proposal draft) unstarted
and the posting itself reserved to Brian personally. So this design has no
upstream input, and the shape question M9 was going to ask upstream is instead
being answered locally — which is exactly what the need gate's rationale
anticipated. If Brian posts the M9 proposal before ratifying this document,
the answer is worth waiting for; Q1 (§5.2) is the decision it would bear on.

---

## 8. Expansion specification

At the precision of overview §3.4. Given the fixture algebra of §9, with
`Ev = Applicative` (the join: `Functor` for `Tell`/`Raise`, `Applicative` for
`Ask`), `DeriveCapability.aspect[CatalogAlg, Dom, Cod, Err, Applicative]` must
produce:

```scala
def weave[F[_]](af: CatalogAlg[F])(implicit F: Applicative[F])
    : CatalogAlg[Aspect.Weave[F, Dom, Cod, *]] =
  new CatalogAlg[Aspect.Weave[F, Dom, Cod, *]] {

    // mixed: Ask + Raise on one method — the shape M8 exists to validate
    def lookup(key: String)(implicit
        A: Ask[Aspect.Weave[F, Dom, Cod, *], Region],
        R: Raise[Aspect.Weave[F, Dom, Cod, *], LookupError]
    ): Aspect.Weave[F, Dom, Cod, String] =
      Aspect.Weave[F, Dom, Cod, String](
        "CatalogAlg",
        List(List(Aspect.Advice[Eval, Dom, String]("key", Eval.now(key))(domString))),
        Aspect.Advice[F, Cod, String](
          "lookup",
          af.lookup(key)(
            WeaveArrows.capabilityPull[F, Dom, Cod, Err, Applicative]
              .apply[Ask, Applicative, Region](A)(CapabilityK.askK, Subsumes.refl, errRegion),
            WeaveArrows.capabilityPull[F, Dom, Cod, Err, Applicative]
              .apply[Raise, Functor, LookupError](R)(
                CapabilityK.raiseK, Subsumes.applicativeFunctor, errLookupError)
          )
        )(codString)
      )

    // single capability, by-name argument alongside it
    def describe(id: Int, fallback: => String)(implicit
        A: Ask[Aspect.Weave[F, Dom, Cod, *], Region]
    ): Aspect.Weave[F, Dom, Cod, String] =
      Aspect.Weave[F, Dom, Cod, String](
        "CatalogAlg",
        List(List(
          Aspect.Advice[Eval, Dom, Int]("id", Eval.now(id))(domInt),
          Aspect.Advice[Eval, Dom, String]("fallback", Eval.always(fallback))(domString)
        )),
        Aspect.Advice[F, Cod, String](
          "describe",
          af.describe(id, fallback)(
            WeaveArrows.capabilityPull[F, Dom, Cod, Err, Applicative]
              .apply[Ask, Applicative, Region](A)(CapabilityK.askK, Subsumes.refl, errRegion)
          )
        )(codString)
      )

    // no capability at all — byte-for-byte the shape today's derivation emits
    def ping: Aspect.Weave[F, Dom, Cod, Unit] =
      Aspect.Weave[F, Dom, Cod, Unit](
        "CatalogAlg", Nil,
        Aspect.Advice[F, Cod, Unit]("ping", af.ping)(codUnit))
  }
```

and for `mapK`:

```scala
def mapK[F[_], G[_]](af: CatalogAlg[F])(
    arrow: CapabilityArrow[F, G, Err, Applicative]): CatalogAlg[G] =
  new CatalogAlg[G] {
    def lookup(key: String)(implicit
        A: Ask[G, Region], R: Raise[G, LookupError]): G[String] =
      arrow.fk(af.lookup(key)(
        arrow.pull.apply[Ask, Applicative, Region](A)(
          CapabilityK.askK, Subsumes.refl, errRegion),
        arrow.pull.apply[Raise, Functor, LookupError](R)(
          CapabilityK.raiseK, Subsumes.applicativeFunctor, errLookupError)
      ))

    def reserve(n: Int)(implicit
        A: Ask[G, Region], T: Tell[G, AuditEntry],
        R1: Raise[G, QuotaError], R2: Raise[G, LookupError]): G[Int] =
      arrow.fk(af.reserve(n)(
        arrow.pull.apply[Ask, Applicative, Region](A)(
          CapabilityK.askK, Subsumes.refl, errRegion),
        arrow.pull.apply[Tell, Functor, AuditEntry](T)(
          CapabilityK.tellK, Subsumes.applicativeFunctor, errAuditEntry),
        arrow.pull.apply[Raise, Functor, QuotaError](R1)(
          CapabilityK.raiseK, Subsumes.applicativeFunctor, errQuotaError),
        arrow.pull.apply[Raise, Functor, LookupError](R2)(
          CapabilityK.raiseK, Subsumes.applicativeFunctor, errLookupError)
      ))

    def ping: G[Unit] = arrow.fk(af.ping)
  }
```

Everything the macro needs is passed **explicitly**: no type argument is left
to inference at the application site, and the three witnesses per capability
parameter (`CapabilityK`, `Subsumes`, `Err`) are summoned at the derivation
site where a failure can be turned into a message naming the method. That is
deliberate — implicit resolution inside generated code is the single most
expensive class of mistake this project has made.

Derivation rules, replacing overview §3.4's list where they differ:

1. Capability parameter recognition per §4.1 (exact symbol, three
   constructors).
2. Capability parameters are transported, never captured in `domain`. All
   other parameters must not mention `F` and are captured as `Advice`s with
   `Dom` instances (strict → `Eval.now`, by-name → `Eval.always`).
3. `F` may otherwise appear only as the top-level return type; anything else is
   a compile error, with the §4.4 messages.
4. `algebraName` = simple name of `Alg`; advice names = parameter/method names.
5. Multiple and mixed capability parameters are transported independently, in
   order, each summoning its own three witnesses.
6. Abstract vals / nullary defs returning `F[A]`: parity with upstream.
7. Declared parameter types used verbatim post-dealias (three variance
   annotations — §4.2).
8. `weave`'s implicit becomes `Ev[F]`, and `Ev` is checked per capability via
   `Subsumes` (§4.3).

**Weave structure is unchanged** relative to today for `Raise`-only algebras.
`ExpectedWeaves` must not need editing for existing fixtures; if it does,
something is wrong. Law C12 pins this.

---

## 9. Fixture algebra

M8 has no external driving use case, so the fixture *is* the use case. It goes
in new files (`CapabilityFixtures.scala` in `raise-aspect-core`'s and
`raise-aspect-laws`' test sources); `TestFixtures.scala` stays as it is.

```scala
final case class Region(name: String)
final case class AuditEntry(message: String)

sealed trait CatalogError
final case class LookupError(key: String) extends CatalogError
final case class QuotaError(requested: Int) extends CatalogError

trait CatalogAlg[F[_]] {
  /** No capability: conservative-extension coverage (C12). */
  def ping: F[Unit]

  /** Tell alone — the cheapest capability, Functor evidence only. */
  def record(entry: AuditEntry)(implicit T: Tell[F, AuditEntry]): F[Unit]

  /** MIXED: Ask + Raise on one method. The shape question 1 exists to answer. */
  def lookup(key: String)(implicit A: Ask[F, Region], R: Raise[F, LookupError]): F[String]

  /** MIXED and multiple: three capabilities, two error types, one method. */
  def reserve(n: Int)(implicit
      A: Ask[F, Region], T: Tell[F, AuditEntry],
      R1: Raise[F, QuotaError], R2: Raise[F, LookupError]): F[Int]

  /** A capability beside strict and by-name ordinary parameters (C8, C13). */
  def describe(id: Int, fallback: => String)(implicit A: Ask[F, Region]): F[String]
}
```

The concrete effect must supply *real* cats-mtl `Ask`, `Tell` and `Raise`
instances rather than hand-rolled ones, so that C4/C5/C7 are exercised against
upstream's own implementations. The spike demonstrated that
`RWST[Either[TestError, *], Config, Vector[String], Int, *]` does; a
`Kleisli`-over-`WriterT`-over-`Either` stack is the smaller candidate but its
instance availability has not been checked. Phase 2 picks one and records
which, rather than assuming.

Two error types over one effect works the same way today's `TestAlg` does:
`Raise[F, -E]` is contravariant, so a `Raise[Either[CatalogError, *],
CatalogError]` serves both `Raise[…, LookupError]` and `Raise[…, QuotaError]`.

**Carry the spike's re-encountered gotcha into these fixtures.** Summoning a
cats-mtl instance straight into an `implicit val` of the same type
(`implicit val askEff: Ask[Eff, Region] = Ask[Eff, Region]`) compiles cleanly
and initializes to `null`, because the right-hand side resolves to the val
being defined. Summon in a private `BaseInstances` object and alias into the
implicit vals — the same trap already documented on `LawsInstances.raiseResult`.

---

## 10. What this design does not solve

Recorded so nobody mistakes silence for coverage:

- `Stateful` transport (§1.2), and with it any algebra that threads state
  through a capability parameter.
- The synthesized-evidence observability hole (§3.2). It is documented,
  bounded, and pinned by C9/C11; it is not closed, and it cannot be closed
  while capabilities expose their evidence members.
- Method-local instance resolution beyond M7's `<:<` rule; `CapabilityK` and
  `Subsumes` inherit exactly M7's limitations.
- Any `Weave` carrier change. `Aspect.Weave`/`Aspect.Advice` stay
  cats-tagless's, verbatim.

---

## 11. Unverified assumptions — Phase 2 spikes

Nothing below has been compiled. Each must be discharged before the code that
depends on it is written; each has a named fallback.

| # | Assumption | Risk if wrong | Fallback |
| --- | --- | --- | --- |
| **SPIKE-1** | `Raise[F, -E]`, `Ask[F, +E]`, `Tell[F, -L]` conform to the invariant higher-kinded slot `C[_[_], _]` on **Scala 2.12**. Demonstrated on 2.13.18 and 3.3.8 only. | The whole `CapabilityK` abstraction is 2.12-hostile | Dropping 2.12 is Brian's call and must be escalated, never taken silently (overview ground rule 6) |
| **SPIKE-2** | `CapabilityPull#apply[C[_[_], _], Ev0[_[_]], X]` — two higher-kinded type parameters on one method — can be *declared*, *implemented*, and *invoked with explicit type arguments from macro-generated code* on 2.12, 2.13 and 3 | Forces a per-capability pull, i.e. re-litigating question 1 | An overloaded pull per capability (three methods), keeping the arrow shape |
| **SPIKE-3** | `WeaveInterpreter.fromCapabilityAspect` can infer `Ev` from the `CapabilityAspect` instance in implicit scope (implicit search with an undetermined HK parameter, driving two dependent searches) | `WeaveInterpreter` gains a sixth parameter and both syntax methods gain a type argument, breaking M11's D6 parity goal | Sixth parameter — and bring it back to Brian, since D6 is ratified |
| **SPIKE-4** | The sentinel `<manufactured>` cannot collide with a real `algebraName`, because `<` is not admissible in a JVM binary class name and `algebraName` is `Alg`'s simple name | A pathologically-named algebra silently loses metadata through `ap` | Keep the tightened three-component identity test (§3.3) and document the collision as improbable rather than impossible |
| **SPIKE-5** | The sentinel `Applicative` passes cats-laws' full `ApplicativeTests` rule set at the structural `Eq`, not merely the five equations the spike checked by hand | A failing clause means the sentinel policy is weaker than believed | Report the failing clause and bring the `Ask` scope decision back to Brian; do **not** weaken the rule set |

Two further checks that are cheap and belong in the same pass, though they are
verifications rather than design risks: that `cats.mtl.syntax.*` (not vendored
— only `core` is) exposes no operation reaching a lifted capability's evidence
member beyond the three defaults §3.2 names; and that the chosen fixture effect
stack really has upstream `Ask`/`Tell`/`Raise` instances (§9).

---

## 12. Decisions and open questions

### Decisions

| # | Decision | Point |
| --- | --- | --- |
| C-D1 | `CapabilityK[C, Ev]` with `transport` + a default-identity `observe`; three instances (`Raise`, `Ask`, `Tell`); no `Stateful` instance | 1 |
| C-D2 | `Subsumes[Ev, Ev0]` as the explicit subsumption witness, summoned per capability parameter at the derivation site | 1 |
| C-D3 | `WeaveEvidence[Ev]` indexes the synthesized carrier evidence; instances at `Functor` and `Applicative` only, which makes `Stateful`'s deferral structural | 1, 2 |
| C-D4 | The arrow is `(fk, pull)` with a capability-polymorphic pull and a nesting `andThen`; the flat `(fk, gf, evF)` triple is rejected because it cannot carry a hook without breaking L2 | 1 |
| C-D5 | `weave`'s constraint is `Ev[F]`, the join over the capabilities the algebra mentions | 1 |
| C-D6 | `Err[X]` is demanded uniformly at every capability parameter (M10 precedent), including the `Ask`/`Tell` tax | 1 |
| C-D7 | Soundness is claimed **only** for the derivation (§3.1); the general "never observable" claim is not restated in any form | 2 |
| C-D8 | The sentinel-identity merge is the `Applicative`'s policy; left- and right-biased are excluded by named law failures | 2 |
| C-D9 | The codomain-instance substitution is stated **as** law C9 rather than forbidden by an unsatisfiable one | 2 |
| C-D10 | Recognition is a closed allowlist keyed by exact dealiased type-constructor symbol; user-registered capabilities are out of scope | 3 |
| C-D11 | Three diagnostic registers: `Stateful` deferred, five capabilities permanently excluded (each naming its own disqualifying member), everything else generic | 3 |
| C-D12 | No shims, no dual code paths, no deprecated overloads (M10 D7 precedent) | 4 |
| C-D13 | Generalized laws are new files in `raise-aspect-laws`; no generalized law contradicts a frozen one | 5 |
| C-D14 | `OnRaise` unchanged and remains the only shipped hook; the *mechanism* generalizes via `CapabilityK#observe`; no `OnTell`, no `OnAsk` | 6 |
| C-D15 | `WeaveInterpreter`'s signature is unchanged; `Ev` is inferred at the instance — subject to SPIKE-3 | 6 |

### Questions for Brian

| # | Question | Recommendation |
| --- | --- | --- |
| **Q1** | Do `RaiseAspect` / `RaiseFunctorK` survive as type aliases pinned at `Ev = Functor` (plus a forwarding `DeriveRaise.aspect`), or is `CapabilityAspect` the only name? §5.2 | One name — but two spellings of one concept is your call under CLAUDE.md, not mine |
| **Q2** | Ship `Ask` at all, given §3.2 — its shells wrap *real* environment values, and `reader` reaches a fabricated `Cod` through ordinary documented API? "`Tell` only" is a defensible landing spot the spike explicitly named | Ship it, with C9/C11 and the scoped claim. But this is the safety story changing, not just widening |
| **Q3** | The frozen law files must be mechanically retyped if Q1 goes to "one name". M10's D2 did exactly this. Is that within the freeze rule? §6 | Yes, on M10's precedent — mechanical retyping that preserves meaning is not a weakening. Independent of Q1 |

---

## 13. Phase 2 milestone map

Smaller than M0–M5's: the module skeleton, the laws seam, both macro
codebases, the differential-oracle pattern and the cross-version test harness
all already exist. Each milestone gets its own `2x-milestone-*.md` at the
granularity of the existing ones.

### M8a — spikes (blocking, no production code)

Discharge SPIKE-1, SPIKE-2 and SPIKE-3 before anything else; SPIKE-4 and
SPIKE-5 before the code that depends on them (M8c and M8d respectively).
Throwaway `scala-cli` programs against the compiled class directories, per the
verification's method, on 2.12.21, 2.13.18 and 3.3.8. Report findings and, for
any failure, come back to the design rather than absorbing it.

1. `CapabilityK` with all three capability variances, 2.12 (SPIKE-1).
2. `CapabilityPull#apply` declared, implemented and invoked with explicit type
   arguments, all three axes (SPIKE-2).
3. `WeaveInterpreter.fromCapabilityAspect` `Ev` inference, all three axes,
   in all four instance-placement scenarios M10 used (SPIKE-3).

### M8b — runtime core

4. `CapabilityK`, `Subsumes`, `WeaveEvidence[Functor]`, `CapabilityPull`,
   `CapabilityArrow`, `CapabilityFunctorK`, `CapabilityAspect`.
5. `WeaveArrows` generalization (`shellK`, `capabilityPull`, `capabilityLift`
   ×2, `eraseWeave`); `CapabilityK#observe` for `Raise`.
6. Hand-written reference `CapabilityAspect[CatalogAlg, …]` — the differential
   oracle for both macros, following the `TestAlgReference` pattern.
7. `CatalogAlg` fixtures and effect stack (§9), including the `null`-implicit
   guard.

### M8c — the synthesized `Applicative`

8. `WeaveEvidence[Applicative]` with the sentinel merge and the tightened
   identity test (SPIKE-4 first).
9. Unit coverage for `pure`/`map`/`ap` shape before the law suite exists.

### M8d — laws

10. New law files per §6; C1–C13.
11. Discipline rule sets, `ApplicativeTests` at the structural `Eq`
    (SPIKE-5), and the `ObservingWeaveRenderer` that C11 needs.
12. C12 differential oracle: `CapabilityAspect` at `Ev = Functor` versus the
    existing `RaiseAspect` fixtures, and versus `cats.tagless.Derive.aspect`
    on capability-free algebras.

### M8e — Scala 2 macro

13. `DeriveCapability.aspect` / `.functorK`; allowlist, `Ev` check, three
    diagnostic registers.
14. `DerivationErrorSpec` cases for all three registers on 2.12 and 2.13.

### M8f — Scala 3 macro

15. Same, on the Scala 3 axis, `@experimental` as today.
16. Cross-version agreement suite extended to `CatalogAlg`.

### M8g — integration

17. `WeaveInterpreter.fromCapabilityAspect`; `fromAspect` and the priority
    ordering unchanged.
18. natchez syntax and docs; the `Ask`/`Tell` `Err` tax documented at the
    syntax methods; `Synthetic`'s scaladoc extended to the `Applicative` case.
19. Whatever Q1 resolves to, applied to names and ascriptions across the
    build.

### Acceptance criteria

- All six of M8's Phase 1 points resolved here and ratified.
- Every `[SPIKE-n]` discharged and its result recorded in this document's
  Status section before the dependent code lands.
- `sbt +test` green on 2.12, 2.13 and 3 for every module; JS linker green;
  `natchezTaglessMtlJVM/doc` succeeds; zero new warnings.
- Laws C1–C13 pass; the frozen L1–L10 suites pass unmodified in substance.
- `ExpectedWeaves` unchanged for existing fixtures (C12).
