package com.dwolla.tracing

import munit.FunSuite

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}
import scala.annotation.experimental

/** `Aspect extends Instrument extends FunctorK extends InvariantK extends
  * Serializable`, so `TraceableAspect` inherits a declared contract that
  * `fromAspect`'s wrapper could break by capturing something unserializable.
  * It captures exactly one field — the underlying `Aspect`, itself declared
  * `Serializable` — so this should hold by construction; assert it anyway,
  * because "by construction" is an argument and this is a check.
  *
  * JVM-only: `ObjectOutputStream` does not exist on Scala.js. `core` is
  * `CrossType.Full`, so this file simply lives under `core/jvm` rather than
  * needing the `Platform.isJvm` constant `raise-aspect` uses.
  *
  * `scala-3` and not plain `scala` under that: the subjects below are
  * `TraceableAspect`, a Scala 3-only main source, and `DerivesLookup`, which
  * only exists under a `scala-3` test root. Moving this file to
  * `core/jvm/src/test/scala` is a compile failure on the 2.12 and 2.13 axes,
  * not merely an unused test.
  */
@experimental
class TraceableAspectSerializationSpec extends FunSuite {

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

  test("a derived TraceableAspect is Serializable") {
    assert(roundTrip(summon[TraceableAspect[DerivesLookup]]).isInstanceOf[TraceableAspect[?]])
  }

  test("a TraceableAspect built with fromAspect is Serializable") {
    assert(
      roundTrip(TraceableAspect.fromAspect(HandWrittenLookupAspect.instance))
        .isInstanceOf[TraceableAspect[?]]
    )
  }
}
