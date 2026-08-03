# otel4s-tagless sources

otel4s versions of `natchez-tagless`'s three tracing interpreters, in package
`com.dwolla.tracing.otel4s`, with syntax in `com.dwolla.tracing.otel4s.syntax`.
Added in milestone **M16**; see `docs/plans/raise-aspect/`.

This module depends on `otel4s-core-trace`, cats, cats-tagless and
`tagless-core` — it must never depend on natchez.

## What is here

| natchez, in `natchez-tagless` | here |
| --- | --- |
| `TraceInstrumentation` | `TracerInstrumentation` |
| `TraceWeaveCapturingInputs` | `TracerWeaveCapturingInputs` |
| `TraceWeaveCapturingInputsAndOutputs` | `TracerWeaveCapturingInputsAndOutputs` |
| `natchez.TraceableValue` | `ToAnyValue` (project-owned) |
| `syntax.traceWithInputs` / `traceWithInputsAndOutputs` / `instrumentAndTrace` | same names |
| `syntax.asTraceParams` | `syntax.asAttributes` |

## What is not here, deliberately

- **No `Raise`/cats-mtl support *in this module*.** `OnRaise`, `RaiseAspect`
  and the `Raise`-aware syntax live one module over, in `otel4s-tagless-mtl`
  (added in **M17**) — see the section at the end of this file. This module
  stays plain-`Aspect` only, so a user who does not take `Raise` parameters
  pays for neither cats-mtl nor `raise-aspect-core`.
- **Nothing `Resource`-shaped.** No `TraceResourceAcquisition`, no
  `ResourceInitializationSpanOps`, no `TraceResourceLifecycleOps`. Those are
  built on `natchez.Trace#spanR: Resource[F, F ~> F]`; otel4s's
  `SpanOps.resource` deliberately does not propagate span context into the
  resource's `use` block (you have to route through `res.trace: F ~> F`), which
  is a different ergonomic problem.
- **No `EntryPoint` analogue.** No `EntryPointRootScope`, no
  `RootSpanProvidingFunctionK`, no `InstrumentableAndTraceableInKleisliOps`.
  otel4s has no `EntryPoint` and no `natchez.Span`-in-`Kleisli` idiom.

## Where a `Tracer[F]` comes from

Not from here. An application gets one from `TracerProvider[F].get(name)`,
supplied by a backend module — `otel4s-oteljava` on the JVM, `otel4s-sdk`
cross-platform. This module depends on `otel4s-core-trace` only, and the one
`Tracer` it can construct itself is `Tracer.noop`, which the tests use.

## The attribute layout

A traced call records **at most two** attributes. Given

```scala
trait Foo[F[_]] {
  def greet(name: String, times: Int): F[String]
}
```

a call to `greet("world", 2)` under `traceWithInputsAndOutputs` produces one
span named `Foo.greet` carrying

| key | value |
| --- | --- |
| `Foo.greet.parameters` | a map, `{"name": "world", "times": 2}` |
| `Foo.greet.returnValue` | the encoded return value |

Parameter names come from the `Aspect`'s `Advice`, and every parameter list is
flattened into the one map — which cannot lose a parameter, because Scala
rejects duplicate parameter names within a signature, including across
parameter lists.

**`parameters` is a real OTLP `kvlistValue`, not a JSON string.** It reaches the
wire as nested `kvlistValue`/`arrayValue`/`intValue`/… , and on the
`otel4s-oteljava` backend it is an `io.opentelemetry.api.common.KeyValueList`
whose entries are typed `Value`s. The JSON-looking text you may see in a log is
`Value.asString`, never the storage.

One structured attribute rather than one per parameter is deliberate: it costs
one slot against `SpanLimits.maxNumberOfAttributes` (default 128) where twenty
flat attributes would cost twenty, and `maxAttributeValueLength` still recurses
into the tree, so nothing escapes truncation by being nested.

`TracerInstrumentation` records neither attribute; it only names the span.

## An empty attribute is omitted, not recorded empty

