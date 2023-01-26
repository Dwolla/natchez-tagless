package com.dwolla.tracing.syntax

import cats.effect.MonadCancelThrow
import cats.mtl._
import natchez._

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
                   L: Local[F, Span[F]]): F[A] =
    entryPoint
      .root(name, options)
      .use(Local[F, Span[F]].scope(fa))

  def runInContinuation[A](name: String, kernel: Kernel)
                          (fa: F[A])
                          (implicit F: MonadCancelThrow[F],
                           L: Local[F, Span[F]]): F[A] =
    runInContinuation(name, kernel, Span.Options.Defaults)(fa)

  def runInContinuation[A](name: String, kernel: Kernel, options: Span.Options)
                          (fa: F[A])
                          (implicit F: MonadCancelThrow[F],
                           L: Local[F, Span[F]]): F[A] =
    entryPoint
      .continue(name, kernel, options)
      .use(Local[F, Span[F]].scope(fa))

  def runInContinuationOrElseRoot[A](name: String, kernel: Kernel)
                           (fa: F[A])
                           (implicit F: MonadCancelThrow[F],
                            L: Local[F, Span[F]]): F[A] =
    runInContinuationOrElseRoot(name, kernel, Span.Options.Defaults)(fa)

  def runInContinuationOrElseRoot[A](name: String, kernel: Kernel, options: Span.Options)
                           (fa: F[A])
                           (implicit F: MonadCancelThrow[F],
                            L: Local[F, Span[F]]): F[A] =
    entryPoint
      .continueOrElseRoot(name, kernel, options)
      .use(Local[F, Span[F]].scope(fa))
}
