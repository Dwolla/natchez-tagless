# Evidence-carrying `Raise` transport, and a backend-agnostic weave interpreter

Design document, 2026-07-30. Covers two related changes proposed by Brian:

- **Part A** — give `RaisePull` a per-error-type evidence parameter, so a
  `TraceableValue[E]` (or any backend's equivalent) can reach the point where
  a raised error is recorded.
- **Part B** — stop hard-coding `Dom`, `Cod`, and the `Weave ~> F` interpreter
  into the tracing typeclasses, so the same machinery serves natchez, a future
  otel4s module, and non-tracing callers.

Numbered `03-` because `02-capability-aspect-design.md` is reserved by M8 for
the `CapabilityAspect` generalization.

Read `01-overview-design-and-laws.md` first. This document amends §3.2, §3.3,
and §3.4 of that overview; those sections are the published design of types
that shipped in M5, so the amendments below are breaking changes, taken
deliberately.

## Status

**Design draft — not ratified.** Both parts are breaking API changes to types
published as of M5. Nothing is implemented.

Open decisions requiring Brian's ruling are collected in
"[Decisions requiring ratification](#decisions-requiring-ratification)".

---

## Part A — evidence-carrying transport

### A.1 Problem

M6 added `OnRaise[F]`, a hook run at the `WeaveArrows.raiseLift` interception
point, and `RaiseRecorder[F]`, which defaults it to recording the typed error
as span fields. That default records `e.getClass.getName` and `e.toString`,
bypassing `TraceableValue` entirely — the only place in this library where a
domain value reaches a tracing backend without going through the rendering
typeclass that exists to control exactly that. M6's final review caught it and
fixed it with a documentation warning, because the shape of the types made it
unfixable:

```scala
trait RaisePull[G[_], F[_]] extends Serializable {
  def apply[E](rg: Raise[G, E]): Raise[F, E]
}
```

`apply[E]` carries no per-`E` evidence, so no per-error-type typeclass can
reach the hook. Everything downstream — `OnRaise[F]`'s universally quantified
`apply[E](e: E)`, `RaiseRecorder`'s `toString`-based default, and the
redaction gap the M6 docs warn about — follows from that one signature.

### A.2 Why `E` cannot move onto the trait

The first idea considered was `trait RaisePull[G[_], F[_], E]`, with `E`
propagating to `RaiseArrow[F, G, E]`. This does not work.

`deriveMapK` applies `arrow.pull` at *every* `Raise[G, e]` parameter of every
method (`DeriveRaiseMacros.scala:423` on the Scala 3 axis, `:431` on the
Scala 2 axis). The shared fixture algebra already exercises a method with two
distinct error types:

```scala
// raise-aspect-core/src/test/scala/com/dwolla/tagless/mtl/TestFixtures.scala:48
def e(implicit R1: Raise[F, ErrA], R2: Raise[F, ErrB]): F[Unit]
```

With one `E` per arrow, `mapK` can no longer be typed for this method.
`Raise`'s contravariance in `E` does not rescue it: passing a
`Raise[G, ErrA]` to a pull expecting `Raise[G, E]` requires `E <: ErrA`, and
using the result as `Raise[F, ErrA]` requires `ErrA <: E`. Together those force
`E = ErrA`, leaving `ErrB` unserved. This is precisely the property overview
§3.2 records as deliberate ("transport is uniform in the error type").

### A.3 Design: parameterize on the constraint, not the type

Transport stays uniform in `E`. What changes is that each *application* of a
pull carries evidence for the `E` at that site.

```scala
package com.dwolla.tagless.mtl

trait RaisePull[G[_], F[_], Err[_]] extends Serializable {
  def apply[E](rg: Raise[G, E])(implicit ev: Err[E]): Raise[F, E]
}

object RaisePull {
  def id[F[_], Err[_]]: RaisePull[F, F, Err] =
    new RaisePull[F, F, Err] {
      def apply[E](rg: Raise[F, E])(implicit ev: Err[E]): Raise[F, E] = rg
    }
}

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

trait RaiseFunctorK[Alg[_[_]], Err[_]] extends Serializable {
  def mapK[F[_], G[_]](af: Alg[F])(arrow: RaiseArrow[F, G, Err]): Alg[G]
}

trait RaiseAspect[Alg[_[_]], Dom[_], Cod[_], Err[_]] extends RaiseFunctorK[Alg, Err] {
  def weave[F[_]](af: Alg[F])(implicit F: Functor[F]): Alg[Aspect.Weave[F, Dom, Cod, *]]
}
```

The evidence is summonable where it is needed. `Err` is concrete at macro
expansion — `DeriveRaise.aspect[Validator, TraceableValue, TraceableValue,
TraceableValue]` — so the derivation resolves `Err[e]` per raise parameter
using machinery that already exists: `summonOrAbort` on the Scala 3 axis,
`inferOrAbort` on the Scala 2 axis, the same calls that already resolve
`Dom[t]` and `Cod[t]` inside `weave`.

`OnRaise` follows:

```scala
trait OnRaise[F[_], Err[_]] extends Serializable {
  def apply[E](e: E)(implicit ev: Err[E]): F[Unit]
}

object OnRaise {
  def noop[F[_], Err[_]](implicit F: Applicative[F]): OnRaise[F, Err] =
    new OnRaise[F, Err] {
      def apply[E](e: E)(implicit ev: Err[E]): F[Unit] = ().pure[F]
    }
}
```

`Raise.raise[E2 <: E, A]` hands the hook an `e: E2` while the evidence in
scope is `Err[E]`. That is fine — `E2 <: E` widens at the call site. No
variance constraint is placed on `Err`.

### A.4 Why `Err` is its own parameter

Two cheaper encodings were considered and rejected, both for the same reason:
each couples error rendering to a knob that exists to control something else.

- **Reuse `Dom`.** Semantically wrong — an error is a way the method returns,
  not an input. And anyone deriving `RaiseAspect[Alg, Trivial, TraceableValue]`
  to opt out of *input* rendering would silently lose error rendering.
- **Reuse `Cod`.** Semantically defensible, but `Cod` is exactly the parameter
  a caller weakens to `Trivial` to opt out of *return-value* rendering. Under
  that encoding, asking not to trace return values silently disables typed
  error recording — the feature this whole part exists to fix — with no
  compile error.

`Err` is also where the parameter structurally belongs: `mapK` has no `Dom` or
`Cod` at all, so `RaiseFunctorK` would otherwise have to borrow a parameter
that means nothing at its level. Both tracing entry points pin
`Err = TraceableValue` regardless of what the caller does with `Dom` or `Cod`,
which is the guarantee that makes the feature reliable.

The cost is a fourth type argument at every `RaiseAspect` ascription and
`DeriveRaise.aspect` call site. Verbose, mechanical, accepted.

### A.5 `WeaveArrows`

Every member gains `Err` and threads the evidence:

```scala
def raisePull[F[_], Dom[_], Cod[_], Err[_]](implicit F: Functor[F])
  : RaisePull[Aspect.Weave[F, Dom, Cod, *], F, Err]

def raiseLift[F[_], Dom[_], Cod[_], Err[_]](implicit F: Functor[F], syn: Synthetic[Cod])
  : RaisePull[F, Aspect.Weave[F, Dom, Cod, *], Err]

def raiseLift[F[_], Dom[_], Cod[_], Err[_]](onRaise: OnRaise[F, Err])(implicit
    F: Apply[F], syn: Synthetic[Cod])
  : RaisePull[F, Aspect.Weave[F, Dom, Cod, *], Err]

def eraseWeave[F[_], Dom[_], Cod[_], Err[_]](implicit F: Functor[F], syn: Synthetic[Cod])
  : RaiseArrow[Aspect.Weave[F, Dom, Cod, *], F, Err]
```

`Synthetic[Cod]` keeps its current meaning and is unaffected: it synthesizes
the `Cod[A]` on a shell whose `A` is never produced. The genuine `Err[E]` and
the synthetic `Cod[A]` coexist in `raiseLift` and mean different things — the
error value is real and rendered, the success value does not exist. Worth a
comment at the construction site.

The `raiseLift` overload pair (hook / no hook) is unchanged in shape;
overload resolution already distinguishes them by the explicit first parameter
list.

### A.6 Derivation

**Scala 3** (`RaiseAspectMacros`):

- `aspect[Alg, Dom, Cod, Err]` and `functorK[Alg, Err]`.
- `deriveWeave` gains `Err: Type`; the raise-parameter transform becomes
  `WeaveArrows.raisePull[F, Dom, Cod, Err](using F).apply(arg)(using <summoned Err[e]>)`.
- `deriveMapK` gains `Err: Type`; the transform becomes
  `arrow.pull(arg)(using <summoned Err[e]>)`.

**Scala 2** (`DeriveRaiseMacros`):

- `raiseWeave(Dom, Cod, Err)` and `raiseMapK(Err)` — `raiseMapK` currently
  takes only the algebra type and has no access to any typeclass, so this is
  where the signature grows.
- `q"$arrow.pull($pn)(${inferOrAbort(appliedType(Err, errorType), describe)})"`.

**Diagnostics.** A missing `Err[E]` gets its own message naming the method,
the error type, and the `Err` type constructor — matching the quality bar the
existing `Dom`/`Cod` messages set, and pinned by new `DerivationErrorSpec`
cases on both axes.

**Weave structure is unchanged.** Evidence affects which code compiles, not
what a woven call produces. `ExpectedWeaves` must not need editing; if it
does, something is wrong.

**M7 interaction.** M7 resolves method-local `Dom`/`Cod` instances. `Err[E]`
has the identical problem — a method supplying its own `TraceableValue[ErrA]`
via a using clause — so M7's hybrid must cover `Err` as a third case. Landing
this work first keeps M7 from having to be reopened.

### A.7 Laws

`RaiseArrowLaws`, `RaiseFunctorKLaws`, `RaiseAspectLaws` and the two
discipline test traits gain an `Err[_]` parameter and an `implicit ev: Err[E]`
wherever a pull is applied — roughly ten call sites. No `<->` changes on
either side; the laws mean what they meant.

What does change is quantification: L4/L5/L7 currently read "for all `E`" and
would read "for all `E` for which an `Err[E]` exists." To keep the original
strength rather than quietly weakening frozen laws, the suites instantiate the
value-level laws **twice**:

- at `Err = Trivial`, whose universal instance makes
  "∀`E` with `Trivial[E]`" literally "∀`E`" — the pre-change law, unweakened;
- at `Err = Render`, exercising the path that actually carries evidence.

The second instantiation needs `Render` instances for `TestError`, `ErrA`, and
`ErrB`, which `TestFixtures.scala` does not currently have (it defines `Render`
only for `Int`, `String`, `Unit` at lines 33–35). Three additions to a fixture
the M7 doc treats as frozen; additive, and required for coverage of the new
path rather than to make anything pass.

`RaiseAspectSuite`'s type ascriptions change (`RaiseAspect[TestAlg, Render,
Render]` → four parameters). That is unavoidable under any encoding of this
change and is not a weakening.

### A.8 natchez module

```scala
sealed trait RaiseRecorder[F[_], Err[_]] { def onRaise: OnRaise[F, Err] }

