package com.dwolla.tagless.mtl
package laws

/** Runs the complete law suite against the macro-derived instance.
  */
class DerivedRaiseAspectSpec extends RaiseAspectSuite {
  def instance: RaiseAspect[TestAlg, Render, Render, Render] =
    DeriveRaise.aspect[TestAlg, Render, Render, Render]
}
