package com.dwolla.tracing

import cats._
import cats.data._
import cats.effect.std._
import cats.effect.syntax.all._
import cats.effect.{IO, Resource, Trace => _, _}
import cats.syntax.all._
import com.dwolla.tracing.instances._
import natchez.{EntryPoint, Span, Trace}

object TraceInitializationExample extends IOApp.Simple {
  private def entryPoint[F[_] : Sync : Env]: Resource[F, EntryPoint[F]] =
    OpenTelemetryAtDwolla[F]("TraceInitializationSpec", DwollaEnvironment.Local)

  private def resources[F[_] : Applicative : Trace]: Resource[F, Unit] =
    Trace[F].span("resource acquisition")(().pure[F])
      .toResource
      .onFinalize(Trace[F].span("resource finalizer")(().pure[F]))

  private def kleisli[F[_] : Async : Env]: Resource[Kleisli[F, Span[F], *], Unit] =
    entryPoint[Kleisli[F, Span[F], *]]
      .flatMap {
        TraceResourceAcquisition(_, "kleisli") { implicit trace =>
          resources
        }
      }

  private def io: Resource[IO, Unit] =
    IOLocal(Span.noop[IO])
      .toResource
      .flatMap { implicit ioLocal =>
        entryPoint[IO].flatMap {
          TraceResourceAcquisition(_, "IOLocal") { implicit trace =>
            resources
          }
        }
      }

  override def run: IO[Unit] =
    io.use_
//    kleisli[IO].use_.run(Span.noop[IO])
}