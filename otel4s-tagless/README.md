# otel4s-tagless sources

otel4s versions of `natchez-tagless`'s three tracing interpreters, in package
`com.dwolla.tracing.otel4s`, with syntax in `com.dwolla.tracing.otel4s.syntax`.

This module depends on `otel4s-core-trace`, `otel4s-semconv`, cats,
cats-tagless, circe-core and `tagless-core` — it must never depend on natchez.

## What is here

`TracerInstrumentation`, `TracerWeaveCapturingInputs`,
`TracerWeaveCapturingInputsAndOutputs`, the `ToAnyValue` type class, and
`AnyValueAspect` (Scala 3 only — see below).

## What is not here, deliberately

- **No `Raise`/cats-mtl support *in this module*.** `OnRaise`, `RaiseAspect`
  and the `Raise`-aware syntax live one module over, in `otel4s-tagless-mtl`
  — see the section at the end of this file. This module stays plain-`Aspect`
  only, so a user who does not take `Raise` parameters pays for neither
  cats-mtl nor `raise-aspect`.

## Where a `Tracer[F]` comes from

Not from here. An application gets one from `TracerProvider[F].get(name)`,
supplied by a backend module — `otel4s-oteljava` on the JVM, `otel4s-sdk`
cross-platform. This module depends on `otel4s-core-trace` and `otel4s-semconv`
(for the stable `code.function.name` key), neither of which is a backend, and
the one `Tracer` it can construct itself is `Tracer.noop`, which the tests use.

## The attribute layout

A traced call records **at most three** attributes. Given

```scala
trait Foo[F[_]] {
  def greet(name: String, times: Int): F[String]
}
```

a call to `greet("world", 2)` under `traceWithInputsAndOutputs` produces one
span named `Foo.greet` carrying

| key | value |
| --- | --- |
| `code.function.name` | `Foo.greet` (stable semantic convention) |
| `com.dwolla.code.function.arguments` | a map, `{"name": "world", "times": 2}` |
| `com.dwolla.code.function.return_value` | the encoded return value |

The keys follow the OpenTelemetry naming conventions (lowercase, snake_case,
and an owned `com.dwolla` prefix where the semantic conventions define
nothing). They never vary by algebra or method; the method is identified by
`code.function.name` and the span name. `core` (natchez) keeps its 0.2.6 keys
(`<Alg>.<method>.<param>` and `<Alg>.<method>.returnValue`), so the two
backends' attribute keys differ deliberately.

Parameter names come from the `Aspect`'s `Advice`, and every parameter list is
flattened into the one map — which cannot lose a parameter, because Scala
rejects duplicate parameter names within a signature, including across
parameter lists.

**`com.dwolla.code.function.arguments` is a real OTLP `kvlistValue`, not a JSON string.** It reaches the
wire as nested `kvlistValue`/`arrayValue`/`intValue`/…, and on the
`otel4s-oteljava` backend it is an `io.opentelemetry.api.common.KeyValueList`
whose entries are typed `Value`s. The JSON-looking text you may see in a log is
`Value.asString`, never the storage.

One structured attribute rather than one per parameter is deliberate: it costs
one slot against `SpanLimits.maxNumberOfAttributes` (default 128) where twenty
flat attributes would cost twenty, and `maxAttributeValueLength` still recurses
into the tree, so nothing escapes truncation by being nested.

`code.function.name` repeats the span name, and it spends a slot anyway
because it is the join key between spans and metrics: `otel4s-tagless-metrics`
records the same attribute on `com.dwolla.code.function.duration`, and a
metric data point has no span name, so this attribute is what lets a query
line a call's span up with its duration measurements.

`TracerInstrumentation` records neither the arguments nor the return value; it
only names the span (and records `code.function.name`).

## An empty attribute is omitted, not recorded empty

Totality lives in the type class; absence lives in the interpreter.

`ToAnyValue` is total: `A => AnyValue`, with `Unit` and `None` encoding to
`AnyValue.empty`. It has no channel for "nothing" and will not get one, because
`Seq(Some("a"), None)` has to encode as `SeqValue([StringValue(a), EmptyValue])`
— an `Option[AnyValue]` result would shrink the sequence and destroy the
positions a sequence exists to preserve. Inside the arguments map, an empty
entry is likewise **kept**: `{"name": "world", "note": null}` records all three
of key, position and absence for one slot.

A **top-level attribute**, though, is **omitted entirely** when its whole value
would be empty:

- a zero-parameter method records **no** `com.dwolla.code.function.arguments` attribute — not one
  holding an empty map;