object RaiseRecorder extends LowPriorityRaiseRecorder {
  implicit def fromOnRaise[F[_], Err[_]](implicit or: OnRaise[F, Err]): RaiseRecorder[F, Err]
}
trait LowPriorityRaiseRecorder {
  implicit def fromTrace[F[_]](implicit T: Trace[F]): RaiseRecorder[F, TraceableValue]
}
```

The priority mechanism is unchanged. `fromTrace` is pinned at
`Err = TraceableValue` — the only `Err` for which it can produce anything —
and its default becomes:

```scala
T.put(
  RaiseRecorder.ErrorTypeKey  -> e.getClass.getName,
  RaiseRecorder.ErrorValueKey -> ev.toTraceValue(e)
)
```

**Span field rename.** `raise.error.message` becomes `raise.error.value`
(`ErrorMessageKey` → `ErrorValueKey`). The field is no longer a message — it
is whatever `TraceableValue[E]` renders, which may be a number or a boolean.
Keeping the old name would misdescribe the contents. This is a user-visible
change to emitted spans; see decision D5.

`raise.error.type` is unchanged.

**Docs.** The `package.scala` paragraph added in commit 2328499 —
"This default rendering is not redaction-aware… `raise.error.message` is the
domain error's raw `e.toString`" — becomes false and must be replaced with the
opposite statement: the default *is* redaction-aware, via the same
newtype-plus-custom-`TraceableValue` pattern documented for
`TraceWeaveCapturingInputs`. The custom-`OnRaise` override example stays, with
its signature updated.

This is the point of Part A: the M6 review finding is fixed at its root
instead of documented around.

### A.9 Breaking changes inventory

Everything below is published API as of M5:

| Type | Change |
| --- | --- |
| `RaisePull[G, F]` | gains `Err`; `apply` gains an implicit parameter |
| `RaiseArrow[F, G]` | gains `Err` |
| `RaiseFunctorK[Alg]` | gains `Err` |
| `RaiseAspect[Alg, Dom, Cod]` | gains `Err` |
| `OnRaise[F]` | gains `Err`; `apply` gains an implicit parameter |
| `WeaveArrows.*` | all four members gain `Err` |
| `DeriveRaise.aspect` / `.functorK` | gain a type parameter |
| `RaiseRecorder[F]` | gains `Err` |
| `raise.error.message` span field | renamed `raise.error.value` |

No shim, no deprecated overload, no dual code path is proposed. Adding one
would be backward compatibility in the sense CLAUDE.md reserves for Brian's
explicit approval; the recommendation is a clean break, which is why
sequencing this before M9 matters (see §C).

---

## Part B — a backend-agnostic weave interpreter

### B.1 Problem

`WithInputsAndOutputsTracer` and `WithInputsTracer`
(`natchez-tagless-mtl/.../syntax/TraceWeaveTracer.scala`) exist to resolve one
question: does this algebra have an `Aspect` or only a `RaiseAspect`? Each
answers it twice — once at high priority, once at low — and each hard-codes
the interpreter (`TraceWeaveCapturingInputsAndOutputs[F]` at line 38 and 62)
and the `Dom`/`Cod` pair.

Consequences:

- The two typeclasses differ only in what they hard-code, so the same
  four-declaration priority dance is written twice.
- `Trace[F]` is a constraint on the *instances*, even though nothing in the
  Aspect-vs-RaiseAspect question involves tracing.
- The `FlatMap`/`Apply` asymmetry between the two typeclasses is an artifact
  of the baked-in interpreters, not of the typeclasses.
- A future otel4s module cannot reuse any of it. otel4s has different
  typeclasses for everything except `Trivial`, so a design that pins
  `natchez.TraceableValue` into the typeclass forces a parallel copy of the
  whole mechanism.

### B.2 Design

One typeclass, in `com.dwolla.tagless.mtl` (`raise-aspect-core`), with
nothing hard-coded:

```scala
sealed trait WeaveInterpreter[Alg[_[_]], Dom[_], Cod[_], Err[_], F[_]] {
  def apply(alg: Alg[F])(
      fk: Aspect.Weave[F, Dom, Cod, *] ~> F,
      onRaise: OnRaise[F, Err]
  ): Alg[F]
}

