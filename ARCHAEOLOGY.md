# Archaeology

Design history, dead ends, and decisions behind the `raise-aspect-*` modules
and the otel4s tracing backend (`otel4s-tagless`, `otel4s-tagless-mtl`). None
of this is needed to use the library — see each module's README and scaladoc
for that. It exists for whoever next has to change this code and wants to
know why it looks the way it does.

The full per-milestone plans and implementation notes this file once
indexed have since been deleted from the repo; this is now the sole
surviving record of the decisions that still matter.

## Design decisions

### `RaiseAspect#intercept` fuses `weave` and `mapK`

`RaiseAspect` was first modeled the way `Aspect` is modeled:
`weave` producing a woven algebra, then a separate `mapK` interpreting it.
That required a `Functor` instance on the woven carrier
(`Aspect.Weave[F, Dom, Cod, *]`), which cats-tagless can only synthesize, not
derive honestly, for methods carrying `Raise` parameters — a real defect. It
was later fixed by fusing the two operations into one `intercept` method: a
`Weave` is now built and handed straight to the interpreter as data, so no
method ever receives a `Raise[Aspect.Weave[F, Dom, Cod, *], E]` and nothing
has to synthesize a `Functor` for it. `Synthetic` and `WeaveArrows`' pair of capability
transports existed only to work around the old defect and were deleted along
with it.

### Laws removed with their APIs

`RaiseAspect` once also extended `RaiseFunctorK`, whose `mapK` changed an
algebra's carrier using a `RaiseArrow` (a forward `F ~> G` plus a `RaisePull`
carrying `Raise` capabilities backward). Nothing outside the laws and the
derivation ever called it — tracing only needs `intercept`, which never changes
carrier — so it was removed along with `RaiseArrow`, `RaisePull`,
`DeriveRaise.functorK`, and the laws that specified them: L1 (`mapK` identity),
L2 (`mapK` composition), and L4 (arrow coherence). L5 and L6 had specified the
lifted capability on the woven carrier, which the fusion of `weave` and `mapK`
into `intercept` (above) eliminated.

If carrier-changing `mapK` is wanted again, add it as a separate type class
with its own derivation rather than as a supertype of `RaiseAspect`: a new
abstract member on `RaiseAspect` would break every existing instance.

### `Err = Trivial` copies of laws L4–L7

