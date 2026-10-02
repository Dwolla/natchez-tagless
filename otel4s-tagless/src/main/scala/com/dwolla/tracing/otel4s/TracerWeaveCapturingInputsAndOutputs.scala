package com.dwolla.tracing.otel4s

import cats.FlatMap
import cats.syntax.all._
import cats.tagless.aop.Aspect.Weave
import cats.~>
import com.dwolla.tagless.WeaveNaming._
import com.dwolla.tracing.otel4s.syntax._
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.{AnyValue, Attribute, Attributes}

/**
 * Use this `FunctionK` when you have an algebra in
 * `Weave[F, ToAnyValue, ToAnyValue, *]` and you want each method call on the
 * algebra to introduce a new child span, using the ambient `Tracer[F]`. Each
 * child span is named using the algebra name and method name captured in the
 * `Weave`, and both the parameters given to the method call and its return
 * value are attached to the span, as `com.dwolla.code.function.arguments`
 * and `com.dwolla.code.function.return_value`, alongside `code.function.name`.
 * `code.function.name` is always recorded; the arguments and return-value
 * attributes are each omitted outright when their value would carry nothing,
 * rather than recorded empty. See this module's README
 * for the full attribute layout, the omission rule, and how the
 * `otel4s-oteljava` backend narrows these values on the way out.
 *
 * There is an asymmetry with `TracerWeaveCapturingInputs` worth knowing about.
 * That interpreter encodes nothing under a noop `Tracer`, because
 * `SpanBuilder#modifyState` returns `this` without applying its function. This
 * one still encodes the ''return value'' under a noop `Tracer`, because
 * `SpanOps#use` does apply its function — `Tracer.noop`'s `build.use(f)` is
 * `f(span)`. The resulting `addAttributes` is a no-op, so nothing is recorded,
 * but `toAnyValue` does run. Keep that in mind if an instance is expensive:
 * disabling tracing does not make it free the way it does for parameters.
 *
 * Also inherited from `TracerInstrumentation`: when a `Throwable` escapes the
 * traced effect, otel4s marks the span as errored on its own — see the
 * README's "Error recording" section. A failed call records no return-value
 * attribute, because there is no return value.
 *
 * The format of the attribute values is controlled by the `ToAnyValue`
 * typeclass. If a parameter or return value is sensitive, one way to keep the
 * sensitive value out of the trace is to give it a newtype and hand-write a
 * redacted `ToAnyValue[Newtype]` instance. For example, using
 * `io.monix::newtypes-core`:
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
 * actual value, wherever the newtype appears: a value is recorded only through
 * its own `ToAnyValue`, and every container, map and tuple instance encodes
 * element-wise. The one exception is a type you opt in with
 * `ToAnyValue.fromEncoder` or `ToAnyValue.fromShow`, which records whatever
 * that `Encoder` or `Show` reveals, including a field of the newtype. Similar
 * functionality can be achieved with the newtype library of your choice.
 *
 * Note if you have an algebra `Alg[F]` for which an
 * `Aspect[Alg, ToAnyValue, ToAnyValue]` exists, it can be converted to
 * `Alg[Weave[F, ToAnyValue, ToAnyValue, *]]` using
 * `Aspect[Alg, ToAnyValue, ToAnyValue].weave`, and turned back into an `Alg[F]`
 * with `.mapK(TracerWeaveCapturingInputsAndOutputs[F])`. Unlike
 * `TracerWeaveCapturingInputs`, this interpreter reads the codomain's
 * `ToAnyValue` instance, so `Aspect.Domain[Alg, ToAnyValue]` — which pins the
 * codomain to `Trivial` — is not enough; the algebra needs `ToAnyValue`
 * instances for its return types too.
 *
 * `FlatMap[F]` is the constraint, matching the natchez version: the return
 * value has to be sequenced after the call that produced it, but nothing here
 * needs `pure`.
 *
 * A plain `.mapK` traces only the calls that cross the algebra's boundary: if
 * one method of the implementation calls another directly, the inner call is
 * invisible. `com.dwolla.tagless.WeaveKnot.weave` ties the knot so the
 * implementation calls the ''traced'' version of itself and the inner call gets
 * its own child span.
 *
 * {{{
 *   import cats.effect.IO
 *   import cats.tagless.aop._
 *   import cats.~>
 *   import com.dwolla.tagless.WeaveKnot
 *   import com.dwolla.tracing.otel4s.{ToAnyValue, TracerWeaveCapturingInputsAndOutputs}
 *   import org.typelevel.otel4s.trace.Tracer
 *
 *   trait Foo[F[_]] {
 *     def greet(name: String): F[String]
 *     def greetTwice(name: String): F[String]
 *   }
 *
 *   implicit val fooAspect: Aspect[Foo, ToAnyValue, ToAnyValue] = new Aspect[Foo, ToAnyValue, ToAnyValue] {
 *     override def weave[F[_]](af: Foo[F]): Foo[Aspect.Weave[F, ToAnyValue, ToAnyValue, *]] =
 *     new Foo[Aspect.Weave[F, ToAnyValue, ToAnyValue, *]] {
 *       override def greet(name: String): Aspect.Weave[F, ToAnyValue, ToAnyValue, String] =
 *       Aspect.Weave[F, ToAnyValue, ToAnyValue, String](
 *         "Foo",
 *         List(List(Aspect.Advice.byValue[ToAnyValue, String]("name", name))),
 *         Aspect.Advice[F, ToAnyValue, String]("greet", af.greet(name))
 *       )
 *
 *       override def greetTwice(name: String): Aspect.Weave[F, ToAnyValue, ToAnyValue, String] =
 *       Aspect.Weave[F, ToAnyValue, ToAnyValue, String](
 *         "Foo",
 *         List(List(Aspect.Advice.byValue[ToAnyValue, String]("name", name))),
 *         Aspect.Advice[F, ToAnyValue, String]("greetTwice", af.greetTwice(name))
 *       )
 *     }
 *
 *     override def mapK[F[_], G[_]](af: Foo[F])(fk: F ~> G): Foo[G] =
 *     new Foo[G] {
 *       override def greet(name: String): G[String] = fk(af.greet(name))
 *       override def greetTwice(name: String): G[String] = fk(af.greetTwice(name))
 *     }
 *   }
 *
 *   // A real application summons this from `TracerProvider[F].get(name)`,
 *   // supplied by a backend module. This library never provides one.
 *   implicit val tracer: Tracer[IO] = Tracer.noop[IO]
 *
 *   // `self.value` is the *traced* algebra, so `greetTwice` produces three
 *   // spans: `Foo.greetTwice` and two nested `Foo.greet`s. Constructing the
 *   // implementation directly and calling `.traceWithInputsAndOutputs` on it
 *   // would produce only the outer one.
 *   val traced: Foo[IO] = WeaveKnot.weave[Foo, IO, ToAnyValue, ToAnyValue](
 *     self => new Foo[IO] {
 *       override def greet(name: String): IO[String] = IO.pure("hello " + name)
 *       override def greetTwice(name: String): IO[String] =
 *       self.value.greet(name).flatMap(a => self.value.greet(name).map(b => a + " " + b))
 *     },
 *     TracerWeaveCapturingInputsAndOutputs[IO]
 *   )
 * }}}
 *
 * On Scala 3 the whole instance above collapses to a `derives` clause:
 * `trait Foo[F[_]] derives AnyValueAspect` with a separate `@experimental
 * object Foo`. See `com.dwolla.tracing.otel4s.AnyValueAspect`, which pins
 * `Dom` and `Cod` to `ToAnyValue` so that `derives` has the one-parameter
 * type constructor it requires. `@experimental` is still required, and
 * ''where'' it goes matters: a `derives` clause invokes `derived` from a
 * given the compiler synthesizes into the algebra's companion object, so the
 * annotation belongs on the companion, not the trait — annotating the trait
 * instead also compiles, but makes the algebra ''type'' experimental,
 * forcing `@experimental` onto every reference to it, including untraced
 * call sites that never touch the instance. The 3.3.x LTS line has no
 * `-experimental` flag to opt out with. There is no Scala 2 equivalent —
 * `derives` does not exist there — so a cross-built algebra keeps the form
 * above.
 *
 * Adding the clause to an algebra that keeps the `fooAspect` above is not a
 * way to have both: the synthesized given is the more specific type, so it
 * silently outranks the hand-written wide one and any custom behaviour in it
 * disappears, with no error and no warning. Pick one. See
 * `com.dwolla.tracing.otel4s.AnyValueAspect` for the full note.
 */
