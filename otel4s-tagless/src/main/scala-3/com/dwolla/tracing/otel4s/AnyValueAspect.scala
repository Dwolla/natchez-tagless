package com.dwolla.tracing.otel4s

import cats.tagless.Derive
import cats.tagless.aop.{Aspect, Instrumentation}
import cats.~>

import scala.annotation.experimental

/** A `cats.tagless.aop.Aspect` with `Dom` and `Cod` pinned to
  * `com.dwolla.tracing.otel4s.ToAnyValue` — the shape
  * `com.dwolla.tracing.otel4s.syntax`'s `traceWithInputsAndOutputs` demands.
  *
  * It exists so Scala 3 can derive it with a `derives` clause. `derives` needs
  * a ''one-parameter'' type constructor whose companion carries `derived`;
  * `Aspect` takes three, which is why upstream cats-tagless offers
  * `derives Instrument` but no `derives Aspect`. Pinning two of them in a trait
  * supplies the missing shape. (Two, not three: the `Err` parameter in
  * `com.dwolla.tracing.otel4s.mtl.AnyValueRaiseAspect` belongs to `RaiseAspect`,
  * and a plain `Aspect` algebra has no `Raise` capability for it to be about.)
  *
  * The relationship is one-way: an `AnyValueAspect[Alg]` ''is'' an
  * `Aspect[Alg, ToAnyValue, ToAnyValue]`, so it satisfies the tracing syntax,
  * `WeaveInterpreter` and everything phrased in terms of `Instrument`; the
  * converse is false, and [[AnyValueAspect.fromAspect]] is how you cross the
  * other way.
  *
  * '''Adding `derives AnyValueAspect` to an algebra that already has a wide
  * instance silently replaces it.''' Because an `AnyValueAspect[Alg]` is the
  * more specific type, the given the `derives` clause synthesizes into the
  * companion outranks a hand-declared
  * `implicit val fooTracingAspect: Aspect[Foo, ToAnyValue, ToAnyValue]`
  * sitting beside it — for an `Aspect` demand, and for an `Instrument` demand
  * too, since `Aspect extends Instrument`. Any custom `weave` behaviour in the
  * hand-written instance (a redacted argument, an extra field) disappears from
  * every `traceWithInputsAndOutputs` and `instrumentAndTrace` call site, with
  * no error and no warning. This is ordinary Scala specificity, not a defect,
  * but it is worth knowing before adding the clause: either delete the
  * hand-written instance, or keep it and skip `derives`. Where you need the
  * hand-written one at a particular call site anyway, lexical scope still
  * outranks implicit scope, so a local `implicit val` wins.
  *
  * Scala 3 only — `derives` does not exist on Scala 2, and this type has no
  * other purpose. A cross-built algebra therefore cannot use `derives` in its
  * shared sources; that is inherent to the feature.
  */
trait AnyValueAspect[Alg[_[_]]] extends Aspect[Alg, ToAnyValue, ToAnyValue]

object AnyValueAspect:
  def apply[Alg[_[_]]](using ev: AnyValueAspect[Alg]): AnyValueAspect[Alg] = ev

  /** Narrow an existing `Aspect` at the otel4s shape.
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
    *
    * All ''three'' of `Aspect`'s members are forwarded, not just the two
    * abstract ones. `instrument` is concrete upstream — `mapK(weave(af))` via
    * `Aspect.Weave.instrumentationK` — but it is concrete precisely so an
    * implementation can replace that derivation, and an implementation that
    * has done so is the interesting case. Forwarding only `weave` and `mapK`
    * would re-derive `instrument` from the default here and silently discard
    * the override, which `instrumentAndTrace` and every other `Instrument`-
    * based call site would then be quietly built on. This is the one place
    * `fromAspect` differs from its
    * `com.dwolla.tracing.otel4s.mtl.AnyValueRaiseAspect` sibling, whose
    * underlying type has no concrete members to lose.
    */
  def fromAspect[Alg[_[_]]](
      underlying: Aspect[Alg, ToAnyValue, ToAnyValue]
  ): AnyValueAspect[Alg] =
    new AnyValueAspect[Alg]:
      def weave[F[_]](af: Alg[F]): Alg[Aspect.Weave[F, ToAnyValue, ToAnyValue, *]] =
        underlying.weave(af)

      def mapK[F[_], G[_]](af: Alg[F])(fk: F ~> G): Alg[G] =
        underlying.mapK(af)(fk)

      override def instrument[F[_]](af: Alg[F]): Alg[Instrumentation[F, *]] =
        underlying.instrument(af)

  /** What a `derives AnyValueAspect` clause calls.
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
    *   import com.dwolla.tracing.otel4s.AnyValueAspect
    *
    *   import scala.annotation.experimental
    *
    *   // no @experimental here: the algebra type stays usable from ordinary code
    *   trait Greeter[F[_]] derives AnyValueAspect {
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
  inline def derived[Alg[_[_]]]: AnyValueAspect[Alg] =
    fromAspect(Derive.aspect[Alg, ToAnyValue, ToAnyValue])
