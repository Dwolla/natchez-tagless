package com.dwolla.tracing

import cats._
import cats.effect.syntax.all._
import cats.effect.{MonadCancelThrow, Resource, Trace => _}
import cats.mtl.Local
import cats.syntax.all._
import natchez.InMemory.Lineage.Root
import natchez.InMemory.NatchezCommand._
import natchez.InMemory.{Lineage, NatchezCommand}
import natchez._

trait AppRoot[F[_]] {
  def handleRequest: F[Unit]
}

object AppRoot {
  def apply[F[_] : MonadCancelThrow](entryPoint: EntryPoint[F], fakeAlg: FakeAlg[F])
                                    (implicit L: Local[F, Span[F]]): AppRoot[F] = new AppRoot[F] {
    override def handleRequest: F[Unit] =
      entryPoint
        .root("app root")
        .flatMap(_.span("app root child"))
        .use {
          L.scope(fakeAlg.foo)
        }
  }
}

trait FakeAlg[F[_]] {
  def foo: F[Unit]
}

object FakeAlg {
  def apply[F[_] : Applicative : Trace]: FakeAlg[F] = new FakeAlg[F] {
    override def foo: F[Unit] = Trace[F].span("FakeAlg.foo")(().pure[F])
  }
}

class TraceInitializationSpec extends InMemorySuite {
  traceTest("TraceResourceAcquisition", new TraceTest {
    private def resources[F[_] : Applicative : Trace]: Resource[F, FakeAlg[F]] =
      Trace[F].span("resource acquisition")(().pure[F])
        .toResource
        .onFinalize(Trace[F].span("resource finalizer")(().pure[F]))
        .as(FakeAlg[F])

    override def program[F[_] : MonadCancelThrow](entryPoint: EntryPoint[F])
                                                 (implicit L: Local[F, Span[F]]): F[Unit] =
      TraceResourceAcquisition(entryPoint, "test") { implicit trace =>
        resources
      }.use { alg =>
        AppRoot(entryPoint, alg).handleRequest
      }

    override def expectedHistory: List[(Lineage, NatchezCommand)] =
      List(
        Root -> CreateRootSpan("test", Kernel(Map.empty), Span.Options.Defaults),
        Root("test") -> CreateSpan("resource acquisition", None, Span.Options.Defaults),
        Root("test") -> ReleaseSpan("resource acquisition"),
        Root -> ReleaseRootSpan("test"),

        Root -> CreateRootSpan("app root", Kernel(Map.empty), Span.Options.Defaults),
        Root("app root") -> CreateSpan("app root child", None, Span.Options.Defaults),
        Root("app root") / "app root child" -> CreateSpan("FakeAlg.foo", None, Span.Options.Defaults),
        Root("app root") / "app root child" -> ReleaseSpan("FakeAlg.foo"),
        Root("app root") -> ReleaseSpan("app root child"),
        Root -> ReleaseRootSpan("app root"),

        Root -> CreateRootSpan("test.finalize", Kernel(Map.empty), Span.Options.Defaults),
        Root("test.finalize") -> CreateSpan("resource finalizer", None, Span.Options.Defaults),
        Root("test.finalize") -> ReleaseSpan("resource finalizer"),
        Root -> ReleaseRootSpan("test.finalize"),
      )
  })
}
