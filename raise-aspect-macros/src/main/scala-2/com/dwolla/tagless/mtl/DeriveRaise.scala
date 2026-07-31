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
  *   implicit val barRaiseAspect: RaiseAspect[Bar, TraceableValue, TraceableValue, TraceableValue] =
  *     DeriveRaise.aspect[Bar, TraceableValue, TraceableValue, TraceableValue]
  * }
  * }}}
  *
  * `Err` is the per-error-type evidence type class: for every `Raise[F, E]`
  * capability parameter the derived code encounters, it summons an `Err[E]`
  * at the derivation site and carries it through so a hook observing a raised
  * error can render it through that type class instead of `toString`. Use
  * `cats.tagless.Trivial` for `Err` to opt out; its universal instance makes
  * the constraint vacuous.
  *
  * `Dom`, `Cod`, and `Err` are resolved at the derivation site first; if that
  * fails, resolution falls back to one of the generated method's own
  * `implicit` parameters, provided its declared type is a subtype of the
  * needed one. This is a plain subtype check, not implicit search — no
  * derivation, no companion scope, no chaining — so a polymorphic or
  * context-bound method (`def poly[A: Render](a: A)`) can supply its own
  * instance even though the derivation site never sees a concrete type to
  * summon against.
  *
  * Because derivation-site resolution runs first, an instance that later
  * becomes available at the derivation site (e.g. a conforming `implicit val`
  * added to the companion) silently takes over from a method-local instance
  * the method was previously relying on — there is no diagnostic marking the
  * switch. Two of the method's own implicit parameters conforming to the
  * needed type is an error (`ambiguous method-local implicits for ...`,
  * naming both); a conforming parameter that isn't declared `implicit` gets a
  * hint naming it, rather than the plain missing-instance message.
  */
object DeriveRaise {

  /** Derive a [[RaiseAspect]], supporting both `intercept` and `mapK`. */
  def aspect[Alg[_[_]], Dom[_], Cod[_], Err[_]]: RaiseAspect[Alg, Dom, Cod, Err] =
    macro DeriveRaiseMacros.aspect[Alg, Dom, Cod, Err]

  /** Derive just a [[RaiseFunctorK]], when no weaving is needed. */
  def functorK[Alg[_[_]], Err[_]]: RaiseFunctorK[Alg, Err] =
    macro DeriveRaiseMacros.functorK[Alg, Err]
}
