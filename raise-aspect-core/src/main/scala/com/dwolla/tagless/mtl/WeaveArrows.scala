package com.dwolla.tagless.mtl

import cats.arrow.FunctionK
import cats.tagless.aop.Aspect
import cats.~>

/** The forgetful arrow from a woven value back to the underlying effect.
  *
  * Before M12 this object also held the pair of capability transports between
  * `F` and the woven carrier. The fused `RaiseAspect#intercept` never puts a
  * capability on the woven carrier, so there is nothing left to transport, and
  * the `Synthetic[Cod]` those transports needed is gone with them.
  */
object WeaveArrows {

  /** Forget the metadata. Also the no-op interpreter, and the `fk` law L3′
    * erases with.
    */
  def codomainTarget[F[_], Dom[_], Cod[_]]: Aspect.Weave[F, Dom, Cod, *] ~> F =
    FunctionK.liftFunction[Aspect.Weave[F, Dom, Cod, *], F](_.codomain.target)
}
