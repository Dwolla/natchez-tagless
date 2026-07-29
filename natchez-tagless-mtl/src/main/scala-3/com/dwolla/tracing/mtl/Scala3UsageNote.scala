package com.dwolla.tracing.mtl

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
  *   import natchez.TraceableValue
  *
  *   import scala.annotation.experimental
  *
  *   sealed trait ValidationError extends Product with Serializable
  *   final case class TooSmall(i: Int) extends ValidationError
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
  *     // One instance serves every F: weave/mapK are separately polymorphic per call.
  *     @experimental
  *     implicit val raiseAspect: RaiseAspect[Validator, TraceableValue, TraceableValue] =
  *       DeriveRaise.aspect[Validator, TraceableValue, TraceableValue]
  *   }
  * }}}
  */
private[mtl] object Scala3UsageNote
