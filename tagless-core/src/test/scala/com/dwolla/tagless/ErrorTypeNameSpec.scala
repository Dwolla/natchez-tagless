package com.dwolla.tagless

import munit.FunSuite

/** Stands in for a Scala 3 enum's simple cases on every Scala version: values
  * of one anonymous class each, distinguished only by `productPrefix`.
  */
sealed abstract class FooError extends Product with Serializable

object FooError {
  private def simpleCase(name: String): FooError =
    new FooError {
      override def productPrefix: String = name
      override def productArity: Int = 0
      override def productElement(n: Int): Any = throw new IndexOutOfBoundsException(n.toString)
      override def canEqual(that: Any): Boolean = this eq that.asInstanceOf[AnyRef]
    }

  val NotFound: FooError = simpleCase("NotFound")
  val Conflict: FooError = simpleCase("Conflict")
}

final case class Invalid(reason: String)
case object Gone

class ErrorTypeNameSpec extends FunSuite {
  test("values of a shared anonymous class are named by enclosing class and productPrefix") {
    assertEquals(ErrorTypeName(FooError.NotFound), "com.dwolla.tagless.FooError.NotFound")
    assertEquals(ErrorTypeName(FooError.Conflict), "com.dwolla.tagless.FooError.Conflict")
  }

  test("ordinary classes, case classes, and case objects keep their runtime class name") {
    assertEquals(ErrorTypeName(new IllegalStateException("x")), "java.lang.IllegalStateException")
    assertEquals(ErrorTypeName(Invalid("x")), "com.dwolla.tagless.Invalid")
    assertEquals(ErrorTypeName(Gone), "com.dwolla.tagless.Gone$")
  }

  test("an anonymous Product with an empty productPrefix keeps its runtime class name") {
    val anonymous: FooError = new FooError {
      override def productArity: Int = 0
      override def productElement(n: Int): Any = throw new IndexOutOfBoundsException(n.toString)
      override def canEqual(that: Any): Boolean = false
    }
    assertEquals(ErrorTypeName(anonymous), anonymous.getClass.getName)
  }

  test("an anonymous class that is not a Product keeps its runtime class name") {
    val anonymous = new RuntimeException("x") {}
    assertEquals(ErrorTypeName(anonymous), anonymous.getClass.getName)
  }
}
