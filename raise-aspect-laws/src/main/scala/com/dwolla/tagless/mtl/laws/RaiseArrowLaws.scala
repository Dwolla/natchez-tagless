package com.dwolla.tagless.mtl
package laws

import cats.laws._
import cats.mtl.Raise

/** Value-level laws about arrows: L4.
  *
  * L5–L7 have no value-level content here: the fused derivation never places
  * a capability on the woven carrier, so there is nothing to lift, pull, or
  * synthesize a `Functor` for. L7's content survives as a one-line `eq`
  * assertion in `RaiseAspectSuite` — `RaiseAspect.observing` sets
  * `functor = R.functor`, true by construction rather than a property to
  * check. See ARCHAEOLOGY.md for how this module got here.
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
