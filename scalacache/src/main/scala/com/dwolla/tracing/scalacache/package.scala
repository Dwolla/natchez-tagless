package com.dwolla.tagless

import _root_.scalacache._
import cats._
import cats.tagless._
import cats.tagless.aop._
import com.dwolla.tracing._
import io.circe.Codec
import io.circe.generic.semiauto._
import natchez.Trace

import scala.concurrent.duration.Duration

package object scalacache {
  implicit def cacheInvariantK[K, V]: InvariantK[Cache[*[_], K, V]] = Derive.invariantK[Cache[*[_], K, V]]

  implicit val flagsEncoder: Codec[Flags] = deriveCodec

  // unfortunately, due to the F[V] parameter in cachingF, this transformation cannot be automatically derived
  def weaveCache[F[_], K: ToTraceValue, V: ToTraceValue](af: Cache[F, K, V]): Cache[Aspect.Weave.Domain[F, ToTraceValue, *], K, V] =
    new Cache[Aspect.Weave.Domain[F, ToTraceValue, *], K, V] {
      override def get(key: K)(implicit flags: Flags): Aspect.Weave.Domain[F, ToTraceValue, Option[V]] =
        Aspect.Weave("Cache", List(
          List(Aspect.Advice("key", Eval.now(key))),
          List(Aspect.Advice("flags", Eval.now(flags))),
        ), Aspect.Advice("get", af.get(key)))

      override def put(key: K)
                      (value: V, ttl: Option[Duration])(implicit flags: Flags): Aspect.Weave.Domain[F, ToTraceValue, Unit] =
        Aspect.Weave("Cache", List(
          List(Aspect.Advice("key", Eval.now(key))),
          List(Aspect.Advice("value", Eval.now(value)), Aspect.Advice("ttl", Eval.now(ttl))),
          List(Aspect.Advice("flags", Eval.now(flags))),
        ), Aspect.Advice("put", af.put(key)(value, ttl)))

      override def remove(key: K): Aspect.Weave.Domain[F, ToTraceValue, Unit] =
        Aspect.Weave("Cache", List(List(Aspect.Advice("key", Eval.now(key)))), Aspect.Advice("remove", af.remove(key)))

      override def removeAll: Aspect.Weave.Domain[F, ToTraceValue, Unit] =
        Aspect.Weave("Cache", List.empty, Aspect.Advice("removeAll", af.removeAll))

      override def caching(key: K)(ttl: Option[Duration])(f: => V)(implicit flags: Flags): Aspect.Weave.Domain[F, ToTraceValue, V] =
        Aspect.Weave("Cache", List(
          List(Aspect.Advice("key", Eval.now(key))),
          List(Aspect.Advice("ttl", Eval.now(ttl))),
          List(Aspect.Advice("f", Eval.always(f))),
          List(Aspect.Advice("flags", Eval.now(flags))),
        ), Aspect.Advice("caching", af.caching(key)(ttl)(f)))

      override def cachingF(key: K)(ttl: Option[Duration])(f: Aspect.Weave.Domain[F, ToTraceValue, V])(implicit flags: Flags): Aspect.Weave.Domain[F, ToTraceValue, V] =
        Aspect.Weave("Cache", List(
          List(Aspect.Advice("key", Eval.now(key))),
          List(Aspect.Advice("ttl", Eval.now(ttl))),
          List(Aspect.Advice("f", Eval.now("unevaluated F[V]"))),
          List(Aspect.Advice("flags", Eval.now(flags))),
        ), Aspect.Advice("cachingF", af.cachingF(key)(ttl)(f.codomain.target)))

      override def close: Aspect.Weave.Domain[F, ToTraceValue, Unit] =
        Aspect.Weave("Cache", List.empty, Aspect.Advice("close", af.close))
    }

  implicit def toCacheOps[F[_], K, V](cache: Cache[F, K, V]): CacheOps[F, K, V] = new CacheOps(cache)
}

package scalacache {
  class CacheOps[F[_], K, V](val cache: Cache[F, K, V]) extends AnyVal {
    def weave(implicit K: ToTraceValue[K], V: ToTraceValue[V]): Cache[Aspect.Weave.Domain[F, ToTraceValue, *], K, V] =
      weaveCache(cache)

    def weaveTracing(implicit F: Apply[F], T: Trace[F], K: ToTraceValue[K], V: ToTraceValue[V]): Cache[F, K, V] =
      InvariantK[Cache[*[_], K, V]].imapK(cache.weave)(new TraceWeaveCapturingInputs)(new CacheWeaveFunctionK[F])
  }

  private class CacheWeaveFunctionK[F[_]] extends (F ~> Aspect.Weave.Domain[F, ToTraceValue, *]) {
    override def apply[A](fa: F[A]): Aspect.Weave.Domain[F, ToTraceValue, A] =
    // This is kind of cheating; it takes advantage of the fact that F[_] only
    // appears in a contravariant position in the cachingF method
      Aspect.Weave("Cache", List.empty, Aspect.Advice("cachingF", fa))
  }
}
