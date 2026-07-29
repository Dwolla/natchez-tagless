# raise-aspect-macros — Scala 3 sources

The Scala 3 `DeriveRaise` derivation lands here in milestone **M4**, adapted
from `reference/upstream/core/src/main/scala-3/cats/tagless/macros/MacroAspect.scala`
and its helpers (`MacroFunctorK`, `DeriveMacros`).

Entry points will be `@experimental`: `Symbol.newClass` is experimental on the
Scala 3 LTS line, so call sites need `@experimental` or `-experimental` (3.4+).
See overview §2.

**License:** cats-tagless is Apache-2.0. Code adapted from it must carry an
attribution header naming cats-tagless, the Apache-2.0 license, the upstream
file, and the fact that it was modified. See `reference/upstream/README.md`.