object TracerWeaveCapturingInputsAndOutputs {
  def apply[F[_]: FlatMap: Tracer]: Weave[F, ToAnyValue, ToAnyValue, *] ~> F =
    new TracerWeaveCapturingInputsAndOutputs[F]
}

private[otel4s] final class TracerWeaveCapturingInputsAndOutputs[F[_]: FlatMap: Tracer]
  extends (Weave[F, ToAnyValue, ToAnyValue, *] ~> F) {

  override def apply[A](fa: Weave[F, ToAnyValue, ToAnyValue, A]): F[A] = {
    val name = fa.qualifiedMethodName

    Tracer[F]
      .spanBuilder(name)
      // asAttributes stays *inside* this lambda. Tracer.noop's modifyState
      // never applies the function, so a disabled tracer pays nothing for
      // encoding the parameters — and by-name parameters are never forced.
      .modifyState(_.withFinalizationStrategy(SpanFinalization.strategy).addAttributes(FunctionCallAttributes.codeFunctionName(name) ++ fa.asAttributes))
      .build
      // `use`, not `surround`: otel4s has no ambient `Tracer[F].put`, so the
      // only way to attach an attribute after the call is to hold the Span.
      // `flatTap` so the underlying target is read exactly once.
      .use { span =>
        fa.codomain.target.flatTap { out =>
          val returnValue = fa.codomain.instance.toAnyValue(out)

          // Omit the attribute for an empty return value, matching
          // asAttributes' zero-parameter convention.
          val attributes: Attributes =
            if (returnValue == AnyValue.empty) Attributes.empty
            else Attributes(Attribute(FunctionCallAttributes.ReturnValueKey, returnValue))

          // `.backend` deliberately: Span#addAttributes is a macro on Scala 2
          // and inline on Scala 3, and Span.Backend#addAttributes is the
          // sealed method underneath it.
          span.backend.addAttributes(attributes)
        }
      }
  }
}
