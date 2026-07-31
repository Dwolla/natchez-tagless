# raise-aspect-core sources

Runtime core for `RaiseAspect`, in package `com.dwolla.tagless.mtl`:
`RaisePull`, `RaiseArrow`, `RaiseFunctorK`, `RaiseAspect`, `WeaveArrows`
(now a single arrow, `codomainTarget`), `OnRaise`, and `WeaveInterpreter`.

Populated in milestone **M1**; `OnRaise` added in **M10**, `WeaveInterpreter`
in **M11**. `RaiseAspect#weave` and `mapK` fused into `intercept` in **M12**,
which deleted `Synthetic` and `WeaveArrows`' capability transports along with
the synthesized-`Functor` defect they existed to work around. See
`docs/plans/raise-aspect/`.

This module depends only on cats-core, cats-mtl, and cats-tagless-core — it
must never depend on natchez.
