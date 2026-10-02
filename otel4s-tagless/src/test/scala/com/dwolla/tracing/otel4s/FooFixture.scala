package com.dwolla.tracing.otel4s

import cats.Applicative
import cats.FlatMap
import cats.effect.{Ref, Sync}
import cats.syntax.all._
import cats.tagless.aop.Aspect
import cats.~>

/** The algebra every suite in this module is written against.
  *
  * `greet` covers the ordinary case — several parameters, a non-`Unit` return
  * — and `ping()` covers the two edges: an empty parameter list and a `Unit`
  * return value.
  */
trait Foo[F[_]] {
  def greet(name: String, times: Int): F[String]
  def ping(): F[Unit]
}

/** How many times each method of a `Foo.counting` algebra has run.
  *
  * Value equality cannot see an interpreter that runs the underlying call
  * twice: both runs produce the same answer, so `assertEquals` is satisfied
  * either way. An interpreter that needs `FlatMap` can very easily use
  * `fa.codomain.target` more than once, so the count is the only assertion
  * that catches it. `TracerWeaveCapturingInputsAndOutputs` is the module's
  * only such interpreter — the `TracerInstrumentation` and
  * `TracerWeaveCapturingInputs` interpreters need nothing but a `Tracer[F]`,
  * so neither can double-invoke at all.
  *
  * '''It cannot catch one under `Id`''', though, so the "ran exactly once"
  * assertions belong in `SpanContentSpec` (over `IO`) and never in
  * `TracerTransparencySpec`. `Aspect.Advice.apply` takes its `adviceTarget`
  * ''strictly'' — it stores `val target = adviceTarget` — so under `Id` the
  * underlying method has already run, once, by the time the `Weave` exists.
  * Re-reading `fa.codomain.target` then re-reads a `val` and runs nothing.
  * This was checked, not assumed: an interpreter mutated to evaluate its
  * target twice leaves `TracerTransparencySpec` green while failing the
  * counts in the `IO` suite.
  *
  * `Id` has no `Sync[Id]`, so a `FooCallCounts` cannot be constructed for
  * `Id` at all — every `Id`-based call site in this module uses `Foo.plain`
  * instead, and never touches this class.
  */
final class FooCallCounts[F[_]](greetRuns: Ref[F, Int], pingRuns: Ref[F, Int]) {
  def greet: F[Int] = greetRuns.get
  def ping: F[Int] = pingRuns.get

  private[otel4s] def recordGreet(): F[Unit] = greetRuns.update(_ + 1)
  private[otel4s] def recordPing(): F[Unit] = pingRuns.update(_ + 1)
}

object FooCallCounts {
  def of[F[_]: Sync]: F[FooCallCounts[F]] =
    (Ref.of[F, Int](0), Ref.of[F, Int](0)).mapN(new FooCallCounts[F](_, _))
}

object Foo {
  /** A `Foo[F]` with no recording apparatus at all — for call sites that only
    * need a `Foo[F]` value and never inspect call counts (every `Id`-based
    * site in this module: `Id` has no `Sync`, so it cannot host
    * `FooCallCounts`'s `Ref`s, and none of these sites read counts anyway).
    */
  def plain[F[_]: Applicative]: Foo[F] =
    new Foo[F] {
      override def greet(name: String, times: Int): F[String] = (s"hello $name" * times).pure[F]
      override def ping(): F[Unit] = ().pure[F]
    }

  /** A `Foo[F]` that counts each of its own runs into `counts`. `counts.recordGreet()`/
    * `recordPing()` are already properly-deferred `F[Unit]` actions (via `Ref#update`),
    * so sequencing them ahead of the return value only needs `FlatMap[F]` — there is no
    * longer a `suspend` parameter to say how a plain side effect enters `F`, because there
    * is no plain side effect: recording is already an `F`-native action.
    */
  def counting[F[_]: FlatMap](counts: FooCallCounts[F]): Foo[F] =
    new Foo[F] {
      override def greet(name: String, times: Int): F[String] =
        counts.recordGreet().as(s"hello $name" * times)

      override def ping(): F[Unit] =
        counts.recordPing()
    }

  // Hand-written rather than derived, so this fixture needs no
  // cats-tagless-macros dependency on Scala 2 and is identical on both axes.
  //
  // Only the Aspect is implicit. Aspect extends Instrument, so `Instrument[Foo]`
  // resolves to this by subtyping; declaring a second `implicit val
  // fooInstrument: Instrument[Foo] = fooAspect` would make every
  // `Instrument[Foo]` summon ambiguous.
  implicit val fooAspect: Aspect[Foo, ToAnyValue, ToAnyValue] =
    new Aspect[Foo, ToAnyValue, ToAnyValue] {
      override def weave[F[_]](af: Foo[F]): Foo[Aspect.Weave[F, ToAnyValue, ToAnyValue, *]] =
        new Foo[Aspect.Weave[F, ToAnyValue, ToAnyValue, *]] {
          override def greet(name: String, times: Int): Aspect.Weave[F, ToAnyValue, ToAnyValue, String] =
            Aspect.Weave[F, ToAnyValue, ToAnyValue, String](
              "Foo",
              List(List(
                Aspect.Advice.byValue[ToAnyValue, String]("name", name),
                Aspect.Advice.byValue[ToAnyValue, Int]("times", times),
              )),
              Aspect.Advice[F, ToAnyValue, String]("greet", af.greet(name, times))
            )

          override def ping(): Aspect.Weave[F, ToAnyValue, ToAnyValue, Unit] =
            Aspect.Weave[F, ToAnyValue, ToAnyValue, Unit](
              "Foo",
              List(List.empty),
              Aspect.Advice[F, ToAnyValue, Unit]("ping", af.ping())
            )
        }

      override def mapK[F[_], G[_]](af: Foo[F])(fk: F ~> G): Foo[G] =
        new Foo[G] {
          override def greet(name: String, times: Int): G[String] = fk(af.greet(name, times))
          override def ping(): G[Unit] = fk(af.ping())
        }
    }
}
