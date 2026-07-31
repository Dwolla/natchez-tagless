package com.dwolla.tracing.mtl
package syntax

import cats.{Apply, FlatMap}
import com.dwolla.tagless.mtl.{Synthetic, WeaveInterpreter}
import com.dwolla.tracing.{TraceWeaveCapturingInputs, TraceWeaveCapturingInputsAndOutputs}
import natchez.{Trace, TraceValue, TraceableValue}

/** Mirrors `com.dwolla.tracing.syntax.ToTraceWeaveOps`/`TraceWeaveOps`, but
  * resolves either an `Aspect` or a `RaiseAspect` instance for the algebra via
  * `WeaveInterpreter` — see that type class for why a single sealed type class
  * is what makes one strategy take priority over the other.
  *
  * The signatures are deliberately identical to the non-mtl `TraceWeaveOps`'s.
  * The two syntax packages cannot be imported into the same scope (that
  * reintroduces exactly the ambiguity this module exists to avoid), so
  * switching an import between them must not break call sites.
  *
  * Import `com.dwolla.tracing.mtl.syntax._` in place of
  * `com.dwolla.tracing.syntax._` to get both capabilities under the same call
  * syntax.
  */
trait ToRaiseTraceWeaveOps {
  implicit def toRaiseTraceWeaveOps[Alg[_[_]], F[_]](alg: Alg[F]): RaiseTraceWeaveOps[Alg, F] =
    new RaiseTraceWeaveOps(alg)

  /** The `Synthetic[TraceableValue]` the `RaiseAspect` runtime needs to build the
    * shell `Weave`s inside `raiseLift`.
    *
    * Per laws L5–L7 (`raise-aspect-laws`), no synthesized instance reaches an
    * interpreter along the derived path: the shell is unwrapped immediately via
    * `codomain.target`, and a raised `F[A]` never yields an `A` for anything to
    * render. That guarantee is about the derivation, not about the type — see
    * `Synthetic`'s scaladoc for the one public route by which a synthesized
    * instance ''can'' be obtained, and why no law can forbid it.
    *
    * This instance is a constant sentinel rather than a rendering of anything,
    * which is what makes that route harmless here: the worst it can produce is a
    * recognizably wrong span attribute, never a leaked value. Keep it that way —
    * a `Synthetic[TraceableValue]` that rendered its argument would turn an API
    * wart into a redaction hole.
    *
    * Lives here, on the syntax trait, rather than in the package object: `syn` is
    * a formal implicit parameter of `WeaveInterpreter.fromRaiseAspect`
    * (`raise-aspect-core`, generic in `Cod` since that module can't know about
    * `TraceableValue`), so it's resolved fresh at every call to `traceWithInputs`/
    * `traceWithInputsAndOutputs`, using that call site's own implicit scope — not
    * baked in once at library-compile time the way the old fixed-`Cod` tracer's
    * internal call to `raiseLift` was. Declaring it in the package object would
    * only reach callers lexically inside `com.dwolla.tracing.mtl`; declaring it
    * here means a single `import com.dwolla.tracing.mtl.syntax._` is sufficient
    * for both syntax methods, from any package — the ergonomic this milestone
    * would otherwise have regressed.
    */
  implicit val syntheticTraceableValue: Synthetic[TraceableValue] =
    new Synthetic[TraceableValue] {
      def apply[A]: TraceableValue[A] = _ => TraceValue.StringValue("«raised»")
    }
}

class RaiseTraceWeaveOps[Alg[_[_]], F[_]](val alg: Alg[F]) extends AnyVal {

  /** `Err` is pinned to `TraceableValue` independently of `Cod`, so opting out
    * of return-value rendering does not silently disable typed error
    * recording.
    */
  def traceWithInputs[Cod[_]](implicit
      F: Apply[F],
      T: Trace[F],
      R: RaiseRecorder[F, TraceableValue],
      ev: WeaveInterpreter[Alg, TraceableValue, Cod, TraceableValue, F]
  ): Alg[F] =
    ev(alg)(TraceWeaveCapturingInputs[F, Cod], R.onRaise)

  def traceWithInputsAndOutputs(implicit
      F: FlatMap[F],
      T: Trace[F],
      R: RaiseRecorder[F, TraceableValue],
      ev: WeaveInterpreter[Alg, TraceableValue, TraceableValue, TraceableValue, F]
  ): Alg[F] =
    ev(alg)(TraceWeaveCapturingInputsAndOutputs[F], R.onRaise)
}
