package com.dwolla.tagless

import munit.FunSuite

enum Color {
  case Red, Green
  case Custom(rgb: Int)
}

object EnumHolder {
  val enumNestedInAnonymousClass: Any =
    new Maker {
      enum Shade {
        case Light, Dark
      }
      def make: Any = Shade.Light
    }.make
}

class ErrorTypeNameEnumSpec extends FunSuite {
  test("simple enum cases record distinct names joined to the enum with a dollar sign") {
    assertEquals(ErrorTypeName(Color.Red), "com.dwolla.tagless.Color$Red")
    assertEquals(ErrorTypeName(Color.Green), "com.dwolla.tagless.Color$Green")
  }

  test("parameterized enum cases keep their own class name") {
    assertEquals(ErrorTypeName(Color.Custom(1)), "com.dwolla.tagless.Color$Custom")
  }

  test("an enum nested in an anonymous class loses the enum's name") {
    val shade = EnumHolder.enumNestedInAnonymousClass
    val className = shade.getClass.getName
    val anonymousSuffix = """\$\$anon\$\d+""".r
    assert(anonymousSuffix.findFirstIn(className).isDefined, className)
    assert(!className.contains("Shade"), className)
    assertEquals(ErrorTypeName(shade), anonymousSuffix.replaceFirstIn(className, "") + "$Light")
  }
}
