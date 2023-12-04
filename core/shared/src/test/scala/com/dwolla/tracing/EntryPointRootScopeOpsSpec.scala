package com.dwolla.tracing

import cats.ApplicativeThrow
import cats.effect.{IO, MonadCancelThrow, Resource, Trace as _}
import cats.mtl.Local
import cats.syntax.all.*
import com.dwolla.tracing.syntax.*
import natchez.*
import natchez.InMemory.Lineage.Root
import natchez.InMemory.NatchezCommand.*
import natchez.InMemory.{Lineage, NatchezCommand}
import natchez.mtl.*

class EntryPointRootScopeOpsSpec extends InMemorySuite {
  traceTest("EntryPointRootScopeOps", new TraceTest {
    override def program[F[_] : MonadCancelThrow](entryPoint: EntryPoint[F])
                                                 (implicit L: Local[F, Span[F]]): F[Unit] =
      entryPoint.runInRoot("EntryPointRootScopeOps") {
        Trace[F].log("test")
      }

    override def expectedHistory: List[(Lineage, NatchezCommand)] =
      List(
        Root -> CreateRootSpan("EntryPointRootScopeOps", Kernel(Map.empty), Span.Options.Defaults),
        Root("EntryPointRootScopeOps") -> LogEvent("test"),
        Root -> ReleaseRootSpan("EntryPointRootScopeOps"),
      )
  })

  testWithLocalSpan("runInRoot succeeds even if finalization fails") { implicit L =>
    new FailOnFinalizationEntryPoint[IO].runInRoot("moot") {
      IO.unit
    }
  }

  testWithLocalSpan("runInRoot succeeds even if initialization fails") { implicit L =>
    new FailOnInitializationEntryPoint[IO].runInRoot("moot") {
        IO.pure(42)
      }
      .map(assertEquals(_, 42))
  }

  testWithLocalSpan("runInContinuation succeeds even if finalization fails") { implicit L =>
    new FailOnFinalizationEntryPoint[IO].runInContinuation("moot", Kernel(Map.empty)) {
      IO.unit
    }
  }

  testWithLocalSpan("runInContinuation succeeds even if initialization fails") { implicit L =>
    new FailOnInitializationEntryPoint[IO].runInContinuation("moot", Kernel(Map.empty)) {
        IO.pure(42)
      }
      .map(assertEquals(_, 42))
  }

  testWithLocalSpan("runInContinuationOrElseRoot succeeds even if finalization fails") { implicit L =>
    new FailOnFinalizationEntryPoint[IO].runInContinuationOrElseRoot("moot", Kernel(Map.empty)) {
      IO.unit
    }
  }

  testWithLocalSpan("runInContinuationOrElseRoot succeeds even if initialization fails") { implicit L =>
    new FailOnInitializationEntryPoint[IO].runInContinuationOrElseRoot("moot", Kernel(Map.empty)) {
        IO.pure(42)
      }
      .map(assertEquals(_, 42))
  }
}

class FailOnFinalizationEntryPoint[F[_] : ApplicativeThrow] extends EntryPoint[F] {
  private def failOnFinalization: Resource[F, Span[F]] =
    Resource.make(Span.noop[F].pure[F]) { _ =>
      new RuntimeException("boom").raiseError[F, Unit]
    }

  override def root(name: String, options: Span.Options): Resource[F, Span[F]] = failOnFinalization
  override def continue(name: String, kernel: Kernel, options: Span.Options): Resource[F, Span[F]] = failOnFinalization
  override def continueOrElseRoot(name: String, kernel: Kernel, options: Span.Options): Resource[F, Span[F]] = failOnFinalization
}

class FailOnInitializationEntryPoint[F[_] : ApplicativeThrow] extends EntryPoint[F] {
  private def failOnInitialization: Resource[F, Span[F]] =
    Resource.make(new RuntimeException("boom").raiseError[F, Span[F]]) { _ =>
      ().pure[F]
    }

  override def root(name: String, options: Span.Options): Resource[F, Span[F]] = failOnInitialization
  override def continue(name: String, kernel: Kernel, options: Span.Options): Resource[F, Span[F]] = failOnInitialization
  override def continueOrElseRoot(name: String, kernel: Kernel, options: Span.Options): Resource[F, Span[F]] = failOnInitialization
}
