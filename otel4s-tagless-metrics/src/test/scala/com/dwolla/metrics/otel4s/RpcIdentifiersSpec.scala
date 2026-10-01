package com.dwolla.metrics.otel4s

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class RpcIdentifiersSpec extends ScalaCheckSuite {
  private val names: Gen[String] = Gen.alphaNumStr

  property("RpcSystem values with the same name are equal and hash alike") {
    forAll(names) { name =>
      assertEquals(RpcSystem(name), RpcSystem(name))
      assertEquals(RpcSystem(name).hashCode, RpcSystem(name).hashCode)
    }
  }

  property("RpcService values with the same name are equal and hash alike") {
    forAll(names) { name =>
      assertEquals(RpcService(name), RpcService(name))
      assertEquals(RpcService(name).hashCode, RpcService(name).hashCode)
    }
  }

  property("different names are not equal") {
    forAll(names, names) { (a, b) =>
      assertEquals(RpcSystem(a) == RpcSystem(b), a == b)
      assertEquals(RpcService(a) == RpcService(b), a == b)
    }
  }

  test("an RpcSystem never equals an RpcService with the same name") {
    assert(RpcSystem("thrift") != (RpcService("thrift"): Any))
  }
}
