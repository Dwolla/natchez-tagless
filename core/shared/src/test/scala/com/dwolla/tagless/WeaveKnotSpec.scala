package com.dwolla.tagless

import cats.*
import cats.data.*
import cats.syntax.all.*
import cats.tagless.Trivial
import cats.tagless.aop.*
import cats.tagless.syntax.all.*
import munit.{FunSuite, ScalaCheckSuite}
import org.scalacheck.{Gen, Prop}

class WeaveKnotSpec extends FunSuite with ScalaCheckSuite {
  test("naive implementation doesn't accumulate all the method calls") {
    val (methods, output) =
      MyAlgebra.naive[Writer[List[String], *]]
        .instrument
        .mapK(new WriteInstrumentation[Id])
        .fooReverse
        .run

    assertEquals(methods, List("MyAlgebra.fooReverse"))
    assertEquals(output, "oof")
  }

  test("implementation using WeaveKnot does accumulate all the method calls") {
    Prop.forAllNoShrink(Gen.asciiPrintableStr) { s =>
      val (methods, output) =
        WeaveKnot(MyAlgebra[Writer[List[String], *]](s))(_.instrument.mapK(new WriteInstrumentation[Id]))
          .fooReverse
          .run

      assertEquals(output, s.reverse)
      assertEquals(methods, List("MyAlgebra.fooReverse", "MyAlgebra.foo"))
    }
  }

  test("implementation using WeaveKnot.instrument accumulates all the method calls") {
    Prop.forAllNoShrink(Gen.asciiPrintableStr) { s =>
      val (methods, output) =
        WeaveKnot.instrument(MyAlgebra[Writer[List[String], *]](s), new WriteInstrumentation[Id])
          .fooReverse
          .run

      assertEquals(output, s.reverse)
      assertEquals(methods, List("MyAlgebra.fooReverse", "MyAlgebra.foo"))
    }
  }

  test("implementation using WeaveKnot.aspect accumulates all the method calls") {
    Prop.forAllNoShrink(Gen.asciiPrintableStr) { s =>
      val (methods, output) =
        WeaveKnot.weave(MyAlgebra[Writer[List[String], *]](s), new WriteWeave[Id])
          .fooReverse
          .run

      assertEquals(output, s.reverse)
      assertEquals(methods, List("MyAlgebra.fooReverse", "MyAlgebra.foo"))
    }
  }

}

trait MyAlgebra[F[_]] {
  def foo: F[String]
  def fooReverse: F[String]
}

object MyAlgebra {
  def apply[F[_] : Applicative](s: String): Eval[MyAlgebra[F]] => MyAlgebra[F] = self =>
    new MyAlgebra[F] {
      override def foo: F[String] = s.pure[F]
      override def fooReverse: F[String] =
        self.value.foo.map(_.reverse)
    }

  def naive[F[_] : Applicative]: MyAlgebra[F] = new MyAlgebra[F] {
    override def foo: F[String] = "foo".pure[F]
    override def fooReverse: F[String] = foo.map(_.reverse)
  }

  // instrumented manually since the Scala 3 version is still experimental
  implicit def aspect: Aspect[MyAlgebra, Trivial, Trivial] = // cats.tagless.Derive.instrument
    new Aspect[MyAlgebra, Trivial, Trivial] {
      def weave[F[_]](af: MyAlgebra[F]): MyAlgebra[Aspect.Weave[F, Trivial, Trivial, *]] = new MyAlgebra[Aspect.Weave[F, Trivial, Trivial, *]] {
        override def foo: Aspect.Weave[F, Trivial, Trivial, String] = Aspect.Weave("MyAlgebra", List.empty, Aspect.Advice("foo", af.foo))
        override def fooReverse: Aspect.Weave[F, Trivial, Trivial, String] = Aspect.Weave("MyAlgebra", List.empty, Aspect.Advice("fooReverse", af.fooReverse))
      }

      override def mapK[F[_], G[_]](af: MyAlgebra[F])(fk: F ~> G): MyAlgebra[G] = new MyAlgebra[G] {
        override def foo: G[String] = fk(af.foo)
        override def fooReverse: G[String] = fk(af.fooReverse)
      }
    }
}

class WriteInstrumentation[F[_] : Monad] extends (Instrumentation[WriterT[F, List[String], *], *] ~> WriterT[F, List[String], *]) {
  override def apply[A](fa: Instrumentation[WriterT[F, List[String], *], A]): WriterT[F, List[String], A] =
    WriterT.tell(List(s"${fa.algebraName}.${fa.methodName}")) >> fa.value
}

class WriteWeave[F[_] : Monad] extends (Aspect.Weave[WriterT[F, List[String], *], Trivial, Trivial, *] ~> WriterT[F, List[String], *]) {
  override def apply[A](fa: Aspect.Weave[WriterT[F, List[String], *], Trivial, Trivial, A]): WriterT[F, List[String], A] =
    WriterT.tell(List(s"${fa.algebraName}.${fa.codomain.name}")) >> fa.codomain.target
}