object WeaveInterpreter extends LowPriorityWeaveInterpreter {
  /** Higher priority: a plain `Aspect` wins whenever both instances exist. */
  implicit def fromAspect[Alg[_[_]], Dom[_], Cod[_], Err[_], F[_]](implicit
      F: Functor[F],
      A: Aspect[Alg, Dom, Cod]
  ): WeaveInterpreter[Alg, Dom, Cod, Err, F]
}

trait LowPriorityWeaveInterpreter {
  implicit def fromRaiseAspect[Alg[_[_]], Dom[_], Cod[_], Err[_], F[_]](implicit
      F: Apply[F],
      A: RaiseAspect[Alg, Dom, Cod, Err],
      syn: Synthetic[Cod]
  ): WeaveInterpreter[Alg, Dom, Cod, Err, F]
}
```

`raise-aspect-core` already depends on `cats-tagless-core`, so `Aspect` is
available there; it has no natchez dependency, so the typeclass is genuinely
backend-agnostic.

`Trace[F]` disappears from both instances. The effect constraints reduce to
what the instances actually use: `Functor[F]` for `weave`, `Apply[F]` for
`raiseLift`'s hook sequencing. Whatever the *interpreter* needs — `FlatMap[F]`
and `Trace[F]` for `TraceWeaveCapturingInputsAndOutputs` — is the caller's
problem, resolved at the syntax method.

`Synthetic[Cod]` becomes an unconditional constraint on the `RaiseAspect`
instance. Today the inputs-and-outputs variant avoids it by pinning
`Cod = TraceableValue` so the natchez module's own `syntheticTraceableValue`
resolves directly. With `Cod` free that dodge is gone. No user-visible change
— the same instance still resolves at the same call sites — but the doc
comment at `TraceWeaveTracer.scala:44-52` explaining why no constraint is
needed becomes false and must go.

**`onRaise` is an explicit argument, not an implicit constraint.** This keeps
the backend-specific priority mechanism (`RaiseRecorder`) in the backend
module, where cross-module implicit ordering is not a problem, while the core
typeclass stays free of any notion of tracing. The `fromAspect` instance
ignores the argument: an `Aspect`-woven algebra has no `Raise` parameters, so
there is no interception point to hook. That is honest but is the one wart in
this design; it needs a comment at the instance.

### B.3 Syntax

Per Brian: neither syntax method takes a type parameter.

```scala
class RaiseTraceWeaveOps[Alg[_[_]], F[_]](val alg: Alg[F]) extends AnyVal {
  def traceWithInputs(implicit
      F: Apply[F], T: Trace[F],
      R: RaiseRecorder[F, TraceableValue],
      ev: WeaveInterpreter[Alg, TraceableValue, Trivial, TraceableValue, F]
  ): Alg[F] =
    ev(alg)(TraceWeaveCapturingInputs[F, Trivial], R.onRaise)

