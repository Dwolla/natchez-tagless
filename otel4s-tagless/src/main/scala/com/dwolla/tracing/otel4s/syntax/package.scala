package com.dwolla.tracing.otel4s

/** Syntax for the otel4s interpreters.
  *
  * The method names deliberately match `com.dwolla.tracing.syntax`'s, so
  * migrating a file from natchez to otel4s changes one import and no call
  * sites. The cost is that a single file cannot wildcard-import both packages —
  * the two implicit conversions would be ambiguous. Import one of them
  * selectively if you genuinely need both backends in one file.
  *
  * On Scala 2 the compiler says so plainly — the error on the call site names
  * both conversions: `Note that implicit conversions are not applicable
  * because they are ambiguous: both method toTraceWeaveOps in trait
  * ToTraceWeaveOps … and method toTracerWeaveOps in trait ToTracerWeaveOps …
  * are possible conversion functions`.
  *
  * '''On Scala 3 it does not.''' The same file fails with `value
  * traceWithInputs is not a member of …, but could be made available as an
  * extension method`, followed by import suggestions that have nothing to do
  * with either syntax package — the ambiguity is never mentioned. So if a
  * method that plainly exists reports as missing on Scala 3, check the imports
  * for the other backend's syntax first.
  *
  * `traceWithInputs`, `traceWithInputsAndOutputs` and `instrumentAndTrace` all
  * take an algebra and give one back; their examples live on the interpreters
  * they delegate to. `asAttributes` is the odd one out — it is the pure
  * function underneath the other two, and it is the shortest way to see exactly
  * what a traced call records:
  *
  * {{{
  *   import cats.Id
  *   import cats.tagless.aop.Aspect
  *   import com.dwolla.tracing.otel4s.ToAnyValue
  *   import com.dwolla.tracing.otel4s.syntax._
  *   import org.typelevel.otel4s.Attributes
  *
  *   val weave: Aspect.Weave[Id, ToAnyValue, ToAnyValue, String] =
  *   Aspect.Weave[Id, ToAnyValue, ToAnyValue, String](
  *     "Foo",
  *     List(List(
  *       Aspect.Advice.byValue[ToAnyValue, String]("name", "world"),
  *       Aspect.Advice.byValue[ToAnyValue, Int]("times", 2),
  *     )),
  *     Aspect.Advice[Id, ToAnyValue, String]("greet", "hello worldhello world")
  *   )
  *
  *   // exactly one attribute, named `Foo.greet.parameters`, whose value is the
  *   // map `{"name": "world", "times": 2}` — not a JSON string, an OTLP
  *   // kvlistValue. A method with no parameters yields Attributes.empty
  *   // instead, because an attribute holding an empty map is not worth a slot.
  *   val attributes: Attributes = weave.asAttributes
  * }}}
  */
package object syntax
  extends ToTracerWeaveOps
    with ToInstrumentableAndTraceableOps
    with ToWeaveAttributesOps
