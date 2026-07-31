# Milestone M8 — generalizing to `CapabilityAspect` (design-gated)

## Status

**Phase 1 in progress** on branch `milestone/m8-capability-aspect`, stacked on
the unmerged M6, M10, M11 and M7. Prerequisites met: M5 merged; M6 and M7 both
complete (their shapes constrain this design — see "Interactions" below).

**Need gate: opened by Brian, 2026-07-30.** Recorded honestly, because neither
of the gate's two literal conditions was met: there is no concrete driving
algebra, and no upstream M9 discussion is open. Brian's rationale is to extend
the transport concept as far as cats-mtl's capabilities allow, which is a
coherent goal for a library whose premise is that concept, and a stronger
basis for the M9 upstreaming proposal than a `Raise`-only form. The gate's
owner opened it deliberately; that is what satisfies it.

The cost that acceptance carries, stated so it is not forgotten: **the design
has no external use case to validate against.** In particular Phase 1's
question 1 — a method taking *mixed* capabilities — has no real-world shape to
anchor it. Mitigation: build the fixture algebra as part of Phase 1 and treat
it as the driving use case, and keep scope to exactly the four verified
transportable capabilities. Extensible user-registered capabilities stay out of
scope (question 3 already says so).

**Design gate: still shut.** Phase 2 does not begin until Brian ratifies
`02-capability-aspect-design.md`.

### What changed under this milestone since it was written

M10 and M11 landed on `milestone/m10-evidence-carrying-transport` (unmerged).
They change the input to three of the six Phase 1 questions:

- **Question 1 (representation).** `RaisePull` now carries an `Err[_]`
  evidence parameter — `apply[E](rg: Raise[G, E])(implicit ev: Err[E])` — so
  the "one polymorphic pull versus a pull per capability" question now also
  has to answer *what evidence each capability's transport carries*, and
  whether a mixed-capability method needs one evidence type class or several.
  M10 settled the analogous question for `Raise` — transport stays uniform in
  the type, evidence arrives per application site — and that answer should be
  treated as precedent rather than re-litigated from scratch.
- **Question 4 (compatibility).** Partly pre-empted: M10 already broke
  `RaiseAspect`, `RaiseFunctorK`, `RaiseArrow`, `RaisePull` and `OnRaise`
  without shims, under ratified decision D7. The precedent for a clean break
  on this API family is established; the question is now narrower.
- **Question 6 (interactions).** M6's `OnRaise` is now `OnRaise[F, Err]`, and
  M11 added `WeaveInterpreter[Alg, Dom, Cod, Err, F]` in `raise-aspect-core`
  as the backend-agnostic resolution point. A generalized capability design
  has to say what `WeaveInterpreter` becomes, not just what `RaiseAspect`
  becomes.

One hard-won constraint worth carrying into any new backend or capability
module, recorded in `03-evidence-carrying-transport-design.md` §B.2: an
instance a syntax import is expected to supply must live somewhere that import
actually carries it. Placing it in a package object works only for callers
lexically inside that package.

**Two gates, both Brian's:**

1. **Need gate (YAGNI).** Do not start this milestone at all without a
   concrete driving use case — a real algebra that needs `Ask`/`Tell`/
   `Stateful` transport — or upstream interest surfaced by the M9
   discussion. The overview deferred this deliberately.
2. **Design gate.** Phase 1 produces a design document; Phase 2
   (implementation) must not begin until Brian has ratified it. This is the
   same overview-then-milestones structure that governed M0–M5, applied
   recursively: Phase 1's deliverable is the next `01-overview`-style doc.

---

Read `01-overview-design-and-laws.md` first.

## Problem

`Raise` transports across `Weave[F, Dom, Cod, *] ⇄ F` because its members
only *produce* `F` values (`raise` returns `F[A]`; nothing consumes one).
Other cats-mtl capabilities appear to share that property, so the same
pull/lift construction should generalize — the overview names `Ask`, `Tell`,
and `Stateful`. `Handle` remains excluded (it consumes `F`), and the
organizing principle for everything else is:

> A capability is transportable iff every abstract member only produces `F`
> values (evidence members like `functor`/`applicative`/`monad` aside).

Classifications — **verified against cats-mtl v1.7.0 (tag `v1.7.0`, commit
`931556e44938a47eaa91af4d907b61a4a0bb4cab`) on 2026-07-30**, sources vendored
under `reference/upstream/cats-mtl/`. All five bullet-point expectations below
were confirmed; two things about the list itself were not:

- **The enumeration was incomplete.** cats-mtl 1.7.0 has **nine** capability
  type classes, not the seven named here. **`Listen`** is missing entirely —
  it is excluded, disqualified by `listen` consuming an `F[A]`, and it sits
  between `Tell` and `Censor` in the hierarchy, so any design that reasons
  about `Censor` must account for it.
- **`Chronicle`'s disqualifier was misattributed.** The grouping below implies
  `confess`/`dictate` consume `F`. They do not; `Chronicle` is disqualified
  specifically by **`materialize`**. The verdict is unchanged, the reason is
  not — and a generalization that keys off the wrong member would classify it
  wrongly.

