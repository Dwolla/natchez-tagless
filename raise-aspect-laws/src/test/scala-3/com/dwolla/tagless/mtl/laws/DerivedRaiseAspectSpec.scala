package com.dwolla.tagless.mtl
package laws

import scala.annotation.experimental

/** Runs the complete law suite against the Scala 3 macro-derived instance,
  * through the same seam the Scala 2 spec uses. Nothing in `raise-aspect-laws` is
  * modified.
  *
  * `@experimental` because `DeriveRaise.aspect` is — see its scaladoc.
  */
@experimental
class DerivedRaiseAspectSpec extends RaiseAspectSuite:
  def instance: RaiseAspect[TestAlg, Render, Render, Render] =
    DeriveRaise.aspect[TestAlg, Render, Render, Render]
