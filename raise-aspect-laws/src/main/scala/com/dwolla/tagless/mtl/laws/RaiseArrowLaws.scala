package com.dwolla.tagless.mtl
package laws

import cats.laws._
import cats.mtl.Raise

/** Value-level laws about arrows: L4.
  *
  * L5, L6a–L6d and L7 lived here until M12. They were laws about
  * `WeaveArrows.raiseLift`/`raisePull` and the synthesized
  * `Functor[Aspect.Weave[F, Dom, Cod, *]]`, all of which the fused derivation
  * deletes: no capability is ever placed on the woven carrier, so there is
  * nothing to lift, pull, or synthesize a functor for. L7's content survives
  * as a one-line `eq` assertion in `RaiseAspectSuite` — `RaiseAspect.observing`
  * sets `functor = R.functor`, so it is true by construction rather than a
  * property to check. See
  * `docs/plans/raise-aspect/22-milestone-M12-fused-derivation.md`.
  */
object RaiseArrowLaws {

  /** L4 — arrow coherence. Pulling a capability backward and then pushing the
    * raised value forward is the same as raising on the far side directly.
    * Holds for `RaiseArrow.id`, for a genuine carrier change, and for their
    * composites.
    */
  def arrowCoherence[F[_], G[_], Err[_], E, A](
      arrow: RaiseArrow[F, G, Err],
      rg: Raise[G, E],
      e: E
  )(implicit ev: Err[E]): IsEq[G[A]] =
    arrow.fk(arrow.pull(rg).raise[E, A](e)) <-> rg.raise[E, A](e)
}
