package com.dwolla.tracing.otel4s

import cats.{Functor, Id}
import cats.tagless.aop.{Aspect, Instrument}
import com.dwolla.tracing.otel4s.ToAnyValueResolutionSpec._
import com.dwolla.tracing.otel4s.syntax._
import io.circe.{Encoder, Json}
import munit.FunSuite
import org.typelevel.otel4s.AnyValue
import org.typelevel.otel4s.trace.TracerProvider

/** Each `implicitly` here is a compile-time assertion: if resolution were
  * missing or ambiguous, the module would not build and this file is where the error lands.
  * The last test is the same kind of assertion aimed at the syntax package: the
  * bodies of its local methods are the claim, and the `assertEquals` calls only
  * exist so the methods are used.
  */
class ToAnyValueResolutionSpec extends FunSuite {
  test("a primitive resolves to its own instance") {
    assertEquals(implicitly[ToAnyValue[String]].toAnyValue("v"), AnyValue.string("v"))
  }

  test("Int and Boolean resolve to their own instances") {
    assertEquals(implicitly[ToAnyValue[Int]].toAnyValue(3), AnyValue.long(3L))
    assertEquals(implicitly[ToAnyValue[Boolean]].toAnyValue(true), AnyValue.boolean(true))
  }

  test("contravariance lets List and Vector use the generic Seq instance") {
    assertEquals(implicitly[ToAnyValue[List[String]]].toAnyValue(List("a")), AnyValue.seq(Seq(AnyValue.string("a"))))
    assertEquals(implicitly[ToAnyValue[Vector[Long]]].toAnyValue(Vector(1L)), AnyValue.seq(Seq(AnyValue.long(1L))))
  }

  test("a List[String] records as a sequence, not as cats' Show rendering") {
    assertEquals(implicitly[ToAnyValue[List[String]]].toAnyValue(List("a")).toString, "SeqValue([StringValue(a)])")
  }

  test("common types resolve without ambiguity alongside the collection and tuple instances") {
    assertEquals(implicitly[ToAnyValue[List[Int]]].toAnyValue(List(1)), AnyValue.seq(Seq(AnyValue.long(1L))))
    assertEquals(implicitly[ToAnyValue[Map[String, Int]]].toAnyValue(Map("k" -> 1)), AnyValue.map(Map("k" -> AnyValue.long(1L))))
    assertEquals(implicitly[ToAnyValue[Option[Int]]].toAnyValue(Option(1)), AnyValue.long(1L))
    assertEquals(implicitly[ToAnyValue[Json]].toAnyValue(Json.obj("k" -> Json.fromInt(1))), AnyValue.map(Map("k" -> AnyValue.long(1L))))
    assertEquals(implicitly[ToAnyValue[Point]].toAnyValue(Point(1)), AnyValue.map(Map("x" -> AnyValue.long(1L))))
  }

  test("types with their own instance keep it rather than the generic Iterable-shaped one") {
    val one = AnyValue.seq(Seq(AnyValue.long(1L)))
    assertEquals(implicitly[ToAnyValue[List[Int]]].toAnyValue(List(1)), one)
    assertEquals(implicitly[ToAnyValue[Vector[Int]]].toAnyValue(Vector(1)), one)
    assertEquals(implicitly[ToAnyValue[Seq[Int]]].toAnyValue(Seq(1)), one)
    assertEquals(implicitly[ToAnyValue[Set[Int]]].toAnyValue(Set(1)), one)
    assertEquals(implicitly[ToAnyValue[Array[Int]]].toAnyValue(Array(1)), one)
    assertEquals(implicitly[ToAnyValue[cats.data.Chain[Int]]].toAnyValue(cats.data.Chain(1)), one)
    assertEquals(implicitly[ToAnyValue[Map[Int, Int]]].toAnyValue(Map(1 -> 1)), AnyValue.map(Map("1" -> AnyValue.long(1L))))
    // Option and Some convert to Iterable too; a sequence here would mean the
    // Iterable-shaped instance had outranked optionToAnyValue.
    assertEquals(implicitly[ToAnyValue[Option[Int]]].toAnyValue(Option(1)), AnyValue.long(1L))
    assertEquals(implicitly[ToAnyValue[Some[Int]]].toAnyValue(Some(1)), AnyValue.long(1L))
  }

  test("a type that opts in with fromEncoder resolves inside a tuple and as a map key") {
    assertEquals(
      implicitly[ToAnyValue[(Point, Int)]].toAnyValue((Point(1), 2)),
      AnyValue.seq(Seq(AnyValue.map(Map("x" -> AnyValue.long(1L))), AnyValue.long(2L))),
    )
    assertEquals(
      implicitly[ToAnyValue[Map[Point, Int]]].toAnyValue(Map(Point(1) -> 2)),
      AnyValue.map(Map("MapValue({x -> LongValue(1)})" -> AnyValue.long(2L))),
    )
  }

  test("traceWithInputs needs nothing but Functor[F] and TracerProvider[F] — a compile-time assertion") {
    // If traceWithInputs required Apply[F] (as the natchez version does), or
    // any other capability, this method would not compile: F is abstract and
    // Functor and TracerProvider are the only instances in scope. Functor is
    // what mapping over the obtained tracer costs.
    def onlyTracerProvider[Alg[_[_]], F[_], Cod[_]](alg: Alg[F])(implicit
                                                                 F: Functor[F],
                                                                 T: TracerProvider[F],
                                                                 A: Aspect[Alg, ToAnyValue, Cod]): F[Alg[F]] =
      alg.traceWithInputs[Cod]

    def onlyTracerProviderInstrument[Alg[_[_]], F[_]](alg: Alg[F])(implicit
                                                                   I: Instrument[Alg],
                                                                   F: Functor[F],
                                                                   T: TracerProvider[F]): F[Alg[F]] =
      alg.instrumentAndTrace

    implicit val tracerProvider: TracerProvider[Id] = TracerProvider.noop[Id]
    val underlying = Foo.plain[Id]

    assertEquals(onlyTracerProvider[Foo, Id, ToAnyValue](underlying).greet("world", 2), "hello worldhello world")
    assertEquals(onlyTracerProviderInstrument[Foo, Id](underlying).greet("world", 2), "hello worldhello world")
  }
}

object ToAnyValueResolutionSpec {
  final case class Point(x: Int)
  object Point {
    implicit val pointEncoder: Encoder[Point] = Encoder.forProduct1("x")(_.x)
    implicit val pointToAnyValue: ToAnyValue[Point] = ToAnyValue.fromEncoder[Point]
  }
}
