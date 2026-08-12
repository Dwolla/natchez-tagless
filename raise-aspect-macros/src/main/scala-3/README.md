# raise-aspect-macros — Scala 3 sources

The Scala 3 `DeriveRaise` derivation, adapted from cats-tagless
[`MacroAspect`](https://github.com/typelevel/cats-tagless/blob/v0.16.5/core/src/main/scala-3/cats/tagless/macros/MacroAspect.scala)
and its helpers (`MacroFunctorK`, `DeriveMacros`), at tag `v0.16.5`.

Entry points will be `@experimental`: `Symbol.newClass` is experimental on the
Scala 3 LTS line, so call sites need `@experimental` or `-experimental` (3.4+).

**License:** cats-tagless is Apache-2.0. Code adapted from it must carry an
attribution header naming cats-tagless, the Apache-2.0 license, the upstream
file, and the fact that it was modified — see the header at the top of
`DeriveRaiseMacros.scala` in this directory for the exact form.
