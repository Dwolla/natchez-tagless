package com.dwolla.tagless.mtl

import munit.FunSuite

import TestError._

class OnRaiseSpec extends FunSuite {
  private type F[A] = Either[TestError, A]

  test("noop.apply with ErrA returns Right(())") {
    val noop = OnRaise.noop[F]
    val err: ErrA = NegativeInput(-1)
    assertEquals(noop.apply[ErrA](err), Right(()))
  }

  test("noop.apply with ErrB returns Right(())") {
    val noop = OnRaise.noop[F]
    val err: ErrB = EmptyInput("test")
    assertEquals(noop.apply[ErrB](err), Right(()))
  }

  test("noop.apply with two distinct error types in one test demonstrates universal quantification") {
    val noop = OnRaise.noop[F]
    val errA: ErrA = NegativeInput(-42)
    val errB: ErrB = EmptyInput("field")

    assertEquals(noop.apply[ErrA](errA), Right(()))
    assertEquals(noop.apply[ErrB](errB), Right(()))
  }

  test("OnRaise.noop instance is Serializable") {
    // Only meaningful on the JVM: java.io.ObjectOutputStream/ObjectInputStream
    // don't exist in Scala.js's java.io emulation, and Platform.isJvm is a
    // compile-time constant, so scalac constant-folds this branch away
    // entirely before the Scala.js linker ever sees it.
    if (Platform.isJvm) {
      val noop = OnRaise.noop[F]

      // Test that the instance can be serialized and deserialized
      val bytes = {
        val bos = new java.io.ByteArrayOutputStream()
        val oos = new java.io.ObjectOutputStream(bos)
        oos.writeObject(noop)
        oos.close()
        bos.toByteArray
      }

      val deserialized = {
        val bis = new java.io.ByteArrayInputStream(bytes)
        val ois = new java.io.ObjectInputStream(bis)
        val obj = ois.readObject().asInstanceOf[OnRaise[F]]
        ois.close()
        obj
      }

      // Verify the deserialized instance works
      assertEquals(deserialized.apply[ErrA](NegativeInput(-1)), Right(()))
      assertEquals(deserialized.apply[ErrB](EmptyInput("x")), Right(()))
    }
  }
}
