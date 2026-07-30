package com.dwolla.tagless.mtl
package laws

/** Runs M2's complete law suite against the macro-derived instance, through the
  * seam M2 built. Nothing in `raise-aspect-laws` is modified: this is the whole
  * substitution, and all ten laws come along unchanged.
  */
class DerivedRaiseAspectSpec extends RaiseAspectSuite {
  def instance: RaiseAspect[TestAlg, Render, Render, Render] =
    DeriveRaise.aspect[TestAlg, Render, Render, Render]
}
