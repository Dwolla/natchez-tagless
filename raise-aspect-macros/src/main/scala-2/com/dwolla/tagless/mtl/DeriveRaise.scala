package com.dwolla.tagless.mtl

import scala.language.experimental.macros

/** Derivation entry points for algebras whose methods take `cats.mtl.Raise`
  * capability parameters.
  *
  * Instances are not derived implicitly; declare them in the algebra's companion,
  * following cats-tagless convention:
  *
  * {{{
  * trait Bar[F[_]] {
  *   def bar(i: Int)(implicit R: Raise[F, BarError]): F[String]
  * }
  *
  * object Bar {
  *   implicit val barRaiseAspect: RaiseAspect[Bar, TraceableValue, TraceableValue] =
  *     DeriveRaise.aspect[Bar, TraceableValue, TraceableValue]
  * }
  * }}}
  */
object DeriveRaise {

  /** Derive a [[RaiseAspect]], supporting both `weave` and `mapK`. */
  def aspect[Alg[_[_]], Dom[_], Cod[_]]: RaiseAspect[Alg, Dom, Cod] =
    macro DeriveRaiseMacros.aspect[Alg, Dom, Cod]

  /** Derive just a [[RaiseFunctorK]], when no weaving is needed. */
  def functorK[Alg[_[_]]]: RaiseFunctorK[Alg] =
    macro DeriveRaiseMacros.functorK[Alg]
}
