package com.dwolla.tagless

import munit.FunSuite

enum Color {
  case Red, Green
  case Custom(rgb: Int)
}

class ErrorTypeNameEnumSpec extends FunSuite {
  test("simple enum cases record distinct names") {
    assertEquals(ErrorTypeName(Color.Red), "com.dwolla.tagless.Color.Red")
    assertEquals(ErrorTypeName(Color.Green), "com.dwolla.tagless.Color.Green")
  }

  test("parameterized enum cases keep their own class name") {
    assertEquals(ErrorTypeName(Color.Custom(1)), "com.dwolla.tagless.Color$Custom")
  }
}
