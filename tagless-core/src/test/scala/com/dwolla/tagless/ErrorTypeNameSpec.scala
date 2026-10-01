package com.dwolla.tagless

import munit.FunSuite

/** An anonymous `Product` with a `productPrefix`, which is what a Scala 3 enum's
  * simple case looks like to `ErrorTypeName` except that it isn't an enum.
  */
sealed abstract class FooError extends Product with Serializable

object FooError {
  val NotFound: FooError =
    new FooError {
      override def productPrefix: String = "NotFound"
      override def productArity: Int = 0
      override def productElement(n: Int): Any = throw new IndexOutOfBoundsException(n.toString)
      override def canEqual(that: Any): Boolean = this eq that.asInstanceOf[AnyRef]
    }
}

final case class Invalid(reason: String)
case object Gone

/** A case class that is also a `Throwable`, so an anonymous subclass can mix in `NoStackTrace`. */
case class StackTracelessError(reason: String) extends Exception(reason)

abstract class Maker {
  def make: Any
}

object AnonymousHolder {
  val anonymousSubclassOfCaseClass: StackTracelessError =
    new StackTracelessError("x") with scala.util.control.NoStackTrace

  val caseClassNestedInAnonymousClass: Any =
    new Maker {
      case class Inner(reason: String)
      def make: Any = Inner("x")
    }.make
}

class ErrorTypeNameSpec extends FunSuite {
  test("an anonymous Product that is not an enum keeps its runtime class name") {
    assertEquals(ErrorTypeName(FooError.NotFound), FooError.NotFound.getClass.getName)
  }

  test("an anonymous subclass of a case class keeps its own anonymous class name") {
    val error = AnonymousHolder.anonymousSubclassOfCaseClass
    assertEquals(ErrorTypeName(error), error.getClass.getName)
  }

  test("a case class nested in an anonymous class records its full class name") {
    val error = AnonymousHolder.caseClassNestedInAnonymousClass
    assertEquals(ErrorTypeName(error), error.getClass.getName)
  }

  test("ordinary classes, case classes, and case objects keep their runtime class name") {
    assertEquals(ErrorTypeName(new IllegalStateException("x")), "java.lang.IllegalStateException")
    assertEquals(ErrorTypeName(Invalid("x")), "com.dwolla.tagless.Invalid")
    assertEquals(ErrorTypeName(Gone), "com.dwolla.tagless.Gone$")
  }

  test("null is named \"null\" rather than failing") {
    assertEquals(ErrorTypeName(null), "null")
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