One thing the verification flagged for the design phase: cats-mtl already
ships `Local.liftTo` via `LiftKind`, which looks like it might already solve
transport for a consuming capability. **It does not.** `LiftKind` lifts only
endomorphisms `F ~> F` into `G ~> G`, and only has instances where `G` is a
canonical monad-transformer stack over `F` (`EitherT`/`IorT`/`Kleisli`/
`OptionT`/`WriterT`) — not for an arbitrary `F ~> G` such as this library's
tracing interpreter. Do not build on it without re-reading
`reference/upstream/cats-mtl/core/src/main/scala/cats/mtl/LiftKind.scala`.

The original expectations, all confirmed:

- `Ask[F, A]`: `ask` produces `F[A]`; evidence member expected to be
  `Applicative[F]`. Transportable.
- `Tell[F, L]`: `tell` produces `F[Unit]`; evidence `Functor[F]`.
  Transportable.
- `Stateful[F, S]`: `get`/`set` produce; evidence `Monad[F]`. Transportable.
- `Local[F, A]` extends `Ask` but `local` consumes an `F[A]` — excluded
  (methods can still take plain `Ask`).
- `Censor`, `Chronicle`, `Handle`: consume `F` — excluded, with diagnostics
  naming them the way `Handle` is named today.

## Phase 1 — design document

Deliverable: `docs/plans/raise-aspect/02-capability-aspect-design.md`,
playing the same role for this work that `01-overview-design-and-laws.md`
played for M0–M5: final decisions, core types at the constructor-call level,
an expansion specification, laws, and its own milestone map for Phase 2.
It must resolve at least the following, each either decided with rationale
or explicitly listed as a question for Brian:

1. **Representation.** All candidate capabilities are binary
   (`C[F[_], X]`). The natural generalization of `RaisePull` is a
   per-capability transport typeclass — sketch, to be validated:

   ```scala
   trait CapabilityK[C[_[_], _]] {
     def pull[F[_], G[_], X](cg: C[G, X])(fk: G ~> F, ...): C[F, X]
     ...
   }
   ```

   The design must answer: is there one `CapabilityArrow` carrying a single
   polymorphic pull (as `RaiseArrow` does today), or a pull per capability
   the algebra mentions — and what does derived `mapK` look like for a
   method taking *mixed* capabilities (`Ask` and `Raise` on one method)?
   The lift direction (`raiseLift`'s analogue) needs the synthetic-shell
   machinery per capability; decide where `Synthetic` fits.

2. **Synthetic evidence on shells.** `Raise` only needed a synthesized
   `Functor` on the shell carrier (overview §3.3, laws L6/L7). `Ask` and
   `Stateful` are expected to demand synthesized `Applicative`/`Monad` on
   `Weave[F, Dom, Cod, *]` — `pure` and `flatMap` that build/rebuild shells
   around real `F` values. Unlike the raise channel, these shells wrap
   *succeeding* effects, so the "a raised `F[A]` never yields an `A`"
   argument no longer carries the soundness claim alone; the section/
   retraction argument (shells are unwrapped immediately by the pull inside
   the woven method and never reach an interpreter) has to do all the work.
   The design doc must state that argument precisely and turn it into laws
   generalizing L5–L7 per capability.

3. **Derivation recognition.** Generalize overview §3.4 rule 1: a fixed
   allowlist keyed by dealiased type constructor (`Raise`, `Ask`, `Tell`,
   `Stateful`), multiple and mixed capability parameters per method, and
   rejection diagnostics for the excluded capabilities by name. Extensible
   user-registered capabilities are out of scope (YAGNI) — say so in the
   doc.

4. **Compatibility.** `RaiseAspect`/`RaisePull`/`RaiseArrow` are published
   API after M5. Decide whether they remain as aliases/subtypes of the
   general machinery or are deprecated in a major version. Anything that
   keeps a second way to do the same thing (shims, dual code paths) is
   backward compatibility and needs Brian's explicit approval per CLAUDE.md
   — present the options, don't pick silently.

5. **Laws placement.** `raise-aspect-laws` is frozen: generalized laws are
   *new* files/modules, never edits. If a generalized law would contradict a
   frozen one, that is a STOP-and-report, not an edit.

6. **Interactions.**
   - M6's `OnRaise` hook: decide whether observation hooks generalize
     (`OnTell` is coherent; `OnAsk` less obviously useful). Default
     position: hooks stay `Raise`-specific until a need exists.
   - M9: if upstream discussion is already open, the maintainers' appetite
     for a general `CapabilityAspect` versus a `Raise`-specific one is
     direct input to this design — check before writing.

## Phase 2 — implementation

Blocked on the design gate. The ratified `02-capability-aspect-design.md`
carries its own milestone map and task lists (the M0–M5 pattern, smaller
since the module skeleton, laws seam, and both macro codebases already
exist). This document deliberately does not pre-write those tasks: writing
implementation tasks against an unratified design would fabricate exactly
the details the design phase exists to decide.

## Acceptance criteria

- **Phase 1:** `02-capability-aspect-design.md` exists, resolves points 1–6
  (decisions with rationale, or explicit questions for Brian), includes an
  expansion spec and law statements at the same level of precision as
  `01-overview-design-and-laws.md`, and has been ratified by Brian.
- **Phase 2:** per the ratified design doc's own milestone map.

## Ground rules reminder

Phase 1 is a writing milestone: no production code changes, no dependency
changes. Verify every cats-mtl member signature you cite against the real
sources (add them to `reference/upstream/` with provenance recorded, the way
the cats-tagless references were vendored).
