package com.dwolla.tracing

import cats.*
import cats.data.*
import cats.effect.std.*
import cats.effect.syntax.all.*
import cats.effect.{IO, Resource, Trace as _, *}
import cats.syntax.all.*
import com.dwolla.buildinfo.BuildInfo
import natchez.{EntryPoint, Span, Trace}
import natchez.mtl.localSpanForKleisli
import org.typelevel.log4cats.{Logger, LoggerFactory}
import org.typelevel.log4cats.noop.*

object TraceInitializationExample extends IOApp.Simple {
  private implicit def logger[F[_] : Applicative]: Logger[F] = NoOpLogger[F]
  private implicit def loggerFactory[F[_] : Applicative]: LoggerFactory[F] = NoOpFactory[F]

  private def entryPoint[F[_] : Async : Env : Random]: Resource[F, EntryPoint[F]] =
    OpenTelemetryAtDwolla[F]("TraceInitializationSpec", BuildInfo.version, DwollaEnvironment.Local)

  private def resources[F[_] : Applicative : Trace]: Resource[F, Unit] =
    Trace[F].span("resource acquisition")(().pure[F])
      .toResource
      .onFinalize(Trace[F].span("resource finalizer")(().pure[F]))

  private def kleisli[F[_] : Async : Env]: Resource[Kleisli[F, Span[F], *], Unit] =
    Random.scalaUtilRandom[Kleisli[F, Span[F], *]].toResource.flatMap { implicit random =>
      entryPoint[Kleisli[F, Span[F], *]]
        .flatMap {
          TraceResourceAcquisition(_, "kleisli") { implicit trace =>
            resources
          }
        }
    }

  private def io: Resource[IO, Unit] =
    IO.local(Span.noop[IO])
      .toResource
      .flatMap { implicit ioLocal =>
        Random.scalaUtilRandom[IO].toResource.flatMap { implicit random =>
          entryPoint[IO].flatMap {
            TraceResourceAcquisition(_, "IOLocal") { implicit trace =>
              resources
            }
          }
        }
      }

  override def run: IO[Unit] =
    io.use_
//    kleisli[IO].use_.run(Span.noop[IO])
}
