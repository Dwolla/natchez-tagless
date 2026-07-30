package com.dwolla.tracing

import com.dwolla.tagless.mtl.Synthetic
import natchez.{TraceValue, TraceableValue}

/** Traces algebras whose methods take `cats.mtl.Raise` capability parameters —
  * algebras `cats.tagless.aop.Aspect` alone cannot weave, because plain `Aspect`
  * (like `FunctorK`) requires the effect type to appear only as each method's
  * top-level return type. `RaiseAspect` (`raise-aspect-core`) lifts that
  * restriction for `Raise[F, E]` parameters specifically; this module wires the
  * result into natchez tracing the same way `com.dwolla.tracing.syntax` already
  * does for plain `Aspect` instances.
  *
  * ==Worked example==
  *
  * The algebra, its implementation, and how it's traced — version-agnostic, so it's
  * shown once here. Declaring the `RaiseAspect` instance itself
  * (`DeriveRaise.aspect[Validator, TraceableValue, TraceableValue, TraceableValue]`, in
  * `Validator`'s companion, per cats-tagless convention) is identical on Scala 2 and
  * 3 except for one detail: Scala 3 requires `@experimental` on that declaration. See
  * `Scala3UsageNote` in this package for the complete, compiled Scala 3 declaration.
  *
  * {{{
  *   import cats.effect.IO
  *   import cats.mtl.{Handle, Raise}
  *   import cats.syntax.all._
  *   import com.dwolla.tagless.mtl.RaiseAspect
  *   import com.dwolla.tracing.mtl.syntax._
  *   import natchez.{Trace, TraceableValue}
  *
  *   sealed trait ValidationError extends Product with Serializable
  *   final case class TooSmall(i: Int) extends ValidationError
  *
  *   trait Validator[F[_]] {
  *     // the capability parameter, `Raise[F, ValidationError]`, is the reason
  *     // plain `Aspect`/`Derive.aspect` cannot handle this algebra: `F` appears
  *     // here, not only as `bar`'s top-level return type.
  *     def validate(i: Int)(implicit R: Raise[F, ValidationError]): F[String]
  *   }
  *
  *   object Validator {
  *     def apply[F[_]: cats.Applicative]: Validator[F] = new Validator[F] {
  *       def validate(i: Int)(implicit R: Raise[F, ValidationError]): F[String] =
  *         if (i < 0) R.raise(TooSmall(i)) else ("ok:" + i.toString).pure[F]
  *     }
  *
  *     // implicit val raiseAspect: RaiseAspect[Validator, TraceableValue, TraceableValue, TraceableValue] =
  *     //   DeriveRaise.aspect[Validator, TraceableValue, TraceableValue, TraceableValue]
  *     // — declared in the companion per cats-tagless convention, not summoned; one
  *     // instance serves every F, since weave/mapK are separately polymorphic per
  *     // call. The exact declaration is version-specific — see above.
  *   }
  *
  *   def run(implicit trace: Trace[IO], RA: RaiseAspect[Validator, TraceableValue, TraceableValue, TraceableValue]): IO[String] = {
  *     val traced: Validator[IO] = Validator[IO].traceWithInputsAndOutputs
  *
  *     // Raise[IO, ValidationError] doesn't exist on its own — Handle.allowF
  *     // constructs one ad hoc, scoped to this block, and .rescue recovers it,
  *     // handing back the domain error (here just re-rendered as a string; a
  *     // real handler would match on it, e.g. `case TooSmall(i) => ...`).
  *     Handle.allowF[IO, ValidationError] { implicit h =>
  *       traced.validate(5)
  *     }.rescue { e =>
  *       IO.pure("rejected: " + e.toString)
  *     }
  *   }
  * }}}
  *
  * ==Design constraints==
  *
  *   - `weave` requires a `Functor[F]` — the underlying runtime needs it to satisfy
  *     `Raise`'s own abstract `functor` member on the shell values used internally
  *     to transport a capability across the woven boundary.
  *   - `Handle[F, E]` parameters are rejected at derivation time, with a message
  *     pointing at this design: `Handle` ''consumes'' `F` (`handleWith` takes an
  *     `F[A]`), so — unlike `Raise`, which only ever produces `F` values — it is
  *     not transportable across the woven boundary. Take `Raise[F, E]` in the
  *     algebra method and introduce `Handle` at the boundary instead, exactly as
  *     the worked example does with `Handle.allowF`/`.rescue`.
  *   - `F` may appear in a method signature only as the top-level return type, or
  *     inside a `Raise[F, E]` parameter (with `E` not itself mentioning `F`).
  *     Anything else — `F` nested in the return type, an effectful parameter, `F`
  *     behind a context-function return type — is a derivation error naming the
  *     method and parameter.
  *
  * ==Scala 3==
  *
  * Declaring a derived instance (`DeriveRaise.aspect`/`DeriveRaise.functorK`)
  * requires an `@experimental` annotation at the call site, or the `-experimental`
  * compiler flag on Scala 3.4+ — this repository stays on the 3.3.x LTS line, where
  * that flag does not exist, so the annotation is the only option. This matches
  * upstream cats-tagless's own Scala 3 derivation, for the same reason: the
  * underlying `quotes.reflect` APIs (`Symbol.newClass`) are experimental on that
  * line. Scala 2 is unaffected. See the Scala 3-specific companion object in this
  * module's test sources for the annotation placement, or `DeriveRaise`'s own
  * scaladoc in `raise-aspect-macros`.
  *
  * ==The Submarine caveat==
  *
  * A raise that crosses the traced wrapper before being rescued still surfaces in
  * the `Throwable` channel as cats-mtl's own opaque `Submarine` exception (see
  * [[https://github.com/typelevel/cats-mtl/issues/648 cats-mtl#648]]), not as
  * `ValidationError` directly — confirmed directly against natchez's own
  * `natchez.mtl.LocalTrace#span`, which calls `attachError` on any exception that
  * escapes a traced call, before `Handle.rescue` ever gets to catch it. That half
  * of the caveat hasn't changed and isn't fixable from this side; it's upstream.
  *
  * What's no longer true: that the domain error is invisible to the trace. By
  * default, every algebra traced via the `RaiseAspect` path records the typed
  * error as span fields at the moment of the raise — `raise.error.type` and
  * `raise.error.value`, named as `RaiseRecorder.ErrorTypeKey` and
  * `RaiseRecorder.ErrorValueKey` on `RaiseRecorder`'s companion — giving the
  * domain error's runtime class name and its `TraceableValue` rendering, even
  * though the `Throwable` channel above still only shows `Submarine`. This
  * happens with no action required from the caller: `WithInputsAndOutputsTracer`/
  * `WithInputsTracer` resolve a `RaiseRecorder[F, TraceableValue]` and sequence
  * its `OnRaise[F, TraceableValue]` hook at the `raiseLift` interception point,
  * falling back to this `Trace`-based recording whenever no more specific hook
  * is in scope.
  *
  * This default rendering ''is'' redaction-aware, like the rest of this
  * library: `raise.error.value` is the error's `TraceableValue[E]` rendering,
  * so the newtype-plus-custom-`TraceableValue` pattern documented on
  * `TraceWeaveCapturingInputs`/`TraceWeaveCapturingInputsAndOutputs` applies
  * to error values too. An error ADT carrying a token or a card number should
  * declare a `TraceableValue` that omits or masks it, exactly as a sensitive
  * parameter type would. Note that `raise.error.type` still records the
  * error's runtime class name unconditionally.
  *
  * ==Overriding the default recording==
  *
  * `RaiseRecorder` resolution is just implicit priority: a user-supplied
  * `OnRaise[F, TraceableValue]` (`com.dwolla.tagless.mtl.OnRaise`) outranks
  * the `Trace`-based default ''if the compiler's implicit search actually
  * finds it'' — and that depends on where it's declared. Implicit scope for
  * `OnRaise[F, TraceableValue]` reaches the companions of `OnRaise`, `F`, and
  * `TraceableValue`; a user's own error ADT appears in none of those, so
  * ''declaring the hook in the error type's companion object does not work'' —
  * unlike a `TraceableValue[MyError]` instance, which does belong there,
  * because `TraceableValue[MyError]` mentions `MyError` and the hook's type
  * doesn't. The hook must instead live somewhere ordinary lexical scoping
  * reaches it: a local `implicit val`/`given` in scope at the call site, or an
  * import. A hook the compiler doesn't find isn't an error — resolution
  * quietly falls back to the `Trace`-based default above, which (per the
  * previous section) still renders through `TraceableValue`, so a missed
  * override degrades to a redaction-aware default rather than to raw
  * `toString`.
  *
  * {{{
  *   import cats.Applicative
  *   import com.dwolla.tagless.mtl.OnRaise
  *   import natchez.{Trace, TraceableValue}
  *
  *   // Reusing `ValidationError`/`TooSmall` from the worked example above — this
  *   // block shares that scope, so no need to redeclare them.
  *
  *   // Just having this implicit in lexical scope is the entire override:
  *   // RaiseRecorder's fromOnRaise instance outranks fromTrace, the default
  *   // used above — but only because this is a local implicit val, not a
  *   // member of ValidationError's own companion object, which implicit
  *   // search for OnRaise[F, TraceableValue] would never look inside.
  *   implicit def onRaiseValidationError[F[_] : Applicative](implicit T: Trace[F]): OnRaise[F, TraceableValue] =
  *     new OnRaise[F, TraceableValue] {
  *       def apply[E](e: E)(implicit ev: TraceableValue[E]): F[Unit] = e match {
  *         case TooSmall(i) => T.put("validation.too_small.value" -> i)
  *         // Any other error reaching this hook is rendered through the ambient
  *         // `TraceableValue[E]` rather than ignored — this is what `ev` is for.
  *         case _ => T.put("validation.other.value" -> ev.toTraceValue(e))
  *       }
  *     }
  *
  *   // RaiseRecorder.fromOnRaise picks up onRaiseValidationError automatically;
  *   // no other change is needed at any tracing call site.
  *   def recorderResolvesViaOnRaise[F[_] : Applicative](implicit T: Trace[F]): RaiseRecorder[F, TraceableValue] =
  *     implicitly[RaiseRecorder[F, TraceableValue]]
  * }}}
  */
package object mtl {

  /** The `Synthetic[TraceableValue]` the `RaiseAspect` runtime needs to build the
    * shell `Weave`s inside `raiseLift`.
    *
    * Per laws L5–L7 (`raise-aspect-laws`), a synthesized instance's output is never
    * observable through the public API: the shell is unwrapped immediately via
    * `codomain.target`, and a raised `F[A]` never yields an `A` for anything to
    * render. The sentinel string exists only so that, if that soundness claim were
    * ever violated by a future bug, the value would be immediately recognizable in
    * a captured span rather than silently indistinguishable from a real one.
    */
  implicit val syntheticTraceableValue: Synthetic[TraceableValue] =
    new Synthetic[TraceableValue] {
      def apply[A]: TraceableValue[A] = _ => TraceValue.StringValue("«raised»")
    }
}