Adding an `implicit ev: Err[E]` parameter to the value-level laws (L4,
L5, L6, L7) narrowed each from "for all `E`" to "for all `E` for which
`Err[E]` exists" — strictly weaker than what the laws had established
before, since `Render` (the law suite's usual `Err`) doesn't cover every `E`.
`cats.tagless.Trivial` has exactly one instance, universal in `E`, so
instantiating each law again at `Err = Trivial` restores the original `∀E`
quantifier. All seven affected properties carried that restoration at the
time — this was an explicit call by the project owner, not a default, and
not one to consolidate away as redundant with the `Render` instantiations.
The fusion into `intercept` (above) later retired L5 and L6 entirely and left
L7 with no `Err` dependency to restate at `Trivial`. L4's copy outlived them,
but L4 was then removed with `RaiseArrow` (see "Laws removed with their APIs",
above), so no `Trivial` copy survives.

### `traceWithInputs`'s effect constraint

An earlier design for `otel4s-tagless-mtl`'s `traceWithInputs` claimed it had
to declare its own effect constraint, matching `RaiseAspect#intercept`'s own
`Apply[F]` requirement. That turned out to be wrong: `WeaveInterpreter.fromRaiseAspect`
resolves whatever `intercept` needs from the caller's own scope, so neither
`traceWithInputs` nor `traceWithInputsAndOutputs` needs to declare it itself.
The claim was retracted.

### Widening `intercept`'s `Apply[F]` to `FlatMap[F]` — considered and reverted

`RaiseAspect.observing` sequences the `onRaise` hook ahead of the underlying
raise with `Apply[F]`'s `*>`. Under an accumulating `Applicative` such as
`Validated` (cats-mtl ships a lawful `Raise[Validated[E, *], E]`), if the
hook's own `F[Unit]` is itself an `Invalid[E2]`, `*>` combines it into the
result via `Semigroup[E]` rather than discarding it — the caller sees
`Invalid(e2 |+| e)` instead of `Invalid(e)`.

A first attempt fixed this by requiring `FlatMap[F]` instead and sequencing
with `>>`, which excludes `Validated`/`Ior` from `intercept` entirely (cats
gives them no `FlatMap` on purpose). That was reverted: accumulating a
hook's own error via the *same* `Semigroup` governing every other error in
the computation is exactly what accumulating validation is for — nothing
about `Validated`'s semantics makes that combination wrong. And the fix
didn't address the actual concern, which is more general than accumulation:
under any monadic `F` (the case every shipped `OnRaise` — `Otel4sDefaultOnRaise`,
natchez's default — actually runs under), a failing hook already
short-circuits `*>`/`>>` identically, *replacing* the real raised value with
the hook's failure rather than combining with it. `FlatMap[F]` changes
nothing about that; it only narrows which `F` can use `intercept`, at zero
benefit to the `F`s still allowed.

The real invariant — a raise observed through a hook must produce the exact
same value as the same raise with no hook attached, matching the
transparency `RaiseTracerTransparencySpec` checks for the shipped hooks — 
can't be enforced by choosing a stronger typeclass; it would need the power
to catch and discard the hook's own failure (`ApplicativeError`-shaped),
which `intercept` deliberately doesn't ask for (see the "Design constraints"
section of `com.dwolla.tracing.mtl`'s package scaladoc for why `Handle[F, E]`
parameters are rejected: capabilities that consume `F` are kept out of this
design on purpose). So it stands as a documented precondition on `OnRaise`
instead — see `OnRaise`'s own scaladoc — the same way a `Functor`/`Monad`
law is trusted rather than type-checked everywhere else in this ecosystem.
Every shipped hook already satisfies it trivially: none of them ever raises
through the capability they're observing.

## Migrating from `natchez-tagless` to the otel4s modules

`otel4s-tagless` and `otel4s-tagless-mtl` are otel4s counterparts of
`natchez-tagless` and `natchez-tagless-mtl`, with the same method names, but a
call site changes in two ways besides its import line: it supplies a
`TracerProvider[F]` instead of a `Trace[F]`, and it gets back an `F[Alg[F]]`
instead of an `Alg[F]` (see "You now pass a `TracerProvider`" below). Your
types are another matter: every type that natchez traced through its
`Encoder`/`Show` fallback needs a `ToAnyValue` (see "What needs new code"
below), and the recorded span data changes too:

| natchez, in `natchez-tagless` / `natchez-tagless-mtl` | otel4s counterpart |
| --- | --- |
| `TraceInstrumentation` | `TracerInstrumentation` |
| `TraceWeaveCapturingInputs` | `TracerWeaveCapturingInputs` |
| `TraceWeaveCapturingInputsAndOutputs` | `TracerWeaveCapturingInputsAndOutputs` |
| `natchez.TraceableValue` | `ToAnyValue` (project-owned, no bridge between the two) |
| `syntax.traceWithInputs` / `traceWithInputsAndOutputs` / `instrumentAndTrace` | same names; take a `TracerProvider[F]` and return `F[Alg[F]]` |
| `syntax.asTraceParams` | `syntax.asAttributes` |

The method names matching is deliberate, but it means a single file cannot
wildcard-import both `com.dwolla.tracing.syntax._` and
`com.dwolla.tracing.otel4s.syntax._` — the conversions become ambiguous.
Scala 2 reports that plainly; Scala 3 instead says
`value traceWithInputs is not a member of …`, with import suggestions
unrelated to either package, and never mentions the ambiguity. If a method
that plainly exists reports as missing on Scala 3, check for a stray import
of the other backend's syntax first.

### You now pass a `TracerProvider`

natchez's syntax takes the ambient `Trace[F]` and returns the traced `Alg[F]`.
The otel4s syntax takes the application's `TracerProvider[F]` and returns
`F[Alg[F]]`: running it obtains this library's own tracer, under the
instrumentation scope `com.dwolla.tracing.otel4s` with the library's version,
which is how OpenTelemetry expects an instrumentation library to identify its
spans. So

```scala
// natchez-tagless
implicit val trace: Trace[IO] = ???
val traced: Foo[IO] = Foo[IO].traceWithInputsAndOutputs
```

becomes

```scala
// otel4s-tagless
implicit val tracerProvider: TracerProvider[IO] = ??? // e.g. from OtelJava
val traced: IO[Foo[IO]] = Foo[IO].traceWithInputsAndOutputs
```

and the traced algebra is built inside `IO`, typically once, while the
application's resources are wired. Pass the same provider the application's own
tracer comes from: tracers from one provider share the current-span context,
so the application's spans still parent this library's. The constraint each
method needs grows by at most a `Functor[F]`, for the map after obtaining the
tracer.

`withMetrics` from `otel4s-tagless-metrics` has the same shape, so the two
compose with `flatMap`: `alg.withMetrics().flatMap(_.instrumentAndTrace)`.

A custom `OnRaise[F, TraceableValue]` hook that asks for `Trace[F]` should be
ported to an `OnRaise[F, ToAnyValue]` that asks for `TracerProvider[F]`, not
`Tracer[F]`. The call site has no `Tracer[F]`, so a hook that needs one counts
as absent, and the default hook silently records in its place. See
"Overriding the default recording" in the `com.dwolla.tracing.otel4s.mtl`
package scaladoc, or the `otel4s-tagless` README.

### What needs new code

**`ToAnyValue` has no implicit fallback; `TraceableValue` does.**
`natchez-tagless`'s `nonPrimitiveTraceValueViaJson` (declared in
`LowPriorityTraceableValueInstances`, which *extends* the trait holding the
`Show` fallback) gives any type with a circe `Encoder` or a cats `Show` a
`TraceableValue` implicitly, preferring the `Encoder` —
`ImplicitPrioritizationSpec` pins exactly that. Under otel4s such a type is a
compile error until it gets a `ToAnyValue`: a value is recorded only through
its own instance, so a redacting instance cannot be bypassed by a container
or case class that encodes the whole value with its `Encoder` or `Show`. Write
an instance, or opt in explicitly with `ToAnyValue.fromEncoder` or
`ToAnyValue.fromShow`. This applies to raised error types recorded by
`otel4s-tagless-mtl` too.

