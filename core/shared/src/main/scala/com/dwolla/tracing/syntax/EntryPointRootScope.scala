package com.dwolla.tracing.syntax

import cats.effect.kernel.Poll
import cats.syntax.all.*
import cats.effect.{MonadCancelThrow, Resource}
import cats.mtl.*
import natchez.*

trait ToEntryPointRootScopeOps {
  implicit def toEntryPointRootScopeOps[F[_]](entryPoint: EntryPoint[F]): EntryPointRootScopeOps[F] =
    new EntryPointRootScopeOps(entryPoint)
}

class EntryPointRootScopeOps[F[_]](val entryPoint: EntryPoint[F]) extends AnyVal {
  def runInRoot[A](name: String)
                  (fa: F[A])
                  (implicit F: MonadCancelThrow[F],
                   L: Local[F, Span[F]]): F[A] =
    runInRoot(name, Span.Options.Defaults)(fa)

  def runInRoot[A](name: String, options: Span.Options)
                  (fa: F[A])
                  (implicit F: MonadCancelThrow[F],
                   L: Local[F, Span[F]],
                  ): F[A] =
    safeSpan(fa)(_.root(name, options))

  def runInContinuation[A](name: String, kernel: Kernel)
                          (fa: F[A])
                          (implicit F: MonadCancelThrow[F],
                           L: Local[F, Span[F]]): F[A] =
    runInContinuation(name, kernel, Span.Options.Defaults)(fa)

  def runInContinuation[A](name: String, kernel: Kernel, options: Span.Options)
                          (fa: F[A])
                          (implicit F: MonadCancelThrow[F],
                           L: Local[F, Span[F]]): F[A] =
    safeSpan(fa)(_.continue(name, kernel, options))

  def runInContinuationOrElseRoot[A](name: String, kernel: Kernel)
                           (fa: F[A])
                           (implicit F: MonadCancelThrow[F],
                            L: Local[F, Span[F]]): F[A] =
    runInContinuationOrElseRoot(name, kernel, Span.Options.Defaults)(fa)

  def runInContinuationOrElseRoot[A](name: String, kernel: Kernel, options: Span.Options)
                                    (fa: F[A])
                                    (implicit F: MonadCancelThrow[F],
                                     L: Local[F, Span[F]]): F[A] =
    safeSpan(fa)(_.continueOrElseRoot(name, kernel, options))

  private def safeSpan[A](fa: F[A])
                         (f: EntryPoint[F] => Resource[F, Span[F]])
                         (implicit F: MonadCancelThrow[F],
                          L: Local[F, Span[F]]): F[A] =
    Resource.applyFull { (poll: Poll[F]) =>
      poll {
        f(entryPoint)
          .handleError(_ => Span.noop[F])
          .allocatedCase
      }.map {
        case (a, release) =>
          (a, release.andThen(_.handleError(_ => ())))
      }
    }
      .use(Local[F, Span[F]].scope(fa))
}
