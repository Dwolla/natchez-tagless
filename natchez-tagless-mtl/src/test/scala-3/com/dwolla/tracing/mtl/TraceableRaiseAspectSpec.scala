package com.dwolla.tracing.mtl

import cats.data.EitherT
import cats.effect.{Ref, SyncIO}
import cats.mtl.Raise
import cats.syntax.all._
import cats.tagless.aop.Aspect
import cats.~>
import com.dwolla.tagless.mtl.{DeriveRaise, OnRaise, RaiseAspect}
import natchez.TraceableValue

import scala.annotation.experimental

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
  * tests say exactly that — the wrapper forwards its one abstract member to
  * the instance it was built from, unchanged.
  */
@experimental
class TraceableRaiseAspectSpec extends munit.CatsEffectSuite {
  private type F[A] = EitherT[SyncIO, BarError, A]

  private val wide = HandWrittenBarRaiseAspect.instance
  private val narrow: TraceableRaiseAspect[Bar] = TraceableRaiseAspect.fromRaiseAspect(wide)

  private val raiseF: Raise[F, BarError] = Raise[F, BarError]

  /** Turns "raised an error nobody expected" into a failed `SyncIO`, so
    * munit-cats-effect's registered `SyncIO` transform reports it as a test
    * failure with a real stack trace instead of silently succeeding on an
    * unexamined `Left`. Inlined rather than shared: this module's
    * `natchezTaglessMtl` project depends on `raiseAspect` for compile
    * only, not `test->test`, so `SyncIOTestSyntax` (defined in
    * `raise-aspect`'s test sources) isn't on this module's test
    * classpath.
    */
  private def runOrFail[A](fa: F[A]): SyncIO[A] =
    fa.value.flatMap {
      case Right(a) => SyncIO.pure(a)
      case Left(e) => SyncIO.raiseError(new AssertionError(s"test raised unexpectedly: $e"))
    }

  /** Records what the interpreter is handed, then behaves like the forgetful
    * arrow — the same technique `RecordingFk` uses in `raise-aspect`.
    */
  private final class Recorder(seenRef: Ref[F, Vector[String]]) {
    def seen: F[Vector[String]] = seenRef.get

    val fk: Aspect.Weave[F, TraceableValue, TraceableValue, *] ~> F =
      new (Aspect.Weave[F, TraceableValue, TraceableValue, *] ~> F) {
        def apply[A](w: Aspect.Weave[F, TraceableValue, TraceableValue, A]): F[A] =
          seenRef.update(_ :+ s"${w.algebraName}.${w.codomain.name}(${w.domain.flatten.map(_.name).mkString(",")})") *>
            w.codomain.target
      }
  }

  private object Recorder {
    def apply(): F[Recorder] = Ref.of[F, Vector[String]](Vector.empty).map(new Recorder(_))
  }

  test("intercept forwards to the underlying instance, weave for weave") {
    runOrFail {
      for {
        wideRec <- Recorder()
        narrowRec <- Recorder()
        viaWide = wide.intercept(Bar[F])(wideRec.fk, OnRaise.noop[F, TraceableValue])
        viaNarrow = narrow.intercept(Bar[F])(narrowRec.fk, OnRaise.noop[F, TraceableValue])
        r1 <- EitherT.liftF[SyncIO, BarError, Either[BarError, String]](viaNarrow.bar(5)(raiseF).value)
        w1 <- EitherT.liftF[SyncIO, BarError, Either[BarError, String]](viaWide.bar(5)(raiseF).value)
        _ = assertEquals(r1, w1)
        r2 <- EitherT.liftF[SyncIO, BarError, Either[BarError, String]](viaNarrow.bar(-1)(raiseF).value)
        w2 <- EitherT.liftF[SyncIO, BarError, Either[BarError, String]](viaWide.bar(-1)(raiseF).value)
        _ = assertEquals(r2, w2)
        narrowSeen <- narrowRec.seen
        wideSeen <- wideRec.seen
        _ = assertEquals(narrowSeen.toList, wideSeen.toList)
        _ = assertEquals(narrowSeen.toList, List("Bar.bar(i)", "Bar.bar(i)"))
      } yield ()
    }
  }

  test("intercept forwards the hook, so a raise is still observed exactly once") {
    runOrFail {
      for {
        rendered <- Ref.of[F, Vector[String]](Vector.empty)
        hook = new OnRaise[F, TraceableValue] {
          def apply[E](e: E)(implicit ev: TraceableValue[E]): F[Unit] =
            rendered.update(_ :+ ev.toTraceValue(e).toString)
        }
        rec <- Recorder()
        intercepted = narrow.intercept(Bar[F])(rec.fk, hook)
        r1 <- EitherT.liftF[SyncIO, BarError, Either[BarError, String]](intercepted.bar(5)(raiseF).value)
        _ = assertEquals(r1, "bar:5".asRight[BarError])
        seen1 <- rendered.get
        _ = assertEquals(seen1.toList, List.empty[String], "no raise, so no hook firing")
        r2 <- EitherT.liftF[SyncIO, BarError, Either[BarError, String]](intercepted.bar(-1)(raiseF).value)
        _ = assertEquals(r2, BarError.Negative(-1).asLeft[String])
        seen2 <- rendered.get
        _ = assertEquals(seen2.size, 1, "the hook must fire exactly once per raise")
        _ = assert(seen2.head.contains("negative:-1"), s"rendered through TraceableValue, got ${seen2.head}")
      } yield ()
    }
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
    runOrFail {
      for {
        derivedRec <- Recorder()
        handRec <- Recorder()
        // Same algebra shape, so the hand-written Bar reference is a valid oracle
        // for DerivesBar once the algebra name is accounted for.
        derivedAlg = summon[TraceableRaiseAspect[DerivesBar]]
          .intercept(DerivesBar[F])(derivedRec.fk, OnRaise.noop[F, TraceableValue])
        handAlg = HandWrittenBarRaiseAspect.instance
          .intercept(Bar[F])(handRec.fk, OnRaise.noop[F, TraceableValue])
        d1 <- EitherT.liftF[SyncIO, BarError, Either[BarError, String]](derivedAlg.bar(5)(using raiseF).value)
        h1 <- EitherT.liftF[SyncIO, BarError, Either[BarError, String]](handAlg.bar(5)(raiseF).value)
        _ = assertEquals(d1, h1)
        d2 <- EitherT.liftF[SyncIO, BarError, Either[BarError, String]](derivedAlg.bar(-1)(using raiseF).value)
        h2 <- EitherT.liftF[SyncIO, BarError, Either[BarError, String]](handAlg.bar(-1)(raiseF).value)
        _ = assertEquals(d2, h2)
        derivedSeen <- derivedRec.seen
        handSeen <- handRec.seen
        // identical but for the algebra name, which is the only thing that differs
        _ = assertEquals(
          derivedSeen.toList,
          handSeen.toList.map(_.replace("Bar.bar", "DerivesBar.bar"))
        )
        _ = assertEquals(derivedSeen.toList, List("DerivesBar.bar(i)", "DerivesBar.bar(i)"))
      } yield ()
    }
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
