package com.dwolla.tracing.syntax

import cats.effect.syntax.all.*
import cats.effect.kernel.Poll
import cats.syntax.all.*
import cats.effect.{MonadCancelThrow, Resource}
import cats.mtl.*
import natchez.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

trait ToEntryPointRootScopeOps {
  implicit def toEntryPointRootScopeOps[F[_]](entryPoint: EntryPoint[F]): EntryPointRootScopeOps[F] =
    new EntryPointRootScopeOps(entryPoint)
}

class EntryPointRootScopeOps[F[_]](val entryPoint: EntryPoint[F]) extends AnyVal {
  @deprecated("use variant accepting Logger[F]", "0.2.5")
  private[syntax] def runInRoot[A](name: String, fa: F[A], F: MonadCancelThrow[F], L: Local[F, Span[F]]): F[A] =
    runInRoot(name)(fa)(F, L, NoOpLogger(F))

  @deprecated("use variant accepting Logger[F]", "0.2.5")
  private[syntax] def runInRoot[A](name: String, options: Span.Options, fa: F[A], F: MonadCancelThrow[F], L: Local[F, Span[F]]): F[A] =
    safeSpan(fa)(_.root(name, options))(F, L, NoOpLogger(F))

  def runInRoot[A](name: String)
                  (fa: F[A])
                  (implicit F: MonadCancelThrow[F],
                   L1: Local[F, Span[F]],
                   L2: Logger[F],
                  ): F[A] =
    runInRoot(name, Span.Options.Defaults)(fa)

  def runInRoot[A](name: String, options: Span.Options)
                  (fa: F[A])
                  (implicit F: MonadCancelThrow[F],
                   L1: Local[F, Span[F]],
                   L2: Logger[F],
                  ): F[A] =
    safeSpan(fa)(_.root(name, options))

  @deprecated("use variant accepting Logger[F]", "0.2.5")
  private[syntax] def runInContinuation[A](name: String, kernel: Kernel, fa: F[A], F: MonadCancelThrow[F], L: Local[F, Span[F]]): F[A] =
    runInContinuation(name, kernel, Span.Options.Defaults)(fa)(F, L, NoOpLogger(F))

  @deprecated("use variant accepting Logger[F]", "0.2.5")
  private[syntax] def runInContinuation[A](name: String, kernel: Kernel, options: Span.Options, fa: F[A], F: MonadCancelThrow[F], L: Local[F, Span[F]]): F[A] =
    safeSpan(fa)(_.continue(name, kernel, options))(F, L, NoOpLogger(F))

  def runInContinuation[A](name: String, kernel: Kernel)
                          (fa: F[A])
                          (implicit F: MonadCancelThrow[F],
                           L1: Local[F, Span[F]],
                           L2: Logger[F],
                          ): F[A] =
    runInContinuation(name, kernel, Span.Options.Defaults)(fa)

  def runInContinuation[A](name: String, kernel: Kernel, options: Span.Options)
                          (fa: F[A])
                          (implicit F: MonadCancelThrow[F],
                           L1: Local[F, Span[F]],
                           L2: Logger[F],
                          ): F[A] =
    safeSpan(fa)(_.continue(name, kernel, options))

  @deprecated("use variant accepting Logger[F]", "0.2.5")
  private[syntax] def runInContinuationOrElseRoot[A](name: String, kernel: Kernel, fa: F[A], F: MonadCancelThrow[F], L: Local[F, Span[F]]): F[A] =
    runInContinuationOrElseRoot(name, kernel, Span.Options.Defaults)(fa)(F, L, NoOpLogger(F))

  @deprecated("use variant accepting Logger[F]", "0.2.5")
  private[syntax] def runInContinuationOrElseRoot[A](name: String, kernel: Kernel, options: Span.Options, fa: F[A], F: MonadCancelThrow[F], L: Local[F, Span[F]]): F[A] =
    safeSpan(fa)(_.continueOrElseRoot(name, kernel, options))(F, L, NoOpLogger(F))

  def runInContinuationOrElseRoot[A](name: String, kernel: Kernel)
                                    (fa: F[A])
                                    (implicit F: MonadCancelThrow[F],
                                     L1: Local[F, Span[F]],
                                     L2: Logger[F],
                                    ): F[A] =
    runInContinuationOrElseRoot(name, kernel, Span.Options.Defaults)(fa)

  def runInContinuationOrElseRoot[A](name: String, kernel: Kernel, options: Span.Options)
                                    (fa: F[A])
                                    (implicit F: MonadCancelThrow[F],
                                     L1: Local[F, Span[F]],
                                     L2: Logger[F],
                                    ): F[A] =
    safeSpan(fa)(_.continueOrElseRoot(name, kernel, options))

  private def safeSpan[A](fa: F[A])
                         (f: EntryPoint[F] => Resource[F, Span[F]])
                         (implicit F: MonadCancelThrow[F],
                          L1: Local[F, Span[F]],
                          L2: Logger[F]): F[A] =
    Resource.applyFull { (poll: Poll[F]) =>
      poll {
        f(entryPoint)
          .handleErrorWith(Logger[F].warn(_: Throwable)("an error occurred initializing tracing").as(Span.noop[F]).toResource)
          .allocatedCase
      }.map {
        case (a, release) =>
          (a, release.andThen(_.handleErrorWith(Logger[F].warn(_)("an error occurred initializing tracing"))))
      }
    }
      .use(Local[F, Span[F]].scope(fa))
}
