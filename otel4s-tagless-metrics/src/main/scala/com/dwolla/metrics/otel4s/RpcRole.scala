package com.dwolla.metrics.otel4s

/** Which side of a Remote Procedure Call an instrumented algebra is. A server
  * implementation and a client of the same interface have the same type, so
  * the caller has to say which one it is instrumenting; it selects the
  * OpenTelemetry metric the call durations are recorded to.
  */
sealed abstract class RpcRole private[otel4s] (private[otel4s] val callDurationMetricName: String,
                                               private[otel4s] val callDurationDescription: String)
  extends Product with Serializable

object RpcRole {
  /** The algebra is a server-side implementation handling incoming calls. */
  case object Server extends RpcRole(
    "rpc.server.call.duration",
    "Measures the duration of an incoming Remote Procedure Call (RPC).",
  )

  /** The algebra is a client making outgoing calls. */
  case object Client extends RpcRole(
    "rpc.client.call.duration",
    "Measures the duration of an outgoing Remote Procedure Call (RPC).",
  )
}
