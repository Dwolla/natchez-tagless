package com.dwolla.tracing.mtl

import cats.Apply
import cats.tagless.aop.Aspect
import cats.~>
import com.dwolla.tagless.mtl.{OnRaise, RaiseArrow, RaiseAspect}
import natchez.TraceableValue

/** A [[com.dwolla.tagless.mtl.RaiseAspect]] with all three of `Dom`, `Cod` and
  * `Err` pinned to `natchez.TraceableValue` — the shape every natchez user
  * wants, and the only one `traceWithInputsAndOutputs` can use.
  *
  * It exists so Scala 3 can derive it with a `derives` clause. `derives` needs
  * a ''one-parameter'' type constructor whose companion carries `derived`;
  * `RaiseAspect` takes four parameters, and a type alias that pinned three of
  * them would have no companion to put `derived` on. A trait fixes both.
  *
  * The relationship is one-way and worth knowing: a `TraceableRaiseAspect[Alg]`
  * ''is'' a `RaiseAspect[Alg, TraceableValue, TraceableValue, TraceableValue]`,
  * so it satisfies `WeaveInterpreter` and the tracing syntax unchanged; the
  * converse is false, and [[TraceableRaiseAspect.fromRaiseAspect]] is how you
  * cross the other way.
  *
  * Scala 3 only — `derives` does not exist on Scala 2, and this type has no
  * other purpose. A cross-built algebra therefore cannot use `derives` in its
  * shared sources; that is inherent to the feature, not to this type.
  */
trait TraceableRaiseAspect[Alg[_[_]]]
    extends RaiseAspect[Alg, TraceableValue, TraceableValue, TraceableValue]

object TraceableRaiseAspect:
  def apply[Alg[_[_]]](using ev: TraceableRaiseAspect[Alg]): TraceableRaiseAspect[Alg] = ev

  /** Narrow an existing `RaiseAspect` at the natchez shape.
    *
    * Deliberately ''not'' `inline`, even though its only in-library caller is
    * the inline `derived`: an anonymous class written directly in an inline
    * method body is duplicated at every call site, and the compiler warns
    * accordingly. Hoisting it into a `private` class does not work either,
    * because the inline body is spliced at the call site and could not see it.
    * A plain method compiles the anonymous class exactly once, here.
    *
    * It is public because it is independently useful: it is the only way to
    * turn a hand-written or Scala 2-derived instance into the narrow type.
    */
  def fromRaiseAspect[Alg[_[_]]](
      underlying: RaiseAspect[Alg, TraceableValue, TraceableValue, TraceableValue]
  ): TraceableRaiseAspect[Alg] =
    new TraceableRaiseAspect[Alg]:
      def intercept[F[_]](af: Alg[F])(
          fk: Aspect.Weave[F, TraceableValue, TraceableValue, *] ~> F,
          onRaise: OnRaise[F, TraceableValue]
      )(using F: Apply[F]): Alg[F] =
        underlying.intercept(af)(fk, onRaise)

      def mapK[F[_], G[_]](af: Alg[F])(arrow: RaiseArrow[F, G, TraceableValue]): Alg[G] =
        underlying.mapK(af)(arrow)
