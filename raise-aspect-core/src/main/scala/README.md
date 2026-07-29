# raise-aspect-core sources

Runtime core for `RaiseAspect`, in package `com.dwolla.tagless.mtl`:
`RaisePull`, `RaiseArrow`, `RaiseFunctorK`, `RaiseAspect`, the canonical
`WeaveArrows`, and `Synthetic`.

Populated in milestone **M1**. See `docs/plans/raise-aspect/`.

This module depends only on cats-core, cats-mtl, and cats-tagless-core — it
must never depend on natchez.
