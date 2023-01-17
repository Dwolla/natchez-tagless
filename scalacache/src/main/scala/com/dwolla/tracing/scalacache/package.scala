package com.dwolla.tagless

import _root_.scalacache._
import cats._
import cats.tagless._
import cats.tagless.aop._
import com.dwolla.tracing._
import io.circe.Codec
import io.circe.generic.semiauto._
import natchez._
import LowPriorityTraceableValueInstances._

import scala.concurrent.duration.Duration

/**
 * Provides cats-tagless instances for ScalaCache's `Cache` trait.
 */
package object scalacache {
  /**
   * An `InvariantK[Cache[*[_], K, V]]` instance for arbitrary `K` and
   * `V` types. This must be `InvariantK` and not e.g. `FunctorK`
   * because of the `F[V]` parameter on the `cachingF` method.
   */
  implicit def cacheInvariantK[K, V]: InvariantK[Cache[*[_], K, V]] = Derive.invariantK[Cache[*[_], K, V]]

  implicit val flagsEncoder: Codec[Flags] = deriveCodec

  /**
   * An implementation of `Cache[Aspect.Weave[F, Cod, Dom, *], K, V]`
   * cannot be auto-derived via `Derive.aspect` because the `cachingF`
   * method has an `F[V]` parameter. We handle it here by kind of
   * cheating, and essentially hard-coding a
   * `F ~> Aspect.Weave[F, Cod, Dom, *]` for it.
   *
   * See the comment in `CacheWeaveFunctionK` below.
   */
  def weaveCache[F[_], Dom[_], Cod[_], K, V](af: Cache[F, K, V])
                                            (implicit
                                             DomK: Dom[K],
                                             DomV: Dom[V],
                                             CodV: Cod[V],
                                             DomF: Dom[Flags],
                                             COV: Cod[Option[V]],
                                             DOD: Dom[Option[Duration]],
                                             CodU: Cod[Unit],
                                             DomS: Dom[String],
                                            ): Cache[Aspect.Weave[F, Dom, Cod, *], K, V] =
    new Cache[Aspect.Weave[F, Dom, Cod, *], K, V] {
      override def get(key: K)(implicit flags: Flags): Aspect.Weave[F, Dom, Cod, Option[V]] =
        Aspect.Weave("Cache", List(
          List(Aspect.Advice("key", Eval.now(key))),
          List(Aspect.Advice("flags", Eval.now(flags))),
        ), Aspect.Advice("get", af.get(key)))

      override def put(key: K)
                      (value: V, ttl: Option[Duration])(implicit flags: Flags): Aspect.Weave[F, Dom, Cod, Unit] =
        Aspect.Weave("Cache", List(
          List(Aspect.Advice("key", Eval.now(key))),
          List(Aspect.Advice("value", Eval.now(value)), Aspect.Advice("ttl", Eval.now(ttl))),
          List(Aspect.Advice("flags", Eval.now(flags))),
        ), Aspect.Advice("put", af.put(key)(value, ttl)))

      override def remove(key: K): Aspect.Weave[F, Dom, Cod, Unit] =
        Aspect.Weave("Cache", List(List(Aspect.Advice("key", Eval.now(key)))), Aspect.Advice("remove", af.remove(key)))

      override def removeAll: Aspect.Weave[F, Dom, Cod, Unit] =
        Aspect.Weave("Cache", List.empty, Aspect.Advice("removeAll", af.removeAll))

      @deprecated("prefer cachingF", "0.2")
      override def caching(key: K)(ttl: Option[Duration])(f: => V)(implicit flags: Flags): Aspect.Weave[F, Dom, Cod, V] =
        Aspect.Weave("Cache", List(
          List(Aspect.Advice("key", Eval.now(key))),
          List(Aspect.Advice("ttl", Eval.now(ttl))),
          List(Aspect.Advice("f", Eval.now("as-yet unevaluated lazy V"))),
          List(Aspect.Advice("flags", Eval.now(flags))),
        ), Aspect.Advice("caching", af.caching(key)(ttl)(f)))

      override def cachingF(key: K)(ttl: Option[Duration])(f: Aspect.Weave[F, Dom, Cod, V])(implicit flags: Flags): Aspect.Weave[F, Dom, Cod, V] =
        Aspect.Weave("Cache", List(
          List(Aspect.Advice("key", Eval.now(key))),
          List(Aspect.Advice("ttl", Eval.now(ttl))),
          List(Aspect.Advice("f", Eval.now("as-yet unevaluated F[V]"))),
          List(Aspect.Advice("flags", Eval.now(flags))),
        ), Aspect.Advice("cachingF", af.cachingF(key)(ttl)(f.codomain.target)))

      override def close: Aspect.Weave[F, Dom, Cod, Unit] =
        Aspect.Weave("Cache", List.empty, Aspect.Advice("close", af.close))
    }

  implicit def toCacheOps[F[_], K, V](cache: Cache[F, K, V]): CacheOps[F, K, V] = new CacheOps(cache)
}

package scalacache {
  class CacheOps[F[_], K, V](val cache: Cache[F, K, V]) extends AnyVal {
    def weave[Dom[_], Cod[_]](implicit
                              DomK: Dom[K],
                              DomV: Dom[V],
                              CodV: Cod[V],
                              DomF: Dom[Flags],
                              COV: Cod[Option[V]],
                              DOD: Dom[Option[Duration]],
                              CodU: Cod[Unit],
                              DomS: Dom[String],
                             ): Cache[Aspect.Weave[F, Dom, Cod, *], K, V] =
      weaveCache(cache)

    def weaveTracing(implicit F: FlatMap[F], T: Trace[F], K: TraceableValue[K], V: TraceableValue[V]): Cache[F, K, V] =
      InvariantK[Cache[*[_], K, V]].imapK(cache.weave)(new TraceWeaveCapturingInputsAndOutputs)(new CacheWeaveFunctionK[F])
  }

  private class CacheWeaveFunctionK[F[_]] extends (F ~> Aspect.Weave[F, TraceableValue, TraceableValue, *]) {
    override def apply[A](fa: F[A]): Aspect.Weave[F, TraceableValue, TraceableValue, A] = {
      implicit val faTraceableValue: TraceableValue[A] = _ => "unevaluated F[A] effect"

      // This seems like it's kind of cheating; it takes advantage of the fact that F[_]
      // only appears in a contravariant position in the cachingF method. We don't capture
      // its value, but we do capture that specific method name. AFAICT this doesn't
      // matter as this value never appears in the trace attributes. 🤷
      Aspect.Weave("Cache", List.empty, Aspect.Advice("cachingF", fa))
    }
  }
}
