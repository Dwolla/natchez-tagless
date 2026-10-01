package com.dwolla.tracing.otel4s

/** Traces algebras whose methods take `cats.mtl.Raise` capability parameters —
  * algebras `cats.tagless.aop.Aspect` alone cannot weave, because plain
  * `Aspect` (like `FunctorK`) requires the effect type to appear only as each
  * method's top-level return type. `RaiseAspect` (`raise-aspect`) lifts
  * that restriction for `Raise[F, E]` parameters specifically; this module
  * wires the result into otel4s tracing the same way
  * `com.dwolla.tracing.otel4s.syntax` already does for plain `Aspect`
  * instances.
  *
  * It is the otel4s counterpart of `com.dwolla.tracing.mtl`
  * (`natchez-tagless-mtl`): the method names match, the recorded attribute
  * keys match, and the resolution mechanism is literally the same code in
  * `raise-aspect`. What differs is the rendering type class —
  * `ToAnyValue` here, `natchez.TraceableValue` there — see ARCHAEOLOGY.md if
  * you're moving an algebra between the two.
  *
  * ==Worked example==
  *
  * The algebra, its implementation, and how it is traced. Declaring the
  * `RaiseAspect` instance itself
  * (`DeriveRaise.aspect[Validator, ToAnyValue, ToAnyValue, ToAnyValue]`, in
  * `Validator`'s companion, per cats-tagless convention) is identical on Scala
  * 2 and 3 except for one detail: Scala 3 requires `@experimental` on that
  * declaration. See `AnyValueRaiseAspect`'s scaladoc, under "Without
  * derives:", for the complete, compiled Scala 3 declaration, and
  * `AnyValueRaiseAspect` for the shorter `derives` spelling.
  *
  * {{{
  *   import cats.Applicative
  *   import cats.effect.IO
  *   import cats.mtl.{Handle, Raise}
  *   import cats.syntax.all._
  *   import com.dwolla.tagless.mtl.RaiseAspect
  *   import com.dwolla.tracing.otel4s.ToAnyValue
  *   import com.dwolla.tracing.otel4s.mtl.syntax._
  *   import org.typelevel.otel4s.AnyValue
  *   import org.typelevel.otel4s.trace.Tracer
  *
  *   // A real error ADT is case classes matched by the ToAnyValue instance —
  *   // `case TooSmall(i) => ...`, as `AnyValueRaiseAspect` shows. A *compiled doc
  *   // example* cannot use them: its declarations end up inside a method, and
  *   // a method-local case class's synthesized equals is an unchecked
  *   // outer-reference type test that Scala 2 warns about. Nothing about the
  *   // module requires this shape.
  *   sealed trait ValidationError { def i: Int }
  *   final class TooSmall(val i: Int) extends ValidationError
  *
  *   // How the error renders into `com.dwolla.raise.error.value`. Every error
  *   // type needs one; there is no implicit `Encoder` or `Show` fallback.
  *   implicit val validationErrorToAnyValue: ToAnyValue[ValidationError] =
  *     ToAnyValue.instance(e => AnyValue.string("too small: " + e.i.toString))
  *
  *   trait Validator[F[_]] {
  *     // the capability parameter, `Raise[F, ValidationError]`, is the reason
  *     // plain `Aspect`/`Derive.aspect` cannot handle this algebra: `F` appears
  *     // here, not only as `validate`'s top-level return type.
  *     def validate(i: Int)(implicit R: Raise[F, ValidationError]): F[String]
  *   }
  *
  *   object Validator {
  *     def apply[F[_]: Applicative]: Validator[F] = new Validator[F] {
  *       def validate(i: Int)(implicit R: Raise[F, ValidationError]): F[String] =
  *         if (i < 0) R.raise(new TooSmall(i)) else ("ok:" + i.toString).pure[F]
  *     }
  *
  *     // implicit val raiseAspect: RaiseAspect[Validator, ToAnyValue, ToAnyValue, ToAnyValue] =
  *     //   DeriveRaise.aspect[Validator, ToAnyValue, ToAnyValue, ToAnyValue]
  *     // — declared in the companion per cats-tagless convention, not summoned; one
  *     // instance serves every F, since intercept is separately polymorphic
  *     // per call. The exact declaration is version-specific — see above.
  *   }
  *
  *   // A real application gets its Tracer from `TracerProvider[F].get(name)`,
  *   // supplied by a backend module (otel4s-oteljava, otel4s-sdk). This library
  *   // never provides one.
  *   def run(implicit
  *           tracer: Tracer[IO],
  *           RA: RaiseAspect[Validator, ToAnyValue, ToAnyValue, ToAnyValue]): IO[String] = {
  *     val traced: Validator[IO] = Validator[IO].traceWithInputsAndOutputs
  *
  *     // Raise[IO, ValidationError] doesn't exist on its own — Handle.allowF
  *     // constructs one ad hoc, scoped to this block, and .rescue recovers it,
  *     // handing back the domain error (here just re-rendered as a string; a
  *     // real handler would match on the ADT, e.g. `case TooSmall(i) => ...`).
  *     Handle.allowF[IO, ValidationError] { implicit h =>
  *       traced.validate(-1)
  *     }.rescue { e =>
  *       IO.pure("rejected: " + e.i.toString)
  *     }
  *   }
  * }}}
  *
  * ==What is recorded, and where==
  *
  * A raise adds '''span attributes''' to the '''method's own span''' — the
  * child span `traceWithInputs`/`traceWithInputsAndOutputs` opened for that
  * call, alongside its `com.dwolla.code.function.arguments` and
  * `com.dwolla.code.function.return_value` attributes. Not a span
  * event, and not the caller's span.
  *
  *   - `com.dwolla.raise.error.type` — the error's runtime class name, always recorded.
  *   - `com.dwolla.raise.error.value` — the error's `ToAnyValue` rendering, recorded
  *     unless it would encode to `AnyValue.empty`, in which case the attribute
  *     is omitted outright. That is the same omit-when-empty rule
  *     `TracerWeaveCapturingInputs`/`AndOutputs` apply to parameters and return
  *     values, and it leaves `ToAnyValue` a total `A => AnyValue`: only the
  *     recording site decides an empty value is not worth an attribute slot.
  *
  * Both names come from `com.dwolla.tagless.mtl.RaiseRecorder.ErrorTypeKey`
  * and `ErrorValueKey` in `raise-aspect`, and are therefore
  * '''byte-identical to what `natchez-tagless-mtl` records''' — a query written
  * against a natchez-instrumented service keeps working after a migration.
  * They are deliberately ''not'' OpenTelemetry's registered semconv
  * `error.type`: semconv's key describes how the operation ''ended'', whereas
  * this hook fires at raise time, so a raise the method rescues internally
  * would leave a successfully-completed span carrying `error.type` and tell
  * every backend that reads that key an operation failed when it did not.
  * Participating in semconv properly means setting `error.type` at span end,
  * which is separate work.
  *
  * Attributes rather than a `raise` span event is a considered choice, and it
  * has one lossy case worth knowing: '''a second raise overwrites the first.'''
  * The hook is attached to the `Raise[F, E]` handed into the method, so a
  * method that raises, rescues internally via `Handle.allow`, and raises again
  * fires the hook twice against the same span. The second `addAttributes`
  * overwrites `com.dwolla.raise.error.type`, but overwrites `com.dwolla.raise.error.value` only if
  * the second error renders non-empty — the omit-when-empty rule above means a
  * second error rendering to `AnyValue.empty` writes no value key at all,
  * leaving the ''first'' raise's value standing beside the ''second'' raise's
  * type. An event would have recorded both with timestamps. This is unusual, and it is documented rather than designed
  * around; the reasons for attributes are parity with the natchez module and
  * the fact that every backend can filter and aggregate on span attributes,
  * whereas events get flattened into pseudo-spans or rendered as logs.
  *
  * The default hook reaches the span through `Tracer[F].currentSpanOrNoop`,
  * which yields the method's own span rather than its caller's;
  * [[Otel4sDefaultOnRaise]] explains why that is the only route and why it
  * finds the right span. `RaiseSpanContentSpec` asserts exactly that against a
  * real SDK, on the child and on the parent. Under `Tracer.noop` the same call
  * yields a noop span whose `addAttributes` does nothing, so a disabled tracer
  * costs nothing.
  *
  * ==Design constraints==
  *
  *   - `intercept` requires an `Apply[F]` — needed only to sequence the
  *     `onRaise` hook's effect before the underlying `Raise[F, E]`'s own
  *     `raise` runs. The woven `Aspect.Weave` is data handed to the
  *     interpreter, never a carrier a capability is transported across, so no
  *     `Functor` is ever synthesized for it. Note this is a constraint of
  *     `RaiseAspect#intercept`, not of the syntax: neither syntax method
  *     declares an `Apply[F]`, because `WeaveInterpreter.fromRaiseAspect`
  *     resolves that one in the caller's scope.
  *   - '''Switching an import is free for `traceWithInputsAndOutputs`, not
  *     for `traceWithInputs`.''' `traceWithInputsAndOutputs` demands exactly
  *     what its non-mtl counterpart in `com.dwolla.tracing.otel4s.syntax`
  *     demands — both declare `FlatMap[F]`. `traceWithInputs` does not: the
  *     non-mtl one declares no effect constraint, while this one resolves a
  *     `RaiseRecorder[F, ToAnyValue]`, and absent a user-supplied `OnRaise`
  *     that resolves through [[Otel4sDefaultOnRaise]], declared
  *     `[F[_] : FlatMap : Tracer]`. A caller taking the default recorder
  *     therefore has to supply `FlatMap[F]`; a caller supplying its own
  *     `OnRaise[F, ToAnyValue]` needs only what that hook needs.
  *     `RaiseTracerConstraintSpec` pins both directions.
  *   - `Handle[F, E]` parameters are rejected at derivation time, with a
  *     message pointing at this design: `Handle` ''consumes'' `F`
  *     (`handleWith` takes an `F[A]`), so — unlike `Raise`, which only ever
  *     produces `F` values — it is not transportable across the woven
  *     boundary. Take `Raise[F, E]` in the algebra method and introduce
  *     `Handle` at the boundary instead, exactly as the worked example does
  *     with `Handle.allowF`/`.rescue`.
  *   - `F` may appear in a method signature only as the top-level return type,
  *     or inside a `Raise[F, E]` parameter (with `E` not itself mentioning
  *     `F`). Anything else — `F` nested in the return type, an effectful
  *     parameter, `F` behind a context-function return type — is a derivation
  *     error naming the method and parameter.
  *
  * ==Scala 3==
  *
  * Declaring a derived instance (`DeriveRaise.aspect`)
  * requires an `@experimental` annotation at the call site, or the
  * `-experimental` compiler flag on Scala 3.4+ — this repository stays on the
  * 3.3.x LTS line, where that flag does not exist, so the annotation is the
  * only option. This matches upstream cats-tagless's own Scala 3 derivation,
  * for the same reason: the underlying `quotes.reflect` APIs
  * (`Symbol.newClass`) are experimental on that line. Scala 2 is unaffected.
  *
  * There is also a shorter Scala-3-only spelling for exactly this otel4s
  * shape: an algebra can declare its instance with a `derives` clause instead
  * of the companion-object `implicit val` above — see `AnyValueRaiseAspect`,
  * which pins `Dom`, `Cod` and `Err` to `ToAnyValue` so that `derives` has a
  * one-parameter type constructor to work with. `@experimental` is still
  * required, on the algebra's '''companion object''' rather than the trait;
  * the natchez-tagless README, "Scala 3: derives and @experimental", explains
  * why. There is no Scala 2 equivalent — `derives` does not exist there —
  * so a cross-built algebra keeps the companion-object declaration shown in
  * the worked example above.
  *
  * ==Raises that escape a traced method==
  *
  * A raise that crosses the traced wrapper before being rescued surfaces in the
  * `Throwable` channel as cats-mtl's own opaque `Submarine` exception (see
  * [[https://github.com/typelevel/cats-mtl/issues/648 cats-mtl#648]]), not as
  * the domain error. The otel4s-tagless interpreters recognize it (see
  * `com.dwolla.tagless.RaisedError`) and finalize the method span as the
  * domain error instead of otel4s's default (measured against the oteljava
  * testkit in `RaiseSpanContentSpec`, with `Handle.allowF[IO, E]`):
  *
  *   - the method span's '''status is `ERROR`''',
  *   - `error.type` is the domain error's runtime class name, and
  *   - there is '''no `exception` span event'''.
  *
  * An enclosing span is unaffected as long as the rescue happens inside it: it
  * finishes with status `UNSET` and no events. This means every raise that is
  * not rescued inside the method marks its span `ERROR`, even where the raise
  * is an ordinary, expected domain outcome — see `otel4s-tagless`'s README for
  * when otel4s can and can't see into a `Raise` channel at all.
  *
  * The raise-time attributes are independent of that and cover every raise,
  * including ones rescued inside the method. By default, every algebra traced
  * via the `RaiseAspect` path records
  * `com.dwolla.raise.error.type` and `com.dwolla.raise.error.value` at the moment of the raise, with
  * no action required from the caller: both syntax methods resolve their hook
  * through [[com.dwolla.tagless.mtl.RaiseRecorder]].
  *
  * That default rendering ''is'' redaction-aware, like the rest of this
  * library: `com.dwolla.raise.error.value` is the error's `ToAnyValue[E]` rendering, so
  * the newtype-plus-custom-`ToAnyValue` pattern documented on
  * `TracerWeaveCapturingInputs`/`TracerWeaveCapturingInputsAndOutputs` applies
  * to error values too. An error ADT carrying a token or a card number should
  * declare a `ToAnyValue` that omits or masks it, exactly as a sensitive
  * parameter type would. Note that `com.dwolla.raise.error.type` still records the error's
  * runtime class name unconditionally.
  *
  * ==Overriding the default recording==
  *
  * `RaiseRecorder` resolution is just implicit priority: a user-supplied
  * `OnRaise[F, ToAnyValue]` (`com.dwolla.tagless.mtl.OnRaise`) outranks the
  * `Tracer`-based default ([[Otel4sDefaultOnRaise]]) ''if the compiler's
  * implicit search actually finds it'' — and that depends on where it is
  * declared. Implicit scope for `OnRaise[F, ToAnyValue]` reaches the companions
  * of `OnRaise`, `F`, and `ToAnyValue`; a user's own error ADT appears in none
  * of those, so ''declaring the hook in the error type's companion object does
  * not work'' — unlike a `ToAnyValue[MyError]` instance, which does belong
  * there, because `ToAnyValue[MyError]` mentions `MyError` and the hook's type
  * does not. The hook must instead live somewhere ordinary lexical scoping
  * reaches it: a local `implicit val`/`given` in scope at the call site, or an
  * import. A hook the compiler does not find is not an error — resolution
  * quietly falls back to the `Tracer`-based default above, which still renders
  * through `ToAnyValue`, so a missed override degrades to a redaction-aware
  * default rather than to raw `toString`.
  *
  * The `Tracer`-based default itself is reached the same lexical way, not
  * automatically: `RaiseRecorder`'s mechanism lives in `raise-aspect`,
  * which cannot name otel4s, so the otel4s default is a `DefaultOnRaise`
  * instance declared in [[Otel4sDefaultOnRaise]] and mixed into
  * `com.dwolla.tracing.otel4s.mtl.syntax`'s package object. It arrives with
  * `import com.dwolla.tracing.otel4s.mtl.syntax._` rather than from implicit
  * scope — the same import
  * `traceWithInputs`/`traceWithInputsAndOutputs` already require, so the normal
  * path is unaffected. What that costs is a bare
  * `implicitly[RaiseRecorder[F, ToAnyValue]]` with no syntax import in scope:
  * that summon does not find the default on its own.
  *
  * A hook is also free to match on the value it receives, to record
  * error-specific attributes under error-specific keys. The example below does
  * not, only because a compiled doc example declares its ADT inside a method,
  * and matching an abstract `E` against a method-local class is an unchecked
  * type test the compiler warns about. In real code, where the ADT is
  * top-level, `case TooSmall(i) => …` is fine.
  *
  * {{{
  *   import cats.FlatMap
  *   import cats.syntax.all._
  *   import com.dwolla.tagless.mtl.{OnRaise, RaiseRecorder}
  *   import com.dwolla.tracing.otel4s.ToAnyValue
  *   import org.typelevel.otel4s.{Attribute, Attributes}
  *   import org.typelevel.otel4s.trace.Tracer
  *
  *   // Just having this implicit in lexical scope is the entire override:
  *   // RaiseRecorder's fromOnRaise instance outranks the Tracer-based default
  *   // used above — but only because this is a local implicit def, not a
  *   // member of ValidationError's own companion object, which implicit
  *   // search for OnRaise[F, ToAnyValue] would never look inside.
  *   implicit def onRaiseUnderCustomKey[F[_] : FlatMap](implicit T: Tracer[F]): OnRaise[F, ToAnyValue] =
  *     new OnRaise[F, ToAnyValue] {
  *       // `ev` is the per-error-type evidence the hook receives. Rendering
  *       // through it, rather than through `e.toString`, is what makes a hook
  *       // honor the same redaction a `ToAnyValue` instance declares.
  *       //
  *       // `.backend` deliberately: `Span#addAttributes` is a macro on Scala 2
  *       // and inline on Scala 3, and `Span.Backend#addAttributes` is the
  *       // sealed method underneath it.
  *       def apply[E](e: E)(implicit ev: ToAnyValue[E]): F[Unit] =
  *         Tracer[F].currentSpanOrNoop.flatMap {
  *           _.backend.addAttributes(Attributes(Attribute("validation.error", ev.toAnyValue(e))))
  *         }
  *     }
  *
  *   // RaiseRecorder.fromOnRaise picks up onRaiseUnderCustomKey automatically;
  *   // no other change is needed at any tracing call site. Note there is no
  *   // syntax import here: fromOnRaise lives in RaiseRecorder's own companion,
  *   // so it is in implicit scope. It is the *default* that needs the import.
  *   def recorderResolvesViaOnRaise[F[_] : FlatMap](implicit T: Tracer[F]): RaiseRecorder[F, ToAnyValue] =
  *     implicitly[RaiseRecorder[F, ToAnyValue]]
  * }}}
  */
package object mtl
