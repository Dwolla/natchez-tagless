package com.dwolla.tracing.otel4s.mtl

import cats.Apply
import cats.tagless.aop.Aspect
import cats.~>
import com.dwolla.tagless.mtl.{DeriveRaise, OnRaise, RaiseAspect}
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
  *
  * Without derives:
  *
  * {{{
  *   import cats.Applicative
  *   import cats.mtl.Raise
  *   import cats.syntax.all._
  *   import com.dwolla.tagless.mtl.{DeriveRaise, RaiseAspect}
  *   import com.dwolla.tracing.otel4s.ToAnyValue
  *   import org.typelevel.otel4s.AnyValue
  *
  *   import scala.annotation.experimental
  *
  *   sealed trait ValidationError extends Product with Serializable
  *   case class TooSmall(i: Int) extends ValidationError
  *
  *   trait Validator[F[_]] {
  *     def validate(i: Int)(using R: Raise[F, ValidationError]): F[String]
  *   }
  *
  *   object Validator {
  *     def apply[F[_]: Applicative]: Validator[F] = new Validator[F] {
  *       def validate(i: Int)(using R: Raise[F, ValidationError]): F[String] =
  *         if (i < 0) R.raise(TooSmall(i))
  *         else ("ok:" + i.toString).pure[F]
  *     }
  *
  *     // The derivation below summons Err[E] (here ToAnyValue[ValidationError])
  *     // per raise parameter at the derivation site first (see DeriveRaise's own
  *     // scaladoc). If no instance is available there, resolution falls back to
  *     // one of validate's own `using` parameters, provided its declared type is
  *     // a subtype of the needed one — no derivation, no companion scope, no
  *     // chaining. Only if neither resolves does the derivation fail, with a
  *     // diagnostic naming the method and the missing error type.
  *     given ToAnyValue[ValidationError] =
  *       ToAnyValue.instance {
  *         case TooSmall(i) => AnyValue.string("too small: " + i.toString)
  *       }
  *
  *     // One instance serves every F: intercept is separately polymorphic per call.
  *     @experimental
  *     given RaiseAspect[Validator, ToAnyValue, ToAnyValue, ToAnyValue] =
  *       DeriveRaise.aspect[Validator, ToAnyValue, ToAnyValue, ToAnyValue]
  *   }
  * }}}
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

  /** What a `derives AnyValueRaiseAspect` clause calls.
    *
    * `@experimental` on the Scala 3.3 LTS line. Put `@experimental` on the
    * algebra's '''companion object''', not on the trait, or the annotation
    * spreads to every consumer of the algebra type. The natchez-tagless README,
    * "Scala 3: derives and @experimental", has the full explanation.
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
