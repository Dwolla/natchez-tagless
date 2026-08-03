package com.dwolla.tracing.otel4s

/** Syntax for the otel4s interpreters.
  *
  * The method names deliberately match `com.dwolla.tracing.syntax`'s, so
  * migrating a file from natchez to otel4s changes one import and no call
  * sites. The cost is that a single file cannot wildcard-import both packages —
  * the two implicit conversions would be ambiguous. Import one of them
  * selectively if you genuinely need both backends in one file.
  */
package object syntax
  extends ToWeaveAttributesOps
