# Milestone M16 — an otel4s module mirroring the plain-`Aspect` interpreters

## Status

**Planned, not started.** The implementation plan is
`31-milestone-M16-implementation-plan.md`.

**Q1 and Q2 are ratified (Brian, 2026-08-02).** The artifact is
**`otel4s-tagless`**, directory `otel4s-tagless/`, sbt project `otel4sTagless`.
Testing is **split**: a cross-platform suite over `Tracer.noop` plus a JVM-only
span-content suite using `otel4s-oteljava-trace-testkit % Test`.

**D1 was replaced on 2026-08-02**, after a spike. The module's `Dom`/`Cod` is
`ToAnyValue[-A]`, producing otel4s's structured `AnyValue`, not the flat
`ToAttributes[-A]` this document originally proposed. Everything downstream of
that — D3, D4, the instance list, the syntax, all six tasks — is written for the
new shape. The spike is
`.superpowers/sdd/spike-anyvalue-attributes.md` (git-ignored scratch); its
findings are reproduced below so this document stands alone.

**Q3 (a circe fallback) is the one open question**, and the spike changed what
it is asking. See *Open questions*.

**M16 stacks on M15.** `com.dwolla.tagless.WeaveKnot` must already live in the
`tagless-core` module before this module can depend on it without dragging in
natchez. M15 (`28-milestone-M15-tagless-core-module.md`) exists for exactly this
reason; do not start M16 until it has landed.

The research this milestone is built on is
`.superpowers/sdd/m16-otel4s-research.md`, a source-verified pass against
otel4s **v1.0.1** (commit `f34c324851e079d0d6fb2c47064c6c9e04cf2c01`), whose
cited sources are vendored under `reference/upstream/otel4s/`. Every `file:line`
citation in this document refers to that tree. Do not re-derive its findings
from memory; read the vendored file.

### Why this milestone exists, in one paragraph

This repository's headline feature is a set of `FunctionK`s that turn a woven
cats-tagless algebra into traced calls: `TraceInstrumentation`,
`TraceWeaveCapturingInputs` and `TraceWeaveCapturingInputsAndOutputs`, plus the
syntax that makes them one method call on an algebra. All three are written
against `natchez.Trace`. otel4s is the Typelevel OpenTelemetry API and is where
new Typelevel tracing is going. M16 ports **those three interpreters and their
syntax, and nothing else**, to otel4s, in a new module that depends on
`otel4s-core-trace` and does not depend on natchez.

---

## Scope

**In scope.** Mirrors of, and only of:

| natchez, in `core` | otel4s, in the new module |
| --- | --- |
| `TraceInstrumentation` | `TracerInstrumentation` |
| `TraceWeaveCapturingInputs` | `TracerWeaveCapturingInputs` |
| `TraceWeaveCapturingInputsAndOutputs` | `TracerWeaveCapturingInputsAndOutputs` |
| `ToTraceValue` / `natchez.TraceableValue` | `ToAnyValue` (project-owned — see below) |
| `syntax.TraceWeaveOps` (`traceWithInputs`, `traceWithInputsAndOutputs`) | `syntax.TracerWeaveOps`, same method names |
| `syntax.TraceParamsOps` (`asTraceParams`) | `syntax.WeaveAttributesOps` (`asAttributes`) |
| `syntax.InstrumentableAndTraceableOps` (`instrumentAndTrace`) | same name, `Tracer`-typed |

**Out of scope, deliberately.**

- **`RaiseAspect` and the whole `raise-aspect-*` family.** There is no otel4s
  analogue of `natchez-tagless-mtl` in this milestone. If a task starts reaching
  for `OnRaise`, `RaiseAspect`, `RaiseRecorder` or an otel4s `RaiseTraceWeaveOps`,
  **stop** — that is a later milestone nobody has asked for.
