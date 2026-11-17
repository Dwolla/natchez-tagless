package com.dwolla.metrics.otel4s.mtl

/** Syntax for metering algebras whose methods take `cats.mtl.Raise`:
  * `alg.withMetrics(...)`. Import it in place of
  * `com.dwolla.metrics.otel4s.syntax._`; see `RaiseWithMetricsOps`.
  */
package object syntax extends ToRaiseWithMetricsOps
