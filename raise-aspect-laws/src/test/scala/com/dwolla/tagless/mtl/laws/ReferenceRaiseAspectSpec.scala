package com.dwolla.tagless.mtl
package laws

/** Runs the full law suite against M1's hand-written reference instance.
  *
  * M3 and M4 add sibling classes that extend [[RaiseAspectSuite]] and supply a
  * macro-derived instance instead — that is the whole substitution seam.
  */
class ReferenceRaiseAspectSpec extends RaiseAspectSuite {
  def instance: RaiseAspect[TestAlg, Render, Render] =
    TestAlgReference.referenceRaiseAspect[Render, Render]
}
