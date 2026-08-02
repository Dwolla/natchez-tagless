package com.dwolla.tracing.mtl

import cats.data.EitherT
import cats.mtl.Raise
import cats.syntax.all._
import cats.tagless.aop.Aspect
import cats.{Eval, Functor, ~>}
import com.dwolla.tagless.mtl.{DeriveRaise, OnRaise, RaiseArrow, RaisePull, RaiseAspect}
import munit.FunSuite
import natchez.TraceableValue

import scala.annotation.experimental
import scala.collection.mutable.ListBuffer

/** Deliberately declares ''both'' instances in one companion — the narrow one
  * the `derives` clause synthesizes, and a hand-declared wide one — so
  * [[TraceableRaiseAspectSpec]] can pin which of them implicit search picks.
  * That situation is what a user creates by adding `derives
  * TraceableRaiseAspect` to an algebra that already had a wide instance.
  */
trait Coexisting[F[_]] derives TraceableRaiseAspect:
  def bar(i: Int)(using R: Raise[F, BarError]): F[String]

@experimental
object Coexisting:
  implicit val wide: RaiseAspect[Coexisting, TraceableValue, TraceableValue, TraceableValue] =
    DeriveRaise.aspect[Coexisting, TraceableValue, TraceableValue, TraceableValue]

/** `TraceableRaiseAspect` adds no behaviour: it pins three type parameters so
  * that `derives` has a one-parameter type constructor to work with. These
  * tests say exactly that — the wrapper forwards both abstract members to the
  * instance it was built from, unchanged.
  */
@experimental
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

  test("the derives clause produces an instance, and it is the narrow type") {
    val derived: TraceableRaiseAspect[DerivesBar] = summon[TraceableRaiseAspect[DerivesBar]]
    assert(derived ne null)
  }

  test("the derived instance agrees with a hand-written one on intercept") {
    val derivedRec = new Recorder
    val handRec = new Recorder

    // Same algebra shape, so the hand-written Bar reference is a valid oracle
    // for DerivesBar once the algebra name is accounted for.
    val derivedAlg =
      summon[TraceableRaiseAspect[DerivesBar]]
        .intercept(DerivesBar[F])(derivedRec.fk, OnRaise.noop[F, TraceableValue])
    val handAlg =
      HandWrittenBarRaiseAspect.instance
        .intercept(Bar[F])(handRec.fk, OnRaise.noop[F, TraceableValue])

    assertEquals(derivedAlg.bar(5)(using raiseF), handAlg.bar(5)(raiseF))
    assertEquals(derivedAlg.bar(-1)(using raiseF), handAlg.bar(-1)(raiseF))

    // identical but for the algebra name, which is the only thing that differs
    assertEquals(
      derivedRec.seen.toList,
      handRec.seen.toList.map(_.replace("Bar.bar", "DerivesBar.bar"))
    )
    assertEquals(derivedRec.seen.toList, List("DerivesBar.bar(i)", "DerivesBar.bar(i)"))
  }

  test("a wide RaiseAspect does not satisfy a demand for the narrow type") {
    // needs an explicit `String` annotation on Scala 3, or a cyclic-reference
    // check fires and captures that error instead of the snippet's diagnostics
    val errors: String = compileErrors(
      "summon[TraceableRaiseAspect[Bar]](using HandWrittenBarRaiseAspect.instance)"
    )
    assert(errors.contains("Required:"), errors)
    assert(errors.contains("TraceableRaiseAspect[com.dwolla.tracing.mtl.Bar]"), errors)
    assert(errors.contains("RaiseAspect[com.dwolla.tracing.mtl.Bar"), errors)
  }

  /** Not a defect — ordinary Scala specificity — but a silent-shadowing hazard
    * worth latching: an algebra that already has a hand-written wide instance
    * doing something extra in `intercept` (redaction, an extra hook) and then
    * gains a `derives TraceableRaiseAspect` clause quietly stops using the
    * hand-written one, with no error and no warning. `-Xprint:typer` confirms
    * the choice statically: the wide demand types to
    * `Coexisting.derived$TraceableRaiseAspect`, the `derives`-synthesized given.
    * The escape hatch is lexical scope, which still outranks implicit scope.
    */
  test("a companion's narrow derived instance silently outranks a wide one beside it, and a local wide instance outranks both") {
    val fromCompanion: RaiseAspect[Coexisting, TraceableValue, TraceableValue, TraceableValue] =
      summon[RaiseAspect[Coexisting, TraceableValue, TraceableValue, TraceableValue]]

    assert(
      fromCompanion eq summon[TraceableRaiseAspect[Coexisting]],
      "a wide demand must resolve to the narrow given the derives clause synthesized"
    )
    assert(
      fromCompanion ne Coexisting.wide,
      "the hand-declared wide instance is the one that loses, silently"
    )

    val localWide: RaiseAspect[Coexisting, TraceableValue, TraceableValue, TraceableValue] =
      DeriveRaise.aspect[Coexisting, TraceableValue, TraceableValue, TraceableValue]

    locally {
      implicit val shadow: RaiseAspect[Coexisting, TraceableValue, TraceableValue, TraceableValue] =
        localWide

      assert(
        summon[RaiseAspect[Coexisting, TraceableValue, TraceableValue, TraceableValue]] eq localWide,
        "a lexically scoped wide instance must beat the companion's narrow one"
      )
    }
  }

  test("...and fromRaiseAspect is how you get one anyway") {
    val fixed: TraceableRaiseAspect[Bar] =
      TraceableRaiseAspect.fromRaiseAspect(HandWrittenBarRaiseAspect.instance)
    assert(fixed ne null)
  }
}
