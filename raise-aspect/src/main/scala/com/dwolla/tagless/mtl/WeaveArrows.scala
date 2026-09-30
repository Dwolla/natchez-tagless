package com.dwolla.tagless.mtl

import cats.arrow.FunctionK
import cats.tagless.aop.Aspect
import cats.~>

/** The forgetful arrow from a woven value back to the underlying effect.
  *
  * `RaiseAspect#intercept` never puts a capability on the woven carrier, so
  * this is the only arrow needed: there is nothing else to transport.
  */
object WeaveArrows {

  /** Forget the metadata. Also the no-op interpreter, and the `fk` law L3′
    * erases with.
    */
  def codomainTarget[F[_], Dom[_], Cod[_]]: Aspect.Weave[F, Dom, Cod, *] ~> F =
    FunctionK.liftFunction[Aspect.Weave[F, Dom, Cod, *], F](_.codomain.target)
}
