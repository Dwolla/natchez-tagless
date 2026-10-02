package com.dwolla.tracing.otel4s.mtl

import munit.FunSuite

/** Pins the effect constraints `traceWithInputs` costs through this module,
  * which `RaiseTracerWeaveOps`, the package scaladoc and the README all
  * describe: the syntax itself declares `Functor[F]`, but the default recorder
  * (`Otel4sDefaultOnRaise`) needs `FlatMap[F]`, while a caller's own hook
  * needs only what that hook needs. `Apply[F]` is in every snippet because
  * `WeaveInterpreter.fromRaiseAspect` needs it for `RaiseAspect#intercept`.
  */
class RaiseTracerConstraintSpec extends FunSuite {
  test("on the default recorder, Apply and TracerProvider are not enough: it needs FlatMap") {
    val errors = compileErrors("""
      import cats.Apply
      import com.dwolla.tracing.otel4s.ToAnyValue
      import com.dwolla.tracing.otel4s.mtl.syntax._
      import org.typelevel.otel4s.trace.TracerProvider
      def traced[F[_]](foo: Foo[F])(implicit A: Apply[F], T: TracerProvider[F]): F[Foo[F]] =
        foo.traceWithInputs[ToAnyValue]
    """)
    assert(errors.contains("RaiseRecorder"), errors)
  }

  test("on the default recorder, FlatMap and TracerProvider are enough") {
    assertNoDiff(compileErrors("""
      import cats.FlatMap
      import com.dwolla.tracing.otel4s.ToAnyValue
      import com.dwolla.tracing.otel4s.mtl.syntax._
      import org.typelevel.otel4s.trace.TracerProvider
      def traced[F[_]](foo: Foo[F])(implicit F: FlatMap[F], T: TracerProvider[F]): F[Foo[F]] =
        foo.traceWithInputs[ToAnyValue]
    """), "")
  }

  test("with the caller's own hook, Apply and TracerProvider are enough") {
    assertNoDiff(compileErrors("""
      import cats.Apply
      import com.dwolla.tagless.mtl.OnRaise
      import com.dwolla.tracing.otel4s.ToAnyValue
      import com.dwolla.tracing.otel4s.mtl.syntax._
      import org.typelevel.otel4s.trace.TracerProvider
      def traced[F[_]](foo: Foo[F], unit: F[Unit])(implicit A: Apply[F], T: TracerProvider[F]): F[Foo[F]] = {
        implicit val hook: OnRaise[F, ToAnyValue] = new OnRaise[F, ToAnyValue] {
          def apply[E](e: E)(implicit ev: ToAnyValue[E]): F[Unit] = unit
        }
        foo.traceWithInputs[ToAnyValue]
      }
    """), "")
  }
}
