package com.dwolla.tracing

import cats.tagless.aop.Aspect
import cats.{Applicative, ~>}
import cats.syntax.all.*
import natchez.TraceableValue

/** M14's fixture algebra. Named `Lookup` rather than `Foo`/`Bar` because
  * `ImplicitPrioritizationSpec` already declares top-level `Foo` and `Bar` in
  * this package.
  *
  * Lives in `src/test/scala-3` because Task 2 gives it a `derives` clause,
  * which is a syntax error on the 2.12 and 2.13 axes.
  */
trait Lookup[F[_]]:
  def get(key: String): F[String]

object Lookup:
  def apply[F[_]: Applicative]: Lookup[F] = new Lookup[F]:
    def get(key: String): F[String] = s"v:$key".pure[F]

/** The differential oracle: the instance a user writes by hand today, which is
  * literally the shape `TraceWeaveCapturingInputsAndOutputs`' scaladoc carries
  * as a worked example. `derives TraceableAspect` must agree with it.
  */
object HandWrittenLookupAspect:
  val instance: Aspect[Lookup, TraceableValue, TraceableValue] =
    new Aspect[Lookup, TraceableValue, TraceableValue]:
      def weave[F[_]](af: Lookup[F]): Lookup[Aspect.Weave[F, TraceableValue, TraceableValue, *]] =
        new Lookup[Aspect.Weave[F, TraceableValue, TraceableValue, *]]:
          def get(key: String): Aspect.Weave[F, TraceableValue, TraceableValue, String] =
            Aspect.Weave[F, TraceableValue, TraceableValue, String](
              "Lookup",
              List(List(Aspect.Advice.byValue[TraceableValue, String]("key", key))),
              Aspect.Advice[F, TraceableValue, String]("get", af.get(key))
            )

      def mapK[F[_], G[_]](af: Lookup[F])(fk: F ~> G): Lookup[G] =
        new Lookup[G]:
          def get(key: String): G[String] = fk(af.get(key))