Totality lives in the type class; absence lives in the interpreter.

`ToAnyValue` is total: `A => AnyValue`, with `Unit` and `None` encoding to
`AnyValue.empty`. It has no channel for "nothing" and will not get one, because
`Seq(Some("a"), None)` has to encode as `SeqValue([StringValue(a), EmptyValue])`
— an `Option[AnyValue]` result would shrink the sequence and destroy the
positions a sequence exists to preserve. Inside the `parameters` map, an empty
entry is likewise **kept**: `{"name": "world", "note": null}` records all three
of key, position and absence for one slot.

A **top-level attribute**, though, is **omitted entirely** when its whole value
would be empty:

- a zero-parameter method records **no** `parameters` attribute — not one
  holding an empty map;
- a `Unit`-returning method records **no** `returnValue` attribute — not one
  holding `EmptyValue`;
- `def ping(): F[Unit]` therefore produces a span with **no attributes at all**.

Both omissions are verified end to end against the real SDK, and the guards are
what produce them: with the guards removed the SDK happily round-trips an empty
map and an `EmptyValue`, so the zero comes from this library, not from SDK
tolerance. The span's own name already says the method ran, and two slots per
span is a real cost on algebras full of `close()` and `ping()`.

## `opentelemetry-api` 1.59.0 is a hard floor

On the `otel4s-oteljava` backend, **your application must be on
`io.opentelemetry:opentelemetry-api` 1.59.0 or newer.** Structured attribute
values reach the OpenTelemetry Java SDK through
`io.opentelemetry.api.common.AttributeType.VALUE`, which is `@since 1.59.0`. An
application that pins an older SDK will fail to link
`AttributeKey.valueKey` inside otel4s's own converter.

otel4s 1.0.1 pulls a new enough version transitively, so the default is fine; a
downstream pin is what breaks. This module declares no dependency on the Java
SDK and cannot enforce the floor for you.

## What the `otel4s-oteljava` backend does to the value on the way out

This module always hands otel4s an `AnyValue`. The OpenTelemetry Java SDK then
narrows most of them back down before storing:
`ArrayBackedAttributesBuilder#put` sees a `VALUE`-typed key holding a `Value`
and delegates to a private `putValue` whose own comment reads *"Convert VALUE
type to narrower type when possible"*.

| encoded `AnyValue` | arrives as |
| --- | --- |
| `StringValue` / `LongValue` / `DoubleValue` / `BooleanValue` | `STRING` / `LONG` / `DOUBLE` / `BOOLEAN` |
| `SeqValue`, non-empty and homogeneous in one of those four scalars | the matching `*_ARRAY` |
| `SeqValue`, empty | `VALUE` |
| `SeqValue`, heterogeneous, or of nested seqs/maps/byte arrays/empties | `VALUE` |
| `MapValue`, empty or not — always, including every `parameters` attribute | `VALUE` |
| `ByteArrayValue` | `VALUE` |
| `EmptyValue` | `VALUE` — but a top-level attribute is omitted before it gets there |

Two consequences are worth planning for:

1. **Arrays narrow too.** "Only structured values stay `VALUE`" is false. A
   `List[String]` return value is a `STRING_ARRAY` attribute, not a structured
   one.
2. **The same attribute key can change type between two calls of the same
   method.** A `Seq[String]` return value arrives as `VALUE` when the sequence
   is empty and as `STRING_ARRAY` when it is not; an `Option[String]` return
   value produces no attribute at all for `None` and a `STRING` for `Some`. A
   backend that infers a schema from the first sample it sees will see that.

This is Java-SDK behaviour, byte-identical in `opentelemetry-api` 1.63.0 and
1.64.0. **It is scoped to `otel4s-oteljava`**; the pure-Scala `otel4s-sdk`
backend has not been checked.

## Whether your backend *renders* structured attributes is unverified

