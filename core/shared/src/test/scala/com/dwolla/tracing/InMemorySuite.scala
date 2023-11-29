package com.dwolla.tracing

import cats.*
import cats.data.*
import cats.effect.{IO, MonadCancelThrow, Trace as _, *}
import cats.mtl.Local
import cats.syntax.all.*
import munit.CatsEffectSuite
import natchez.InMemory.{Lineage, NatchezCommand}
import natchez.*

trait InMemorySuite extends CatsEffectSuite {
  trait TraceTest {
    def program[F[_] : MonadCancelThrow](entryPoint: EntryPoint[F])
                                        (implicit L: Local[F, Span[F]]): F[Unit]
    def expectedHistory: List[(Lineage, NatchezCommand)]
  }

  def traceTest(name: String, tt: TraceTest): Unit = {
    test(s"$name - Kleisli")(
      testTraceKleisli(tt.program[Kleisli[IO, Span[IO], *]], tt.expectedHistory)
    )
    test(s"$name - IOLocal")(testTraceIoLocal(implicit L => tt.program[IO], tt.expectedHistory))
  }

  implicit def localSpan[F[_]](implicit F: MonadCancel[F, ?]): Local[Kleisli[F, Span[F], *], Span[Kleisli[F, Span[F], *]]] =
    new Local[Kleisli[F, Span[F], *], Span[Kleisli[F, Span[F], *]]] {
      override def local[A](fa: Kleisli[F, Span[F], A])
                           (f: Span[Kleisli[F, Span[F], *]] => Span[Kleisli[F, Span[F], *]]): Kleisli[F, Span[F], A] =
        fa.local {
          f.andThen(_.mapK(Kleisli.applyK(Span.noop[F])))
            .compose(_.mapK(Kleisli.liftK))
        }

      override def applicative: Applicative[Kleisli[F, Span[F], *]] =
        Kleisli.catsDataApplicativeForKleisli

      override def ask[E2 >: Span[Kleisli[F, Span[F], *]]]: Kleisli[F, Span[F], E2] =
        Kleisli.ask[F, Span[F]].map(_.mapK(Kleisli.liftK))
    }

  def testTraceKleisli[F[_] : Async](traceProgram: EntryPoint[Kleisli[F, Span[F], *]] => Kleisli[F, Span[F], Unit],
                                     expectedHistory: List[(Lineage, NatchezCommand)]
                                    ): Kleisli[F, Span[F], Unit] =
    testTrace(
      traceProgram,
      expectedHistory
    )

  def testTraceIoLocal[A](traceProgram: Local[IO, Span[IO]] => EntryPoint[IO] => IO[A],
                          expectedHistory: List[(Lineage, NatchezCommand)]
                         ): IO[Unit] =
    IOLocal(Span.noop[IO])
      .map(localViaIoLocal(_))
      .map(traceProgram)
      .flatMap {
        testTrace(_, expectedHistory)
      }

  def testTrace[F[_] : Concurrent, A](traceProgram: EntryPoint[F] => F[A],
                                      expectedHistory: List[(Lineage, NatchezCommand)]
                                     ): F[Unit] =
    InMemory.EntryPoint.create[F].flatMap { ep =>
      traceProgram(ep) *> ep.ref.get.map(_.toList).map {
        assertEquals(_, expectedHistory)
      }
    }

  // from https://github.com/armanbilge/oxidized/blob/412be9cd0a60b901fd5f9157ea48bda8632c5527/core/src/main/scala/oxidized/instances/io.scala#L34-L43
  private def localViaIoLocal[E](implicit ioLocal: IOLocal[E]): Local[IO, E] =
    new Local[IO, E] {
      override def local[A](fa: IO[A])(f: E => E): IO[A] =
        ioLocal.get.flatMap { initial =>
          ioLocal.set(f(initial)) >> fa.guarantee(ioLocal.set(initial))
        }

      override def applicative: Applicative[IO] = implicitly

      override def ask[E2 >: E]: IO[E2] = ioLocal.get
    }

}
