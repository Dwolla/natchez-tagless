package com.dwolla.tracing

import munit.FunSuite

import java.io.{ByteArrayOutputStream, ObjectOutputStream}
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
  * needing the `Platform.isJvm` constant `raise-aspect-core` uses.
  */
@experimental
class TraceableAspectSerializationSpec extends FunSuite {
  private def roundTrips(a: AnyRef): Boolean = {
    val bytes = new ByteArrayOutputStream()
    val out = new ObjectOutputStream(bytes)
    out.writeObject(a)
    out.close()
    bytes.size() > 0
  }

  test("a derived TraceableAspect is Serializable") {
    assert(roundTrips(summon[TraceableAspect[DerivesLookup]]))
  }

  test("a TraceableAspect built with fromAspect is Serializable") {
    assert(roundTrips(TraceableAspect.fromAspect(HandWrittenLookupAspect.instance)))
  }
}
