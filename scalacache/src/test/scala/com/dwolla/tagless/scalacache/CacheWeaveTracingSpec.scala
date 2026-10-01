package com.dwolla.tagless.scalacache

import _root_.scalacache.{Cache, Flags}
import cats.effect.{IO, Trace as _}
import munit.CatsEffectSuite
import natchez.InMemory.NatchezCommand.Put
import natchez.TraceValue.StringValue
import natchez.{InMemory, Trace, TraceValue}

import scala.concurrent.duration.*

/** Pins the span attributes `weaveTracing` records, including those for the
  * `Cache`'s own `Flags` and `Option[Duration]` parameters.
  */
class CacheWeaveTracingSpec extends CatsEffectSuite {
  private val cache: Cache[IO, String, String] = new Cache[IO, String, String] {
    override def get(key: String)(implicit flags: Flags): IO[Option[String]] = IO.none
    override def put(key: String)(value: String, ttl: Option[Duration])(implicit flags: Flags): IO[Unit] = IO.unit
    override def remove(key: String): IO[Unit] = IO.unit
    override def removeAll: IO[Unit] = IO.unit
    override def caching(key: String)(ttl: Option[Duration])(f: => String)(implicit flags: Flags): IO[String] = IO(f)
    override def cachingF(key: String)(ttl: Option[Duration])(f: IO[String])(implicit flags: Flags): IO[String] = f
    override def close: IO[Unit] = IO.unit
  }

  private def recordedAttributes(use: Cache[IO, String, String] => IO[Unit]): IO[List[(String, TraceValue)]] =
    InMemory.EntryPoint.create[IO].flatMap { entryPoint =>
      entryPoint.root("root").use { root =>
        Trace.ioTrace(root).flatMap { implicit trace =>
          use(cache.weaveTracing)
        }
      } >> entryPoint.ref.get.map(_.toList.collect { case (_, Put(fields)) => fields }.flatten)
    }

  test("put records its key, value, ttl, flags, and return value") {
    recordedAttributes(_.put("key")("value", Some(1.second))(Flags(readsEnabled = true, writesEnabled = false)))
      .map { attributes =>
        assertEquals(attributes, List(
          "Cache.put.key" -> StringValue("key"),
          "Cache.put.value" -> StringValue("value"),
          "Cache.put.ttl" -> StringValue("1 second"),
          "Cache.put.flags" -> StringValue("""{"readsEnabled":true,"writesEnabled":false}"""),
          "Cache.put.returnValue" -> StringValue("()"),
        ))
      }
  }

  test("put records a missing ttl as None") {
    recordedAttributes(_.put("key")("value", None)(Flags.defaultFlags))
      .map { attributes =>
        assert(attributes.contains("Cache.put.ttl" -> StringValue("None")), attributes)
      }
  }
}
