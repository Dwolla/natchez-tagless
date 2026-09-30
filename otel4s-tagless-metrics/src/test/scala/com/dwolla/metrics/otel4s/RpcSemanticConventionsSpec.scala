package com.dwolla.metrics.otel4s

import munit.FunSuite
import org.typelevel.otel4s.semconv.MetricSpec
import org.typelevel.otel4s.semconv.attributes.ErrorAttributes
import org.typelevel.otel4s.semconv.experimental.metrics.RpcExperimentalMetrics.{ClientCallDuration, ServerCallDuration}

/** The RPC names main code inlines, checked against otel4s's experimental
  * semantic-conventions module — which main code must not depend on, because
  * it makes no binary-compatibility promise.
  */
class RpcSemanticConventionsSpec extends FunSuite {
  private val roles: List[(RpcRole, MetricSpec)] = List(
    RpcRole.Server -> ServerCallDuration,
    RpcRole.Client -> ClientCallDuration,
  )

  roles.foreach { case (role, spec) =>
    test(s"$role's call-duration metric matches ${spec.name}") {
      assertEquals(role.callDurationMetricName, spec.name)
      assertEquals(role.callDurationDescription, spec.description)
      assertEquals(CallDuration.DurationUnit, spec.unit)
    }
  }

  test("the attribute keys match both RPC call-duration metrics' attribute specs") {
    assertEquals(RpcSemanticConventions.RpcSystemName, ServerCallDuration.AttributeSpecs.rpcSystemName.key)
    assertEquals(RpcSemanticConventions.RpcMethod, ServerCallDuration.AttributeSpecs.rpcMethod.key)
    assertEquals(ErrorAttributes.ErrorType, ServerCallDuration.AttributeSpecs.errorType.key)

    assertEquals(RpcSemanticConventions.RpcSystemName, ClientCallDuration.AttributeSpecs.rpcSystemName.key)
    assertEquals(RpcSemanticConventions.RpcMethod, ClientCallDuration.AttributeSpecs.rpcMethod.key)
    assertEquals(ErrorAttributes.ErrorType, ClientCallDuration.AttributeSpecs.errorType.key)
  }

  test("every attribute key the RPC flavor sets is one the spec defines for the metric") {
    val ourKeys = Set(RpcSemanticConventions.RpcSystemName, RpcSemanticConventions.RpcMethod, ErrorAttributes.ErrorType)
    List(ServerCallDuration, ClientCallDuration).foreach { spec =>
      val specKeys = spec.attributeSpecs.map(_.key).toSet[Any]
      assert(ourKeys.forall(specKeys.contains), s"${spec.name} does not define ${ourKeys.filterNot(specKeys.contains)}")
    }
  }
}
