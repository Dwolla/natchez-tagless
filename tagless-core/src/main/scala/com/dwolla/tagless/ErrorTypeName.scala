package com.dwolla.tagless

import scala.util.matching.Regex

/** The name recorded for an error's type: its runtime class name, except for
  * the simple cases of a Scala 3 `enum`. Those share one anonymous class per
  * enum (`com.example.FooError\$\$anon\$1`), so they are named by the class
  * name up to its last `\$\$anon\$<digits>` suffix, then `\$`, then their
  * `productPrefix` (`com.example.FooError\$NotFound`), matching the names of
  * parameterized cases (`com.example.FooError\$Invalid`). Only enum values
  * whose class name ends in that suffix are rewritten; anonymous subclasses
  * of anything else, and classes nested inside anonymous classes, keep their
  * runtime class name. An enum declared inside an anonymous class loses its own
  * name, because its cases' class name never contains it: they are named by
  * whatever precedes the anonymous suffix, plus `\$` and the case.
  *
  * A `null` error is named `"null"`, so recording the type of a raised `null`
  * can't fail the traced call it describes.
  */
object ErrorTypeName {
  private val AnonymousClassSuffix: Regex = """(?s)(.*)\$\$anon\$\d+""".r

  def apply(error: Any): String =
    if (error == null) "null"
    else {
      val className = error.getClass.getName
      error match {
        case product: Product if ScalaEnum.isEnum(product) && product.productPrefix.nonEmpty =>
          className match {
            case AnonymousClassSuffix(enclosing) => s"$enclosing$$${product.productPrefix}"
            case _ => className
          }
        case _ => className
      }
    }
}