- a `Unit`-returning method records **no** `com.dwolla.code.function.return_value` attribute — not one
  holding `EmptyValue`;
- `def ping(): F[Unit]` therefore produces a span with **no attributes beyond `code.function.name`**.

Both omissions are verified end to end against the real SDK, and the guards are
what produce them: with the guards removed the SDK happily round-trips an empty
map and an `EmptyValue`, so the zero comes from this library, not from SDK
tolerance. The span's own name already says the method ran, and two extra slots per
span is a real cost on algebras full of `close()` and `ping()`.

## `opentelemetry-api` 1.59.0 is a hard floor

On the `otel4s-oteljava` backend, **your application must be on
`io.opentelemetry:opentelemetry-api` 1.59.0 or newer.** Structured attribute
values reach the OpenTelemetry Java SDK through
`io.opentelemetry.api.common.AttributeType.VALUE`, which is `@since 1.59.0`. An
application that pins an older SDK will fail to link
`AttributeKey.valueKey` inside otel4s's own converter.

otel4s 1.1.0 pulls `opentelemetry-api` 1.64.0 transitively, which satisfies
that floor, so the default is fine; a downstream pin is what breaks. This module declares no dependency on the Java
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
| `MapValue`, empty or not — always, including every arguments attribute | `VALUE` |
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

The OTLP wire format is confirmed: a `com.dwolla.code.function.arguments` attribute leaves the exporter
as a native `kvlistValue`, end to end. What was **not** tested is whether
Jaeger, Tempo, Honeycomb, Datadog, or whatever collector sits in the path
renders `kvlistValue` span attributes rather than flattening or dropping them on
ingest. Structured span attributes are new enough that support varies, and this
is the single biggest practical unknown in the design — check it against your
own pipeline before relying on it.

If you need flat attributes today, you are one instance away: write a
`ToAnyValue` for your parameter type that encodes to `AnyValue.string`.

## `ToAnyValue` is not `TraceableValue`

If you use both `natchez-tagless` and `otel4s-tagless`, you write two
instances per domain type — there is no bridge between `TraceableValue` and
`ToAnyValue`. A conversion between them would be lossy for `BigDecimal` and
`BigInt`, and it would give this module a natchez dependency, which the
module exists to avoid. See ARCHAEOLOGY.md if you're moving a type's
instances from one to the other.

The method names on the syntax packages match, so switching a file between
backends is one import line and no call-site changes — but a single file
cannot wildcard-import both `com.dwolla.tracing.syntax._` and
`com.dwolla.tracing.otel4s.syntax._`; import one selectively if you need both
backends in one file. On Scala 2 the compiler names both conversions in the
ambiguity error. **On Scala 3 it does not**: the same source instead reports
`value traceWithInputs is not a member of …`, with import suggestions
unrelated to either package and no mention of the ambiguity at all. If a
method that plainly exists reports as missing on Scala 3, check for a stray
import of the other backend's syntax first.

**There is no implicit fallback to a circe `Encoder` or a cats `Show`.** A
type with no `ToAnyValue` is a compile error where the `Aspect` is derived.
Built-in instances cover the primitives, `String`, `Char`, `Unit`,
`BigDecimal`, `BigInt`, `UUID`, `URI`, circe's `Json` and `JsonObject`, and the
collections, maps and tuples listed below. For a domain type, write an
instance in its companion, or opt in to an existing encoding explicitly:

```scala
implicit val moneyToAnyValue: ToAnyValue[Money] = ToAnyValue.fromEncoder[Money]
implicit val distanceToAnyValue: ToAnyValue[Distance] = ToAnyValue.fromShow[Distance]
```

`fromEncoder` folds the type's `Json` into a structured `AnyValue` tree — a
`JsonObject` becomes an `AnyValue.map`, a JSON array an `AnyValue.seq`, and so
on — not a JSON string; `fromShow` records the rendering as a string. The
same opt-in is the way to record `java.time` types, which have no built-in
instance here: `ToAnyValue.fromEncoder[java.time.Instant]` uses circe's
ISO-8601 encoding (on Scala.js that needs `scala-java-time`, as circe's
`java.time` encoders always do).

The `Float` widening (`ToAnyValue[Float]`) is exact in the IEEE-754 sense and
inexact-looking in print — `0.1f` records as `0.10000000149011612`. The
alternative, `_.toString.toDouble`, prints prettily by silently changing the
value, which is worse in a library. Shadow the instance if you want the
shorter rendering.

### Redaction

A value is recorded only through its own `ToAnyValue`. A hand-written
redacting instance is therefore honored everywhere the type appears: bare, as
a parameter or return value, and inside every container, map and tuple, which
encode element-wise:

