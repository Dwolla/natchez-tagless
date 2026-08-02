package com.dwolla.tracing

import cats.tagless.aop.Aspect
import cats.~>
import natchez.TraceableValue

/** A `cats.tagless.aop.Aspect` with `Dom` and `Cod` pinned to
  * `natchez.TraceableValue` — the shape `com.dwolla.tracing.syntax`'s
  * `traceWithInputsAndOutputs` demands.
  *
  * It exists so Scala 3 can derive it with a `derives` clause. `derives` needs
  * a ''one-parameter'' type constructor whose companion carries `derived`;
  * `Aspect` takes three, which is why upstream cats-tagless offers
  * `derives Instrument` but no `derives Aspect`. Pinning two of them in a trait
  * supplies the missing shape. (Two, not three: the `Err` parameter in
  * `com.dwolla.tracing.mtl.TraceableRaiseAspect` belongs to `RaiseAspect`, and
  * a plain `Aspect` algebra has no `Raise` capability for it to be about.)
  *
  * The relationship is one-way: a `TraceableAspect[Alg]` ''is'' an
  * `Aspect[Alg, TraceableValue, TraceableValue]`, so it satisfies the tracing
  * syntax, `WeaveInterpreter` and everything phrased in terms of `Instrument`;
  * the converse is false, and [[TraceableAspect.fromAspect]] is how you cross
  * the other way.
  *
  * Scala 3 only — `derives` does not exist on Scala 2, and this type has no
  * other purpose. A cross-built algebra therefore cannot use `derives` in its
  * shared sources; that is inherent to the feature.
  */
trait TraceableAspect[Alg[_[_]]] extends Aspect[Alg, TraceableValue, TraceableValue]

object TraceableAspect:
  def apply[Alg[_[_]]](using ev: TraceableAspect[Alg]): TraceableAspect[Alg] = ev

  /** Narrow an existing `Aspect` at the natchez shape.
    *
    * Deliberately ''not'' `inline`, even though its only in-library caller is
    * the inline `derived`: an anonymous class written directly in an inline
    * method body is duplicated at every call site and the compiler warns
    * accordingly, while hoisting it into a `private` class fails outright
    * because the inline body is spliced at the call site and could not see it.
    * A plain method compiles the anonymous class exactly once, here.
    *
    * Public because it is independently useful: it is the only way to turn a
    * hand-written or Scala 2-derived `Aspect` into the narrow type.
    */
  def fromAspect[Alg[_[_]]](
      underlying: Aspect[Alg, TraceableValue, TraceableValue]
  ): TraceableAspect[Alg] =
    new TraceableAspect[Alg]:
      def weave[F[_]](af: Alg[F]): Alg[Aspect.Weave[F, TraceableValue, TraceableValue, *]] =
        underlying.weave(af)

      def mapK[F[_], G[_]](af: Alg[F])(fk: F ~> G): Alg[G] =
        underlying.mapK(af)(fk)
