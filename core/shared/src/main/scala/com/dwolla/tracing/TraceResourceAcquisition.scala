package com.dwolla.tracing

import cats.effect.{Trace => _, _}
import cats.mtl.Local
import cats.syntax.all._
import com.dwolla.tracing.syntax._
import natchez._
import natchez.mtl._

object TraceResourceAcquisition {
  /**
   * Given an entrypoint and a root span name, introduce tracing for the
   * resource returned by the passed `resource` function. The acquisition
   * and finalization phases of the `Resource[F, A]` will be traced in
   * separate root spans.
   *
   * @param entryPoint a Natchez `EntryPoint[F]` used to create the new root spans
   * @param name       the name of the root span. a good default might be `"app initialization"`
   * @param resource   function that returns a resource to be traced using the ambient `Trace[F]` passed to the function
   * @param L          an implicit `Local[F, Span[F]]` instance for the given effect type
   * @tparam F the effect type in which to run
   * @tparam A inner type of the `Resource[F, A]`
   * @return `Resource[F, A]` that is the same as the one returned by the `resource` function, but with tracing introduced
   */
  def apply[F[_] : MonadCancelThrow, A](entryPoint: EntryPoint[F], name: String)
                                       (resource: Trace[F] => Resource[F, A])
                                       (implicit L: Local[F, Span[F]]): Resource[F, A] =
    TraceResourceAcquisition(entryPoint, name, Span.Options.Defaults)(resource)

  /**
   * Given an entrypoint and a root span name, introduce tracing for the
   * resource returned by the passed `resource` function. The acquisition
   * and finalization phases of the `Resource[F, A]` will be traced in
   * separate root spans.
   *
   * @param entryPoint a Natchez `EntryPoint[F]` used to create the new root spans
   * @param name       the name of the root span. a good default might be `"app initialization"`
   * @param resource   function that returns a resource to be traced using the ambient `Trace[F]` passed to the function
   * @param options    options to set on the newly created root span
   * @param L          an implicit `Local[F, Span[F]]` instance for the given effect type
   * @tparam F the effect type in which to run
   * @tparam A inner type of the `Resource[F, A]`
   * @return `Resource[F, A]` that is the same as the one returned by the `resource` function, but with tracing introduced
   */
  def apply[F[_] : MonadCancelThrow, A](entryPoint: EntryPoint[F], name: String, options: Span.Options)
                                       (resource: Trace[F] => Resource[F, A])
                                       (implicit L: Local[F, Span[F]]): Resource[F, A] =
    TraceResourceAcquisition(entryPoint, name, options, resource(natchezMtlTraceForLocal))

  /**
   * Given an entrypoint and a root span name, ensure that the acquisition
   * and finalization phases of the passed `Resource[F, A]` are traced in
   * separate root spans.
   *
   * @param entryPoint a Natchez `EntryPoint[F]` used to create the new root spans
   * @param name       the name of the root span. a good default might be `"app initialization"`
   * @param resource   a resource that was traced using the `Local[F, Span[F]]` passed implicitly as `L`
   * @param L          an implicit `Local[F, Span[F]]` instance for the given effect type
   * @tparam F the effect type in which to run
   * @tparam A inner type of the `Resource[F, A]`
   * @return `Resource[F, A]` that is the same as the one returned by the `resource` function, but with tracing introduced
   */
  def apply[F[_] : MonadCancelThrow, A](entryPoint: EntryPoint[F], name: String, resource: Resource[F, A])
                                       (implicit L: Local[F, Span[F]]): Resource[F, A] =
    TraceResourceAcquisition(entryPoint, name, Span.Options.Defaults, resource)

  /**
   * Given an entrypoint and a root span name, ensure that the acquisition
   * and finalization phases of the passed `Resource[F, A]` are traced in
   * separate root spans.
   *
   * @param entryPoint a Natchez `EntryPoint[F]` used to create the new root spans
   * @param name       the name of the root span. a good default might be `"app initialization"`
   * @param resource   a resource that was traced using the `Local[F, Span[F]]` passed implicitly as `L`
   * @param options    options to set on the newly created root span
   * @param L          an implicit `Local[F, Span[F]]` instance for the given effect type
   * @tparam F the effect type in which to run
   * @tparam A inner type of the `Resource[F, A]`
   * @return `Resource[F, A]` that is the same as the one returned by the `resource` function, but with tracing introduced
   */
  def apply[F[_] : MonadCancelThrow, A](entryPoint: EntryPoint[F],
                                        name: String,
                                        options: Span.Options,
                                        resource: Resource[F, A])
                                       (implicit L: Local[F, Span[F]]): Resource[F, A] =
    Resource {
      entryPoint
        .runInRoot(name, options) {
          resource
            .allocated
            .map { case (a, finalizer) =>
              a -> entryPoint
                .root(s"$name.finalize")
                .use {
                  Local[F, Span[F]].scope(finalizer)
                }
            }
        }
    }
}
