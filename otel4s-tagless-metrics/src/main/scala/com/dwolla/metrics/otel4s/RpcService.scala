package com.dwolla.metrics.otel4s

/** The fully-qualified name of the RPC service an algebra implements or calls,
  * e.g. `RpcService("com.example.FooService")`. Recorded as the
  * part of `rpc.method` before the `/`; the method name after it comes from
  * the algebra. Supplied by the caller because cats-tagless only knows an
  * algebra's simple name, and the semantic conventions want the qualified one.
  *
  * A distinct type rather than a `String` so it cannot be swapped with the
  * `RpcSystem` beside it. Not a case class, so it can gain members without
  * breaking binary compatibility. Two values with the same name are equal.
  */
sealed abstract class RpcService {
  def name: String

  override def toString: String = s"RpcService($name)"

  final override def equals(other: Any): Boolean = other match {
    case that: RpcService => name == that.name
    case _ => false
  }

  final override def hashCode: Int = name.hashCode
}

object RpcService {
  def apply(name: String): RpcService = new Impl(name)

  private final class Impl(override val name: String) extends RpcService
}
