package com.dwolla.tracing.otel4s

import cats.FlatMap
import cats.syntax.all._
import cats.tagless.aop.Aspect.Weave
import cats.~>
import com.dwolla.tracing.otel4s.syntax._
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.{AnyValue, Attribute, Attributes}

object TracerWeaveCapturingInputsAndOutputs {
  def apply[F[_]: FlatMap: Tracer]: Weave[F, ToAnyValue, ToAnyValue, *] ~> F =
    new TracerWeaveCapturingInputsAndOutputs[F]
}

/**
 * Use this `FunctionK` when you have an algebra in
 * `Weave[F, ToAnyValue, ToAnyValue, *]` and you want each method call on the
 * algebra to introduce a new child span, using the ambient `Tracer[F]`. Each
 * child span is named using the algebra name and method name captured in the
 * `Weave`, and both the parameters given to the method call and its return
 * value are attached to the span, as `<algebraName>.<methodName>.parameters`
 * and `.returnValue`. Either attribute is omitted outright when its value
 * would carry nothing, rather than recorded empty. See this module's README
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
 * README's "Error recording" section. A failed call records no `returnValue`
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
 * actual value. Similar functionality can be achieved with the newtype library
 * of your choice.
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
 */
class TracerWeaveCapturingInputsAndOutputs[F[_]: FlatMap: Tracer]
  extends (Weave[F, ToAnyValue, ToAnyValue, *] ~> F) {

  override def apply[A](fa: Weave[F, ToAnyValue, ToAnyValue, A]): F[A] = {
    val name = s"${fa.algebraName}.${fa.codomain.name}"

    Tracer[F]
      .spanBuilder(name)
      // asAttributes stays *inside* this lambda. Tracer.noop's modifyState
      // never applies the function, so a disabled tracer pays nothing for
      // encoding the parameters — and by-name parameters are never forced.
      .modifyState(_.addAttributes(fa.asAttributes))
      .build
      // `use`, not `surround`: otel4s has no ambient `Tracer[F].put`, so the
      // only way to attach an attribute after the call is to hold the Span.
      // `flatTap` so the underlying target is read exactly once.
      .use { span =>
        fa.codomain.target.flatTap { out =>
          // The ascription is redundant *here* and kept for symmetry:
          // `ToAnyValue#toAnyValue` is declared `: AnyValue`, so the inferred
          // type is already `AnyValue` and `Attribute(name, returnValue)`
          // resolves KeySelect with or without it.
          //
          // The site where the same pattern is load-bearing is
          // `WeaveAttributesOps.asAttributes`, whose `AnyValue.map(...)` really
          // does return the precise subtype `AnyValue.MapValue`. KeySelect is
          // invariant, so dropping the widening *there* does not compile, and
          // the @implicitNotFound message names only the eight flat types and
          // never mentions AnyValue — which makes the fix look like a missing
          // KeySelect instance when it is a missing widening. Do not add a
          // KeySelect instance.
          val returnValue: AnyValue = fa.codomain.instance.toAnyValue(out)

          // An empty return value (Unit, or any type that happens to encode to
          // AnyValue.empty) omits the attribute entirely rather than recording
          // EmptyValue. ToAnyValue is unchanged — this check is local to the
          // interpreter, exactly as asAttributes' zero-parameter check is.
          val attributes: Attributes =
            if (returnValue == AnyValue.empty) Attributes.empty
            else Attributes(Attribute(s"$name.returnValue", returnValue))

          // `.backend` deliberately: Span#addAttributes is a macro on Scala 2
          // and inline on Scala 3, and Span.Backend#addAttributes is the sealed
          // method underneath it. Attributes is already an
          // immutable.Iterable[Attribute[_]], so it passes straight through,
          // and Attributes.empty is a no-op iterable when there is nothing to
          // add.
          span.backend.addAttributes(attributes)
        }
      }
  }
}