- `Option`, recorded as the value itself or an empty value;
- `Seq` (and so `List`, `Vector`, …), `Set` (including `SortedSet`), `Array`,
  `Chain`, `OneAnd`, and the cats `NonEmptyList`, `NonEmptyVector`,
  `NonEmptySeq`, `NonEmptyChain` and `NonEmptySet`, recorded as a sequence;
- any other single-parameter `C[A]` that converts to an `Iterable[A]` —
  `Iterable` itself, `scala.collection.Seq` and `scala.collection.Set`, and
  the mutable sequences and sets — also recorded as a sequence.
  `scala.collection.Map` and `mutable.Map` have no instance; convert them with
  `.toMap`. On Scala 2, a type of your own that extends `Iterable`, `Seq` or
  `Set` and has its own instance in its companion is ambiguous with this
  generic one; bring your instance into lexical scope with an import;
- tuples of arity 1 to 22, recorded as a sequence of their elements in
  position, with a sequence-valued element kept nested. (`ToAnyValue`'s
  `product`, and so `(ta, tb).tupled`, flattens one level instead, so the two
  differ when a component encodes to a sequence.)
- `Map` and `NonEmptyMap`, recorded as a map whose values use the value's
  instance. Keys are rendered from the key's own instance: a string as-is, a
  number or boolean with `toString`, and anything else with otel4s's
  `Show[AnyValue]`. Keys that render to the same string collapse to one
  entry, and under a redacting key instance every key does:
  `Map(a -> 1, b -> 2, c -> 3)` keyed by a redacted type records a single
  `redacted` entry, and which value survives follows the map's iteration
  order, which is unspecified for an unsorted `Map`.

A container with no instance here (`Either`, `Validated`, `Ior`,
`NonEmptyLazyList`, …) is a compile error rather than a leak. Choosing its
encoding is up to you; build it from the element instances so a redacting one
is still honored. For example, as a tagged map:

```scala
import cats.data.Validated
import cats.syntax.all._
import com.dwolla.tracing.otel4s.ToAnyValue
import org.typelevel.otel4s.AnyValue

implicit def eitherToAnyValue[L: ToAnyValue, R: ToAnyValue]: ToAnyValue[Either[L, R]] =
  ToAnyValue.instance(_.fold(
    l => AnyValue.map(Map("left" -> ToAnyValue[L].toAnyValue(l))),
    r => AnyValue.map(Map("right" -> ToAnyValue[R].toAnyValue(r))),
  ))

implicit def validatedToAnyValue[E: ToAnyValue, A: ToAnyValue]: ToAnyValue[Validated[E, A]] =
  ToAnyValue[Either[E, A]].contramap(_.toEither)
```

`contramap` comes from `cats.syntax.all._` and `ToAnyValue`'s `Contravariant`
instance. Both instances compile and redact on Scala 2.13 and 3.

The one caveat is the opt-in: **a type given its instance with `fromEncoder`
or `fromShow` records whatever that `Encoder` or `Show` reveals**, and they
encode the whole value without consulting any field's or element's
`ToAnyValue`. A case class opted in with `fromEncoder` records a sensitive
field in the clear if its `Encoder` writes it, even when the field's own type
redacts. Give such a type a hand-written instance instead.

A `Set` records its iteration order, which for an unsorted `Set` is
unspecified and can differ between two equal sets, so equal sets can record as
differently ordered sequences.

## Error recording

A `Throwable` that escapes a traced method gets otel4s's default
(`SpanFinalizer.Strategy.reportAbnormal`): an exception event and span status
`Error`. A cancelation sets `Error` with `"canceled"`.

How a cats-mtl `Raise` error is reported depends on where the `Raise` instance
puts the error.

- When you get your `Raise` from **`Handle.allowF` over a `MonadThrow` `F`**,
  which is the shape `otel4s-tagless-mtl`'s own examples use, cats-mtl's
  submarine encoding makes `R.raise(e)` really *be*
  `F.raiseError(Submarine(e))`. If that escapes the traced method, the span is
  reported as the domain error: status `ERROR`, `error.type` set to the error's
  runtime class name, and no exception event. The module recognizes cats-mtl's
  private `Submarine` for this; see `com.dwolla.tagless.RaisedError`. On
  Scala.js that recognition relies on runtime class names, which Scala.js keeps
  by default; an application whose linker strips them
  (`runtimeClassNameMapper`) falls back to reporting the raw exception.
- When `Raise` lives in the effect's **success** channel — `EitherT`, say — the
  effect *succeeds* carrying a value that describes a failure, so the span is
  finalized as OK.

