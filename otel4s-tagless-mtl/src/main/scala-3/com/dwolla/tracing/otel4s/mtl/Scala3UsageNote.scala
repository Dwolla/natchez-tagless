package com.dwolla.tracing.otel4s.mtl

/** Carries the Scala 3-specific half of the worked example in this package's own
  * scaladoc: declaring a derived `RaiseAspect` instance needs `@experimental` at the
  * call site on the 3.3.x LTS line. This file exists so that requirement is verified
  * to actually compile (via this repo's doctest setup) rather than only described in
  * prose.
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
private[mtl] object Scala3UsageNote
