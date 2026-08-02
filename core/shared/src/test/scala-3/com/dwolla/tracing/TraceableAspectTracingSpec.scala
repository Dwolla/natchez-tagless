package com.dwolla.tracing

import cats.effect.MonadCancelThrow
import cats.mtl.Local
import cats.syntax.all.*
import com.dwolla.tracing.syntax.*
import natchez.InMemory.Lineage.Root
import natchez.InMemory.NatchezCommand.*
import natchez.InMemory.{Lineage, NatchezCommand}
import natchez.TraceValue.StringValue
import natchez.*

import scala.annotation.experimental

/** The point of M14, stated as a test: an algebra that says
  * `derives TraceableAspect` and nothing else traces exactly as one with a
  * hand-declared `Aspect` does.
  *
  * The `DerivesLookup` half mentions no `Derive`, declares no instance and
  * names no `Aspect`; the `Lookup` half is the hand-written control, so it
  * necessarily does all three. The two `expectedHistory` lists below differ
  * only in the algebra name — that difference, and no other, is the whole
  * claim.
  */
@experimental
class TraceableAspectTracingSpec extends InMemorySuite {
  private def historyFor(alg: String): List[(Lineage, NatchezCommand)] = List(
    Root -> CreateRootSpan("test", Kernel(Map.empty), Span.Options.Defaults),
    Root("test") -> CreateSpan(s"$alg.get", None, Span.Options.Defaults),
    Root("test") / s"$alg.get" -> Put(List(s"$alg.get.key" -> StringValue("k"))),
    Root("test") / s"$alg.get" -> Put(List(s"$alg.get.returnValue" -> StringValue("v:k"))),
    Root("test") -> ReleaseSpan(s"$alg.get"),
    Root -> ReleaseRootSpan("test")
  )

  traceTest(
    "an algebra deriving TraceableAspect captures span, input, and output",
    new TraceTest {
      def program[F[_]: MonadCancelThrow](entryPoint: EntryPoint[F])(implicit L: Local[F, Span[F]]): F[Unit] = {
        import natchez.mtl.*
        val traced: DerivesLookup[F] = DerivesLookup[F].traceWithInputsAndOutputs
        entryPoint.root("test").use(L.scope(traced.get("k").void))
      }

      override def expectedHistory: List[(Lineage, NatchezCommand)] = historyFor("DerivesLookup")
    }
  )

  traceTest(
    "...identically to the same algebra with a hand-written Aspect",
    new TraceTest {
      def program[F[_]: MonadCancelThrow](entryPoint: EntryPoint[F])(implicit L: Local[F, Span[F]]): F[Unit] = {
        import natchez.mtl.*
        implicit val a: cats.tagless.aop.Aspect[Lookup, TraceableValue, TraceableValue] =
          HandWrittenLookupAspect.instance
        val traced: Lookup[F] = Lookup[F].traceWithInputsAndOutputs
        entryPoint.root("test").use(L.scope(traced.get("k").void))
      }

      override def expectedHistory: List[(Lineage, NatchezCommand)] = historyFor("Lookup")
    }
  )
}