The OTLP wire format is confirmed: a `parameters` attribute leaves the exporter
as a native `kvlistValue`, end to end. What was **not** tested is whether
Jaeger, Tempo, Honeycomb, Datadog, or whatever collector sits in the path
renders `kvlistValue` span attributes rather than flattening or dropping them on
ingest. Structured span attributes are new enough that support varies, and this
is the single biggest practical unknown in the design — check it against your
own pipeline before relying on it.

If you need flat attributes today, you are one instance away: write a
`ToAnyValue` for your parameter type that encodes to `AnyValue.string`.

## `ToAnyValue` is not `TraceableValue`

If you use both `natchez-tagless` and `otel4s-tagless`, you write **two
instances per domain type** — a `natchez.TraceableValue[Money]` and a
`ToAnyValue[Money]`. There is deliberately no bridge. A
`TraceableValue[A] => ToAnyValue[A]` conversion would have to map
`TraceValue.NumberValue(java.lang.Number)` onto `Long` or `Double`, which is
lossy for `BigDecimal` and `BigInt`, and it would give this module a natchez
dependency — precisely what the module exists to avoid. If it is ever wanted it
belongs in a third, interop module.

The method names on the syntax packages *do* match, so migrating a file is one
import line and no call-site changes. The cost is that a single file cannot
wildcard-import both `com.dwolla.tracing.syntax._` and
`com.dwolla.tracing.otel4s.syntax._`; import one selectively if you need both
backends in one file. On Scala 2 the compiler names both conversions and the
problem is obvious. **On Scala 3 it does not**: the same source reports
`value traceWithInputs is not a member of …, but could be made available as an
extension method`, followed by import suggestions unrelated to either syntax
package, and never mentions the ambiguity at all. If a method that plainly
exists reports as missing on Scala 3, check the imports first.

## Semantic divergences from natchez's `ToTraceValue`

| value | `natchez-tagless` records | here |
| --- | --- | --- |
| a type with both a circe `Encoder` and a `Show` | its JSON, via `nonPrimitiveTraceValueViaJson` | its `Show` rendering — there is no circe fallback here |
| `()` | the string `"()"` | `AnyValue.empty` (and the attribute is omitted if it is the whole value) |
| `None` | the string `"None"` | `AnyValue.empty` |
| `Float` | a `Float` | widened to the exact `Double` — `0.1f` records as `0.10000000149011612` |
| `BigDecimal`, `BigInt` | numbers | strings, via the `Show` fallback — no instance ships |

**The first row is the one that changes your data without telling you.** In
`com.dwolla.tracing.ToTraceValue`, `nonPrimitiveTraceValueViaJson` is declared in
`LowPriorityTraceableValueInstances`, which *extends* the trait holding the
`Show` fallback, so for a type that has both an `Encoder` and a `Show` the JSON
encoding wins — `ImplicitPrioritizationSpec` pins exactly that. `ToAnyValue` has
no circe fallback at all, only the `Show` one. So a `Money` that traces today as
`{"cents":150}` compiles unchanged after the import moves to
`com.dwolla.tracing.otel4s.syntax._` and records its `Show` rendering instead:
different attribute value, no warning, no compile error. (A type with an
`Encoder` and no `Show` fails to compile here, which is the loud, easy case.)

The two fallbacks also differ in *how they arrive*, which compounds it.
`natchez.TraceableValue`'s own companion carries only six primitive instances;
both of this repo's fallbacks are members of traits, reachable only through
`import com.dwolla.tracing.LowPriorityTraceableValueInstances._`, so a file that
omits that import gets neither. `ToAnyValue`'s `Show` fallback is a member of
`LowPriorityToAnyValueInstances`, which `object ToAnyValue` extends — it is in
the companion's implicit scope and applies with no import at all. One edit
therefore drops an opt-in JSON encoding and picks up an unconditional `Show`
one.

Whether a structural `Json => AnyValue` fallback should ship here is still open
(Q3 in `docs/plans/raise-aspect/30-milestone-M16-otel4s-module.md`). Even if the
answer stays "no", a migrating user has to be told the fallback is gone. Until
then, write the `ToAnyValue` instance you want: it lives in the companion of
your own type and outranks the `Show` fallback.

