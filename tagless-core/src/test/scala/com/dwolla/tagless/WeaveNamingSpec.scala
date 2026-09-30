package com.dwolla.tagless

import cats.Eval
import cats.tagless.Trivial
import cats.tagless.aop.Aspect
import munit.FunSuite
import WeaveNaming._

class WeaveNamingSpec extends FunSuite {
  test("qualifiedMethodName joins the algebra name and the codomain advice's name with a dot") {
    val weave = Aspect.Weave[Option, Trivial, Trivial, Int](
      "MyAlgebra",
      List.empty[List[Aspect.Advice[Eval, Trivial]]],
      Aspect.Advice[Option, Trivial, Int]("frobnicate", Some(1))
    )

    assertEquals(weave.qualifiedMethodName, "MyAlgebra.frobnicate")
  }
}
