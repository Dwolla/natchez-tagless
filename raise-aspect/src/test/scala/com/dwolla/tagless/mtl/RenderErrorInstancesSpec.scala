package com.dwolla.tagless.mtl

import munit.FunSuite

import TestError._

/** Pins the distinctive prefixes the `Err = Render` law and macro
  * instantiations assert against. Without a prefix, a `Render` instance and a
  * bare `toString` are indistinguishable, and a test that meant to prove
  * evidence was used would pass either way.
  */
class RenderErrorInstancesSpec extends FunSuite {
  test("Render[ErrA] prefixes its rendering") {
    assertEquals(Render[ErrA].render(NegativeInput(-3)), "errA:NegativeInput(-3)")
  }

  test("Render[ErrB] prefixes its rendering") {
    assertEquals(Render[ErrB].render(EmptyInput("x")), "errB:EmptyInput(x)")
  }

  test("Render[TestError] prefixes its rendering") {
    assertEquals(Render[TestError].render(NegativeInput(-3)), "testError:NegativeInput(-3)")
  }
}
