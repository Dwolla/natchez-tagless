package com.dwolla.metrics.otel4s

/** The value recorded as `rpc.system.name` — the RPC framework in use, e.g.
  * `RpcSystem("thrift")`. The semantic conventions list well-known values
  * (`grpc`, `dubbo`, `connectrpc`, `jsonrpc`); for anything else, use a short
  * lowercase name.
  *
  * A distinct type rather than a `String` so it cannot be swapped with the
  * `RpcService` beside it. Not a case class, so it can gain members without
  * breaking binary compatibility. Two values with the same name are equal.
  */
sealed abstract class RpcSystem {
  def name: String

  override def toString: String = s"RpcSystem($name)"

  final override def equals(other: Any): Boolean = other match {
    case that: RpcSystem => name == that.name
    case _ => false
  }

  final override def hashCode: Int = name.hashCode
}

object RpcSystem {
  def apply(name: String): RpcSystem = new Impl(name)

  private final class Impl(override val name: String) extends RpcSystem
}