The `Float` widening is exact in the IEEE-754 sense and inexact-looking in
print. The alternative, `_.toString.toDouble`, prints prettily by silently
changing the value, which is worse in a library. Shadow the instance if you want
the shorter rendering.

## Error recording

otel4s does this for you and this module neither adds to it nor takes it away.
`SpanBuilder`'s default finalization strategy is
`SpanFinalizer.Strategy.reportAbnormal`, so a `Throwable` that escapes a traced
method is recorded as an exception event and the span status is set to `Error`;
a cancelation sets `Error` with `"canceled"`. Migrating from natchez, you will
see error-marked spans you did not see before: none of the three natchez
interpreters this module mirrors calls `Trace[F].attachError`.

**Whether a cats-mtl `Raise` error reaches any of that depends on where the
`Raise` instance puts the error, and neither answer is the one you want.**
`reportAbnormal` is driven by `Resource.ExitCase`, which only knows about
`MonadCancel` outcomes — succeeded, errored, canceled.

- When `Raise` lives in the effect's **success** channel — `EitherT`, say — the
  effect *succeeds* carrying a value that happens to describe a failure, and
  the span is finalized as OK. otel4s cannot see into the error channel of a
  type it knows nothing about.
- When you get your `Raise` from **`Handle.allowF` over a `MonadThrow` `F`**,
  which is the shape `otel4s-tagless-mtl`'s own examples use, cats-mtl uses its
  submarine encoding: `R.raise(e)` really *is* `F.raiseError(Submarine(e))`. So
  the resource exits `Errored`, and `reportAbnormal` marks the span `ERROR` and
  attaches an `exception` event whose `exception.type` is
  `cats.mtl.Handle.Submarine` — an opaque wrapper that names neither your error
  type nor its value. Measured against the oteljava testkit in
  `otel4s-tagless-mtl`'s `RaiseSpanContentSpec`.

Either way, the *domain* error is not in the trace. Putting it there needs an
interpreter that inspects the `Raise` channel explicitly, which is what
`otel4s-tagless-mtl` does — see below.

If you want to change or suppress the automatic behaviour, the sealed escape
hatch is `SpanBuilder.State.withFinalizationStrategy` reached through
`modifyState` — note that `SpanBuilder#withFinalizationStrategy` of the same
name is a macro. This module exposes no knob for it; a user who needs one writes
their own `Weave ~> F`, which is four lines.

## Coverage asymmetry: span content is not asserted on Scala.js

Encoding (`ToAnyValue`, `asAttributes`) and interpreter transparency are tested
on **both** the JVM and Scala.js. Span *content* — that the right attributes end
up on the right span — is asserted on the **JVM only**, in `SpanContentSpec`,
against a real SDK through `otel4s-oteljava-trace-testkit`.

The reason is availability, not preference. Every otel4s span type is sealed
with a `private[otel4s]` `Unsealed` variant, so a recording `Tracer` cannot be
hand-rolled the way this repo hand-rolls a `natchez.Trace`; a testkit is
required. At otel4s 1.0.1 there is no testkit that works on Scala.js:
`otel4s-oteljava-trace-testkit` publishes no `_sjs1_` artifact at any version,
and the cross-platform `otel4s-sdk-trace-testkit` stops at 0.19.0. Revisit when
`otel4s-sdk-trace-testkit` reaches 1.0.x.

## Scala 2.12

**This module compiles nothing and ships nothing on 2.12.** No
`otel4s-tagless_2.12` or `otel4s-tagless_sjs1_2.12` artifact is published, and
none ever will be at otel4s 1.0.x.

otel4s has never published a `_2.12` artifact, at any version — its own build
sets `crossScalaVersions := Seq("2.13.18", "3.3.8")`, and Maven Central 404s for
`otel4s-core-trace_2.12` and `otel4s-core-trace_sjs1_2.12`. So this is not a
2.12 drop: every other module in this build — `natchez-tagless`,
`natchez-tagless-scalacache`, `natchez-tagless-mtl`, `tagless-core` and the
three `raise-aspect-*` modules — keeps publishing `_2.12` artifacts, unchanged.
One new module simply never gains 2.12.

