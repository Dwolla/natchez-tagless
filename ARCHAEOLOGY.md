# Archaeology

Design history, dead ends, and decisions behind the `raise-aspect-*` modules
and the otel4s tracing backend (`otel4s-tagless`, `otel4s-tagless-mtl`). None
of this is needed to use the library — see each module's README and scaladoc
for that. It exists for whoever next has to change this code and wants to
know why it looks the way it does.

The full per-milestone plans and implementation notes live in
`docs/plans/raise-aspect/`; this file is a shorter index of the decisions
that still matter, not a replacement for them.

## Design decisions

### `RaiseAspect#intercept` fuses `weave` and `mapK` (M12)

Earlier milestones modeled `RaiseAspect` the way `Aspect` is modeled:
`weave` producing a woven algebra, then a separate `mapK` interpreting it.
That required a `Functor` instance on the woven carrier
(`Aspect.Weave[F, Dom, Cod, *]`), which cats-tagless can only synthesize, not
derive honestly, for methods carrying `Raise` parameters — a real defect
(see `docs/plans/raise-aspect/22-milestone-M12-fused-derivation.md`). M12
fused the two operations into one `intercept` method: a `Weave` is now built
and handed straight to the interpreter as data, so no method ever receives a
`Raise[Aspect.Weave[F, Dom, Cod, *], E]` and nothing has to synthesize a
`Functor` for it. `Synthetic` and `WeaveArrows`' pair of capability
transports existed only to work around the old defect and were deleted along
with it.

### `Err = Trivial` copies of laws L4–L7 (M10)

M10 added an `implicit ev: Err[E]` parameter to the value-level laws (L4,
L5, L6, L7), narrowing each from "for all `E`" to "for all `E` for which
`Err[E]` exists" — strictly weaker than what M1–M9 had established, since
`Render` (the law suite's usual `Err`) doesn't cover every `E`.
`cats.tagless.Trivial` has exactly one instance, universal in `E`, so
instantiating each law again at `Err = Trivial` restores the original `∀E`
quantifier. All seven affected properties carry that restoration — this was
an explicit call by the project owner, not a default; don't consolidate them
away as redundant with the `Render` instantiations, since they're the only
thing still proving the laws hold at their pre-M10 strength.

### `traceWithInputs`'s `Apply[F]` (D9, M17)

An earlier design for `otel4s-tagless-mtl`'s `traceWithInputs` claimed it had
to declare an `Apply[F]` constraint, matching `RaiseAspect#intercept`'s own
requirement. That turned out to be wrong: `WeaveInterpreter.fromRaiseAspect`
resolves the `Apply[F]` `intercept` needs from the caller's own scope, so
neither `traceWithInputs` nor `traceWithInputsAndOutputs` needs to declare it
itself. The claim was retracted — see D9 in
`docs/plans/raise-aspect/32-milestone-M17-otel4s-tagless-mtl.md`.

## Migrating from `natchez-tagless` to the otel4s modules

`otel4s-tagless` and `otel4s-tagless-mtl` are otel4s counterparts of
`natchez-tagless` and `natchez-tagless-mtl`, built so a call site's import
line is usually the only thing that changes:

| natchez, in `natchez-tagless` / `natchez-tagless-mtl` | otel4s counterpart |
| --- | --- |
| `TraceInstrumentation` | `TracerInstrumentation` |
| `TraceWeaveCapturingInputs` | `TracerWeaveCapturingInputs` |
| `TraceWeaveCapturingInputsAndOutputs` | `TracerWeaveCapturingInputsAndOutputs` |
| `natchez.TraceableValue` | `ToAnyValue` (project-owned, no bridge between the two) |
| `syntax.traceWithInputs` / `traceWithInputsAndOutputs` / `instrumentAndTrace` | same names |
| `syntax.asTraceParams` | `syntax.asAttributes` |

The method names matching is deliberate, but it means a single file cannot
wildcard-import both `com.dwolla.tracing.syntax._` and
`com.dwolla.tracing.otel4s.syntax._` — the conversions become ambiguous.
Scala 2 reports that plainly; Scala 3 instead says
`value traceWithInputs is not a member of …`, with import suggestions
unrelated to either package, and never mentions the ambiguity. If a method
that plainly exists reports as missing on Scala 3, check for a stray import
of the other backend's syntax first.

### What silently changes value when you migrate

Swapping the import is usually a no-op, but two things record differently
without a compile error:

- **JSON outranks `Show` in both libraries, but the shape still differs.**
  `natchez-tagless`'s `nonPrimitiveTraceValueViaJson` (declared in
  `LowPriorityTraceableValueInstances`, which *extends* the trait holding the
  `Show` fallback) prefers a circe `Encoder` over `Show` when both exist —
  `ImplicitPrioritizationSpec` pins exactly that — so a `Money` with both
  instances traces as the *string* `{"cents":150}` under natchez.
  `ToAnyValue.encodableToAnyValue` ranks the same way — a type with both now
  records its `Encoder` rendering here too — but it folds the `Json` into a
  *structured* `AnyValue` tree rather than a string: the same `Money` traces
  as `AnyValue.map(Map("cents" -> AnyValue.long(150)))`, not
  `StringValue("{\"cents\":150}")`. The type that used to be the loud,
  easy-to-notice case here — an `Encoder` with no `Show` — now compiles fine
  under both libraries; a type with *neither* instance is the only remaining
  compile error. What survives as the silent divergence is the shape, not
  the priority: string under natchez, structured under otel4s, for exactly
  the types that carry both instances. (This closes Q3 in
  `docs/plans/raise-aspect/30-milestone-M16-otel4s-module.md`, which asked
  whether `ToAnyValue` should gain a JSON fallback at all.)
- **Spans get marked errored that weren't before.** otel4s's `SpanBuilder`
  finalizes with `SpanFinalizer.Strategy.reportAbnormal` by default, so any
  `Throwable` escaping a traced method is recorded as an exception event and
  the span status set to `Error` — natchez's three interpreters never call
  `Trace[F].attachError`. A `cats.mtl.Raise` error usually does *not* get
  this treatment: when `Raise` lives in the effect's success channel
  (`EitherT`), the effect *succeeds*, and otel4s can't see into it; when
  `Raise` comes from `Handle.allowF` over a `MonadThrow`, cats-mtl's
  submarine encoding does trigger `reportAbnormal`, but the resulting
  exception event's type is the opaque `cats.mtl.Handle.Submarine`, naming
  neither your error type nor its value. Either way the domain error itself
  isn't in the trace unless something inspects the `Raise` channel
  explicitly — which is what `otel4s-tagless-mtl`'s `RaiseAspect` support
  does; see its README. The same string-vs-structured divergence described
  above for parameters and return values applies to `raise.error.value`
  too: an error ADT with both an `Encoder` and a `Show` records JSON text
  under `natchez-tagless-mtl` and a structured `AnyValue` tree here — both
  now rank the `Encoder` above `Show`, just in different shapes.

### Other differences worth knowing if you've used natchez-tagless

- `Float` widens to the exact `Double` bit pattern (`0.1f` records as
  `0.10000000149011612`) rather than `_.toString.toDouble`'s prettier but
  value-changing rendering.
- `BigDecimal` and `BigInt` both have circe `Encoder` instances, so they now
  fold to `AnyValue.long` or `AnyValue.double` (whichever the value's `Json`
  folds to) via `encodableToAnyValue`, rather than to a string via `Show`.
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
