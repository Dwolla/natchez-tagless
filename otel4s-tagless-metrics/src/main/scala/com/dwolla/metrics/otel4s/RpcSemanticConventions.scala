package com.dwolla.metrics.otel4s

import org.typelevel.otel4s.AttributeKey

/** The RPC attribute keys this module records. Our own constants rather than a
  * dependency on otel4s's experimental semantic-conventions module, which makes
  * no binary-compatibility promise; `RpcSemanticConventionsSpec` checks them
  * against it. `error.type` is stable and comes from `otel4s-semconv` directly.
  */
private[otel4s] object RpcSemanticConventions {
  val RpcSystemName: AttributeKey[String] = AttributeKey("rpc.system.name")
  val RpcMethod: AttributeKey[String] = AttributeKey("rpc.method")
}
