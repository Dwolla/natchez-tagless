package com.dwolla.tagless.mtl
package laws

import cats.tagless.Derive
import cats.tagless.aop.Aspect

import scala.annotation.experimental

/** Scala 3 call site for law L9.
  *
  * `cats.tagless.Derive` is annotated `@experimental` on Scala 3 because its
  * derivation uses `Symbol.newClass`, so this call site must be `@experimental`
  * too. The repo stays on the 3.3.x LTS line, where the `-experimental`
  * compiler flag does not exist, making the annotation the only option. Our own
  * `DeriveRaise` will carry the same requirement in M4.
  */
@experimental
class ConservativeExtensionSpec extends ConservativeExtensionSuite {
  def upstream: Aspect[PlainAlg, Render, Render] =
    Derive.aspect[PlainAlg, Render, Render]
}
