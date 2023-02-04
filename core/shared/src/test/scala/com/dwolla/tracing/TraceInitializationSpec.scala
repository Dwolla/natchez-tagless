package com.dwolla.tracing

import cats._
import cats.effect.{MonadCancelThrow, Resource, Trace => _}
import cats.mtl.Local
import cats.syntax.all._
import com.dwolla.tracing.syntax.toTraceResourceLifecycleOps
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
  trait TraceResourceAcquisitionTest extends TraceTest {
    val name = "test"

    def resourceA[F[_]]: Resource[F, Unit] =
      Resource.unit[F]

    def resourceB[F[_] : Applicative : Trace]: Resource[F, FakeAlg[F]] =
      Resource.pure(FakeAlg[F])

    def use[F[_] : MonadCancelThrow](entryPoint: EntryPoint[F])
                                    (alg: FakeAlg[F])
                                    (implicit L: Local[F, Span[F]]): F[Unit] =
      AppRoot(entryPoint, alg).handleRequest

    override def expectedHistory: List[(Lineage, NatchezCommand)] = {
      List(
        Root -> CreateRootSpan(name, Kernel(Map.empty), Span.Options.Defaults),
        Root(name) -> CreateSpan("resource a", None, Span.Options.Defaults),
        Root(name) -> ReleaseSpan("resource a"),
        Root(name) -> CreateSpan("resource b", None, Span.Options.Defaults),
        Root(name) -> ReleaseSpan("resource b"),
        Root -> ReleaseRootSpan(name),

        Root -> CreateRootSpan("app root", Kernel(Map.empty), Span.Options.Defaults),
        Root("app root") -> CreateSpan("app root child", None, Span.Options.Defaults),
        Root("app root") / "app root child" -> CreateSpan("FakeAlg.foo", None, Span.Options.Defaults),
        Root("app root") / "app root child" -> ReleaseSpan("FakeAlg.foo"),
        Root("app root") -> ReleaseSpan("app root child"),
        Root -> ReleaseRootSpan("app root"),

        Root -> CreateRootSpan(s"$name.finalize", Kernel(Map.empty), Span.Options.Defaults),
        Root(s"$name.finalize") -> CreateSpan("resource b.finalize", None, Span.Options.Defaults),
        Root(s"$name.finalize") -> ReleaseSpan("resource b.finalize"),
        Root(s"$name.finalize") -> CreateSpan("resource a.finalize", None, Span.Options.Defaults),
        Root(s"$name.finalize") -> ReleaseSpan("resource a.finalize"),
        Root -> ReleaseRootSpan(s"$name.finalize"),
      )
    }
  }

  traceTest("TraceResourceAcquisition via Trace[F]", new TraceResourceAcquisitionTest {
    private def resources[F[_] : MonadCancelThrow : Trace]: Resource[F, FakeAlg[F]] =
      for {
        _ <- resourceA[F].traceResourceLifecycleAs("resource a")
        alg <- resourceB[F].traceResourceLifecycleAs("resource b")
      } yield alg

    override def program[F[_] : MonadCancelThrow](entryPoint: EntryPoint[F])
                                                 (implicit L: Local[F, Span[F]]): F[Unit] =
      TraceResourceAcquisition(entryPoint, "test") { implicit trace =>
        resources
      }.use(use(entryPoint))
  })

  traceTest("TraceResourceAcquisition via Local[F, Span[F]]", new TraceResourceAcquisitionTest {
    import natchez.mtl._

    private def resources[F[_] : MonadCancelThrow](implicit L: Local[F, Span[F]]): Resource[F, FakeAlg[F]] =
      for {
        _ <- resourceA[F].traceResourceLifecycleAs("resource a")
        alg <- resourceB[F].traceResourceLifecycleAs("resource b")
      } yield alg

    override def program[F[_] : MonadCancelThrow](entryPoint: EntryPoint[F])
                                                 (implicit L: Local[F, Span[F]]): F[Unit] = {
      resources.traceResourceLifecycleInRootSpans("test", entryPoint)
        .use(use(entryPoint))
    }
  })
}
