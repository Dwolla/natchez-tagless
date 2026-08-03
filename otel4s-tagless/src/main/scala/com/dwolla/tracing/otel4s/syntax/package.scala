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
  */
package object syntax
  extends ToTracerWeaveOps
    with ToInstrumentableAndTraceableOps
    with ToWeaveAttributesOps
