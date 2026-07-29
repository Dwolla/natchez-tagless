package com.dwolla.tagless.mtl

import cats.tagless.Trivial

/** Produces a `Cod` instance for any type.
  *
  * Used only inside the shell `Weave` that `WeaveArrows.raiseLift` builds. That
  * shell is unwrapped immediately via `codomain.target`, and a raised `F[A]`
  * never yields an `A`, so the synthesized instance is never observable through
  * the public API. Laws L5–L7 pin that claim down.
  */
trait Synthetic[Cod[_]] extends Serializable {
  def apply[A]: Cod[A]
}

object Synthetic {
  def apply[Cod[_]](implicit ev: Synthetic[Cod]): Synthetic[Cod] = ev

  implicit val trivial: Synthetic[Trivial] =
    new Synthetic[Trivial] {
      def apply[A]: Trivial[A] = Trivial.instance[A]
    }
}
