# raise-aspect-core sources

Runtime core for `RaiseAspect`, in package `com.dwolla.tagless.mtl`:
`RaisePull`, `RaiseArrow`, `RaiseFunctorK`, `RaiseAspect`, the canonical
`WeaveArrows`, `Synthetic`, `OnRaise`, and `WeaveInterpreter`.

Populated in milestone **M1**; `OnRaise` added in **M10**, `WeaveInterpreter`
in **M11**. See `docs/plans/raise-aspect/`.

This module depends only on cats-core, cats-mtl, and cats-tagless-core — it
must never depend on natchez.
