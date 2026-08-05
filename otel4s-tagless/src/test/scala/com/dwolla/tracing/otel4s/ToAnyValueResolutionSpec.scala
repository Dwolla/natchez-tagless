package com.dwolla.tracing.otel4s

import cats.Id
import cats.tagless.aop.{Aspect, Instrument}
import com.dwolla.tracing.otel4s.syntax._
import munit.FunSuite
import org.typelevel.otel4s.AnyValue
import org.typelevel.otel4s.trace.Tracer

/** Each `implicitly` here is a compile-time assertion: if the priority ladder
  * did not work, the module would not build and this file is where the error lands.
  * The last test is the same kind of assertion aimed at the syntax package: the
  * bodies of its local methods are the claim, and the `assertEquals` calls only
  * exist so the methods are used.
  */
class ToAnyValueResolutionSpec extends FunSuite {
  test("a primitive resolves to its own instance, not to the Show fallback") {
    // Show[String] exists, so without the priority ladder this is ambiguous.
    assertEquals(implicitly[ToAnyValue[String]].toAnyValue("v"), AnyValue.string("v"))
  }

  test("Show[Int] and Show[Boolean] do not shadow the primitive instances either") {
    assertEquals(implicitly[ToAnyValue[Int]].toAnyValue(3), AnyValue.long(3L))
    assertEquals(implicitly[ToAnyValue[Boolean]].toAnyValue(true), AnyValue.boolean(true))
  }

  test("contravariance lets List and Vector use the generic Seq instance") {
    assertEquals(implicitly[ToAnyValue[List[String]]].toAnyValue(List("a")), AnyValue.seq(Seq(AnyValue.string("a"))))
    assertEquals(implicitly[ToAnyValue[Vector[Long]]].toAnyValue(Vector(1L)), AnyValue.seq(Seq(AnyValue.long(1L))))
  }

  test("Show[List[String]] does not shadow the generic Seq instance") {
    // cats has Show[List[A]]; if the fallback outranked the companion, this
    // would be StringValue("List(a)") instead.
    assertEquals(implicitly[ToAnyValue[List[String]]].toAnyValue(List("a")).toString, "SeqValue([StringValue(a)])")
  }

  test("traceWithInputs needs nothing but Tracer[F] — a compile-time assertion") {
    // If traceWithInputs required Apply[F] (as the natchez version does), or
    // any other capability, this method would not compile: F is abstract and
    // Tracer is the only instance in scope.
    def onlyTracer[Alg[_[_]], F[_], Cod[_]](alg: Alg[F])(implicit
                                                         T: Tracer[F],
                                                         A: Aspect[Alg, ToAnyValue, Cod]): Alg[F] =
      alg.traceWithInputs[Cod]

    def onlyTracerInstrument[Alg[_[_]], F[_]](alg: Alg[F])(implicit
                                                           I: Instrument[Alg],
                                                           T: Tracer[F]): Alg[F] =
      alg.instrumentAndTrace

    implicit val tracer: Tracer[Id] = Tracer.noop[Id]
    val underlying = Foo.counting[Id](new FooCallCounts)(f => f())

    assertEquals(onlyTracer[Foo, Id, ToAnyValue](underlying).greet("world", 2), "hello worldhello world")
    assertEquals(onlyTracerInstrument[Foo, Id](underlying).greet("world", 2), "hello worldhello world")
  }
}
