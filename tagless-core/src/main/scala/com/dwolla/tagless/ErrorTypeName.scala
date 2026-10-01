package com.dwolla.tagless

/** The name recorded for an error's type: its runtime class name, except for
  * values whose class is anonymous. Every simple case of a Scala 3 `enum`
  * shares one anonymous class (`com.example.FooError\$\$anon\$1`), so those are
  * named by the enclosing class plus their `productPrefix`
  * (`com.example.FooError.NotFound`). Anonymity is read from the class name
  * because `Class#isAnonymousClass` isn't available on every platform.
  *
  * A `null` error is named `"null"`, so recording the type of a raised `null`
  * can't fail the traced call it describes.
  */
object ErrorTypeName {
  private val AnonymousMarker = "$$anon$"

  def apply(error: Any): String =
    if (error == null) "null"
    else {
      val className = error.getClass.getName
      val anonymousAt = className.indexOf(AnonymousMarker)
      error match {
        case product: Product if anonymousAt > 0 && product.productPrefix.nonEmpty =>
          s"${className.substring(0, anonymousAt)}.${product.productPrefix}"
        case _ => className
      }
    }
}
