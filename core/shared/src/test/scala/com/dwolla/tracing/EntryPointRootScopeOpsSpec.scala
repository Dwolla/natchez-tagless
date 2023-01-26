package com.dwolla.tracing

import cats.effect.{MonadCancelThrow, Trace => _}
import cats.mtl.Local
import com.dwolla.tracing.syntax._
import natchez.InMemory.Lineage.Root
import natchez.InMemory.NatchezCommand._
import natchez.InMemory.{Lineage, NatchezCommand}
import natchez._
import natchez.mtl._

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

}
