# Milestone M13 — `TraceableRaiseAspect`: `derives` ergonomics for the natchez shape

## Status

**Planned, not started.** The implementation plan is
`25-milestone-M13-implementation-plan.md`. Ratify the Decisions section below
before Task 1 starts.

M13, M14 and M15 were planned together on 2026-08-02 and are independent of one
another; M15 additionally prepares for **M16 (otel4s), which is planned and
being researched separately** — do not do M16's work here.

### Why this milestone exists, in one paragraph

Declaring a derived instance today means writing out four type parameters,
three of which are the same word:

```scala
object Bar {
  @experimental
  implicit val barRaiseAspect: RaiseAspect[Bar, TraceableValue, TraceableValue, TraceableValue] =
    DeriveRaise.aspect[Bar, TraceableValue, TraceableValue, TraceableValue]
}
```

That is the shape every natchez user wants and the only shape the tracing
syntax's headline method (`traceWithInputsAndOutputs`) can use. Scala 3 already
has a one-word spelling for "give me the obvious instance of this type class" —
`derives` — and cats-tagless already uses it for higher-kinded type classes
(`trait Lookup[F[_]] derives Instrument`). M13 makes that spelling available
here:

```scala
@experimental
trait Bar[F[_]] derives TraceableRaiseAspect {
  def bar(i: Int)(using R: Raise[F, BarError]): F[String]
}
```

No new capability, no new behaviour, no new law. One name instead of a
companion object and four type arguments.

---

## The obstruction, and why a new type is the answer

`derives X` desugars to a synthesized `given X[Alg] = X.derived`. Two
consequences follow, and together they rule out every cheaper option:

1. **`X` must be a one-parameter type constructor.**
   `RaiseAspect[Alg, Dom, Cod, Err]` takes four. There is no way to write
   `derives RaiseAspect` and have the compiler know which `Dom`, `Cod` and
   `Err` you meant. (The same arity obstruction is why upstream cats-tagless has
   no `derives Aspect` either — see M14.)
2. **`X` must have a companion object carrying `derived`.** A type alias
   `type TraceableRaiseAspect[Alg[_[_]]] = RaiseAspect[Alg, TraceableValue,
   TraceableValue, TraceableValue]` fixes the arity but has no companion, so it
   cannot carry `derived` and cannot appear in a `derives` clause.

A `trait` fixes both at once: it pins the three parameters *and* it has a
companion. That is also what the owner specified ("extends").

**Signatures cited in this document come from:**

| Symbol | File |
| --- | --- |
| `RaiseAspect#intercept`, `RaiseFunctorK#mapK` | `raise-aspect-core/src/main/scala/com/dwolla/tagless/mtl/RaiseAspect.scala:41-59` |
| `DeriveRaise.aspect` | `raise-aspect-macros/src/main/scala-3/com/dwolla/tagless/mtl/DeriveRaise.scala` |
| `OnRaise`, `RaiseArrow` | `raise-aspect-core/src/main/scala/com/dwolla/tagless/mtl/{OnRaise,RaiseArrow}.scala` |
| `WeaveInterpreter.fromRaiseAspect` | `raise-aspect-core/src/main/scala/com/dwolla/tagless/mtl/WeaveInterpreter.scala:65-74` |
| `RaiseTraceWeaveOps#traceWithInputs(AndOutputs)` | `natchez-tagless-mtl/src/main/scala/com/dwolla/tracing/mtl/syntax/RaiseTraceWeaveOps.scala:28-49` |
| `object Derive` (`@experimental`), `Derive.aspect` | cats-tagless-core 0.16.5, `cats/tagless/Derive.scala:28-29,53` |
| `object Instrument extends DerivedInstrument` | cats-tagless-core 0.16.5, `cats/tagless/aop/Instrument.scala:37` |

---

## The design

New file, `natchez-tagless-mtl/src/main/scala-3/com/dwolla/tracing/mtl/TraceableRaiseAspect.scala`:

