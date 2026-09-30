package com.dwolla.tagless

import cats.tagless.aop.Aspect

/** The `algebraName.methodName` naming convention shared by every tracing
  * backend's span and attribute names. Both natchez and otel4s backends
  * derived this same formula independently at each call site; extracting it
  * here means a future change to the convention (see
  * `com.dwolla.tracing.syntax.TraceParamsOps`'s own TODO about attribute name
  * verbosity) only has one place to change.
  */
object WeaveNaming {
  implicit class WeaveNamingOps[F[_], Dom[_], Cod[_], A](private val weave: Aspect.Weave[F, Dom, Cod, A]) extends AnyVal {
    def qualifiedMethodName: String = s"${weave.algebraName}.${weave.codomain.name}"
  }
}