Mechanically, the project still *appears* in the 2.12 cross-build (dropping it
from `crossScalaVersions` does not remove it from sbt's root aggregate, which
then fails resolving `tagless-core_2.13`). Instead, on 2.12 the otel4s
coordinate is not declared, both source directories are emptied, and
`publish / skip` is true. Nothing observable claims 2.12 support.

## `otel4s-tagless-mtl`, the sibling module

Added in milestone **M17**. Artifact `otel4s-tagless-mtl`, package
`com.dwolla.tracing.otel4s.mtl`, syntax in
`com.dwolla.tracing.otel4s.mtl.syntax`. It depends on this module,
`raise-aspect-core` and `raise-aspect-macros`, and carries the identical 2.12
containment for the identical reason.

**What it is for.** An algebra whose methods take a `cats.mtl.Raise[F, E]`
capability parameter cannot be woven by plain `Aspect`, which requires `F` to
appear only as each method's top-level return type. `RaiseAspect`
(`raise-aspect-core`) lifts that restriction for `Raise` parameters
specifically; this module wires it into otel4s tracing. It is the otel4s
counterpart of `natchez-tagless-mtl`, file for file.

| this module's syntax | the mtl module's syntax |
| --- | --- |
| `com.dwolla.tracing.otel4s.syntax._` | `com.dwolla.tracing.otel4s.mtl.syntax._` |
| `traceWithInputs` / `traceWithInputsAndOutputs` | same names, same signatures |
| `instrumentAndTrace` | — (`Instrument` is a plain-`Aspect` notion) |

The signatures are identical, so switching a call site is one import line. The
two syntax packages cannot be wildcard-imported into the same scope — that
reintroduces the ambiguity the split exists to avoid. The mtl syntax resolves
*either* a plain `Aspect` or a `RaiseAspect` for the algebra, via
`WeaveInterpreter`, so it is a strict superset: an algebra with no `Raise`
parameters still traces.

**What a raise records.** Two attributes, on the **method's own span** — the
same child span that carries `parameters` and `returnValue`, not the caller's:

| key | value |
| --- | --- |
| `raise.error.type` | the error's runtime class name; always recorded |
| `raise.error.value` | the error's `ToAnyValue` rendering; **omitted** when it would encode to `AnyValue.empty` |

The omission is the same omit-when-empty rule described above for parameters
and return values, and it keeps `ToAnyValue` total. Both keys are constants on
`com.dwolla.tagless.mtl.RaiseRecorder` in `raise-aspect-core`, so they are
byte-identical to what `natchez-tagless-mtl` records: a query written against a
natchez-instrumented service keeps working after a migration. They are
deliberately not semconv's `error.type`, which describes how an operation
*ended* — this hook fires at raise time, so an internally-rescued raise would
otherwise leave a successful span claiming failure.

Recording happens with no action from the caller: both syntax methods resolve a
`RaiseRecorder[F, ToAnyValue]` and hand its hook to the interpreter. Supply your
own `com.dwolla.tagless.mtl.OnRaise[F, ToAnyValue]` in *lexical* scope to
override it — not in your error type's companion, which implicit search for that
type never looks inside.

One lossy case: a method that raises, rescues internally, and raises again fires
the hook twice against one span, and the second write **overwrites** the first.

**Rendering diverges from natchez for error values exactly as it does for
parameters** — see "Semantic divergences" above. An error ADT with both a circe
`Encoder` and a `Show` records its JSON under natchez and, after the import
swap, compiles unchanged and records its `Show` rendering.

**Scala 3 only:** `derives AnyValueRaiseAspect` is the short spelling for the
companion-object `RaiseAspect` declaration. `@experimental` goes on the
algebra's **companion object**, never on the trait.