```scala
package com.dwolla.tracing.mtl

import cats.Apply
import cats.tagless.aop.Aspect
import cats.~>
import com.dwolla.tagless.mtl.{DeriveRaise, OnRaise, RaiseArrow, RaiseAspect}
import natchez.TraceableValue

import scala.annotation.experimental

trait TraceableRaiseAspect[Alg[_[_]]]
    extends RaiseAspect[Alg, TraceableValue, TraceableValue, TraceableValue]

object TraceableRaiseAspect:
  def apply[Alg[_[_]]](implicit ev: TraceableRaiseAspect[Alg]): TraceableRaiseAspect[Alg] = ev

  def fromRaiseAspect[Alg[_[_]]](
      underlying: RaiseAspect[Alg, TraceableValue, TraceableValue, TraceableValue]
  ): TraceableRaiseAspect[Alg] =
    new TraceableRaiseAspect[Alg]:
      def intercept[F[_]](af: Alg[F])(
          fk: Aspect.Weave[F, TraceableValue, TraceableValue, *] ~> F,
          onRaise: OnRaise[F, TraceableValue]
      )(implicit F: Apply[F]): Alg[F] =
        underlying.intercept(af)(fk, onRaise)

      def mapK[F[_], G[_]](af: Alg[F])(arrow: RaiseArrow[F, G, TraceableValue]): Alg[G] =
        underlying.mapK(af)(arrow)

  @experimental
  inline def derived[Alg[_[_]]]: TraceableRaiseAspect[Alg] =
    fromRaiseAspect(DeriveRaise.aspect[Alg, TraceableValue, TraceableValue, TraceableValue])
```

Two abstract members, both forwarded — `intercept` and `mapK` are exactly
`RaiseAspect`'s abstract surface post-M12.

**Why `fromRaiseAspect` is a separate, non-`inline` method.** `derived` must be
`inline`, because `DeriveRaise.aspect` is an inline macro entry point. An
anonymous class written *directly* in an `inline def` body is duplicated at
every call site, and the compiler says so:

```
New anonymous class definition will be duplicated at each inline site
```

Hoisting it into a `private` named class does not work either: the inline body
is spliced at the call site, so the class must be accessible there
(`private class FromAspect can only be accessed from object TraceableAspect`).
A **non-inline factory method** solves both — the anonymous class is compiled
once, inside `fromRaiseAspect`, and the inline body is a plain call. All three
behaviours were measured; see Evidence.

