package com.dwolla.tracing.otel4s.mtl

import cats.Apply
import cats.tagless.aop.Aspect
import cats.~>
import com.dwolla.tagless.mtl.{DeriveRaise, OnRaise, RaiseArrow, RaiseAspect}
import com.dwolla.tracing.otel4s.ToAnyValue

import scala.annotation.experimental

/** A [[com.dwolla.tagless.mtl.RaiseAspect]] with all three of `Dom`, `Cod` and
  * `Err` pinned to `com.dwolla.tracing.otel4s.ToAnyValue` — the shape every
  * otel4s user wants, and the only one `traceWithInputsAndOutputs` can use.
  *
  * It exists so Scala 3 can derive it with a `derives` clause. `derives` needs
  * a ''one-parameter'' type constructor whose companion carries `derived`;
  * `RaiseAspect` takes four parameters, and a type alias that pinned three of
  * them would have no companion to put `derived` on. A trait fixes both.
  *
  * The relationship is one-way and worth knowing: an `AnyValueRaiseAspect[Alg]`
  * ''is'' a `RaiseAspect[Alg, ToAnyValue, ToAnyValue, ToAnyValue]`, so it
  * satisfies `WeaveInterpreter` and the tracing syntax unchanged; the converse
  * is false, and [[AnyValueRaiseAspect.fromRaiseAspect]] is how you cross the
  * other way.
  *
  * Pinning `Cod` is what makes `derives` possible, and it is also the one shape
  * this type does not serve: `traceWithInputs[Trivial]` demands a
  * `RaiseAspect[Alg, ToAnyValue, Trivial, ToAnyValue]`, which an
  * `AnyValueRaiseAspect[Alg]` is not, so an algebra that only derives gets a
  * missing-implicit error there. Serve that shape with a separate
  * four-parameter `given`/`implicit val`
  * (`DeriveRaise.aspect[Alg, ToAnyValue, Trivial, ToAnyValue]`);
  * [[AnyValueRaiseAspect.fromRaiseAspect]] does ''not'' close this gap, since it
  * only converts an instance that is already at the pinned shape.
  *
  * Scala 3 only — `derives` does not exist on Scala 2, and this type has no
  * other purpose. A cross-built algebra therefore cannot use `derives` in its
  * shared sources; that is inherent to the feature, not to this type.
  */
trait AnyValueRaiseAspect[Alg[_[_]]]
    extends RaiseAspect[Alg, ToAnyValue, ToAnyValue, ToAnyValue]

object AnyValueRaiseAspect:
  def apply[Alg[_[_]]](using ev: AnyValueRaiseAspect[Alg]): AnyValueRaiseAspect[Alg] = ev

  /** Narrow an existing `RaiseAspect` at the otel4s shape.
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
      underlying: RaiseAspect[Alg, ToAnyValue, ToAnyValue, ToAnyValue]
  ): AnyValueRaiseAspect[Alg] =
    new AnyValueRaiseAspect[Alg]:
      def intercept[F[_]](af: Alg[F])(
          fk: Aspect.Weave[F, ToAnyValue, ToAnyValue, *] ~> F,
          onRaise: OnRaise[F, ToAnyValue]
      )(using F: Apply[F]): Alg[F] =
        underlying.intercept(af)(fk, onRaise)

      def mapK[F[_], G[_]](af: Alg[F])(arrow: RaiseArrow[F, G, ToAnyValue]): Alg[G] =
        underlying.mapK(af)(arrow)

  /** What a `derives AnyValueRaiseAspect` clause calls.
    *
    * `@experimental` because `DeriveRaise.aspect` is: the derivation
    * synthesizes a class with `quotes.reflect`'s `Symbol.newClass`, which is
    * experimental on the 3.3.x LTS line.
    *
    * The annotation is required wherever `derived` is ''invoked'' from, and a
    * `derives` clause invokes it from a given the compiler synthesizes into the
    * algebra's '''companion object''' — so `@experimental` belongs on the
    * companion, as below, not on the trait. A sibling `@experimental`
    * definition elsewhere in the same file is not enough; with the annotation
    * nowhere at all the `derives` clause itself reports `method derived is
    * marked @experimental and therefore may only be used in an experimental
    * scope`.
    *
    * Annotating the trait instead also compiles, and it is the placement to
    * avoid: it makes the algebra ''type'' experimental, so the annotation goes
    * viral across the algebra's whole consumer surface — an unrelated, untraced
    * `def use[F[_]](v: Validator[F])` then fails with `trait Validator is
    * marked @experimental and therefore may only be used in an experimental
    * scope`. On the companion it reaches only the companion's own members, and
    * every consumer of the algebra type is unaffected. This is still slightly
    * more than the hand-written spelling costs — there the annotation sits on
    * the single `implicit val` (see `Scala3UsageNote`), whereas `derives` has no
    * way to annotate the synthesized given alone — but both leave the algebra
    * type itself clean, and both require `@experimental` at the sites that
    * summon the instance.
    *
    * The one case with no good answer: an algebra that declares no companion at
    * all has nowhere to put the annotation but the trait. Declaring an empty
    * `@experimental object Alg` alongside it avoids the virality.
    *
    * {{{
    *   import cats.Applicative
    *   import cats.mtl.Raise
    *   import cats.syntax.all.*
    *   import com.dwolla.tracing.otel4s.ToAnyValue
    *   import com.dwolla.tracing.otel4s.mtl.AnyValueRaiseAspect
    *   import org.typelevel.otel4s.AnyValue
    *
    *   import scala.annotation.experimental
    *
    *   sealed trait ValidationError extends Product with Serializable
    *   case class TooSmall(i: Int) extends ValidationError
    *
    *   object ValidationError {
    *     implicit val toAnyValue: ToAnyValue[ValidationError] =
    *       ToAnyValue.instance {
    *         case TooSmall(i) => AnyValue.string("too small: " + i.toString)
    *       }
    *   }
    *
    *   // no @experimental here: the algebra type stays usable from ordinary code
    *   trait Validator[F[_]] derives AnyValueRaiseAspect {
    *     def validate(i: Int)(using R: Raise[F, ValidationError]): F[String]
    *   }
    *
    *   // ...it goes here instead, where the synthesized given lands
    *   @experimental
    *   object Validator {
    *     def apply[F[_]: Applicative]: Validator[F] = new Validator[F] {
    *       def validate(i: Int)(using R: Raise[F, ValidationError]): F[String] =
    *         if (i < 0) R.raise(TooSmall(i)) else ("ok:" + i.toString).pure[F]
    *     }
    *   }
    * }}}
    */
  @experimental
  inline def derived[Alg[_[_]]]: AnyValueRaiseAspect[Alg] =
    fromRaiseAspect(DeriveRaise.aspect[Alg, ToAnyValue, ToAnyValue, ToAnyValue])
