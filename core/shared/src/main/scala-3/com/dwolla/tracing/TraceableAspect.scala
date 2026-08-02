package com.dwolla.tracing

import cats.tagless.Derive
import cats.tagless.aop.Aspect
import cats.~>
import natchez.TraceableValue

import scala.annotation.experimental

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

  /** What a `derives TraceableAspect` clause calls.
    *
    * `@experimental` because cats-tagless's `object Derive` is annotated in its
    * entirety. The annotation is required wherever `derived` is ''invoked''
    * from, and a `derives` clause invokes it from a given the compiler
    * synthesizes into the algebra's '''companion object''' — so `@experimental`
    * belongs on the companion, as below, not on the trait. A sibling
    * `@experimental` definition elsewhere in the same file is not enough; with
    * the annotation nowhere at all the `derives` clause itself reports `method
    * derived is marked @experimental and therefore may only be used in an
    * experimental scope`. On Scala 3.4+ the `-experimental` compiler flag is an
    * alternative; this repository targets the 3.3.x LTS line, where that flag
    * does not exist.
    *
    * Annotating the trait instead also compiles, and it is the placement to
    * avoid: it makes the algebra ''type'' experimental, so the annotation goes
    * viral across the algebra's whole consumer surface — an unrelated, untraced
    * `def use[F[_]](v: Greeter[F])` would then fail with `trait Greeter is
    * marked @experimental and therefore may only be used in an experimental
    * scope`. On the companion it reaches only the companion's own members, and
    * every consumer of the algebra type is unaffected. This is still slightly
    * more than the hand-written spelling costs — there the annotation sits on
    * a single `implicit val`, whereas `derives` has no way to annotate the
    * synthesized given alone, so `@experimental object Greeter` makes ''every''
    * companion member experimental — but both leave the algebra type itself
    * clean.
    *
    * The one case with no good answer: an algebra that declares no companion at
    * all has nowhere to put the annotation but the trait. Declaring an empty
    * `@experimental object Alg` alongside it avoids the virality.
    *
    * {{{
    *   import cats.Applicative
    *   import cats.syntax.all.*
    *   import com.dwolla.tracing.TraceableAspect
    *
    *   import scala.annotation.experimental
    *
    *   // no @experimental here: the algebra type stays usable from ordinary code
    *   trait Greeter[F[_]] derives TraceableAspect {
    *     def greet(name: String): F[String]
    *   }
    *
    *   // ...it goes here instead, where the synthesized given lands
    *   @experimental
    *   object Greeter {
    *     def apply[F[_]: Applicative]: Greeter[F] = new Greeter[F] {
    *       def greet(name: String): F[String] = ("hello, " + name).pure[F]
    *     }
    *   }
    * }}}
    */
  @experimental
  inline def derived[Alg[_[_]]]: TraceableAspect[Alg] =
    fromAspect(Derive.aspect[Alg, TraceableValue, TraceableValue])
