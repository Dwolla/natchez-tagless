package com.dwolla.tagless

import cats.*
import cats.tagless.aop.*
import cats.tagless.syntax.all.*

/** Utility object for creating a fixed-point instance of a parametric algebra, allowing for transformations to be
 * applied before the instance is fully constructed. It supports instrumenting method calls or weaving cross-cutting
 * aspects into the algebra.
 *
 * Without this kind of utility, transformations applied to an instance will not be used when one of the instance's
 * methods calls another method on the instance.
 */
object WeaveKnot {
  def instrument[Alg[_[_]], F[_]](constructor: Eval[Alg[F]] => Alg[F],
                                  transformation: Instrumentation[F, *] ~> F)
                                 (implicit I: Instrument[Alg]): Alg[F] =
    WeaveKnot(constructor)(_.instrument.mapK(transformation))

  def weave[Alg[_[_]], F[_], Dom[_], Cod[_]](constructor: Eval[Alg[F]] => Alg[F],
                                             transformation: Aspect.Weave[F, Dom, Cod, *] ~> F)
                                            (implicit A: Aspect[Alg, Dom, Cod]): Alg[F] =
    WeaveKnot(constructor)(_.weave.mapK(transformation))

  def apply[Alg[_[_]], F[_]](constructor: Eval[Alg[F]] => Alg[F])
                            (transform: Alg[F] => Alg[F]): Alg[F] = {
    lazy val self: Alg[F] = {
      // Capture the transformed instance in an `Eval.later` so that it's available inside
      // the instance. This lets implementation methods call the transformed variant of
      // the other methods on the instance.
      val evalSelfLater = Eval.later(self)

      transform(constructor(evalSelfLater))
    }

    self
  }
}
