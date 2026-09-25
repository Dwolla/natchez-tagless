# raise-aspect

`RaiseAspect` — cats-tagless `Aspect` support for tagless-final algebras whose
methods take a `cats.mtl.Raise[F, E]` capability parameter — and its Scala 2 and
Scala 3 derivation (`DeriveRaise`), in package `com.dwolla.tagless.mtl`. The
runtime core is `RaiseAspect`, `WeaveArrows`, `OnRaise`, `DefaultOnRaise`,
`RaiseRecorder`, and `WeaveInterpreter`.

This module depends only on cats-core, cats-mtl, and cats-tagless-core. It must
never depend on a tracing backend, natchez included: `natchez-tagless-mtl` and
`otel4s-tagless-mtl` both build on it.

Laws and the discipline test kit live in the separate `raise-aspect-laws`
module; see its `LAWS.md`.

## Derivation sources

`src/main/scala-2/com/dwolla/tagless/mtl/DeriveRaiseMacros.scala` is adapted
from cats-tagless's Scala 2
[`DeriveMacros`](https://github.com/typelevel/cats-tagless/blob/v0.16.5/macros/src/main/scala-2/cats/tagless/DeriveMacros.scala)
(the `aspect` derivation), and
`src/main/scala-3/com/dwolla/tagless/mtl/DeriveRaiseMacros.scala` from its
Scala 3
[`MacroAspect`](https://github.com/typelevel/cats-tagless/blob/v0.16.5/core/src/main/scala-3/cats/tagless/macros/MacroAspect.scala)
and its helper `DeriveMacros`, both at tag `v0.16.5`. cats-tagless is
Apache-2.0: code adapted from it must carry an attribution header naming
cats-tagless, the license, the upstream file, and the fact that it was
modified. The headers on those two files show the exact form.

Both Scala 2 axes (2.12 and 2.13) share the `scala-2` tree; version-guard only
where the reflect APIs force it.

The Scala 3 entry points are `@experimental`: `Symbol.newClass` is
experimental on the Scala 3 LTS line, so call sites need `@experimental`, or
the `-experimental` compiler flag on Scala 3.4+.
