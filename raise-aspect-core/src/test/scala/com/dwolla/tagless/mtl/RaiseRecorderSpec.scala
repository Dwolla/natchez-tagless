package com.dwolla.tagless.mtl

import cats.effect.{Ref, SyncIO}
import munit.CatsEffectSuite
import org.typelevel.scalaccompat.annotation.*

class RaiseRecorderSpec extends CatsEffectSuite {
  trait Rendered[A] { def render(a: A): String }
  object Rendered {
    implicit val intRendered: Rendered[Int] = (a: Int) => a.toString
  }

  // Distinguishable so the test observes WHICH instance resolved, not merely
  // that one did.
  private def recording(into: Ref[SyncIO, Vector[String]], label: String): OnRaise[SyncIO, Rendered] =
    new OnRaise[SyncIO, Rendered] {
      def apply[E](e: E)(implicit ev: Rendered[E]): SyncIO[Unit] = into.update(_ :+ s"$label:${ev.render(e)}")
    }

  test("with only a DefaultOnRaise in scope, the default resolves and runs") {
    for {
      log <- Ref.of[SyncIO, Vector[String]](Vector.empty)
      _ <- {
        implicit val default: DefaultOnRaise[SyncIO, Rendered] = new DefaultOnRaise[SyncIO, Rendered] {
          def onRaise: OnRaise[SyncIO, Rendered] = recording(log, "default")
        }
        implicitly[RaiseRecorder[SyncIO, Rendered]].onRaise(42)
      }
      seen <- log.get
    } yield assertEquals(seen.toList, List("default:42"))
  }

  test("a user OnRaise outranks the DefaultOnRaise, and it is the one that runs") {
    for {
      log <- Ref.of[SyncIO, Vector[String]](Vector.empty)
      _ <- {
        // Deliberately unreferenced: fromOnRaise outranks fromDefault whenever
        // both are in scope, so implicit search never touches this val — being
        // an unchosen candidate is exactly what this test is checking.
        @unused implicit val default: DefaultOnRaise[SyncIO, Rendered] = new DefaultOnRaise[SyncIO, Rendered] {
          def onRaise: OnRaise[SyncIO, Rendered] = recording(log, "default")
        }
        implicit val user: OnRaise[SyncIO, Rendered] = recording(log, "user")
        implicitly[RaiseRecorder[SyncIO, Rendered]].onRaise(42)
      }
      seen <- log.get
    } yield assertEquals(seen.toList, List("user:42"))
  }

  test("resolving with both in scope reports no ambiguous implicit") {
    // Guards the shape: if someone later gives fromDefault a different
    // type-parameter list from fromOnRaise, this fails on 2.13 (and
    // passes on 2.12 and 3).
    val errors: String = compileErrors(
      """import cats.effect.SyncIO
implicit val default: DefaultOnRaise[SyncIO, Rendered] = new DefaultOnRaise[SyncIO, Rendered] {
  def onRaise: OnRaise[SyncIO, Rendered] = new OnRaise[SyncIO, Rendered] {
    def apply[E](e: E)(implicit ev: Rendered[E]): SyncIO[Unit] = SyncIO.unit
  }
}
implicit val user: OnRaise[SyncIO, Rendered] = new OnRaise[SyncIO, Rendered] {
  def apply[E](e: E)(implicit ev: Rendered[E]): SyncIO[Unit] = SyncIO.unit
}
implicitly[RaiseRecorder[SyncIO, Rendered]]"""
    )
    assertNoDiff(errors, "")
  }
}
