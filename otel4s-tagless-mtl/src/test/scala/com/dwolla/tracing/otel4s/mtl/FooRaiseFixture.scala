package com.dwolla.tracing.otel4s.mtl

import cats.{Applicative, Apply}
import cats.mtl.Raise
import cats.syntax.all._
import cats.tagless.aop.Aspect
import cats.~>
import com.dwolla.tagless.mtl.{OnRaise, RaiseArrow, RaiseAspect}
import com.dwolla.tracing.otel4s.ToAnyValue
import org.typelevel.otel4s.AnyValue

/** The module's shared fixture algebra: a method-level `Raise` capability, the otel4s
  * counterpart of `natchez-tagless-mtl`'s `BarFixture`, substituting
  * `ToAnyValue` for `TraceableValue`.
  */
sealed trait FooError extends Product with Serializable

object FooError {
  final case class Negative(i: Int) extends FooError

  /** Deliberately not `.toString`: `RaiseTracerTransparencySpec` and any
    * span-content assertion built on this fixture can tell the default
    * recorder rendered through `ToAnyValue` rather than falling back to the
    * error's own `toString`.
    */
  implicit val fooErrorToAnyValue: ToAnyValue[FooError] =
    ToAnyValue.instance {
      case Negative(i) => AnyValue.string(s"negative:$i")
    }
}

trait Foo[F[_]] {
  def foo(i: Int)(implicit R: Raise[F, FooError]): F[String]
}

object Foo {

  /** Raises on negative input, succeeds otherwise — so both the success and
    * the raise/rescue paths are exercised through the same implementation.
    */
  def apply[F[_]: Applicative]: Foo[F] = new Foo[F] {
    def foo(i: Int)(implicit R: Raise[F, FooError]): F[String] =
      if (i < 0) R.raise(FooError.Negative(i)) else s"foo:$i".pure[F]
  }

  /** Hand-written, macro-free `RaiseAspect` instance — mirrors
    * `natchez-tagless-mtl`'s `HandWrittenBarRaiseAspect`. Deliberately not
    * derived via `DeriveRaise.aspect`: that macro's Scala 3 entry point is
    * `@experimental`, which a call site shared between the 2.13 and 3.3.8 test
    * sources (this module has no `scala-2`/`scala-3` split) cannot satisfy on
    * both versions at once. `Cod` is pinned at `ToAnyValue`, which is enough
    * to resolve `WeaveInterpreter` for both `traceWithInputs[ToAnyValue]` and
    * `traceWithInputsAndOutputs`.
    */
  implicit val fooRaiseAspect: RaiseAspect[Foo, ToAnyValue, ToAnyValue, ToAnyValue] =
    new RaiseAspect[Foo, ToAnyValue, ToAnyValue, ToAnyValue] {
      def intercept[F[_]](af: Foo[F])(
          fk: Aspect.Weave[F, ToAnyValue, ToAnyValue, *] ~> F,
          onRaise: OnRaise[F, ToAnyValue]
      )(implicit F: Apply[F]): Foo[F] =
        new Foo[F] {
          def foo(i: Int)(implicit R: Raise[F, FooError]): F[String] =
            fk(
              Aspect.Weave[F, ToAnyValue, ToAnyValue, String](
                "Foo",
                List(List(Aspect.Advice.byValue[ToAnyValue, Int]("i", i))),
                Aspect.Advice[F, ToAnyValue, String](
                  "foo",
                  af.foo(i)(RaiseAspect.observing(R, onRaise))
                )
              )
            )
        }

      def mapK[F[_], G[_]](af: Foo[F])(arrow: RaiseArrow[F, G, ToAnyValue]): Foo[G] =
        new Foo[G] {
          def foo(i: Int)(implicit R: Raise[G, FooError]): G[String] =
            arrow.fk(af.foo(i)(arrow.pull(R)))
        }
    }
}
