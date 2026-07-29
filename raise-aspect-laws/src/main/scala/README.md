# raise-aspect-laws sources

Law traits and the discipline test kit for `RaiseAspect`, in package
`com.dwolla.tagless.mtl.laws`. Laws L1–L10 are specified in
`docs/plans/raise-aspect/01-overview-design-and-laws.md` §4.

Populated in milestone **M2**, and **frozen** thereafter: later milestones may
add new test files but must not weaken, delete, or modify existing laws. A
session that believes a law is wrong stops and flags it rather than editing it.

Follow the conventions in `reference/upstream/laws/` (cats-tagless `FunctorK`
and `Aspect` law and discipline chains).
