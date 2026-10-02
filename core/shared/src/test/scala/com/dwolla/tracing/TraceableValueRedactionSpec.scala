package com.dwolla.tracing

import cats.*
import cats.data.*
import com.dwolla.tracing.LowPriorityTraceableValueInstances.*
import com.dwolla.tracing.TraceableValueRedactionSpec.*
import io.circe.{Encoder, KeyEncoder}
import munit.FunSuite
import natchez.TraceValue.StringValue
import natchez.{TraceValue, TraceableValue}

import scala.collection.immutable.SortedSet

/** A type whose companion redacts it, but whose circe `Encoder` and
  * `KeyEncoder` and cats `Show` reveal it, must redact in every shape the
  * `LowPriorityTraceableValueInstances` import covers.
  */
class TraceableValueRedactionSpec extends FunSuite {
  private val secret = Secret("hunter2")

  private def assertRedacted[A](value: A, expected: String)(implicit A: TraceableValue[A]): Unit = {
    val recorded = A.toTraceValue(value)
    assert(!recorded.value.toString.contains(secret.value), s"$recorded leaked the secret")
    assertEquals(recorded, StringValue(expected))
  }

  test("a bare Secret uses its companion's redacting instance") {
    assertRedacted(secret, "redacted")
  }

  test("Option[Secret] redacts") {
    assertRedacted(Option(secret), "redacted")
  }

  test("List[Secret] redacts each element") {
    assertRedacted(List(secret, secret), """["redacted","redacted"]""")
  }

  test("Seq[Secret] redacts each element") {
    assertRedacted(Seq(secret), """["redacted"]""")
  }

  test("Vector[Secret] redacts each element") {
    assertRedacted(Vector(secret), """["redacted"]""")
  }

  test("Set[Secret] redacts each element") {
    assertRedacted(Set(secret), """["redacted"]""")
  }

  test("Chain[Secret] redacts each element") {
    assertRedacted(Chain(secret), """["redacted"]""")
  }

  test("NonEmptyList[Secret] redacts each element") {
    assertRedacted(NonEmptyList.one(secret), """["redacted"]""")
  }

  test("NonEmptyVector[Secret] redacts each element") {
    assertRedacted(NonEmptyVector.one(secret), """["redacted"]""")
  }

  test("NonEmptySet[Secret] redacts each element") {
    assertRedacted(NonEmptySet.one(secret), """["redacted"]""")
  }

  test("NonEmptyChain[Secret] redacts each element") {
    assertRedacted(NonEmptyChain.one(secret), """["redacted"]""")
  }

  test("Map[String, Secret] redacts each value") {
    assertRedacted(Map("password" -> secret), """{"password":"redacted"}""")
  }

  test("Map[Secret, Int] redacts each key") {
    assertRedacted(Map(secret -> 1), """{"redacted":1}""")
  }

  test("(Secret, Int) redacts the Secret") {
    assertRedacted((secret, 1), """["redacted",1]""")
  }

  test("(Int, Secret, Int) redacts the Secret") {
    assertRedacted((1, secret, 2), """[1,"redacted",2]""")
  }

  test("(Int, Int, Int, Secret) redacts the Secret") {
    assertRedacted((1, 2, 3, secret), """[1,2,3,"redacted"]""")
  }

  test("(Int, Int, Int, Int, Secret) redacts the Secret") {
    assertRedacted((1, 2, 3, 4, secret), """[1,2,3,4,"redacted"]""")
  }

  test("nested containers redact the innermost Secret") {
    assertRedacted(Map("passwords" -> List(secret)), """{"passwords":["redacted"]}""")
    assertRedacted(List((secret, Set(secret))), """[["redacted",["redacted"]]]""")
  }

  test("a SortedSet[Secret], which has no instance, does not compile instead of leaking") {
    // SortedSet is pinned here because it has a circe Encoder and a cats Show,
    // so the fallback this import used to provide would have revealed it.
    val _ = SortedSet(secret)
    assertNoTraceableValue(compileErrors("TraceableValue[SortedSet[Secret]]"))
  }

  test("a six-element tuple, beyond the provided arities, does not compile instead of leaking") {
    assertNoTraceableValue(compileErrors("TraceableValue[(Secret, Int, Int, Int, Int, Int)]"))
  }

  test("a type with only a circe Encoder has no implicit TraceableValue") {
    assertNoTraceableValue(compileErrors("TraceableValue[EncoderOnly]"))
    assertNoTraceableValue(compileErrors("TraceableValue[List[EncoderOnly]]"))
  }

  test("a type with only a cats Show has no implicit TraceableValue") {
    assertNoTraceableValue(compileErrors("TraceableValue[ShowOnly]"))
    assertNoTraceableValue(compileErrors("TraceableValue[Option[ShowOnly]]"))
  }

  test("a map key with only a circe KeyEncoder has no implicit TraceableValue") {
    assertNoTraceableValue(compileErrors("TraceableValue[Map[KeyEncoderOnly, Int]]"))
  }

  private def assertNoTraceableValue(errors: String): Unit = {
    assert(errors.nonEmpty, "expected a compile error")
    assert(errors.contains("TraceableValue"), errors)
  }
}

object TraceableValueRedactionSpec {
  final case class Secret(value: String)
  object Secret {
    implicit val secretTraceableValue: TraceableValue[Secret] = _ => TraceValue.StringValue("redacted")
    implicit val secretEncoder: Encoder[Secret] = Encoder[String].contramap(_.value)
    implicit val secretKeyEncoder: KeyEncoder[Secret] = KeyEncoder[String].contramap(_.value)
    implicit val secretShow: Show[Secret] = Show.show(_.value)
    implicit val secretOrder: Order[Secret] = Order.by(_.value)
    implicit val secretOrdering: Ordering[Secret] = secretOrder.toOrdering
  }

  final case class EncoderOnly(value: Int)
  object EncoderOnly {
    implicit val encoderOnlyEncoder: Encoder[EncoderOnly] = Encoder[Int].contramap(_.value)
  }

  final case class ShowOnly(value: Int)
  object ShowOnly {
    implicit val showOnlyShow: Show[ShowOnly] = Show.fromToString
  }

  final case class KeyEncoderOnly(value: String)
  object KeyEncoderOnly {
    implicit val keyEncoderOnlyKeyEncoder: KeyEncoder[KeyEncoderOnly] = KeyEncoder[String].contramap(_.value)
  }
}
