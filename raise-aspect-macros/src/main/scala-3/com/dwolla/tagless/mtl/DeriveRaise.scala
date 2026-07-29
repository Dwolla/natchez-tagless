package com.dwolla.tagless.mtl

import scala.annotation.experimental

/** Derivation entry points for algebras whose methods take `cats.mtl.Raise`
  * capability parameters.
  *
  * ==Experimental on Scala 3==
  *
  * The derivation synthesizes an anonymous class with `quotes.reflect`'s
  * `Symbol.newClass`, which is `@experimental` on the Scala 3 LTS line. Matching
  * upstream cats-tagless, these entry points are therefore annotated
  * `@experimental`, and so must their call sites — including the companion object
  * that declares the instance:
  *
  * {{{
  * trait Bar[F[_]] {
  *   def bar(i: Int)(using R: Raise[F, BarError]): F[String]
  * }
  *
  * object Bar {
  *   import scala.annotation.experimental
  *
  *   @experimental
  *   implicit val barRaiseAspect: RaiseAspect[Bar, TraceableValue, TraceableValue] =
  *     DeriveRaise.aspect[Bar, TraceableValue, TraceableValue]
  * }
  * }}}
  *
  * On Scala 3.4+ the `-experimental` compiler flag is an alternative to the
  * annotation, but this repository targets the 3.3.x LTS line, where that flag does
  * not exist. Instances are not derived implicitly; declare them in the algebra's
  * companion, following cats-tagless convention.
  */
object DeriveRaise:

  /** Derive a [[RaiseAspect]], supporting both `weave` and `mapK`. */
  @experimental
  inline def aspect[Alg[_[_]], Dom[_], Cod[_]]: RaiseAspect[Alg, Dom, Cod] =
    ${ RaiseAspectMacros.aspect[Alg, Dom, Cod] }

  /** Derive just a [[RaiseFunctorK]], when no weaving is needed. */
  @experimental
  inline def functorK[Alg[_[_]]]: RaiseFunctorK[Alg] =
    ${ RaiseAspectMacros.functorK[Alg] }
