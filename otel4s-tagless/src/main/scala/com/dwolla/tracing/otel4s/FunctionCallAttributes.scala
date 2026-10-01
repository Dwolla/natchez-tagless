package com.dwolla.tracing.otel4s

import org.typelevel.otel4s.{Attribute, Attributes}
import org.typelevel.otel4s.semconv.attributes.CodeAttributes

/** Span attribute keys the otel4s tracing interpreters record, named per the
  * OpenTelemetry naming conventions: lowercase, snake_case, and — where
  * semantic conventions define nothing — under an owned `com.dwolla` prefix.
  * The method is identified by the stable `code.function.name` attribute, so
  * the keys themselves never vary by algebra or method.
  */
private[otel4s] object FunctionCallAttributes {
  /** All of a call's arguments, as one `AnyValue` map keyed by parameter name. */
  val ArgumentsKey: String = "com.dwolla.code.function.arguments"

  /** The call's encoded return value. */
  val ReturnValueKey: String = "com.dwolla.code.function.return_value"

  /** `code.function.name = <algebraName>.<methodName>`. cats-tagless knows only
    * an algebra's simple name, so this is not fully qualified.
    */
  def codeFunctionName(qualifiedMethodName: String): Attributes =
    Attributes(Attribute(CodeAttributes.CodeFunctionName, qualifiedMethodName))
}
