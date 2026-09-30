package com.dwolla.tagless.mtl
package laws

import cats.tagless.Derive
import cats.tagless.aop.Aspect

/** Scala 2 call site for law L9. `Derive.aspect` here is the whitebox macro
  * from cats-tagless-macros.
  */
class ConservativeExtensionSpec extends ConservativeExtensionSuite {
  def upstream: Aspect[PlainAlg, Render, Render] =
    Derive.aspect[PlainAlg, Render, Render]
}
