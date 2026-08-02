package com.dwolla.tracing.mtl

import cats.data.EitherT
import cats.mtl.Raise
import cats.syntax.all._
import cats.tagless.aop.Aspect
import cats.{Eval, Functor, Id, ~>}
import com.dwolla.tagless.mtl.{OnRaise, RaiseArrow, RaisePull, RaiseAspect}
import munit.FunSuite
import natchez.TraceableValue

import scala.collection.mutable.ListBuffer

/** `TraceableRaiseAspect` adds no behaviour: it pins three type parameters so
  * that `derives` has a one-parameter type constructor to work with. These
  * tests say exactly that — the wrapper forwards both abstract members to the
  * instance it was built from, unchanged.
  */
class TraceableRaiseAspectSpec extends FunSuite {
  private type F[A] = Either[BarError, A]

  private val wide = HandWrittenBarRaiseAspect.instance
  private val narrow: TraceableRaiseAspect[Bar] = TraceableRaiseAspect.fromRaiseAspect(wide)

  private val raiseF: Raise[F, BarError] = Raise[F, BarError]

  /** Records what the interpreter is handed, then behaves like the forgetful
    * arrow — the same technique `RecordingFk` uses in `raise-aspect-core`.
    */
  private final class Recorder {
    val seen: ListBuffer[String] = ListBuffer.empty

    val fk: Aspect.Weave[F, TraceableValue, TraceableValue, *] ~> F =
      new (Aspect.Weave[F, TraceableValue, TraceableValue, *] ~> F) {
        def apply[A](w: Aspect.Weave[F, TraceableValue, TraceableValue, A]): F[A] = {
          val _ = seen += s"${w.algebraName}.${w.codomain.name}(${w.domain.flatten.map(_.name).mkString(",")})"
          w.codomain.target
        }
      }
  }

  test("intercept forwards to the underlying instance, weave for weave") {
    val wideRec = new Recorder
    val narrowRec = new Recorder

    val viaWide = wide.intercept(Bar[F])(wideRec.fk, OnRaise.noop[F, TraceableValue])
    val viaNarrow = narrow.intercept(Bar[F])(narrowRec.fk, OnRaise.noop[F, TraceableValue])

    assertEquals(viaNarrow.bar(5)(raiseF), viaWide.bar(5)(raiseF))
    assertEquals(viaNarrow.bar(-1)(raiseF), viaWide.bar(-1)(raiseF))
    assertEquals(narrowRec.seen.toList, wideRec.seen.toList)
    assertEquals(narrowRec.seen.toList, List("Bar.bar(i)", "Bar.bar(i)"))
  }

  test("intercept forwards the hook, so a raise is still observed exactly once") {
    val rendered = ListBuffer.empty[String]

    val hook: OnRaise[F, TraceableValue] = new OnRaise[F, TraceableValue] {
      def apply[E](e: E)(implicit ev: TraceableValue[E]): F[Unit] = {
        val _ = rendered += ev.toTraceValue(e).toString
        Right(())
      }
    }

    val rec = new Recorder
    val intercepted = narrow.intercept(Bar[F])(rec.fk, hook)

    assertEquals(intercepted.bar(5)(raiseF), "bar:5".asRight[BarError])
    assertEquals(rendered.toList, List.empty[String], "no raise, so no hook firing")

    assertEquals(intercepted.bar(-1)(raiseF), BarError.Negative(-1).asLeft[String])
    assertEquals(rendered.size, 1, "the hook must fire exactly once per raise")
    assert(rendered.head.contains("negative:-1"), s"rendered through TraceableValue, got ${rendered.head}")
  }

  test("mapK forwards to the underlying instance") {
    type G[A] = EitherT[Eval, BarError, A]

    val arrow: RaiseArrow[F, G, TraceableValue] =
      RaiseArrow(
        new (F ~> G) { def apply[A](fa: F[A]): G[A] = EitherT(Eval.now(fa)) },
        new RaisePull[G, F, TraceableValue] {
          def apply[E](rg: Raise[G, E])(implicit ev: TraceableValue[E]): Raise[F, E] =
            new Raise[F, E] {
              val functor: Functor[F] = Functor[F]
              def raise[E2 <: E, A](e: E2): F[A] = rg.raise[E2, A](e).value.value
            }
        }
      )

    val raiseG: Raise[G, BarError] = Raise[G, BarError]

    assertEquals(
      narrow.mapK(Bar[F])(arrow).bar(5)(raiseG).value.value,
      wide.mapK(Bar[F])(arrow).bar(5)(raiseG).value.value
    )
    assertEquals(
      narrow.mapK(Bar[F])(arrow).bar(-1)(raiseG).value.value,
      BarError.Negative(-1).asLeft[String]
    )
  }

  test("the narrow instance is accepted wherever the wide one is") {
    val asWide: RaiseAspect[Bar, TraceableValue, TraceableValue, TraceableValue] = narrow
    assert(asWide ne null)
  }
}
