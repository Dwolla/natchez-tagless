# Upstream reference material — otel4s

Read-only reference copies of selected [otel4s][repo] sources, kept here so the M16
otel4s-module milestone can be checked against the real upstream code instead of
against recollection. This is a **separate upstream** from the `cats-tagless` material
at `reference/upstream/{core,laws,macros}` and the `cats-mtl` material at
`reference/upstream/cats-mtl/`; all three live in distinct subtrees so they aren't
confused with one another. Package paths also don't collide: this tree is entirely
`org.typelevel.otel4s.*`.

**Nothing in this directory is compiled.** It is not a source directory of any sbt
project: `build.sbt` declares projects rooted at `core/`, `scalacache/`,
`raise-aspect-core/`, `raise-aspect-laws/`, `raise-aspect-macros/`,
`natchez-tagless-mtl/`, and `buildInfoForTests/`, and nothing adds `reference/` to
`unmanagedSourceDirectories` (verified by grepping `build.sbt` and `project/` for
`reference`). The nested `core/{common,trace}/src/main/scala*` paths below mirror the
upstream layout only — they are inert here. Do not wire them into the build; if you
need this code, adapt it into our own namespace (see *License* below).

## Provenance

| | |
| --- | --- |
| Source repo | <https://github.com/typelevel/otel4s> |
| Tag | `v1.0.1` |
| Commit SHA | `f34c324851e079d0d6fb2c47064c6c9e04cf2c01` |
| Commit date | 2026-06-19 |
| `build.sbt` version confirmation | `ThisBuild / tlBaseVersion := "1.0"` at that commit (`build.sbt:3`) |
| Maven Central confirmation | `<latest>1.0.1</latest>` in `maven-metadata.xml` for `otel4s-core-trace_2.13`, `_3`, `_sjs1_2.13`, and `_sjs1_3` |
| License | Apache License 2.0 (see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE)) |

Reproduce with:

```
git clone --depth 1 --branch v1.0.1 https://github.com/typelevel/otel4s
```

otel4s is **not** currently a dependency of this project; `build.sbt` was deliberately
left untouched during the M16 research phase. The clone was made in a throwaway
scratch directory and deleted afterwards. Every file here was verified byte-for-byte
against that clone with `diff` before the clone was removed.

Do not edit these files — if they drift from upstream they stop being useful as a
reference. To refresh, re-clone at a new tag and update this table.

## Contents

### 1. The attribute model

`core/common/src/main/scala/org/typelevel/otel4s/`

- `Attribute.scala` — `Attribute[A]` (key + value), and the two conversion type
  classes: `Attribute.From[-Value, Key]` (value coercion, **two** type parameters) and
  `Attribute.Make[A, Key]` (value → `Attribute`, key name baked in, **two** type
  parameters). This is the file that answers "does otel4s ship a `TraceableValue`
  equivalent?" — see `.superpowers/sdd/m16-otel4s-research.md` §3.
- `AttributeKey.scala` — `AttributeKey[A]` and `AttributeKey.KeySelect[A]`, the
  single-parameter type class enumerating the eight legal attribute value types plus
  `AnyValue`.
- `AttributeType.scala` — the closed set of attribute value types.
- `Attributes.scala` — the `Attributes` collection, plus `Attributes.Make[-A]` (the one
  *single*-parameter conversion type class otel4s ships).
- `AnyValue.scala` — referenced by `AttributeType.AnyValue`; copied so the others read
  standalone.

`core/common/src/main/scala-{2,3}/org/typelevel/otel4s/AttributesScalaVersionCompanion.scala`
— the per-Scala-version companion mix-ins. The Scala 3 one defines the union types
`AttributeOrOption[A]` and `AttributeOrIterableOnce` that the Scala 3 inline `span`/
`addAttributes` overloads take; the Scala 2 one is empty. Copied because the varargs
signatures differ between Scala 2 and Scala 3 and that difference matters to M16.

### 2. The tracing API

`core/trace/src/main/scala/org/typelevel/otel4s/trace/`

- `Tracer.scala` — the `Tracer[F]` type class. Note it is `sealed` and its only
  span-creating member is `spanBuilder(name)`; the familiar `span(name, attrs*)` lives
  in `TracerMacro` (below).
- `TracerProvider.scala`, `TracerBuilder.scala` — how a `Tracer[F]` is obtained.
- `SpanOps.scala` — what `spanBuilder(...).build` returns: `startUnmanaged`,
  `resource`, `use`, `use_`, `surround`. otel4s's span lifecycle is resource-scoped;
  there is no `span(name)(fa)` one-shot.
- `Span.scala` — `Span[F]`, `Span.Backend[F]` (the non-macro attribute/exception/status
  API), `Span.Meta[F]`.
- `SpanBuilder.scala` — `SpanBuilder[F]`, `SpanBuilder.State` and `modifyState`, the
  macro-free path for attaching runtime-computed attributes.
- `SpanFinalizer.scala` — `SpanFinalizer.Strategy` and `Strategy.reportAbnormal`, the
  default error/cancelation recording behavior.
- `StatusCode.scala`, `meta/InstrumentMeta.scala` — small supporting types.

`core/trace/src/main/scala-{2,3}/org/typelevel/otel4s/trace/`

- `TracerMacro.scala`, `SpanMacro.scala`, `SpanBuilderMacro.scala` — both the Scala 2
  blackbox-macro and the Scala 3 inline versions. These carry the public `span`,
  `addAttribute(s)`, `recordException`, and `setStatus` signatures, which differ
  between Scala versions. **Verify those signatures here rather than guessing them.**

The `oteljava`, `sdk`, `semconv`, and `instrumentation` modules were not copied — M16
only targets the pure-Scala `otel4s-core-trace` API surface, and the backends add
nothing to the interpreter design.

## License

otel4s is licensed under the Apache License 2.0, copyright the otel4s maintainers. The
full license text is in [`LICENSE`](LICENSE) and the upstream attribution notice in
[`NOTICE`](NOTICE); the per-file headers are retained verbatim.

This repository (natchez-tagless) is MIT-licensed. Apache-2.0 permits redistribution
and derivative works provided the license, copyright notice, and a statement of changes
are carried along.

**Obligation for adapted code:** if any code from here is adapted into our own
namespace, the adapted file must carry an attribution header naming otel4s, the
Apache-2.0 license, the upstream file, and the fact that it was modified. Verbatim
copies that stay in this directory need no change beyond the header they already have.

[repo]: https://github.com/typelevel/otel4s
