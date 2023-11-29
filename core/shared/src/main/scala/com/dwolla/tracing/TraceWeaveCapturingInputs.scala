package com.dwolla.tracing

import cats._
import cats.syntax.all._
import cats.tagless.aop.Aspect.Weave
import com.dwolla.tracing.syntax._
import natchez.{Trace, TraceableValue}

object TraceWeaveCapturingInputs {
  def apply[F[_] : Apply : Trace, Cod[_]]: Weave[F, TraceableValue, Cod, *] ~> F =
    new TraceWeaveCapturingInputs[F, Cod]
}

/**
 * Use this `FunctionK` when you have an algebra in
 * `Weave[F, TraceableValue, Cod, *]` and you want each method call on
 * the algebra to introduce a new child span, using the ambient
 * `Trace[F]`. Each child span will be named using the algebra name and
 * method name as captured in the `Instrumentation[F, A]`, and the
 * parameters given to the method call will be attached to the span
 * as attributes.
 *
 * The format of the attributes is controlled via the implementation
 * of the `TraceableValue` typeclass. There are provided implementations
 * for `String`, `Int`, `Boolean`, and `Unit`, as well as `Option[A]`
 * where `TraceableValue[A]` exists. Other types that have `Show` or
 * Circe `Encoder` instances will also be converted.
 *
 * If a parameter is sensitive, one way to ensure the sensitive value
 * is not included in the trace is to use a newtype for the parameter
 * type, and then manually write a redacted `TraceableValue[Newtype]`
 * instance. For example, using `io.monix::newtypes-core`:
 *
 * {{{
 *   import monix.newtypes._, natchez._
 *
 *   type Password = Password.Type
 *
 *   object Password extends NewtypeWrapped[String] {
 *     implicit val PasswordTraceableValue: TraceableValue[Password] = new TraceableValue[Password] {
 *       override def toTraceValue(a: Password): TraceValue = "redacted password value"
 *     }
 *   }
 * }}}
 *
 * With that implementation of `TraceableValue[Password]`, the span will
 * record "redacted password value" as an attribute, but the actual value
 * will not be recorded. Similar functionality can be achieved using the
 * newtype library of your choice.
 *
 * `TraceWeaveCapturingInputs` ignores the codomain (i.e. output) type,
 * so your algebra could have an `Aspect.Domain[Alg, TraceableValue]`
 * (equivalent to `Aspect[Alg, TraceableValue, Trivial]`),
 * `Aspect[Alg, TraceableValue, TraceableValue]`, or really any other
 * typeclass in the `Cod[_]` position, and still work with
 * this transformation.
 *
 * Note if you have an algebra `Alg[F]` for which an
 * `Aspect.Domain[Alg, TraceableValue]` exists, it can be converted to
 * `Alg[Weave.Domain[F, TraceableValue, *]]`
 * using `Aspect.Domain[Alg, TraceableValue].weave`:
 *
 * {{{
 *   import cats.effect._, cats.tagless._, cats.tagless.aop._, cats.tagless.syntax.all._, cats._
 *
 *   trait Foo[F[_]] {
 *     def foo(i: Int): F[Unit]
 *   }
 *
 *   object Foo {
 *     implicit val fooTracingAspect: Aspect.Domain[Foo, TraceableValue] = { // Derive.instrument
 *       // TODO reintroduce derived instance when cats-tagless-macros supports Scala 3
 *       new Aspect.Domain[Foo, TraceableValue] {
 *         override def weave[F[_]](af: Foo[F]): Foo[Aspect.Weave[F, TraceableValue, Trivial, *]] =
 *           new Foo[Aspect.Weave[F, TraceableValue, Trivial, *]] {
 *             override def foo(i: Int): Aspect.Weave[F, TraceableValue, Trivial, Unit] =
 *               Aspect.Weave[F, TraceableValue, Trivial, Unit](
 *                 "Foo",
 *                 List(List(Aspect.Advice.byValue[TraceableValue, Int]("i", i))),
 *                 Aspect.Advice[F, Trivial, Unit]("foo", af.foo(i))
 *               )
 *           }

 *         override def mapK[F[_], G[_]](af: Foo[F])(fk: F ~> G): Foo[G] =
 *           new Foo[G] {
 *             override def foo(i: Int): G[Unit] = fk(af.foo(i))
 *           }
 *       }
 *     }
 *   }
 *
 *   def myFoo: Foo[IO] = new Foo[IO] {
 *     def foo(i: Int) = IO.println(s"foo!").replicateA_(i)
 *   }
 *
 *   val wovenFoo: Foo[Aspect.Weave.Domain[IO, TraceableValue, *]] =
 *     myFoo.weave
 * }}}
 *
 * Ensure that `TraceableValue` instances exist for all the method
 * parameter types in the algebra, or you'll see compile-time errors
 * similar to this:
 *
 * <pre>
 *   exception during macro expansion:
 *   scala.reflect.macros.TypecheckException: could not find implicit value for evidence parameter of type TraceableValue[X]
 *       implicit val fooTracingAspect: Aspect.Domain[Foo, TraceableValue] = Derive.aspect
 *                                                                                ^
 *   one error found
 * </pre>
 *
 * Ensure there is a `TraceableValue` for the missing type (in the
 * example above, it would be `X`) in the implicit scope where the
 * `Aspect` is being derived, and then try again. You may have to
 * iterate several times before all the necessary types are defined.
 */
class TraceWeaveCapturingInputs[F[_] : Apply : Trace, Cod[_]] extends (Weave[F, TraceableValue, Cod, *] ~> F) {
  override def apply[A](fa: Weave[F, TraceableValue, Cod, A]): F[A] =
    Trace[F].span(s"${fa.algebraName}.${fa.codomain.name}") {
      Trace[F].put(fa.asTraceParams: _*) *> fa.codomain.target
    }
}