- **Everything `Resource`-shaped**: `TraceResourceAcquisition`,
  `syntax.ResourceInitializationSpanOps`, `syntax.TraceResourceLifecycleOps`.
  These are built on `natchez.Trace#spanR: Resource[F, F ~> F]`. otel4s's
  `SpanOps.resource: Resource[F, SpanOps.Res[F]]` is close in shape but
  explicitly does **not** propagate span context into the resource's `use`
  block — you must route through `res.trace: F ~> F` (`SpanOps.scala:191`,
  `:290`, documented at `:90-110`, tracked upstream as
  [otel4s#194](https://github.com/typelevel/otel4s/issues/194)). That is a
  different ergonomic problem and a distinct chunk of work.
- **`EntryPointRootScope`, `RootSpanProvidingFunctionK`,
  `syntax.InstrumentableAndTraceableInKleisliOps`.** otel4s has no `EntryPoint`
  and no `natchez.Span`-in-`Kleisli` idiom; the nearest pieces are
  `TracerProvider.get` and `Tracer[F].rootScope` (`Tracer.scala:178`). Nothing
  to mirror one-to-one.
- **Any change to `core`, `scalacache`, `natchez-tagless-mtl` or the three
  `raise-aspect-*` modules.** M16 is additive.

---

## The 2.12 exclusion — read this first

**Scala 2.12 is excluded from this module, and only this module. Ruled by
Brian, 2026-08-02.**

CLAUDE.md requires that dropping 2.12 support be called out. This is that
call-out, and the honest framing is: **the library does not drop 2.12; one new
module never gains it.** `core`, `scalacache`, `raise-aspect-core`,
`raise-aspect-laws`, `raise-aspect-macros`, `natchez-tagless-mtl` and
`tagless-core` all keep publishing `_2.12` artifacts, unchanged. The otel4s
module's published artifact list is a strict subset of the rest of the build's.

The reason is not preference, it is availability:

- otel4s's own build says so. `otel4s/build.sbt:35` reads
  `ThisBuild / crossScalaVersions := Seq(Scala213, "3.3.8")` where
  `Scala213 = "2.13.18"` (`build.sbt:34`). A `val Scala212 = "2.12.21"` exists
  at `build.sbt:33` and **is referenced nowhere else** in `build.sbt` or
  `project/`; it is dead.
- Maven Central agrees, at every version, not just 1.0.1:
  `https://repo1.maven.org/maven2/org/typelevel/otel4s-core-trace_2.12/maven-metadata.xml`
  → **HTTP 404**, and likewise `otel4s-core-trace_sjs1_2.12`. There is no
  2.12 artifact to depend on. It was never published, not dropped.

So the choice is between a 2.13/3 module and no module. Narrowing
`crossScalaVersions` on the otel4s project alone is what sbt supports and what
the plan does.

**A consequence worth writing down for whoever reads this next:** the
`val _ = …` double-assignment hazard that bit this repo on 2.12 does not apply
here. There is no 2.12 axis in this module. Do not copy 2.12 warning-shape
workarounds into it, and do not assume a warning seen on 2.12 elsewhere in the
build has a counterpart here.

**Scala.js is in scope.** otel4s publishes `_sjs1_2.13` and `_sjs1_3` at 1.0.1
(HTTP 200 on each jar), and `core-trace`/`core-common` are
`crossProject(JVMPlatform, JSPlatform, NativePlatform)` upstream
(`otel4s/build.sbt:150`, `:204`). The module cross-builds JVM and JS like the
rest of this repo.

---

## The load-bearing design decision: what plays `Dom`/`Cod`

`Aspect.Weave[F, Dom, Cod, A]` needs `Dom` and `Cod` at kind `* -> *`. natchez
hands us `natchez.TraceableValue[A]` for free. **otel4s ships nothing of that
shape.** The research established this by reading every candidate:

| candidate | kind | why it does not work |
| --- | --- | --- |
| `Attribute.From[-Value, Key]` (`Attribute.scala:82`) | `(*, *) -> *` | two parameters; wrong kind |
| `Attribute.Make[A, Key]` (`Attribute.scala:131`) | `(*, *) -> *` | two parameters, **and** both `const` overloads bake the attribute *name* into the instance (`:164`, `:193`) |
| `Attributes.Make[-A]` (`Attributes.scala:141-143`) | `* -> *` ✓ | name still baked in, and upstream ships essentially no instances — see below |
| `AttributeKey.KeySelect[A]` (`AttributeKey.scala:118`) | `* -> *` ✓ | not a conversion at all — it enumerates the legal attribute value types and nothing more |

### Why `Attributes.Make` is not reused

`Attributes.Make[-A] { def make(a: A): Attributes }` is the only upstream type
class at the right kind, and it is the obvious reuse candidate, so its absence
from the design needs an explicit reason rather than silence.

1. **It takes no parameter name.** `make(a: A): Attributes` has to know, inside
   the instance, what the attribute will be called. natchez keeps the name at
   the *call site* — `Trace[F].put(fields: (String, TraceValue)*)` — which is
   what lets `TraceParamsOps.asTraceParams` build `Alg.method.param` from the
   `Advice`. An instance that bakes the name in is not a type class, it is a
   per-call-site constant wearing one.
2. **Upstream ships almost no instances for it.** `object Make extends
   MakeCompanion`, and on Scala 2 the `MakeCompanion` in
   `core/common/src/main/scala-2/.../AttributesScalaVersionCompanion.scala` is
   **empty** — the whole file is a license header plus two empty traits. The
   Scala 3 companion covers only values that are already `Attribute`s. There is
   no `Make[String]`, no `Make[Long]`; every domain type would need a
   hand-written instance from the first line of use.
3. **Under the new design nobody needs a name-taking type class at all.**
   Parameter names become *map keys inside one structured value*, not attribute
   names. The type class the module actually wants is
   `A => AnyValue` — no name in the signature, and therefore no reason to
   reach for `Make`'s shape either.

`Attributes.Make` was considered and rejected. It is not an oversight.

### What the spike established

The original D1 was a flat design: `ToAttributes[-A] { def toAttributes(name:
String, value: A): Attributes }`, with every parameter becoming its own
top-level attribute keyed `Alg.method.param`. It was replaced because a spike
demonstrated that otel4s 1.0.1 supports **structured** attribute values, end to
end, and that the structured form is measurably better. The spike report is
`.superpowers/sdd/spike-anyvalue-attributes.md`. Its load-bearing results:

1. **otel4s 1.0.1 has structured attribute values.**
   `AttributeType.AnyValue` exists (`AttributeType.scala:38`), `KeySelect`
   includes `implicit val anyValueKey: KeySelect[AnyValue]`
   (`AttributeKey.scala:142-143`), and `AnyValue` is a full recursive tree:
   `StringValue`, `BooleanValue`, `LongValue`, `DoubleValue`, `ByteArrayValue`,
   `SeqValue(Seq[AnyValue])`, `MapValue(Map[String, AnyValue])` and
   `EmptyValue` (`AnyValue.scala:56-127`).
2. **It survives the oteljava round trip losslessly.** A span carrying
   `Attribute[AnyValue]("params", AnyValue.map(…))` reads back out of
   `otel4s-oteljava-trace-testkit` as `AnyValue.MapValue` with typed leaves. On
   the Java side it is a real `io.opentelemetry.api.common.KeyValueList` whose
   `getValue` is a `List<KeyValue>` of typed `Value`s — **not** a stringified
   blob. The JSON-looking text is only `Value.asString`/`toString`, never the
   storage. Arbitrary nesting works (map-in-map-in-map, map-inside-seq,
   heterogeneous seqs, `EmptyValue`, `ByteArrayValue`), and flat attributes sit
   on the same span with no interference.
3. **It reaches the OTLP wire as native structure.** Exported through
   `OtlpJsonLoggingSpanExporter`, the attribute is a `kvlistValue` containing
   nested `kvlistValue`/`arrayValue`/`intValue`/`doubleValue`/`boolValue`/
   `bytesValue`, and `{}` for `EmptyValue` — the OTLP `AnyValue` protobuf shape.
4. **Both conversion sites preserve it**, in `otel4s-oteljava-common_3:1.0.1`
   (read from the published sources jar).
   `org.typelevel.otel4s.oteljava.AttributeConverters.Explicit.toJavaAttributes`
   has `case AttributeType.AnyValue => builder.put(key.name,
   value.asInstanceOf[AnyValue].toJava)`, with the key made by
   `JAttributeKey.valueKey`; and
   `org.typelevel.otel4s.oteljava.AnyValueConverters.Explicit.toJava`/`toScala`
   are total recursive structural mappings with no lossy branch in either
   direction.
5. **The structured form is better than flat on two measured counts.** A whole
   structured map counts as **one** attribute against
   `SpanLimits.maxNumberOfAttributes` (default 128), so a 20-parameter method
   costs 1 slot rather than 20. And `SpanLimits.maxAttributeValueLength`
   **recurses into the tree** — a string nested inside the map is truncated
   exactly like a top-level one — so the structured form does not smuggle
   unbounded payloads past the limits. Both were observed, not reasoned:
   `setMaxAttributeValueLength(5)` truncated the nested string.

### The decision

**D1 — the module defines its own type class:**

```scala
trait ToAnyValue[-A] {
  def toAnyValue(a: A): AnyValue
}
```

Kind `* -> *`; no name in the signature; it is both `Dom` and `Cod`.

**How a call becomes attributes.** Exactly two attributes per traced call, at
most:

| what | attribute key | attribute value |
| --- | --- | --- |
| all parameters, from every parameter list | `<Alg>.<method>.parameters` | an `AnyValue.map` from parameter name to encoded value |
| the return value | `<Alg>.<method>.returnValue` | the encoded value |

The `parameters` key is Brian's ratified choice. The `returnValue` key mirrors
the natchez module verbatim:
`core/shared/src/main/scala/com/dwolla/tracing/TraceWeaveCapturingInputsAndOutputs.scala:139`
records `s"${fa.algebraName}.${fa.codomain.name}.returnValue"`, and the otel4s
module uses the same spelling so a migrating query keeps working.

Parameter names come from `Aspect.Advice#name`, exactly as `asTraceParams` gets
them today. Two parameters cannot collide in the map: Scala rejects duplicate
parameter names within a single method signature, **including across parameter
lists** — verified by compiling `def f(a: Int)(a: String)` on both 2.13.18
("a is already defined as value a") and 3.3.8 ("a is already defined as
parameter a").

**`TracerInstrumentation` records neither**; it only names the span, as its
natchez counterpart does.

**The compile gotcha that shapes the type class's signature.**
`AnyValue.map(…)` is typed at the precise subtype `AnyValue.MapValue`
(`AnyValue.scala:121-122`), and `KeySelect` is **invariant**, so
`Attribute("params", AnyValue.map(…))` does **not** compile. The error is
actively misleading, because upstream's `@implicitNotFound` string
(`AttributeKey.scala:114-117`) was never updated when `anyValueKey` was added
and omits `AnyValue` entirely:

```
Could not find the `KeySelect` for org.typelevel.otel4s.AnyValue.MapValue. The `KeySelect` is defined for the following types:
String, Boolean, Long, Double, Seq[String], Seq[Boolean], Seq[Long], Seq[Double].
```

Any widening fixes it. **The rule, and the reason the type class is declared
the way it is: the encoder's declared result type is `AnyValue`, never
`AnyValue.MapValue`.** With `def toAnyValue(a: A): AnyValue`, inference at every
`Attribute(...)` call site produces `Attribute[AnyValue]`, `anyValueKey`
resolves, and no caller ever meets the stale message. Anywhere the module builds
an `AnyValue.map(...)` inline — the `parameters` attribute is the one place — it
binds it to an explicitly-`AnyValue`-typed `val` first. This is a task-level
instruction in the plan, not a stylistic preference.

**The `opentelemetry-api` floor: `>= 1.59.0`.** This is the price of the
design and it must be documented where a user will see it — the `ToAnyValue`
scaladoc and the module README, not only here.
`io.opentelemetry.api.common.AttributeType` gained its ninth case, `VALUE`, in
release **1.59.0** (javadoc `@since 1.59.0`, read from the published sources of
`opentelemetry-api-1.63.0`). An application on the `otel4s-oteljava` backend
that pins an older OpenTelemetry Java SDK will fail to link
`AttributeKey.valueKey` inside otel4s's own converter. otel4s 1.0.1 pulls
1.63.0 transitively, so the default is fine; a downstream pin is what breaks.
Note the floor is a **backend** constraint, not a dependency of this module:
`otel4s-core-trace` has no OpenTelemetry Java dependency at all.

**Why contravariant, where `TraceableValue` is invariant.** `A` occurs only in
negative position, so `ToAnyValue[-A]` is well-formed, and upstream's own
`Attributes.Make[-A]` sets the precedent. It buys something concrete: a method
parameter typed `List[String]` or `Vector[Long]` resolves the generic
`ToAnyValue[Seq[A]]` instance, which an invariant type class could not do.
`TraceableValue`'s invariance is an upstream constraint we are not obliged to
inherit. **If contravariance turns out to make implicit resolution ambiguous in
practice, the fallback is invariance plus explicit `List`/`Vector` instances** —
that is a task-level check in the plan, not an assumption here.

### What ships, and what happens with no instance

Instances, in the companion body (high priority):

| instance | encodes to |
| --- | --- |
| `String`, `Boolean`, `Long`, `Double` | `AnyValue.string` / `.boolean` / `.long` / `.double` |
| `Int`, `Short`, `Byte` | `AnyValue.long` after `.toLong` — otel4s has no integral leaf but `Long` |
| `Float` | `AnyValue.double` after `.toDouble` — see D3 |
| `Unit` | `AnyValue.empty` — see D3 |
| `Option[A: ToAnyValue]` | `Some` delegates; `None` is `AnyValue.empty` — see D3 |
| `Seq[A: ToAnyValue]` | `AnyValue.seq` of the encoded elements — generic, see D4 |
| `Map[String, A: ToAnyValue]` | `AnyValue.map` of the encoded values |

In a `LowPriorityToAnyValueInstances` parent trait:

| instance | encodes to |
| --- | --- |
| any `A: Show` | `AnyValue.string(a.show)` |

**A type with no instance is a compile error at the point the `Aspect` is
derived**, exactly as on the natchez side, with the same iterate-until-it-builds
experience. In practice the `Show` fallback means almost everything has one, so
the failure mode is the same as natchez's: silence rather than an error, and the
`Show` rendering rather than the intended one. The scaladoc says so.

**The unique-key hazard the old design carried is gone.** `Attributes` contains
only unique keys (`Attributes.scala:30-31`; the `Monoid`'s `combine` is `x ++ y`,
so `y` wins), which is why the flat design could not offer a generic
`Seq[A]` instance — combining per-element `Attributes` under one name would have
silently recorded only the last element. Under `AnyValue` a sequence is one
value, not several attributes, so the generic instance is correct and ships. See
D4.

### Two type classes for one domain type

A user with both modules on the classpath needs a `TraceableValue[Money]` *and*
a `ToAnyValue[Money]`. That is unavoidable — the two libraries have
incompatible attribute models — and it is worth saying out loud rather than
discovering. A `TraceableValue[A] => ToAnyValue[A]` bridge is conceivable but
is **not** in M16: it would have to map `TraceValue.NumberValue(java.lang.Number)`
onto `Long` or `Double`, which is lossy for `BigDecimal`/`BigInt`, and it would
give the otel4s module a natchez dependency — the precise thing M15 was done to
avoid. If it is ever wanted it belongs in a third, interop module.

---

## The three mismatches, resolved

### 1. There is no `Trace[F].span(name)(fa)`

natchez (`natchez/Trace.scala:39`):
`def span[A](name: String, options: Span.Options = …)(k: F[A]): F[A]`.

otel4s splits this into builder → `SpanOps` → run. `Tracer[F]` is **sealed**
(`Tracer.scala:45`) and exposes `spanBuilder(name): SpanBuilder[F]` (`:83`);
`SpanBuilder#build: SpanOps[F]` (`SpanBuilder.scala:52`); and `SpanOps` gives
`use[A](f: Span[F] => F[A])` (`SpanOps.scala:219`) and
`final def surround[A](fa: F[A]): F[A] = use(_ => fa)` (`:247`). There is also
no ambient `Tracer[F].put` — post-hoc attributes need a `Span[F]` handle, from
`use` or from `Tracer[F].currentSpanOrNoop` (`Tracer.scala:58`).

**Resolution: all three interpreters use the builder path, and the shape change
is an improvement in two of the three.**

- `TracerInstrumentation`:
  `Tracer[F].spanBuilder(name).build.surround(fa.value)`. Constraint profile
  unchanged from natchez — `Tracer[F]` alone, no `Functor`. `surround`/`use`
  impose no constraint on `F` at the call site.
- `TracerWeaveCapturingInputs`: the `parameters` attribute is attached to the
  **builder** (`modifyState(_.addAttributes(…))`, `SpanBuilder.scala:43`,
  `:140`) instead of being `put` inside the span. Two consequences: the
  `Apply[F]` constraint the natchez version needs in order to sequence
  `put *> target` (`TraceWeaveCapturingInputs.scala:117`) **is not needed**, and
  the input attributes exist at span *start*, where a sampler can see them.
  Strictly better on both counts.
- `TracerWeaveCapturingInputsAndOutputs`: the return value genuinely has to be
  attached after the call, so this one uses
  `.build.use { span => target.flatTap(out => span.backend.addAttributes(…)) }`.
  `FlatMap[F]` — the same constraint the natchez version carries.

`.backend` rather than the `Span` itself is deliberate, and is mismatch 2.

### 2. `Tracer.span`, `Span.addAttributes` and `recordException` are macros

On Scala 2 they are blackbox macros
(`scala-2/.../{TracerMacro,SpanMacro,SpanBuilderMacro}.scala`); on Scala 3 they
are `inline` with union-typed varargs
(`AttributeOrIterableOnce = Attribute[?] | IterableOnce[Attribute[?]]`,
`scala-3/.../AttributesScalaVersionCompanion.scala:22`) that has no Scala 2
counterpart. The list is long: `Tracer.span`, `Span.addAttribute(s)`,
`Span.recordException`, `Span.setStatus`, `SpanBuilder.addAttribute(s)`,
`withFinalizationStrategy`, `withSpanKind`, `withStartTimestamp`, `withParent`.

**Resolution: the module never calls any of them, in main sources *or* in
scaladoc.** The sealed, non-macro path is
`spanBuilder → modifyState → build → surround`/`use`, plus
`span.backend.addAttributes` — every one of which is an ordinary method on a
sealed trait taking `immutable.Iterable[Attribute[_]]`
(`SpanBuilder.scala:140`, `Span.scala:166`), which `Attributes` already is
(`Attributes.scala:36-38`).

The scaladoc half of that is not decoration. `build.sbt:41-50` sets
`doctestOnlyCodeBlocksMode := true` and applies `doctestSettings` to every
module that has scaladoc examples, so **every `{{{ }}}` block in this module is
compiled and run on both 2.13 and 3**. A doctest that showed
`Tracer[F].span("x").surround(fa)` would be exercising precisely the macro
asymmetry the module is avoiding. The plan makes this a grep-able acceptance
criterion rather than a habit.

### 3. otel4s records exceptions and sets `StatusCode.Error` automatically

`SpanBuilder.State`'s default finalization strategy is
`SpanFinalizer.Strategy.reportAbnormal` (`SpanBuilder.scala:192`), which does
`recordException(e) |+| setStatus(StatusCode.Error)` on
`Resource.ExitCase.Errored` and `setStatus(StatusCode.Error, "canceled")` on
cancelation (`SpanFinalizer.scala:47-56`). It applies to `use`, `surround` and
`resource` (`SpanOps.scala:113`, `:205`).

**Resolution: M16 adds no error recording of its own, and leaves the default
strategy alone.** This is not a compromise, it is the scope reading:

- **Nothing double-records, because there is nothing to double.** The three
  natchez interpreters being mirrored do not record errors either — none of them
  calls `Trace[F].attachError`. Error recording in this repo is the M6
  `OnRaise`/`RaiseRecorder` work, and that lives in `natchez-tagless-mtl`, which
  M16 does not mirror. Adding exception recording to these three interpreters
  would *create* the double-recording, not avoid it.
- **The behaviour difference is real and favours otel4s, and must be
  documented.** With natchez, a `Throwable` escaping a traced method leaves the
  span with no error marking from these interpreters. With otel4s, the same
  escape is recorded as an exception event and the span status is set to `Error`
  — for free, by the library. Users migrating will see spans they did not see
  before. Say so in the scaladoc.
- **cats-mtl `Raise` errors remain invisible, by a different mechanism than on
  the natchez side.** `reportAbnormal` keys off `Resource.ExitCase`, which only
  observes `MonadCancel` failures. An error carried in a `Handle`/`Raise`
  channel completes *successfully* as far as `F` is concerned, so the span is
  marked OK. M6's docs record the same gap for natchez but the mechanism there
  is "we never called `attachError`"; here it is "the automatic strategy cannot
  see it". **The wording must be rewritten, not copied.** It goes in the module
  README rather than in an interpreter's scaladoc, since closing the gap is a
  future mtl-module milestone's job, not these interpreters'.
- If a user does want to suppress or change the automatic behaviour, the sealed
  escape hatch is `SpanBuilder.State.withFinalizationStrategy`
  (`SpanBuilder.scala:160`) reached through `modifyState` — note the
  `SpanBuilder#withFinalizationStrategy` of the same name is a macro
  (`scala-2/.../SpanBuilderMacro.scala:100`) and is not what to document.
  M16 does not expose a knob for this; a user who needs it writes their own
  `Weave ~> F`, which is four lines.

---

## The module

### Shape

```scala
lazy val otel4sTagless = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("otel4s-tagless"))
  .settings(
    name := "otel4s-tagless",
    // otel4s has never published a 2.12 artifact — see M16's milestone doc.
    crossScalaVersions := Seq(Scala213, "3.3.8"),
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "otel4s-core-trace" % otel4sVersion,
      "org.typelevel" %%% "cats-core" % catsVersion,
      "org.typelevel" %%% "cats-tagless-core" % catsTaglessVersion,
      "org.scalameta" %%% "munit" % munitVersion % Test,
    ),
    mimaPreviousArtifacts := Set.empty,
  )
  .settings(doctestSettings *)
  .dependsOn(taglessCore)
```

`CrossType.Pure`, matching every module added since `core`. `doctestSettings`
because the module has scaladoc examples and they must be compiled.

**Every cross-built dependency uses `%%%`, including test-scope ones.** This is
not cosmetic. Two commits before this milestone, three `%%` test dependencies in
`core`'s shared settings block meant `coreJS` ran **zero** munit tests while
reporting success: the JVM jars type-checked but produced no `.sjsir`, the munit
framework never registered, and dead-code elimination dropped every suite.
Fixed in `106bf17`. `%%` is correct only inside `.jvmSettings`, where there is
no JS artifact to get wrong.

**`mimaPreviousArtifacts := Set.empty` is a carry-forward hazard, not a
resolution.** Five modules already carry it — `tagless-core`,
`raise-aspect-core`, `raise-aspect-laws`, `raise-aspect-macros`,
`natchez-tagless-mtl` — and `otel4s-tagless` will be the sixth. It is not a
"not yet published" marker that expires on its own: nothing suppresses
publishing, so each of these modules **will** be published with a real API at
the next release and MiMa will not catch a breaking change after that. The fix
is `tlVersionIntroduced := Map(…)` in place of `Set.empty`, one decision
covering all six, **needed before the next publish**. M16 records the hazard and
does not fix it; see *Anything a later milestone needs* in
`28-milestone-M15-tagless-core-module.md`.

### Dependencies, verified against the published POMs

`otel4s-core-trace_2.13:1.0.1`'s POM declares, at **compile** scope:
`otel4s-core-common` 1.0.1, `cats-effect-kernel` 3.7.0, `scodec-bits` 1.2.5.
`otel4s-core-common_2.13:1.0.1` declares, at compile scope: `cats-core` 2.13.0,
**`cats-effect` 3.7.0** (the full artifact, not just kernel), `cats-mtl` 1.7.0,
`scodec-bits` 1.2.5, `vault` 3.7.0. `scalac-compat-annotation` is **`provided`**
in `core-trace` and therefore does not come through.

| dependency | otel4s 1.0.1 wants | this repo | verdict |
| --- | --- | --- | --- |
| cats-core | 2.13.0 | 2.13.0 | match |
| cats-mtl | 1.7.0 | 1.7.0 | match |
| cats-effect | 3.7.0 | not pinned in this repo | arrives transitively; the module declares none |
| scodec-bits | 1.2.5 | — | **new to the build**, transitively |
| vault | 3.7.0 | — | **new to the build**, transitively |

`cats-core` and `cats-tagless-core` are declared explicitly because the module
uses them directly (`~>`, `Show`, `syntax.all`, `Aspect`, `Instrument`,
`Instrumentation`), not because they are missing. **The only new direct
dependency in the whole build is `otel4s-core-trace`.** `AnyValue`,
`Attribute`, `AttributeKey` and `Attributes` all live in `otel4s-core-common`,
which `otel4s-core-trace` depends on, so the one coordinate covers the whole
design. `vault` is a transitive the research did not list; it is
`org.typelevel`, cross-published, and harmless.

### Package

`com.dwolla.tracing.otel4s`, with syntax at `com.dwolla.tracing.otel4s.syntax` —
exactly the shape `natchez-tagless-mtl` already uses (`com.dwolla.tracing.mtl`
and `com.dwolla.tracing.mtl.syntax`). It must not be `com.dwolla.tracing`
itself: a downstream application may depend on both modules, and the two
`syntax` package objects would then mix conflicting implicits into one scope.

**One footgun to know about.** Inside `package com.dwolla.tracing.otel4s`, the
bare identifier `otel4s` resolves to *this* package, not to
`org.typelevel.otel4s`. Import upstream types by their full path
(`import org.typelevel.otel4s.AnyValue`) and never write a partially qualified
`otel4s.Something`. On 2.13 this is additionally safe because `-Xsource:3` —
which `TypelevelSettingsPlugin` supplies build-wide, confirmed for `core` on
2.13 during M15 — makes imports absolute.

### The syntax-method name collision, and why the names still match

`com.dwolla.tracing.syntax` and `com.dwolla.tracing.otel4s.syntax` will both
offer `traceWithInputs`, `traceWithInputsAndOutputs` and `instrumentAndTrace` on
`Alg[F]`, through differently-named implicit conversions. A single file that
wildcard-imports **both** will get an ambiguous-implicit-conversion error at the
call site.

That is the right trade. Matching names mean a migrating file changes exactly
one import line and no call sites; the alternative — renaming the otel4s
methods — imposes churn on every user in exchange for supporting a
same-file-two-backends case that is rare and that has an obvious workaround
(import one selectively). The scaladoc says so, in one sentence, on the syntax
package object.

The *class* names do diverge (`TracerInstrumentation`, not
`TraceInstrumentation`), because those are used by direct construction —
`alg.weave.mapK(new TracerWeaveCapturingInputs)` — where two same-named imports
would be a genuine nuisance, and because `Tracer` is what the otel4s type is
actually called.

### The `WeaveKnot` dependency

`.dependsOn(taglessCore)`. None of the three interpreters *calls* `WeaveKnot`;
neither does `core`, which merely ships it. The dependency exists so that a user
of `otel4s-tagless` has the same knot-tying facility a user of
`natchez-tagless` has, without taking on natchez — which is verbatim the reason
M15 was done. Task 6's doctest exercises it, so the dependency is used rather
than merely declared. If Brian prefers strict minimality it is one line to drop,
but then M15's stated purpose goes unmet.

---

## Testing — Q2, ratified: split, with a known coverage asymmetry

`Tracer[F]`, `Span[F]`, `SpanOps[F]`, `SpanBuilder[F]` and `Span.Backend[F]` are
**all sealed**, and their `private[otel4s] trait Unsealed` escape hatches
(`Tracer.scala:223`, `SpanOps.scala:261`, `SpanBuilder.scala:66`,
`Span.scala:202`) are package-private to otel4s. **You cannot hand-roll a
recording `Tracer[F]`** the way this repo hand-rolls a `Trace[F]` in
`InMemorySuite`. Asserting on span names and attributes requires an otel4s
testkit.

Checked against Maven Central on 2026-08-02:

| testkit | latest published | JS? |
| --- | --- | --- |
| `otel4s-oteljava-trace-testkit` | **1.0.1** | **no** — no `_sjs1_` artifact exists |
| `otel4s-sdk-trace-testkit` | **0.19.0** | yes (`_sjs1_2.13`, `_sjs1_3`) |
| `otel4s-testkit` | 0.4.0 | — |

The cross-platform SDK family has **not been released at 1.0.x** —
`otel4s-sdk-trace` and `otel4s-sdk-trace-testkit` both stop at 0.19.0, and
`https://repo1.maven.org/maven2/org/typelevel/otel4s-sdk-trace-testkit_sjs1_2.13/1.0.1/`
is a 404. So at otel4s 1.0.1 **there is no testkit that works on Scala.js.**

**Ratified resolution.** Split the tests:

- **Cross-platform, no testkit.** `ToAnyValue` instances and `asAttributes` are
  pure functions over `AnyValue`/`Attributes`; they need no `Tracer` at all. The
  interpreters get a *transparency* suite over `Tracer.noop` (`Tracer.scala:239`,
  in `core-trace`): an instrumented call returns exactly what the underlying
  call returns, by-name arguments are not forced, and the constraint sets
  resolve. That is real coverage of the parts that are ours, and it runs on JVM
  and JS.
- **JVM only, with a testkit.** Span names, span nesting and recorded
  attributes are asserted with `otel4s-oteljava-trace-testkit % Test` in
  `.jvmSettings`, in a JVM-only test source directory. Both patterns already
  exist in this build: `core` has a JVM-only test dependency on
  `dwolla-otel-natchez` (`build.sbt:112-116`) and a JVM-only test source
  (`core/jvm/src/test/.../TraceInitializationExample.scala`), and
  `raise-aspect-core` has the
  `Test / unmanagedSourceDirectories += … "scala-jvm"` split
  (`build.sbt:164-166`).

**The accepted coverage asymmetry, recorded deliberately.** The module's
headline behaviour — *the right attributes end up on the right span* — is
verified on the JVM and **not** on Scala.js. The reason is availability, not
choice: `otel4s-oteljava-trace-testkit` publishes no `_sjs1_` artifact at any
version, and the cross-platform `otel4s-sdk-trace-testkit` stops at 0.19.0, two
minor versions behind the API this module is built against. The alternatives are
worse — mixing `otel4s-core-trace` 1.0.1 with a 0.19.0 testkit invites an
eviction across a major version boundary, and pinning the whole module to 0.19.0
to get a JS testkit would ship against a pre-1.0 API. On Scala.js the module is
covered for encoding and transparency, and uncovered for span content. Revisit
when `otel4s-sdk-trace-testkit` reaches 1.0.x.

**Assert on the decoded tree, never on JSON text.** `AnyValue.MapValue` wraps a
Scala `Map`, which is unordered, and the spike observed keys coming back in a
different order than they were written. `Value.asString` and every `toString`
in this stack render JSON-looking text; a test that compares that text will pass
locally and fail somewhere else. Read `SpanData#getAttributes` and walk the
`io.opentelemetry.api.common.Value` tree (or convert back to otel4s
`Attributes` and compare `AnyValue`s, which have a lawful `Hash`,
`AnyValue.scala:129`).

**And verify the JS suites actually run.** `+otel4sTaglessJS/test` reporting
success is not evidence that any test executed — see the `%%%` note above.
`show otel4sTaglessJS/Test/definedTests` must be non-empty, and the test count
must be reported per platform.

---

## Decisions (D1–D10)

**D1 — `ToAnyValue[-A]` with `toAnyValue(a: A): AnyValue` is the module's `Dom`
and `Cod`.** All of a call's parameters become **one** attribute keyed
`<Alg>.<method>.parameters`, whose value is an `AnyValue.map` from parameter
name to encoded value; the return value becomes `<Alg>.<method>.returnValue`.
The encoder's declared result type is `AnyValue`, never a subtype, because
`KeySelect` is invariant. Rationale and spike evidence above. This is the
decision everything else in the module is shaped by.

**D2 — no `NotGiven` guards; instance priority is by trait inheritance.**
`ToTraceValue.scala:25-32` and `:40-47` carry six `NotGiven[A =:= …]` guards
each. Those exist for one reason: the `Show`/circe fallbacks live in *our*
traits while the primitive instances live in *upstream's* `TraceableValue`
companion, so the two are at the same implicit priority and collide. We own the
whole of `ToAnyValue`, so the primitives go in the companion object's body and
the fallback goes in a `LowPriorityToAnyValueInstances` parent trait, and
ordinary implicit-priority-by-inheritance resolves it. Saves a dependency on
`scalac-compat-features` and about twelve lines of noise. Proved by compiling,
not argued — see *Evidence*.

**D3 — absence is `AnyValue.empty`, never an omitted entry; `Float` widens to
`Double`.** Two divergences from `ToTraceValue`'s semantics, both because
otel4s's model can express something natchez's cannot:

- **`Unit` and `None` encode to `AnyValue.empty`.** natchez records the strings
  `"()"` and `"None"`; a typed empty value is strictly better than a string that
  looks like data. The alternative considered was *omitting* the map entry (and
  the `returnValue` attribute) entirely, and it was rejected on two grounds.
  First, the type class has no channel for it: `A => AnyValue` is total, and
  adding one would mean `A => Option[AnyValue]`, which forces every instance and
  every nesting site to answer a question it has no business answering — what
  does `Seq` do with an absent element? Second, omission was only attractive
  under the *old* design, where an absent value cost a whole top-level attribute
  slot; as a map entry it costs almost nothing, and `EmptyValue` is precisely
  OTLP's encoding of "no value" (it reaches the wire as `{}`), not a stand-in
  for it. One rule, applied everywhere, no conditionals in the interpreters.
- **A method with no parameters still records `<Alg>.<method>.parameters`, as an
  empty map.** Same rule, no special case: `Attributes(AnyValue(Foo.ping.parameters)=MapValue({}))`.
  The alternative is a second rule whose only benefit is cosmetic, and uniform
  presence is easier for a span consumer to query than conditional presence.
- **`Float` → `Double` via `.toDouble`.** otel4s has no `Float` leaf
  (`AnyValue.scala:56-87`) and no `Attribute.From` for one
  (`Attribute.scala:89-101`); `Long` and `Double` are the only numeric leaves.
  The widening is exact in the IEEE-754 sense and *inexact-looking* in print —
  `0.1f` records as `DoubleValue(0.10000000149011612)`, observed, not predicted.
  The scaladoc says so and points at writing your own instance if a different
  rendering is wanted. The alternative (`_.toString.toDouble`) prints prettily
  by silently changing the value, which is worse in a library.

Also record, for `BigDecimal`/`BigInt`: no instance ships, so they fall to the
`Show` fallback and record as strings. natchez accepted them as `NumberValue`.
This is a real behaviour difference, not a naming one.

**D4 — a generic `ToAnyValue[Seq[A]]` ships, and so does
`ToAnyValue[Map[String, A]]`.** This reverses the old D4, and the reversal is
the point: the flat design could not offer a generic sequence instance because
`Attributes` deduplicates by key, so per-element attributes under one name would
have kept only the last element. `AnyValue.seq` makes a sequence one value, so
the hazard is gone and the natural instance is also the correct one. It composes
to arbitrary depth — `List[List[Int]]` encodes as
`SeqValue([SeqValue([LongValue(1)])])`, verified — and it subsumes the seven
hand-written `Seq[…]` instances the flat design needed.

**D5 — `Show` fallback, no circe fallback** (subject to Q3). Keeps the module's
dependency list at `otel4s-core-trace` + cats + cats-tagless.

**D6 — the module never calls an otel4s macro or `inline` method, including in
doctests.** Grep-checked in the plan.

**D7 — no error recording, and the default finalization strategy is left
alone.** Mismatch 3 above.

**D8 — 2.12 is excluded for this module only, by narrowing
`crossScalaVersions` on this project.** Ruled by Brian, 2026-08-02.

**D9 — no compatibility shims and no changes to any existing module.** M16 is
purely additive. Nothing in `core` is deprecated, aliased or re-pointed.

**D10 — the `opentelemetry-api >= 1.59.0` floor is documented, not enforced.**
`AttributeType.VALUE` does not exist before 1.59.0, so an application on the
`otel4s-oteljava` backend that pins an older OpenTelemetry Java SDK will fail to
link. This module declares no dependency on the Java SDK and cannot enforce the
floor with a version range; the mechanism is documentation, in the `ToAnyValue`
scaladoc and the module README, where a user will actually read it. Ratified by
Brian, 2026-08-02.

---

## Evidence: demonstrated versus argued

**Demonstrated (2026-08-02).** No sbt has been run in this repository for M16.
Two other kinds of demonstration have happened, and both are real: the spike ran
otel4s 1.0.1 against the OpenTelemetry Java SDK 1.63.0 under scala-cli, and the
design sketch below was compiled and executed under scala-cli on both Scala
axes.

- **The whole D1 design compiles and runs on 2.13.18 and 3.3.8.** A scratch
  scala-cli project containing `ToAnyValue` with every instance listed above,
  `WeaveAttributesOps#asAttributes`, all three interpreters, all three syntax
  ops classes and the `syntax` package object was compiled against
  `otel4s-core-trace:1.0.1` and `cats-tagless-core:0.16.5` on **3.3.8**
  (`-Ykind-projector`) and on **2.13.18** (kind-projector 0.13.4, `-Xsource:3`).
  Both compiled with no errors and no warnings beyond the
  `-language:implicitConversions` feature warning that every syntax package in
  this repo produces. Observed values, identical on both versions:

  | expression | result |
  | --- | --- |
  | `ToAnyValue[Float].toAnyValue(0.1f)` | `DoubleValue(0.10000000149011612)` |
  | `ToAnyValue[Unit].toAnyValue(())` | `EmptyValue` |
  | `ToAnyValue[Option[String]].toAnyValue(None)` | `EmptyValue` |
  | `ToAnyValue[List[String]].toAnyValue(List("a"))` | `SeqValue([StringValue(a)])` |
  | `ToAnyValue[Vector[Long]].toAnyValue(Vector(1L))` | `SeqValue([LongValue(1)])` |
  | `ToAnyValue[Money].toAnyValue(Money(500))` (`Show` only) | `StringValue($500)` |
  | `ToAnyValue[Map[String, Int]].toAnyValue(Map("k" -> 1))` | `MapValue({k -> LongValue(1)})` |
  | `ToAnyValue[Seq[Money]].toAnyValue(Seq(Money(1)))` | `SeqValue([StringValue($1)])` |
  | `ToAnyValue[List[List[Int]]].toAnyValue(List(List(1)))` | `SeqValue([SeqValue([LongValue(1)])])` |

  and, through the syntax, for a hand-built `Weave` with two parameter lists:

  ```
  Attributes(AnyValue(Foo.greet.parameters)=MapValue({name -> StringValue(world), times -> LongValue(2), note -> EmptyValue}))
  Attributes(AnyValue(Foo.ping.parameters)=MapValue({}))
  ```

  This settles D2 (the priority ladder resolves `String`, `Int`, `Boolean`,
  `Option[String]`, `List[String]` and a `Show`-only type with no `NotGiven`
  guards and no ambiguity) and the contravariance half of D1 (`List` and
  `Vector` reach the `Seq` instance). The plan still re-asserts both inside the
  real build, because scala-cli is not sbt and does not carry this repo's
  scalacOptions.
- **The spike's round trip** — see *What the spike established* above. Every
  claim there is an observation from the spike's recorded output, including the
  OTLP JSON and the `SpanLimits` behaviour.
- **The `KeySelect` compile error is verbatim**, from the spike's
  `repro/MinRepro.scala`, and both widening forms in `repro/Ascribed.scala`
  compiled.
- **Duplicate parameter names are impossible**, compiled on both axes; see D1.
- Every API signature quoted in this document was read from
  `reference/upstream/otel4s/`, a byte-for-byte vendored subset of otel4s
  `v1.0.1` (see that directory's `README.md` for the provenance table).
- `SpanBuilder.scala:192` sets
  `finalizationStrategy = SpanFinalizer.Strategy.reportAbnormal` as the default;
  `SpanFinalizer.scala:47-56` is the strategy's body. Read directly, not
  inferred from the scaladoc that mentions it.
- The Scala 2 `AttributesScalaVersionCompanion` really does define an empty
  `MakeCompanion` — the whole file is a license header plus two empty traits.
- All five sealed types have `private[otel4s] trait Unsealed` and no public
  escape hatch (grepped across the vendored `trace` package).
- Maven Central, queried directly: `otel4s-core-trace_2.12` and
  `otel4s-core-trace_sjs1_2.12` are 404. `otel4s-oteljava-trace-testkit_2.13`
  is at 1.0.1 with no `_sjs1_` variant published at any version.
  `otel4s-sdk-trace-testkit` (all four platform/version combinations) stops at
  0.19.0, and its `1.0.1/` directory is a 404.
- The published POMs for `otel4s-core-trace_2.13:1.0.1` and
  `otel4s-core-common_2.13:1.0.1`, parsed for scope: the compile-scope
  dependency set in the table above, with `scalac-compat-annotation` at
  `provided`.
- `Tracer.noop`'s span path really is transparent, read rather than assumed:
  `Tracer.noop` delegates to `SpanBuilder.noop` (`Tracer.scala:239-245`), whose
  `modifyState(f) = this` and whose `build.use(f) = f(span)`
  (`SpanBuilder.scala:236-253`). So
  `spanBuilder(n).modifyState(g).build.surround(fa)` reduces to `fa`, which is
  exactly what the cross-platform transparency suite asserts. Note
  `Tracer.noop[F]` and `SpanBuilder.noop[F]` both require `Applicative[F]`.
- `otel4s-oteljava-trace-testkit_2.13:1.0.1`'s **sources jar** was downloaded and
  read: `TracesTestkit.inMemory[F: Async: LocalContextProvider](customize = identity): Resource[F, TracesTestkit[F]]`,
  with `def tracerProvider: TracerProvider[F]`,
  `def finishedSpans: F[List[io.opentelemetry.sdk.trace.data.SpanData]]` and
  `def resetSpans: F[Unit]` on the sealed result. Assertions therefore read the
  **OpenTelemetry Java** `SpanData` model (`getName`, `getAttributes`), or the
  testkit's own `SpanExpectation`/`TraceExpectations` DSL. The spike resolved
  and used this artifact successfully.
- `.github/workflows/ci.yml:59` runs `sbt githubWorkflowCheck`, and `:78`/`:82`
  enumerate per-project target directories — so **adding a module requires
  regenerating and committing the workflow** or CI fails on a check that has
  nothing to do with the code. `.mergify.yml` likewise carries one
  `files~=^<module>/` label rule per module and is regenerated by
  `mergifyGenerate`.

**Argued from source, not compiled — each of these is a task-level check in the
plan, not an assumption:**

- That the design that compiles under scala-cli also compiles under this build's
  scalacOptions, in particular with `-Xfatal-warnings` forced.
- **Where `LocalContextProvider[IO]` comes from.** `TracesTestkit.inMemory`
  needs one, and `LocalContextProvider[F]` is a type alias for
  `org.typelevel.otel4s.context.LocalProvider[F, Context]`. otel4s's own
  examples call `TracesTestkit.inMemory[IO]()` with no extra wiring, which
  implies an implicit instance for `IO`, but the instance was **not** located in
  the 1.0.1 sources. Task 2 must read it, not guess it.
- **Whether `org.typelevel.otel4s.oteljava.AttributeConverters` exposes public
  syntax for reading Java `Attributes` back into otel4s `Attributes`.** The
  spike used a round trip and printed the otel4s form, but the conversion
  *object* it named (`AttributeConverters.Explicit`) is private. Task 2 checks
  whether the public entry point exists; if it does not, assertions walk the
  Java `io.opentelemetry.api.common.Value` tree, which is unambiguously public.
- That `sbt`'s `++ 2.12` skips a project whose `crossScalaVersions` lacks it,
  rather than failing the aggregate build. This is documented sbt ≥ 1.4
  behaviour and the build is on 1.12.13, but it is the mechanism the whole 2.12
  exclusion rests on and CI runs `++ 2.12` over the root aggregate
  (`ci.yml:66`).
- That `otel4s-oteljava-trace-testkit` runs on JDK 8, which `tlJdkRelease :=
  Some(8)` and the CI matrix require. `core` already runs the OpenTelemetry Java
  SDK under the same constraint via `dwolla-otel-natchez`, so the precedent is
  good, but it has not been checked for this artifact. The spike ran on JDK 17.
- Every line of scaladoc: the doctests compile only when the module does.

**Known unverified risk, recorded rather than resolved.** The spike confirmed
the wire format; it did not confirm that observability *backends* render
`kvlistValue` span attributes rather than flattening or dropping them on ingest.
Jaeger, Tempo, Honeycomb, Datadog and whatever collector sits in the path are
each a separate question. This is not a blocker — the flat and structured forms
are one `ToAnyValue` instance apart, and a user who needs flat attributes today
can encode a case class to `AnyValue.string` — but it belongs in the README so
nobody is surprised.

---

## Open questions for Brian

**Q3 — should `ToAnyValue` ship a circe-`Encoder` fallback, mirroring
`ToTraceValue.nonPrimitiveTraceValueViaJson`?** Still open, and the spike
changed what the question is asking.

Under the old flat design, a circe fallback would have meant
`_.asJson.noSpaces` — a stringified JSON blob in a string attribute, which is
exactly the anti-pattern the OpenTelemetry attribute model exists to avoid.
Under `AnyValue` it would instead be a **structural** `Json => AnyValue`
mapping: `JString → AnyValue.string`, `JNumber → long`/`double`, `JBool →
boolean`, `JArray → AnyValue.seq`, `JObject → AnyValue.map`, `JNull →
AnyValue.empty`. That is total, lossless up to number widening, and genuinely
attractive in a way the old version was not — it would let any case class with
an `Encoder` become a properly structured span attribute for free.

The counter-argument is unchanged and still holds: it adds `circe-core` as a
direct dependency of a module whose entire dependency list is currently
`otel4s-core-trace` + cats + cats-tagless, and a user can write the same
`ToAnyValue[A]` from their own `Encoder` in a few lines.

**Current default: no, on YAGNI grounds**, pending Brian's ruling. If the
answer becomes yes, it is a new low-priority instance in
`LowPriorityToAnyValueInstances` (below `Show`, or above it — that is part of
the question) plus one dependency line, and it does not disturb D1.

---

## Acceptance criteria

- [ ] A `crossProject(JVMPlatform, JSPlatform)` / `CrossType.Pure` module exists
      at `otel4s-tagless/` with `crossScalaVersions := Seq("2.13.18", "3.3.8")`,
      one new direct dependency (`otel4s-core-trace`), every cross-built
      coordinate on `%%%`, and `mimaPreviousArtifacts := Set.empty` carrying the
      other five modules' note.
- [ ] `show otel4sTaglessJVM/crossScalaVersions` does **not** contain 2.12, and
      `show coreJVM/crossScalaVersions` (and every other module's) still does.
      `sbt "++ 2.12 natchez-tagless-rootJVM/test"` is green, having skipped the
      new module rather than failed on it.
- [ ] `com.dwolla.tracing.otel4s` contains `ToAnyValue`,
      `TracerInstrumentation`, `TracerWeaveCapturingInputs` and
      `TracerWeaveCapturingInputsAndOutputs`, and
      `com.dwolla.tracing.otel4s.syntax` contains `traceWithInputs`,
      `traceWithInputsAndOutputs`, `instrumentAndTrace` and `asAttributes`.
- [ ] A traced call records **at most two** attributes:
      `<Alg>.<method>.parameters`, an `AnyValue.map` keyed by parameter name,
      and (for `TracerWeaveCapturingInputsAndOutputs`)
      `<Alg>.<method>.returnValue`. Asserted end to end through the testkit on
      the decoded tree, never on JSON text.
- [ ] `grep -rn "AnyValue\.MapValue\|AnyValue\.SeqValue\|AnyValue\.StringValue" otel4s-tagless/src/main`
      returns nothing: no declared type in the module is an `AnyValue` subtype.
- [ ] `grep -rnE "\.span\(|\.addAttribute\(|\.recordException\(|\.setStatus\(|\.withFinalizationStrategy\(|\.withSpanKind\(|\.withStartTimestamp\(|\.withParent\(" otel4s-tagless/src`
      returns nothing except `.backend.addAttributes(` and
      `State#addAttributes` inside `modifyState` — no otel4s macro is called
      anywhere, main sources or scaladoc.
- [ ] The transparency suite (over `Tracer.noop`) and the `ToAnyValue` suite
      run and pass on **both** JVM and JS; the testkit suite runs and passes on
      the JVM. `show otel4sTaglessJS/Test/definedTests` is non-empty and the
      per-platform test counts are recorded.
- [ ] `ToAnyValue` resolves without ambiguity for `String`, `Int`, `Float`,
      `Unit`, `Option[String]`, `List[String]`, `Map[String, Int]` and a
      `Show`-only type, and **fails to compile** for a type with neither an
      instance nor a `Show`. The `NotGiven`-free priority ladder is proved by a
      compiling call, not by argument (D2).
- [ ] The `opentelemetry-api >= 1.59.0` floor appears in `ToAnyValue`'s
      scaladoc **and** in the module README (D10).
- [ ] Doctests compile **and run** on 2.13.18 and 3.3.8;
      `otel4sTaglessJVM/doc` succeeds.
- [ ] Zero new compiler warnings on 2.13.18 and 3.3.8, verified locally with
      `-Xfatal-warnings` forced. CI does not enforce this
      (`sbt-typelevel-settings` 0.8.6 defaults `tlFatalWarnings := false`,
      unoverridden).
- [ ] `.github/workflows/ci.yml` and `.mergify.yml` are regenerated by
      `sbt githubWorkflowGenerate mergifyGenerate` and committed;
      `sbt githubWorkflowCheck` passes.
- [ ] No file outside `otel4s-tagless/`, `build.sbt`,
      `.github/workflows/ci.yml`, `.mergify.yml` and `docs/` is modified.
- [ ] `01-overview-design-and-laws.md` §3.1 names the new module and records the
      2.12 exclusion; M15's status section is updated to say its purpose was
      met.

## Ground rules reminder

- **`RaiseAspect` and `raise-aspect-*` are out of scope.** No `OnRaise`, no
  `RaiseAspect`, no otel4s `RaiseTraceWeaveOps`. If a task drifts there, stop.
- **The encoder's result type is `AnyValue`, never an `AnyValue` subtype.**
- **Do not call an otel4s macro or `inline` method**, in main sources or in a
  doctest.
- **Do not modify `core`** or any other existing module. If M16 appears to
  require it, stop and report — that is a design problem, not a task.
- **Do not add a second otel4s compile dependency.** `otel4s-core-trace` brings
  the attribute model with it; `otel4s-core`, `oteljava-*` and `sdk-*` are
  backends and belong in applications.
- **Cross-built dependencies use `%%%`**, including test-scope ones. `%%` is
  correct only inside `.jvmSettings`.
- Do not re-derive the research's or the spike's findings from memory. Read
  `reference/upstream/otel4s/` and
  `.superpowers/sdd/spike-anyvalue-attributes.md`.
- Never use `--no-verify` or any other hook-bypass flag.
