package com.dwolla.tracing.mtl
package syntax

/** Mirrors `com.dwolla.tracing.syntax.ToTraceWeaveOps`/`TraceWeaveOps`, but resolves
  * either an `Aspect` or a `RaiseAspect` instance for the algebra — see
  * [[WithInputsAndOutputsTracer]] for why this can't simply extend the existing one.
  *
  * Import `com.dwolla.tracing.mtl.syntax._` in place of `com.dwolla.tracing.syntax._`
  * to get both capabilities under the same call syntax; importing both in the same
  * scope reintroduces the ambiguity this module exists to avoid, since neither can be
  * made to take priority over the other across module boundaries.
  */
trait ToRaiseTraceWeaveOps {
  implicit def toRaiseTraceWeaveOps[Alg[_[_]], F[_]](alg: Alg[F]): RaiseTraceWeaveOps[Alg, F] =
    new RaiseTraceWeaveOps(alg)
}

class RaiseTraceWeaveOps[Alg[_[_]], F[_]](val alg: Alg[F]) extends AnyVal {
  def traceWithInputs[Cod[_]](implicit ev: WithInputsTracer[Alg, Cod, F]): Alg[F] =
    ev(alg)

  def traceWithInputsAndOutputs(implicit ev: WithInputsAndOutputsTracer[Alg, F]): Alg[F] =
    ev(alg)
}
