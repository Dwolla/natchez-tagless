package com.dwolla.tracing.otel4s

import cats.tagless.aop.Aspect.Weave
import cats.~>
import com.dwolla.tagless.WeaveNaming._
import com.dwolla.tracing.otel4s.syntax._
import org.typelevel.otel4s.trace.Tracer

/**
 * Use this `FunctionK` when you have an algebra in
 * `Weave[F, ToAnyValue, Cod, *]` and you want each method call on the algebra
 * to introduce a new child span, using the ambient `Tracer[F]`. Each child span
 * is named using the algebra name and method name captured in the `Weave`, and
 * the parameters given to the method call are attached to the span.
 *
 * The format of the attribute values is controlled by the `ToAnyValue`
 * typeclass. If a parameter is sensitive, one way to keep the sensitive value
 * out of the trace is to give the parameter a newtype and hand-write a redacted
 * `ToAnyValue[Newtype]` instance. For example, using `io.monix::newtypes-core`:
 *
 * {{{
 *   import monix.newtypes._
 *   import org.typelevel.otel4s.AnyValue
 *   import com.dwolla.tracing.otel4s.ToAnyValue
 *
 *   type Password = Password.Type
 *
 *   object Password extends NewtypeWrapped[String] {
 *     implicit val PasswordToAnyValue: ToAnyValue[Password] = new ToAnyValue[Password] {
 *       override def toAnyValue(a: Password): AnyValue = AnyValue.string("redacted password value")
 *     }
 *   }
 * }}}
 *
 * With that instance the span records `"redacted password value"` and never the
 * actual value. Similar functionality can be achieved with the newtype library
 * of your choice.
 *
 */
object TracerWeaveCapturingInputs {
  def apply[F[_]: Tracer, Cod[_]]: Weave[F, ToAnyValue, Cod, *] ~> F =
    new TracerWeaveCapturingInputs[F, Cod]
}

private[otel4s] final class TracerWeaveCapturingInputs[F[_]: Tracer, Cod[_]] extends (Weave[F, ToAnyValue, Cod, *] ~> F) {
  override def apply[A](fa: Weave[F, ToAnyValue, Cod, A]): F[A] =
    Tracer[F]
      .spanBuilder(fa.qualifiedMethodName)
      // asAttributes stays *inside* this lambda. Tracer.noop's modifyState
      // never applies the function, so a disabled tracer pays nothing for
      // encoding — and by-name parameters are never forced.
      .modifyState(_.addAttributes(FunctionCallAttributes.codeFunctionName(fa.qualifiedMethodName) ++ fa.asAttributes))
      .build
      .surround(fa.codomain.target)
}
