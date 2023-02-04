package com.dwolla.tracing.syntax

import cats.effect.{MonadCancelThrow, Resource}
import cats.mtl.Local
import cats.syntax.all._
import com.dwolla.tracing.TraceResourceAcquisition
import natchez.{EntryPoint, Span, Trace}

trait ToTraceResourceLifecycleOps {
  implicit def toTraceResourceLifecycleOps[F[_], A](resource: Resource[F, A]): TraceResourceLifecycleOps[F, A] =
    new TraceResourceLifecycleOps(resource)
}

class TraceResourceLifecycleOps[F[_], A](val resource: Resource[F, A]) extends AnyVal {
  /**
   * Wrap the acquisition and release phases of the given `Resource[F, A]`
   * in children spans of the given `Trace[F]`.
   *
   * Note: this requires a `Trace[F]` built from `Local[F, Span[F]]`
   * (e.g. using `natchez.mtl.natchezMtlTraceForLocal`) or
   * `Trace[Kleisli[F, Span[F], *]]`. Notably, the `natchez.Trace.ioTrace`
   * instance is *not* compatible with this method.
   *
   * @param name the base of the span name to use for acquisition (the finalization span will append ".finalize" to this value to form its span name)
   * @param F    a `MonadCancelThrow[F]` instance for the given effect type
   * @param T    a `Trace[F]` for the given effect type. See the note in the description above; not every `Trace[F]` instance will work
   * @return the input `Resource[F, A]` with its acquisition and release phases wrapped in Natchez spans
   */
  def traceResourceLifecycleAs(name: String)
                              (implicit F: MonadCancelThrow[F],
                               T: Trace[F]): Resource[F, A] =
    Resource {
      Trace[F].spanR(name)
        .use(_(resource.allocated))
        .nested
        .map(f => Trace[F].spanR(s"$name.finalize").use(_(f)))
        .value
    }

  /**
   * Given an entrypoint and a root span name, ensure that the acquisition
   * and finalization phases of the passed `Resource[F, A]` are traced in
   * separate root spans.
   *
   * @param name       the base of the span name to use for acquisition (the finalization span will append ".finalize" to this value to form its span name)
   * @param entryPoint an `EntryPoint[F]` capable of creating new root spans
   * @param F          a `MonadCancelThrow[F]` instance for the given effect type
   * @param L          a `Local[F, Span[F]]` instance for the given effect type
   * @return the input `Resource[F, A]` with its acquisition and release phases wrapped in Natchez root spans
   */
  def traceResourceLifecycleInRootSpans(name: String,
                                        entryPoint: EntryPoint[F])
                                       (implicit
                                        F: MonadCancelThrow[F],
                                        L: Local[F, Span[F]]): Resource[F, A] =
    TraceResourceAcquisition(entryPoint, name, resource)
}
