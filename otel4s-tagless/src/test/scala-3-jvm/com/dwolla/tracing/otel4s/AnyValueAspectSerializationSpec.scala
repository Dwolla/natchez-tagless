package com.dwolla.tracing.otel4s

import munit.FunSuite

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}
import scala.annotation.experimental

/** `Aspect extends Instrument extends FunctorK extends InvariantK extends
  * Serializable`, so `AnyValueAspect` inherits a declared contract that
  * `fromAspect`'s wrapper could break by capturing something unserializable.
  * It captures exactly one field — the underlying `Aspect`, itself declared
  * `Serializable` — so this should hold by construction; assert it anyway,
  * because "by construction" is an argument and this is a check.
  *
  * JVM-only: `ObjectOutputStream` does not exist on Scala.js. Unlike `core`
  * (`CrossType.Full`, so its JVM-only test simply lives under `core/jvm`),
  * `otel4s-tagless` is `CrossType.Pure` and has no `jvm`/`js` split at all;
  * its JVM-only test sources instead live under `src/test/scala-jvm`, or, for
  * a file that also needs Scala 3, `src/test/scala-3-jvm` — both wired into
  * `Test / unmanagedSourceDirectories` in `build.sbt`'s
  * `otel4sTagless.jvmSettings`.
  *
  * `scala-3-jvm` and not plain `scala-jvm`: the subjects below are
  * `AnyValueAspect`, a Scala 3-only main source, and `DerivesLookup`, which
  * only exists under a `scala-3` test root. Moving this file to
  * `src/test/scala-jvm` is a compile failure on the 2.13 axis, not merely an
  * unused test.
  */
@experimental
class AnyValueAspectSerializationSpec extends FunSuite {

  /** Serialize and deserialize, returning what came back. `writeObject` alone
    * would catch the real risk — it throws `NotSerializableException` if
    * `fromAspect`'s wrapper captures something unserializable — but reading the
    * bytes back is what makes the round trip claim true rather than assumed.
    */
  private def roundTrip(a: AnyRef): AnyRef = {
    val bytes = new ByteArrayOutputStream()
    val out = new ObjectOutputStream(bytes)
    out.writeObject(a)
    out.close()

    val in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray))
    try in.readObject()
    finally in.close()
  }

  test("a derived AnyValueAspect is Serializable") {
    assert(roundTrip(summon[AnyValueAspect[DerivesLookup]]).isInstanceOf[AnyValueAspect[?]])
  }

  test("an AnyValueAspect built with fromAspect is Serializable") {
    assert(
      roundTrip(AnyValueAspect.fromAspect(HandWrittenLookupAspect.instance))
        .isInstanceOf[AnyValueAspect[?]]
    )
  }
}
