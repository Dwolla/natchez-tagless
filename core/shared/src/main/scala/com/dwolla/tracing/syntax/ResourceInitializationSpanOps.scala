package com.dwolla.tracing
package syntax

import cats._
import cats.effect.{Trace => _, _}
import natchez.Span

trait ToResourceInitializationSpanOps {
  implicit def toResourceInitializationSpanOps[F[_], A](rsrc: Resource[F, A]): ResourceInitializationSpanOps[F, A] =
    new ResourceInitializationSpanOps(rsrc)
}

class ResourceInitializationSpanOps[F[_], A](val rsrc: Resource[F, A]) extends AnyVal {
  def initializeWithSpan[G[_]](span: Span[G])
                              (implicit F: MonadCancel[F, _], G: MonadCancel[G, _], gk: Span[G] => (F ~> G)): Resource[G, A] =
    rsrc.mapK(gk(span))
}
