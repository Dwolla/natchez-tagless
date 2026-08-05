package com.dwolla.tracing

import cats.tagless.Derive
import cats.tagless.aop.{Aspect, Instrumentation}
import cats.{Applicative, ~>}
import cats.syntax.all.*
import natchez.TraceableValue

import scala.annotation.experimental

/** Named `Lookup` rather than `Foo`/`Bar` because
  * `ImplicitPrioritizationSpec` already declares top-level `Foo` and `Bar` in
  * this package.
  *
  * `Lookup` itself takes no `derives` clause — it is the hand-written oracle
  * the derived instance is compared against. The whole file lives in
  * `src/test/scala-3` because its sibling `DerivesLookup` below carries a
  * `derives` clause, which is a syntax error on the 2.12 and 2.13 axes, and
  * because the specs that compare the two need both in scope together.
  */
trait Lookup[F[_]]:
  def get(key: String): F[String]

object Lookup:
  def apply[F[_]: Applicative]: Lookup[F] = new Lookup[F]:
    def get(key: String): F[String] = s"v:$key".pure[F]

/** The differential oracle: the instance a user writes by hand today, which is
  * literally the shape `TraceWeaveCapturingInputsAndOutputs`' scaladoc carries
  * as a worked example. `derives TraceableAspect` must agree with it.
  *
  * It overrides `instrument` — `Aspect` declares three members, not two, and
  * leaves `instrument` concrete precisely so an implementation can replace the
  * default weave-then-mapK derivation. An oracle that took the default could
  * not tell a wrapper that ''delegates'' `instrument` from one that silently
  * re-derives it, because both produce the same answer; the override is what
  * gives `TraceableAspectSpec` something to discriminate with. The distinctive
  * algebra and method names below are answers `Aspect`'s default cannot
  * produce.
  */
object HandWrittenLookupAspect:
  val instance: Aspect[Lookup, TraceableValue, TraceableValue] =
    new Aspect[Lookup, TraceableValue, TraceableValue]:
      override def instrument[F[_]](af: Lookup[F]): Lookup[Instrumentation[F, *]] =
        new Lookup[Instrumentation[F, *]]:
          def get(key: String): Instrumentation[F, String] =
            Instrumentation(af.get(key), "HandWrittenLookup", "lookedUp")

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

/** Structurally identical to `Lookup`, so the two
  * can be compared directly and the expected span history differs only in the
  * algebra name.
  *
  * `@experimental` is required, and where it goes matters: a `derives` clause
  * invokes `derived` from a given the compiler synthesizes into the algebra's
  * companion object, so the annotation belongs on the companion — not the
  * trait, which stays unannotated so the algebra type itself is usable from
  * ordinary code. `TraceableAspect.derived` is `@experimental` because the
  * whole of cats-tagless's `object Derive` is, and the 3.3.x LTS line has no
  * `-experimental` flag to opt out with.
  */
trait DerivesLookup[F[_]] derives TraceableAspect:
  def get(key: String): F[String]

@experimental
object DerivesLookup:
  def apply[F[_]: Applicative]: DerivesLookup[F] = new DerivesLookup[F]:
    def get(key: String): F[String] = s"v:$key".pure[F]

/** Deliberately declares ''both'' instances in one companion — the narrow one
  * the `derives` clause synthesizes, and a hand-declared wide one — so
  * [[TraceableAspectSpec]] can pin which of them implicit search picks.
  *
  * That situation is what a user creates by adding `derives TraceableAspect`
  * to an algebra that already carries the companion
  * `implicit val fooTracingAspect: Aspect[Foo, TraceableValue, TraceableValue]`
  * this repository's own scaladoc recommends (see
  * `TraceWeaveCapturingInputsAndOutputs`). Mirrors
  * `com.dwolla.tracing.mtl.Coexisting`, which pins the same hazard for
  * `TraceableRaiseAspect`.
  */
trait Coexisting[F[_]] derives TraceableAspect:
  def get(key: String): F[String]

@experimental
object Coexisting:
  implicit val wide: Aspect[Coexisting, TraceableValue, TraceableValue] =
    Derive.aspect[Coexisting, TraceableValue, TraceableValue]