### What silently changes value when you migrate

Once everything compiles, three things record differently without a compile
error:

- **Span attribute keys change.** natchez records one attribute per parameter,
  named `<Alg>.<method>.<param>`, plus `<Alg>.<method>.returnValue`. otel4s
  records a single `com.dwolla.code.function.arguments` map, a
  `com.dwolla.code.function.return_value`, and `code.function.name`. Dashboards
  and queries keyed on the natchez names must change. The exception is the
  raise-time attributes: `com.dwolla.raise.error.type` and
  `com.dwolla.raise.error.value` are shared through `RaiseRecorder` and are
  identical in both.

- **An `Encoder`-backed type records structured data, not a JSON string.**
  natchez traces a type through its circe `Encoder` as the *string*
  `{"cents":150}`. A type opted in here with `ToAnyValue.fromEncoder` folds
  the same `Json` into a structured `AnyValue` tree:
  `AnyValue.map(Map("cents" -> AnyValue.long(150)))`, not
  `StringValue("{\"cents\":150}")`.
- **Spans get marked errored that weren't before.** otel4s's `SpanBuilder`
  finalizes with `SpanFinalizer.Strategy.reportAbnormal` by default, so any
  `Throwable` escaping a traced method is recorded as an exception event and
  the span status set to `Error` — natchez's three interpreters never call
  `Trace[F].attachError`. A `cats.mtl.Raise` error never gets the exception
  event. When `Raise` lives in the effect's success channel (`EitherT`), the
  effect *succeeds*, so the span is OK and otel4s can't see into it. When
  `Raise` comes from `Handle.allowF` over a `MonadThrow`, cats-mtl's submarine
  encoding triggers a finalization strategy that recognizes the `Submarine`
  and reports the domain error instead: status `ERROR`, `error.type` set to
  its type name (computed by `ErrorTypeName`; Scala 3 enum cases:
  `<Enum>$<Case>`), and no exception event. The domain error's *value* is
  in the trace only if something inspects the `Raise` channel
  explicitly — which is what `otel4s-tagless-mtl`'s `RaiseAspect` support
  does; see its README. The string-vs-structured difference described above
  applies to `com.dwolla.raise.error.value` too: an error type opted in with
  `fromEncoder` records a structured `AnyValue` tree where
  `natchez-tagless-mtl` records JSON text.

### Other differences worth knowing if you've used natchez-tagless

- `Float` widens to the exact `Double` bit pattern (`0.1f` records as
  `0.10000000149011612`) rather than `_.toString.toDouble`'s prettier but
  value-changing rendering.
- `BigDecimal` and `BigInt` have built-in instances that record
  `AnyValue.long` or `AnyValue.double` when that is exact, and the exact
  decimal string otherwise, rather than a string via `Show`.
- `()` and `None` both encode to `AnyValue.empty` (natchez records the
  strings `"()"` and `"None"`), and a top-level attribute whose value would
  be entirely empty is omitted rather than recorded empty — see the
  `otel4s-tagless` README's "empty attribute" section for the full rule.
- There is no `Resource`-shaped support (`TraceResourceAcquisition` and
  friends) — otel4s's `SpanOps.resource` doesn't propagate span context into
  the `use` block the way `natchez.Trace#spanR` does, and closing that gap is
  a different ergonomic problem this repo hasn't taken on.
- There is no `EntryPoint` analogue — otel4s has no `EntryPoint` and no
  `natchez.Span`-in-`Kleisli` idiom.
