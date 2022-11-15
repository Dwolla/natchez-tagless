package com.dwolla.tracing

import cats.data.Kleisli
import cats.effect.MonadCancelThrow
import cats.tagless.aop._
import cats.~>
import natchez.{EntryPoint, Span}

object RootSpanProvidingFunctionK {
  def apply[F[_]: MonadCancelThrow](
      entryPoint: EntryPoint[F]
  ): Instrumentation[Kleisli[F, Span[F], *], *] ~> F =
    new RootSpanProvidingFunctionK(entryPoint)
}

class RootSpanProvidingFunctionK[F[_]: MonadCancelThrow](
    entryPoint: EntryPoint[F]
) extends (Instrumentation[Kleisli[F, Span[F], *], *] ~> F) {
  override def apply[A](fa: Instrumentation[Kleisli[F, Span[F], *], A]): F[A] =
    entryPoint.root(s"${fa.algebraName}.${fa.methodName}").use(fa.value.run(_))
}
