package com.dwolla.tracing

import cats._
import cats.syntax.all._
import cats.tagless.aop.Aspect.Weave
import com.dwolla.tracing.syntax._
import natchez.Trace

object TraceWeaveCapturingInputsAndOutputs {
  def apply[F[_] : FlatMap : Trace]: Weave[F, ToTraceValue, ToTraceValue, *] ~> F =
    new TraceWeaveCapturingInputsAndOutputs[F]
}

/**
 * Use this `FunctionK` when you have an algebra in
 * `Weave[F, ToTraceValue, ToTraceValue, *]` and you want each method
 * call on the algebra to introduce a new child span, using the ambient
 * `Trace[F]`. Each child span will be named using the algebra name and
 * method name as captured in the `Instrumentation[F, A]`, and the
 * parameters given to the method call and its return value will be
 * attached to the span as attributes.
 *
 * The format of the attributes is controlled via the implementation
 * of the `ToTraceValue` typeclass. There are implementations provided
 * for `String`, `Int`, `Boolean`, and `Unit`, as well as `Option[A]`
 * where `ToTraceValue[A]` exists. Other types that have `Show` or
 * Circe `Encoder` instances will also be converted.
 *
 * If a parameter or return value is sensitive, one way to ensure the
 * sensitive value is not included in the trace is to use a newtype
 * for the parameter type, and then manually write a redacted
 * `ToTraceValue[Newtype]` instance. For example, using
 * `io.monix::newtypes-core`:
 *
 * {{{
 *   type Password = Password.Type
 *
 *   object Password extends NewtypeWrapped[String] {
 *     implicit val PasswordToTraceValue: ToTraceValue[Password] = new ToTraceValue[Password] {
 *       override def toTraceValue(a: Password): TraceValue = "redacted password value"
 *     }
 *   }
 * }}}
 *
 * With that implementation of `ToTraceValue[Password]`, the span will
 * record "redacted password value" as an attribute, but the actual
 * value will not be recorded. Similar functionality can be achieved
 * using the newtype library of your choice.
 *
 * Note if you have an algebra `Alg[F]` for which an
 * `Aspect[Alg, ToTraceValue, ToTraceValue]` exists, it can be
 * converted to `Alg[Weave[F, ToTraceValue, ToTraceValue, *]]`
 * using `Aspect[Alg, ToTraceValue, ToTraceValue].weave`:
 *
 * {{{
 *   import cats.tagless._, cats.tagless.aop._, cats.tagless.syntax.all._
 *
 *   trait Foo[F[_]] {
 *     def foo(i: Int): F[Unit]
 *   }
 *
 *   object Foo {
 *     implicit val fooTracingAspect: Aspect.Domain[Foo, ToTraceValue, ToTraceValue] = Derive.aspect
 *   }
 *
 *   val myFoo: Foo[F] = ???
 *
 *   val wovenFoo: Foo[Weave[F, ToTraceValue, ToTraceValue, *]] =
 *     myFoo.weave
 * }}}
 *
 * Ensure that `ToTraceValue` instances exist for all the method
 * parameter and return types in the algebra, or you'll see
 * compile-time errors similar to this:
 *
 * {{{
 *   exception during macro expansion:
 *   scala.reflect.macros.TypecheckException: could not find implicit value for evidence parameter of type ToTraceValue[X]
 *       implicit val fooTracingAspect: Aspect.Domain[Foo, ToTraceValue] = Derive.aspect
 *                                                                                         ^
 *   one error found
 * }}}
 *
 * Ensure there is a `ToTraceValue` for the missing type (in the
 * example above, it would be `X`) in the implicit scope where the
 * `Aspect` is being derived, and then try again. You may have to
 * iterate several times before all the necessary types are defined.
 */
class TraceWeaveCapturingInputsAndOutputs[F[_] : FlatMap : Trace] extends (Weave[F, ToTraceValue, ToTraceValue, *] ~> F) {
  override def apply[A](fa: Weave[F, ToTraceValue, ToTraceValue, A]): F[A] =
    Trace[F].span(s"${fa.algebraName}.${fa.codomain.name}") {
      for {
        _ <- Trace[F].put(fa.asTraceParams: _*)
        out <- fa.codomain.target
        _ <- Trace[F].put(s"${fa.algebraName}.${fa.codomain.name}.returnValue" -> fa.codomain.instance.toTraceValue(out))
      } yield out
    }
}