  def traceWithInputsAndOutputs(implicit
      F: FlatMap[F], T: Trace[F],
      R: RaiseRecorder[F, TraceableValue],
      ev: WeaveInterpreter[Alg, TraceableValue, TraceableValue, TraceableValue, F]
  ): Alg[F] =
    ev(alg)(TraceWeaveCapturingInputsAndOutputs[F], R.onRaise)
}
```

A future otel4s module writes the same two methods against its own rendering
typeclass and its own interpreters, reusing `WeaveInterpreter`, `RaiseArrow`,
`WeaveArrows`, and `OnRaise` unchanged. Only `Trivial` is shared between the
two backends' type arguments, which is exactly the split this design creates.

**Consequence of dropping `traceWithInputs`'s `Cod` parameter.** Today
`traceWithInputs[Cod]` lets one derived instance serve both methods: derive at
`Cod = TraceableValue`, then call `traceWithInputs[TraceableValue]` on chatty
methods and `traceWithInputsAndOutputs` elsewhere. Pinning `Cod = Trivial`
means wanting both methods requires deriving two instances, one per `Cod`.

`Trivial` is nonetheless the right pin. The caller who reaches for
"inputs only" is either avoiding sensitive/oversized return values or has no
`TraceableValue` for the return type at all; `Trivial` serves both, while
`TraceableValue` serves only the first and demands instances the caller is
explicitly declining to use. The cost is an extra macro expansion, not a
correctness problem, and the escape hatch for any other combination is to call
`WeaveInterpreter` directly:

```scala
WeaveInterpreter[Alg, TraceableValue, Cod, TraceableValue, F]
  .apply(alg)(TraceWeaveCapturingInputs[F, Cod], onRaise)
