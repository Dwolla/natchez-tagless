package com.dwolla.tagless.mtl
package laws

/** Runs the full law suite against the hand-written reference instance.
  */
class ReferenceRaiseAspectSpec extends RaiseAspectSuite {
  def instance: RaiseAspect[TestAlg, Render, Render, Render] =
    TestAlgReference.referenceRaiseAspect[Render, Render, Render]
}
