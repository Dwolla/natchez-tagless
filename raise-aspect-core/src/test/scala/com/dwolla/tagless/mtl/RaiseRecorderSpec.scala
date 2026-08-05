package com.dwolla.tagless.mtl

import cats.Id
import munit.FunSuite
import org.typelevel.scalaccompat.annotation.*

class RaiseRecorderSpec extends FunSuite {
  trait Rendered[A] { def render(a: A): String }
  object Rendered {
    implicit val intRendered: Rendered[Int] = (a: Int) => a.toString
  }

  // Distinguishable so the test observes WHICH instance resolved, not merely
  // that one did.
  private def recording(into: collection.mutable.Buffer[String], label: String): OnRaise[Id, Rendered] =
    new OnRaise[Id, Rendered] {
      def apply[E](e: E)(implicit ev: Rendered[E]): Id[Unit] = { into += s"$label:${ev.render(e)}"; () }
    }

  test("with only a DefaultOnRaise in scope, the default resolves and runs") {
    val log = collection.mutable.Buffer.empty[String]
    implicit val default: DefaultOnRaise[Id, Rendered] = new DefaultOnRaise[Id, Rendered] {
      def onRaise: OnRaise[Id, Rendered] = recording(log, "default")
    }

    implicitly[RaiseRecorder[Id, Rendered]].onRaise(42)
    assertEquals(log.toList, List("default:42"))
  }

  test("a user OnRaise outranks the DefaultOnRaise, and it is the one that runs") {
    val log = collection.mutable.Buffer.empty[String]
    // Deliberately unreferenced: fromOnRaise outranks fromDefault whenever both
    // are in scope, so implicit search never touches this val — being an
    // unchosen candidate is exactly what this test is checking.
    @unused implicit val default: DefaultOnRaise[Id, Rendered] = new DefaultOnRaise[Id, Rendered] {
      def onRaise: OnRaise[Id, Rendered] = recording(log, "default")
    }
    implicit val user: OnRaise[Id, Rendered] = recording(log, "user")

    implicitly[RaiseRecorder[Id, Rendered]].onRaise(42)
    assertEquals(log.toList, List("user:42"))
  }

  test("resolving with both in scope reports no ambiguous implicit") {
    // Guards the shape: if someone later gives fromDefault a different
    // type-parameter list from fromOnRaise, this fails on 2.13 (and
    // passes on 2.12 and 3).
    val errors: String = compileErrors(
      """import cats.Id
implicit val default: DefaultOnRaise[Id, Rendered] = new DefaultOnRaise[Id, Rendered] {
  def onRaise: OnRaise[Id, Rendered] = new OnRaise[Id, Rendered] {
    def apply[E](e: E)(implicit ev: Rendered[E]): Id[Unit] = ()
  }
}
implicit val user: OnRaise[Id, Rendered] = new OnRaise[Id, Rendered] {
  def apply[E](e: E)(implicit ev: Rendered[E]): Id[Unit] = ()
}
implicitly[RaiseRecorder[Id, Rendered]]"""
    )
    assertNoDiff(errors, "")
  }
}
