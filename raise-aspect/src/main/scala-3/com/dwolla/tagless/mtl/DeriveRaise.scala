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
  * `using`/`implicit` parameters, provided its declared type is a subtype of
  * the needed one. This is a plain subtype check, not implicit search — no
  * derivation, no companion scope, no chaining — so a polymorphic or
  * context-bound method (`def poly[A: Render](a: A)`) can supply its own
  * instance even though the derivation site never sees a concrete type to
  * summon against.
  *
  * Because derivation-site resolution runs first, an instance that later
  * becomes available at the derivation site (e.g. a conforming `given` added
  * to the companion) silently takes over from a method-local instance the
  * method was previously relying on — there is no diagnostic marking the
  * switch. Two of the method's own `using`/`implicit` parameters conforming
  * to the needed type is an error (`ambiguous method-local givens for ...`,
  * naming both); a conforming parameter that isn't declared `using`/`implicit`
  * gets a hint naming it, rather than the plain missing-instance message.
  *
  * On Scala 3.4+ the `-experimental` compiler flag is an alternative to the
  * annotation, but this repository targets the 3.3.x LTS line, where that flag does
  * not exist. Instances are not derived implicitly; declare them in the algebra's
  * companion, following cats-tagless convention.
  */
object DeriveRaise:

  /** Derive a [[RaiseAspect]]. */
  @experimental
  inline def aspect[Alg[_[_]], Dom[_], Cod[_], Err[_]]: RaiseAspect[Alg, Dom, Cod, Err] =
    ${ RaiseAspectMacros.aspect[Alg, Dom, Cod, Err] }