`fromRaiseAspect` is public rather than an implementation detail because it is
independently useful: it lifts a hand-written or otherwise-obtained
`RaiseAspect` at the natchez shape into the narrower type, which is the only way
a Scala 2-derived instance could ever reach it (see D2's consequence).

---

## Decisions (proposed — ratify before starting, then final)

**D1 — A `trait`, not a type alias.** The owner specified "extends", and the
obstruction section above shows a type alias could not carry `derived` anyway.

The consequence is a **one-way** relationship, and it must be stated plainly
because it is the only thing about this milestone that can surprise someone:

- A `TraceableRaiseAspect[Bar]` **is** a `RaiseAspect[Bar, TraceableValue,
  TraceableValue, TraceableValue]`, so it satisfies every existing downstream
  demand — `WeaveInterpreter.fromRaiseAspect`'s `A: RaiseAspect[Alg, Dom, Cod,
  Err]`, and through it `traceWithInputs` and `traceWithInputsAndOutputs`.
  Verified on the structural analogue that implicit search at the wider type,
  with `Dom`/`Cod` still free type parameters, does find the subtype instance.
- An existing `RaiseAspect[Bar, TraceableValue, TraceableValue, TraceableValue]`
  is **not** a `TraceableRaiseAspect[Bar]`. Nothing in the repo asks for the
  narrower type, so nothing breaks; but a user who writes
  `summon[TraceableRaiseAspect[Bar]]` against a hand-written wide instance gets
  a missing-implicit error, and `TraceableRaiseAspect.fromRaiseAspect` is the
  fix. Both directions are asserted in the tests, the negative one with
  `compileErrors`.

**D2 — Scala 3 only, under `src/main/scala-3`.** `derives` does not exist on
Scala 2, and this milestone's entire stated purpose is the `derives` spelling.
A Scala 2 copy of the trait would be a type with no way to construct it that a
user could not already write by hand, so it would be pure surface area.

Two consequences, both inherent to `derives` rather than to this design, and
both worth saying out loud so nobody discovers them mid-implementation:

- **A cross-built downstream library cannot use `derives` in shared sources.**
  If your algebra compiles on 2.13 and 3, its declaration lives in
  `src/main/scala`, where `derives TraceableRaiseAspect` will not compile on the
  2.13 axis. Such a library keeps the existing per-axis instance declarations.
  This is not a limitation M13 introduces or could remove.
- The `natchez-tagless-mtl` module already has a `src/main/scala-3` directory
  (`Scala3UsageNote.scala`), so no build change is needed.

**D3 — `derived` wraps `DeriveRaise.aspect` and is `@experimental inline`.**
`DeriveRaise.aspect` is `@experimental` (it synthesizes a class with
`Symbol.newClass`), so `derived` must be too, and so must its call sites.

**Measured, and this is the load-bearing ergonomics caveat:** on 3.3.8, the
algebra itself (or an enclosing scope) must carry `@experimental`. A sibling
`@experimental` definition in the same compilation unit is *not* enough. The
exact diagnostic is

```
method derived is marked @experimental and therefore may only be used in an experimental scope.
```

reported at the `derives` clause. This is **not a regression** — the existing
hand-written form already requires `@experimental` on the `implicit val` and on
every use site (see `natchez-tagless-mtl/src/test/scala-3/com/dwolla/tracing/mtl/RaiseTraceIntegrationSpec.scala:9,18`,
where both test classes carry it). The annotation moves from the companion's
`implicit val` to the trait; it does not appear or disappear.

**D4 — No `Trivial`-codomain sibling in this milestone.** See Open questions.

**D5 — The tests prove ergonomics, not just typeability.** A test that only
checks `summon[TraceableRaiseAspect[Bar]]` compiles would pass even if the
derived instance wove nothing. Two things are required instead:

1. An algebra declared with `derives TraceableRaiseAspect` must actually trace
   end to end through the *existing* syntax — `Bar[IO].traceWithInputsAndOutputs`
   against natchez's `InMemory` backend, asserting the same command history the
   hand-written instance produces today, unedited.
2. The derived instance must be **differentially** equal to a hand-written one:
   same weaves in the same order out of `intercept`, same result from `mapK`.

---

## What changes

| Component | Fate |
| --- | --- |
| `TraceableRaiseAspect` (new) | `natchez-tagless-mtl/src/main/scala-3/com/dwolla/tracing/mtl/TraceableRaiseAspect.scala`. Trait + companion with `apply`, `fromRaiseAspect`, `derived`. |
| `RaiseAspect`, `RaiseFunctorK`, `DeriveRaise` | **untouched.** |
| `WeaveInterpreter` | **untouched.** The subtype satisfies its existing `RaiseAspect` demand unchanged. |
| `RaiseTraceWeaveOps`, `RaiseRecorder` | **untouched.** No signature changes. |
| `build.sbt` | **untouched.** `src/main/scala-3` is already a source directory for this module (verified: `natchezTaglessMtlJVM/Compile/unmanagedSourceDirectories` under 3.3.8 lists it). |
| MiMa | not applicable — `natchezTaglessMtl` has `mimaPreviousArtifacts := Set.empty` (`build.sbt:172`). |
| Laws | **unchanged.** M13 adds no operation and no law; the trait's inhabitants are exactly `RaiseAspect`'s at the pinned parameters, so `RaiseAspectTests` already covers them. |

### Serializability

`RaiseFunctorK extends Serializable`
(`raise-aspect-core/src/main/scala/com/dwolla/tagless/mtl/RaiseAspect.scala:14`),
so the contract is inherited. `fromRaiseAspect`'s anonymous class captures one
field, `underlying`, which is itself a `Serializable` by that same declaration —
so the property is preserved by construction rather than by luck.

It is **not asserted by a test in this milestone**, and that is a deliberate,
stated gap rather than an oversight: `natchez-tagless-mtl` is `CrossType.Pure`
with no JVM/JS test-source split, so asserting it would mean adding one
(`raise-aspect-core` needed exactly that for its own `Platform.isJvm` guard —
see `build.sbt:110-120` and
`raise-aspect-core/src/test/scala-jvm/com/dwolla/tagless/mtl/Platform.scala`).
That is a build change, and this milestone has none. M14, whose module already
has a JVM test directory, does assert it — see `26-…`.

---

## Evidence: demonstrated versus argued

**Demonstrated.** Compiled and run on **Scala 3.3.8** with `-Ykind-projector`
and `-Wunused:all` (the flags `show coreJVM/scalacOptions` reports for this
repo under 3.3.8), against the **published** artifacts this repo depends on —
cats-tagless-core 0.16.5 and natchez-core 0.3.10:

- `derives X` for a higher-kinded `X` works, and invokes `X.derived`. Proven by
  the failure mode as well as the success: with an algebra method returning
  `F[Option[Long]]`, the derivation ran and reported
  `Not found: given natchez.TraceableValue[scala.Option[scala.Long]]` **at the
  `derives` clause** — so the macro genuinely executed rather than the clause
  being ignored.
- `@experimental` on the trait, or on an enclosing object, is sufficient; a
  sibling `@experimental` definition in the same file is **not** (exact
  diagnostic quoted in D3).
- An anonymous class directly inside the `inline def derived` produces the
  duplication warning; a `private` named class fails to compile at the call
  site; the non-inline factory compiles with **zero warnings** under
  `-Wunused:all -Wvalue-discard -Ykind-projector`.
- The derived subtype satisfies implicit search phrased at the wider type,
  including when the wider type's extra parameters are still free
  (`def traceWithInputs[Alg[_[_]], Cod[_]](using Aspect[Alg, TraceableValue, Cod])`
  resolves against a `TraceableAspect[Lookup]` given).
- End to end at runtime: the derived instance's woven call reports
  `Lookup.get = v:k` — correct algebra name, method name and value.

**The measurement was made on the structural analogue, not on
`RaiseAspect` itself.** `raise-aspect-core` and `raise-aspect-macros` are
unpublished, so a standalone `scala-cli` check cannot reach them; the spike used
`Aspect`/`Derive.aspect` (three parameters, two abstract members `weave` and
`mapK`) in place of `RaiseAspect`/`DeriveRaise.aspect` (four parameters, two
abstract members `intercept` and `mapK`). Every property above is about
`derives`, `@experimental`, `inline` and subtyping — none of them depends on
which type class is being derived. But it is an analogue, and the plan treats
each of these as a **task-level check** to be re-observed in-tree, not as an
assumption. M14 exercises the real types, so it is also the direct confirmation
for M13.

**Argued from source, not compiled:**

- That the doctest harness (`doctestSettings`, `build.sbt:40-49,174`) copes with
  a `{{{ }}}` block containing `@experimental` and `derives`. Strongly
  supported — `Scala3UsageNote.scala`'s doctest already contains `@experimental`
  and compiles — but `derives` inside a doctest is new. Task-level check.
- That no existing implicit resolution changes. Nothing in the tree asks for
  `TraceableRaiseAspect`, and adding a subtype does not make a previously
  unambiguous search ambiguous *unless* both the wide and the narrow instance
  are in scope for the same algebra. The tests deliberately construct that
  situation once, to see what happens, rather than assuming it cannot arise.

---

## Open questions for Brian

**Q1 — Is a `Trivial`-codomain sibling wanted, now or ever?**

`TraceableRaiseAspect[Alg]` pins `Cod = TraceableValue`, which serves
`traceWithInputsAndOutputs` and `traceWithInputs[TraceableValue]`. It does
**not** serve `traceWithInputs[Trivial]` — the "trace the inputs, don't render
the return value" shape. That shape is not hypothetical: this repo's own test
suite declares an instance for it
(`natchez-tagless-mtl/src/test/scala-3/com/dwolla/tracing/mtl/RaiseTraceIntegrationSpec.scala:14-15`,
`barRaiseAspectTrivialCod: RaiseAspect[Bar, TraceableValue, Trivial, TraceableValue]`).

A user wanting `derives` for it has nothing, and cannot get it, because an
algebra can only carry one `derives` clause per type class and the two would be
different type classes. Options, none obviously right:

- (a) Ship only `TraceableRaiseAspect` now. Simplest, and covers the headline
  method. The inputs-only user keeps today's hand-written instance.
- (b) Ship a second trait too. Needs a name that reads well next to the first
  and does not lie about what it does; `TraceableInputsRaiseAspect` is the best
  I have and I do not like it.
- (c) Wait for a real user to ask.

**M13 as planned does (a)**, on YAGNI grounds and because (b)'s naming problem
is not one I should settle silently. If you want (b), it is roughly +40 lines of
source and +60 of test on top of this plan, and it should be part of M13 rather
than a later milestone — the two traits' scaladoc has to explain each other.

---

## Acceptance criteria

- [ ] `trait TraceableRaiseAspect[Alg[_[_]]] extends RaiseAspect[Alg, TraceableValue, TraceableValue, TraceableValue]`
      exists in `com.dwolla.tracing.mtl`, in `natchez-tagless-mtl/src/main/scala-3`.
- [ ] An algebra declared `@experimental trait X[F[_]] derives TraceableRaiseAspect`
      compiles, and its instance traces end to end through the **unedited**
      existing syntax against `InMemory`, producing a command history equal to
      the one a hand-written instance produces.
- [ ] The derived and hand-written instances agree differentially on `intercept`
      (same weaves, same arrival order, same results) and on `mapK`.
- [ ] `TraceableRaiseAspect[X]` satisfies `WeaveInterpreter`'s `RaiseAspect`
      demand with no change to `WeaveInterpreter`, `RaiseTraceWeaveOps` or
      `RaiseRecorder`.
- [ ] The converse is asserted to fail: a wide `RaiseAspect[…]` does not satisfy
      `summon[TraceableRaiseAspect[…]]` (`compileErrors`), and
      `fromRaiseAspect` fixes it.
- [ ] `+natchezTaglessMtlJVM/test` green on 2.12.21, 2.13.18 and 3.3.8 — the
      2.12/2.13 axes unchanged in test count, since the new sources are Scala 3
      only. `+natchezTaglessMtlJS/Test/scalaJSLinkerResult` green.
      `natchezTaglessMtlJVM/doc` succeeds.
- [ ] Zero new warnings, verified locally with `-Xfatal-warnings` forced.
- [ ] No `build.sbt` change, no dependency change, no change to any existing
      main source file's signatures.

## Ground rules reminder

- **Additive only.** If implementing this requires editing `RaiseAspect`,
  `WeaveInterpreter`, `RaiseTraceWeaveOps` or `RaiseRecorder`, STOP and report —
  the milestone's premise is that the subtype satisfies the existing demands
  unchanged, and needing to edit one falsifies it.
- No expected span history may be edited. M13 adds no behaviour; a changed
  history means the derived instance is not equivalent, which is a finding.
- Do not do M14's or M15's work here, and do not touch M16 (otel4s).
- Never use `--no-verify` or any other hook-bypass flag.