```

**Divergence from `core`.** `com.dwolla.tracing.syntax.TraceWeaveOps`
(`core`, non-mtl) keeps `traceWithInputs[Cod]`. After this change the two
syntax packages — which already cannot be imported together — differ in
signature, so switching an import from `com.dwolla.tracing.syntax._` to
`com.dwolla.tracing.mtl.syntax._` breaks any `traceWithInputs[X]` call site.
Changing `core` to match is out of scope here (it would break non-mtl users
for no benefit to them); the migration note belongs in the mtl module's docs.
See decision D6.

### B.4 Naming

`WithInputsAndOutputsTracer` / `WithInputsTracer` / their `LowPriority`
partners collapse into `WeaveInterpreter` / `LowPriorityWeaveInterpreter`.
`fromAspect` and `fromRaiseAspect` stay accurate. Alternatives considered and
not preferred: `Weaver` (says nothing about interpretation), `Interpretable`
(reads as a property of the algebra rather than a resolution strategy).

---

## C. Sequencing

**Part A before Part B.** Part B's typeclass signature includes `Err` and
`OnRaise[F, Err]`. Landing B first means writing that file twice.

**Both before M9 (upstreaming).** M9 proposes these types to cats-tagless.
Upstreaming `RaiseAspect[Alg, Dom, Cod]` and then breaking it to
`RaiseAspect[Alg, Dom, Cod, Err]` is the worst available order. This is the
strongest sequencing constraint in this document.

**Before M7.** M7's method-local instance resolution must cover `Err` as well
as `Dom`/`Cod` (§A.6). Cheaper to land Part A first than to reopen M7.

**Interaction with M8.** M8's design question 1 asks whether a generalized
`CapabilityAspect` carries one polymorphic pull or a pull per capability. Part
A answers the analogous question for evidence — uniform transport, per-site
evidence — and that answer should be treated as precedent, not silently
re-litigated. M8's point 4 (compatibility of the published `RaiseArrow` family)
is partly pre-empted: Part A already breaks those types.

Suggested milestone numbering: **M10** for Part A, **M11** for Part B, with
task-level plans produced separately.

---

## D. Decisions requiring ratification

| # | Decision | Status |
| --- | --- | --- |
| D1 | `Err[_]` as a fourth parameter on `RaiseAspect`, second on `RaiseFunctorK` — not a reuse of `Dom` or `Cod` | Ratified 2026-07-30 |
| D2 | Laws gain the evidence argument; value-level laws instantiated at both `Trivial` and `Render` to preserve the original ∀`E` strength; three `Render` instances added to the frozen fixture | Ratified 2026-07-30, pending the fixture-edit caveat |
| D3 | Syntax methods take no type parameters; `traceWithInputs` pins `Cod = Trivial`, at the cost of needing a second derived instance to use both methods | Ratified 2026-07-30 in principle; the two-instance consequence is newly surfaced here |
| D4 | `WeaveInterpreter` lives in `raise-aspect-core` and takes `(fk, onRaise)` explicitly; `RaiseRecorder` stays backend-local; the `Aspect` instance ignores `onRaise` | **Open** |
| D5 | Rename the `raise.error.message` span field to `raise.error.value` | **Open** |
| D6 | Accept signature divergence between `core`'s `traceWithInputs[Cod]` and the mtl module's `traceWithInputs` | **Open** |
| D7 | No compatibility shims for any of §A.9 | **Open** |

---

## E. Acceptance criteria

**Part A**

- `RaisePull`, `RaiseArrow`, `RaiseFunctorK`, `RaiseAspect`, `OnRaise`, and all
  four `WeaveArrows` members carry `Err`, and evidence is threaded at every
  application site.
- Both macro axes summon `Err[E]` per raise parameter and emit an actionable
  diagnostic naming the method, error type, and typeclass when it is missing;
  `DerivationErrorSpec` pins the message on 2.12, 2.13, and 3.
- `ExpectedWeaves` is unchanged — evidence does not alter woven structure.
- The full law suite passes at both `Err = Trivial` and `Err = Render`; the
  differential oracle, cross-version agreement, and edge-case suites pass
  unmodified except for type ascriptions.
- `RaiseRecorder.fromTrace` renders via `TraceableValue[E]`; a test proves a
  custom `TraceableValue` for an error type reaches the span field, and the
  `package.scala` redaction warning is replaced by its inverse.

**Part B**

- One `WeaveInterpreter` typeclass in `raise-aspect-core` replaces all four
  declarations in `TraceWeaveTracer.scala`; no `natchez` import remains in the
  resolution mechanism.
- `AspectPrioritySpec` and `RaiseRecorderPrioritySpec` pass unmodified in
  substance — `Aspect` still outranks `RaiseAspect`, a user `OnRaise` still
  outranks the `Trace` default.
- Both syntax methods take no type parameters and produce spans identical to
  today's for the inputs-and-outputs path.
- A written note confirms the `Dom`/`Cod`/`Err`/interpreter quadruple is
  sufficient for an otel4s module — no natchez type appears in any signature
  in `raise-aspect-core`.

**Both**

- `sbt +test` green on 2.12, 2.13, and 3 for every module; JS linker green;
  `natchezTaglessMtlJVM/doc` succeeds.
- Zero new compiler warnings.