Recording the domain error's value as well needs an interpreter that inspects
the `Raise` channel explicitly, which is what `otel4s-tagless-mtl` does — see
below.

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
required. `otel4s-oteljava-trace-testkit` publishes no `_sjs1_` artifact at any
version, but a cross-platform testkit does exist: `otel4s-sdk-trace-testkit`
(otel4s-sdk is pre-1.0 and versioned separately; 0.19.4 is built against
otel4s-core 1.1.0). Using it would add an otel4s-sdk backend, so these modules
assert span content with the JVM oteljava testkit instead; adopting the sdk
testkit is a possible follow-up.

## Scala 2.12

**This module compiles nothing and ships nothing on 2.12.** No
`otel4s-tagless_2.12` or `otel4s-tagless_sjs1_2.12` artifact is published, and
none ever will be, because otel4s itself publishes none.

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

## `derives AnyValueAspect` (Scala 3 only)

An algebra with no `Raise` parameters that wants the short `derives` spelling
instead of a hand-declared `implicit val fooAspect: Aspect[Foo, ToAnyValue,
ToAnyValue]` can write `trait Foo[F[_]] derives AnyValueAspect`. `@experimental`
goes on the algebra's **companion object**, never on the trait — see "Scala 3:
derives and @experimental" in [the root README](../README.md#scala-3-derives-and-experimental)
for why. An algebra whose methods take
`cats.mtl.Raise` parameters needs `AnyValueRaiseAspect` instead, one module
over — see the section below.

## `otel4s-tagless-mtl`, the sibling module

Artifact `otel4s-tagless-mtl`, package `com.dwolla.tracing.otel4s.mtl`, syntax
in `com.dwolla.tracing.otel4s.mtl.syntax`. It depends on this module,
`raise-aspect`, and carries the identical 2.12
containment for the identical reason.

**What it is for.** An algebra whose methods take a `cats.mtl.Raise[F, E]`
capability parameter cannot be woven by plain `Aspect`, which requires `F` to
appear only as each method's top-level return type. `RaiseAspect`
(`raise-aspect`) lifts that restriction for `Raise` parameters
specifically; this module wires it into otel4s tracing. It is the otel4s
counterpart of `natchez-tagless-mtl`, file for file.

| this module's syntax | the mtl module's syntax |
| --- | --- |
| `com.dwolla.tracing.otel4s.syntax._` | `com.dwolla.tracing.otel4s.mtl.syntax._` |
| `traceWithInputsAndOutputs` | same name, same signature |
| `traceWithInputs` | same name; the default recorder adds a `FlatMap[F]` |
| `instrumentAndTrace` | — (`Instrument` is a plain-`Aspect` notion) |

Switching a call site is one import line for `traceWithInputsAndOutputs`, whose
signature matches exactly — both versions declare `FlatMap[F]`. It is not quite
free for `traceWithInputs`: this module's declares no effect constraint at all,
while the mtl one resolves a `RaiseRecorder[F, ToAnyValue]`, and with no
user-supplied `OnRaise[F, ToAnyValue]` in scope that resolves through
`Otel4sDefaultOnRaise`, declared `[F[_] : FlatMap : Tracer]`. So a caller on the
default recorder must supply a `FlatMap[F]` it did not need here; a caller
supplying its own `OnRaise` needs only what that hook needs. (`natchez-tagless`
and `natchez-tagless-mtl` *are* an exact match on both methods — their default
recorder needs only `Trace[F]`.)

The two syntax packages cannot be wildcard-imported into the same scope — that
reintroduces the ambiguity the split exists to avoid. The mtl syntax resolves
*either* a plain `Aspect` or a `RaiseAspect` for the algebra, via
`WeaveInterpreter`, so it is a strict superset: an algebra with no `Raise`
parameters still traces.

**What a raise records.** Two attributes, on the **method's own span** — the
same child span that carries the arguments and return value, not the caller's:

| key | value |
| --- | --- |
| `com.dwolla.raise.error.type` | the error's runtime class name; always recorded |
| `com.dwolla.raise.error.value` | the error's `ToAnyValue` rendering; **omitted** when it would encode to `AnyValue.empty` |

The omission is the same omit-when-empty rule described above for parameters
and return values, and it keeps `ToAnyValue` total. Both keys are constants on
`com.dwolla.tagless.mtl.RaiseRecorder` in `raise-aspect`, so they are
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

**Scala 3 only:** `derives AnyValueRaiseAspect` is the short spelling for the
companion-object `RaiseAspect` declaration. `@experimental` goes on the
algebra's **companion object**, never on the trait.
