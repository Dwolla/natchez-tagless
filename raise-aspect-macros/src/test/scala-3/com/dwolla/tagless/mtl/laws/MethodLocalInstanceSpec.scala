package com.dwolla.tagless.mtl
package laws

import cats.Applicative
import cats.arrow.FunctionK
import cats.data.EitherT
import cats.effect.{Ref, SyncIO}
import cats.mtl.Raise
import cats.syntax.all.*
import munit.CatsEffectSuite

import com.dwolla.tagless.mtl.SyncIOTestSyntax.*

import scala.annotation.experimental

/** Instances the derivation cannot see, because the method supplies them
  * itself.
  *
  * `Widget`, `WidgetError`, `Thing` and `SubThing` deliberately have no `Render`
  * instance anywhere: every instance these algebras use arrives through a
  * method's own `using` clause, never through `Dom`/`Cod`/`Err` summoned at
  * the derivation site, where these parameters don't exist for these
  * algebras.
  *
  * Every success case here asserts at ''runtime'' that the woven advice carries
  * the instance the call supplied — the same method is called twice with two
  * different instances and must render differently both times. Compiling is not
  * enough: an implementation that binds some other conforming instance would
  * still compile. That is not hypothetical: a `summonInline`-based
  * implementation tried during development had exactly that flaw.
  */
final case class Widget(id: Int)
final case class WidgetError(reason: String)

/** Method-local `Dom`: `Render[Widget]` is supplied only by `show`'s own `using`
  * clause, not at the derivation site.
  */
trait WidgetShowAlg[F[_]]:
  def show(w: Widget)(using R: Render[Widget]): F[String]

/** Method-local `Cod`: same story, but for the codomain advice on `make`'s
  * `F[Widget]` result.
  */
trait WidgetMakeAlg[F[_]]:
  def make(i: Int)(using R: Render[Widget]): F[Widget]

/** Method-local `Err`: `Render[WidgetError]` is supplied only by `risky`'s own
  * `using` clause, alongside the `Raise[F, WidgetError]` capability.
  */
trait WidgetRiskyAlg[F[_]]:
  def risky(i: Int)(using RE: Render[WidgetError], R: Raise[F, WidgetError]): F[String]

/** All three method-local instance kinds on one algebra. */
trait WidgetAlg[F[_]] extends WidgetShowAlg[F], WidgetMakeAlg[F], WidgetRiskyAlg[F]

/** `Render[A]` can ''never'' resolve at the derivation site, because `A` is
  * abstract there; only the method's own clause has it.
  */
trait WidgetPolyAlg[F[_]]:
  def poly[A](a: A)(using R: Render[A]): F[A]

/** The same, spelled as a context bound — the given parameter is synthetic and
  * named by the compiler, so this proves the fallback does not depend on the
  * parameter having a user-written name.
  */
trait WidgetBoundedAlg[F[_]]:
  def bounded[A: Render](a: A): F[A]

/** A conforming candidate is not always the needed type spelled exactly:
  * `WidgetRender` is a strict subtype, `AliasedRender` is the same type behind an
  * alias, and `several` hands over two instances of which only one conforms.
  */
trait WidgetRender extends Render[Widget]

trait WidgetVariationsAlg[F[_]]:
  def sub(w: Widget)(using R: WidgetRender): F[String]
  def aliased(w: Widget)(using R: MethodLocal.AliasedRender): F[String]
  def several(w: Widget)(using S: Render[WidgetError], R: Render[Widget]): F[String]

/** Scala 3 alone can put the conforming given somewhere other than the final
  * clause, so the candidate search has to look at every `using` clause.
  */
trait WidgetMultiUsingAlg[F[_]]:
  def multi(w: Widget)(using R: Render[Widget])(using i: Render[Int]): F[String]

/** Contravariant widening — why the conformance test is `<:<` and not `=:=`.
  * `sub` is handed a `Contra[Thing]` and the derivation needs `Contra[SubThing]`;
  * a describer of every `Thing` can describe a `SubThing`, so accepting it is
  * sound as well as convenient.
  */
trait Contra[-A] extends Serializable:
  def describe(a: A): String

class Thing(val label: String)
class SubThing(label: String) extends Thing(label)

trait ContraAlg[F[_]]:
  def sub(s: SubThing)(using C: Contra[Thing]): F[String]

// --- shapes that must still be rejected ------------------------------------

/** Deriving `Render[List[Widget]]` from a method-local `Render[Widget]` is out
  * of scope: the fallback references what the method is handed, it does not run
  * implicit search.
  */
trait WidgetListAlg[F[_]]:
  def listy(ws: List[Widget])(using R: Render[Widget]): F[String]

/** `Render` is invariant, so a `Render[Thing]` is not a `Render[SubThing]`. */
trait WidgetInvariantAlg[F[_]]:
  def inv(s: SubThing)(using R: Render[Thing]): F[String]

/** The instance is reachable only by projecting out of the parameter. */
final case class Box[A](unbox: Render[A])

trait WidgetBoxAlg[F[_]]:
  def boxed(w: Widget)(using B: Box[Widget]): F[String]

/** A conforming instance handed as an ordinary parameter — a very plausible
  * mistake, and the one the diagnostic has to point at.
  */
trait WidgetUnmarkedAlg[F[_]]:
  def unmarked(w: Widget, R: Render[Widget]): F[String]

/** Two conforming given parameters. Silently taking the first would be a
  * wrong-instance bug waiting to happen.
  */
trait WidgetAmbiguousAlg[F[_]]:
  def ambiguous(w: Widget)(using R: Render[Widget], S: Render[Widget]): F[String]

/** Both sources are available for `Render[Int]`: `Render.renderInt` at the
  * derivation site and the method's own given parameter. The hybrid is
  * derivation-site-first, so the fallback must not fire.
  */
trait PrecedenceAlg[F[_]]:
  def pick(i: Int)(using R: Render[Int]): F[String]

object MethodLocal:
  type WidgetResult[A] = Either[WidgetError, A]
  type WidgetLazily[A] = EitherT[SyncIO, WidgetError, A]
  type AliasedRender = Render[Widget]

  /** Two visibly different instances of each type class, so a test can prove the
    * advice carries the one the ''call'' supplied rather than any other.
    */
  val loud: Render[Widget] = (w: Widget) => s"loud:${w.id}"
  val quiet: Render[Widget] = (w: Widget) => s"quiet:${w.id}"
  val loudError: Render[WidgetError] = (e: WidgetError) => s"loudError:${e.reason}"
  val quietError: Render[WidgetError] = (e: WidgetError) => s"quietError:${e.reason}"
  val loudThing: Contra[Thing] = (t: Thing) => s"loudThing:${t.label}"
  val quietThing: Contra[Thing] = (t: Thing) => s"quietThing:${t.label}"

  given Renderable[Contra] with
    def render[A](instance: Contra[A])(a: A): String = instance.describe(a)

  def widgets[F[_]: Applicative]: WidgetAlg[F] = new WidgetAlg[F]:
    def show(w: Widget)(using R: Render[Widget]): F[String] = R.render(w).pure[F]
    def make(i: Int)(using R: Render[Widget]): F[Widget] = Widget(i).pure[F]
    def risky(i: Int)(using RE: Render[WidgetError], R: Raise[F, WidgetError]): F[String] =
      if i < 0 then R.raise(WidgetError(s"negative:$i")) else s"ok:$i".pure[F]

  def poly[F[_]: Applicative]: WidgetPolyAlg[F] = new WidgetPolyAlg[F]:
    def poly[A](a: A)(using R: Render[A]): F[A] = a.pure[F]

  def bounded[F[_]: Applicative]: WidgetBoundedAlg[F] = new WidgetBoundedAlg[F]:
    def bounded[A: Render](a: A): F[A] = a.pure[F]

  def variations[F[_]: Applicative]: WidgetVariationsAlg[F] = new WidgetVariationsAlg[F]:
    def sub(w: Widget)(using R: WidgetRender): F[String] = R.render(w).pure[F]
    def aliased(w: Widget)(using R: AliasedRender): F[String] = R.render(w).pure[F]
    def several(w: Widget)(using S: Render[WidgetError], R: Render[Widget]): F[String] =
      R.render(w).pure[F]

  def multiUsing[F[_]: Applicative]: WidgetMultiUsingAlg[F] = new WidgetMultiUsingAlg[F]:
    def multi(w: Widget)(using R: Render[Widget])(using i: Render[Int]): F[String] =
      R.render(w).pure[F]

  def contra[F[_]: Applicative]: ContraAlg[F] = new ContraAlg[F]:
    def sub(s: SubThing)(using C: Contra[Thing]): F[String] = C.describe(s).pure[F]

  def precedence[F[_]: Applicative]: PrecedenceAlg[F] = new PrecedenceAlg[F]:
    def pick(i: Int)(using R: Render[Int]): F[String] = R.render(i).pure[F]

  /** An arrow whose `pull` renders every raised error through the `Err` evidence
    * the ''derivation'' handed it. That evidence is the only observable trace of
    * which `Err[E]` the macro resolved, since `RaisePull.id` ignores it.
    */
  def recordingArrow[F[_]: Applicative](recorded: Ref[F, Vector[String]]): RaiseArrow[F, F, Render] =
    RaiseArrow(
      FunctionK.id[F],
      new RaisePull[F, F, Render]:
        def apply[E](rg: Raise[F, E])(implicit ev: Render[E]): Raise[F, E] =
          new Raise[F, E]:
            val functor: cats.Functor[F] = rg.functor
            def raise[E2 <: E, A](e: E2): F[A] =
              recorded.update(_ :+ ev.render(e)) *> rg.raise[E2, A](e)
    )

@experimental
class MethodLocalInstanceSpec extends CatsEffectSuite:
  import LawsInstances.renderableRender
  import MethodLocal.{*, given}

  private val showAspect: RaiseAspect[WidgetShowAlg, Render, Render, Render] =
    DeriveRaise.aspect[WidgetShowAlg, Render, Render, Render]
  private val makeAspect: RaiseAspect[WidgetMakeAlg, Render, Render, Render] =
    DeriveRaise.aspect[WidgetMakeAlg, Render, Render, Render]
  private val riskyAspect: RaiseAspect[WidgetRiskyAlg, Render, Render, Render] =
    DeriveRaise.aspect[WidgetRiskyAlg, Render, Render, Render]
  private val widgetAspect: RaiseAspect[WidgetAlg, Render, Render, Render] =
    DeriveRaise.aspect[WidgetAlg, Render, Render, Render]
  private val polyAspect: RaiseAspect[WidgetPolyAlg, Render, Render, Render] =
    DeriveRaise.aspect[WidgetPolyAlg, Render, Render, Render]
  private val boundedAspect: RaiseAspect[WidgetBoundedAlg, Render, Render, Render] =
    DeriveRaise.aspect[WidgetBoundedAlg, Render, Render, Render]
  private val variationsAspect: RaiseAspect[WidgetVariationsAlg, Render, Render, Render] =
    DeriveRaise.aspect[WidgetVariationsAlg, Render, Render, Render]
  private val multiUsingAspect: RaiseAspect[WidgetMultiUsingAlg, Render, Render, Render] =
    DeriveRaise.aspect[WidgetMultiUsingAlg, Render, Render, Render]
  private val contraAspect: RaiseAspect[ContraAlg, Contra, Render, Render] =
    DeriveRaise.aspect[ContraAlg, Contra, Render, Render]
  private val precedenceAspect: RaiseAspect[PrecedenceAlg, Render, Render, Render] =
    DeriveRaise.aspect[PrecedenceAlg, Render, Render, Render]
  private val riskyFunctorK: RaiseFunctorK[WidgetRiskyAlg, Render] =
    DeriveRaise.functorK[WidgetRiskyAlg, Render]

  private val raiseWidgetLazily: Raise[WidgetLazily, WidgetError] = Raise[WidgetLazily, WidgetError]

  test("the Dom advice carries the Render the method itself was handed") {
    (for {
      recorder <- RecordingFk[WidgetLazily, Render, Render]
      instrumented = showAspect.intercept(widgets[WidgetLazily])(recorder.fk, OnRaise.noop[WidgetLazily, Render])
      _ <- instrumented.show(Widget(1))(using loud)
      w1 <- recorder.weaves
      rendered1 = WeaveRenderer.render(w1.last.weave)
      _ = assertEquals(rendered1.algebraName, "WidgetShowAlg")
      _ = assertEquals(rendered1.methodName, "show")
      _ = assertEquals(rendered1.domain, List(List("w" -> "loud:1")))
      _ <- instrumented.show(Widget(1))(using quiet)
      w2 <- recorder.weaves
      _ = assertEquals(WeaveRenderer.render(w2.last.weave).domain, List(List("w" -> "quiet:1")))
    } yield ()).runOrFail
  }

  test("the Cod advice carries the Render the method itself was handed") {
    (for {
      recorder <- RecordingFk[WidgetLazily, Render, Render]
      instrumented = makeAspect.intercept(widgets[WidgetLazily])(recorder.fk, OnRaise.noop[WidgetLazily, Render])
      _ <- instrumented.make(2)(using loud)
      w1 <- recorder.weaves
      loudly = w1.last.weave
      _ = assertEquals(loudly.codomain.name, "make")
      loudTarget <- EitherT.liftF[SyncIO, WidgetError, WidgetResult[String]](
        loudly.codomain.target.map(loudly.codomain.instance.render).value
      )
      _ = assertEquals(loudTarget, Right("loud:2"): WidgetResult[String])
      _ <- instrumented.make(2)(using quiet)
      w2 <- recorder.weaves
      quietly = w2.last.weave
      quietTarget <- EitherT.liftF[SyncIO, WidgetError, WidgetResult[String]](
        quietly.codomain.target.map(quietly.codomain.instance.render).value
      )
      _ = assertEquals(quietTarget, Right("quiet:2"): WidgetResult[String])
    } yield ()).runOrFail
  }

  test("the Err evidence transported with the capability is the one the method was handed") {
    (for {
      recorded <- Ref.of[WidgetLazily, Vector[String]](Vector.empty)
      mapped = riskyAspect.mapK(widgets[WidgetLazily])(recordingArrow(recorded))
      r1 <- EitherT.liftF[SyncIO, WidgetError, WidgetResult[String]](
        mapped.risky(-1)(using loudError, raiseWidgetLazily).value
      )
      _ = assertEquals(r1, Left(WidgetError("negative:-1")): WidgetResult[String])
      r2 <- EitherT.liftF[SyncIO, WidgetError, WidgetResult[String]](
        mapped.risky(-2)(using quietError, raiseWidgetLazily).value
      )
      _ = assertEquals(r2, Left(WidgetError("negative:-2")): WidgetResult[String])
      seen <- recorded.get
      _ = assertEquals(seen.toList, List("loudError:negative:-1", "quietError:negative:-2"))
    } yield ()).runOrFail
  }

  test("the intercept hook renders a raise through the method-local Err instance") {
    // `intercept` wires the hook in directly —
    // `RaiseAspect.observing($pn, $onRaise)($applyF, $errInstance)` — so this
    // asserts a stronger claim than the test above (which observes the hook
    // only indirectly, through `mapK` and a hand-rolled recording
    // `RaisePull`): the *method-local* `Err[WidgetError]` the call was handed
    // is exactly what reaches the hook, not a derivation-site instance and
    // not `toString`.
    (for {
      rendered <- Ref.of[WidgetLazily, Vector[String]](Vector.empty)
      hook = new OnRaise[WidgetLazily, Render]:
        def apply[E](e: E)(implicit ev: Render[E]): WidgetLazily[Unit] = rendered.update(_ :+ ev.render(e))
      recorder <- RecordingFk[WidgetLazily, Render, Render]
      instrumented = riskyAspect.intercept(widgets[WidgetLazily])(recorder.fk, hook)
      _ <- EitherT.liftF[SyncIO, WidgetError, Unit](
        instrumented.risky(-7)(using loudError, raiseWidgetLazily).value.void
      )
      seen1 <- rendered.get
      _ = assertEquals(seen1.toList, List("loudError:negative:-7"))
      _ <- EitherT.liftF[SyncIO, WidgetError, Unit](
        instrumented.risky(-8)(using quietError, raiseWidgetLazily).value.void
      )
      seen2 <- rendered.get
      _ = assertEquals(seen2.toList, List("loudError:negative:-7", "quietError:negative:-8"))
    } yield ()).runOrFail
  }

  test("the functorK path resolves Err from the method's own using clause too") {
    (for {
      recorded <- Ref.of[WidgetLazily, Vector[String]](Vector.empty)
      mapped = riskyFunctorK.mapK(widgets[WidgetLazily])(recordingArrow(recorded))
      r1 <- EitherT.liftF[SyncIO, WidgetError, WidgetResult[String]](
        mapped.risky(3)(using loudError, raiseWidgetLazily).value
      )
      _ = assertEquals(r1, Right("ok:3"): WidgetResult[String])
      seen1 <- recorded.get
      _ = assertEquals(seen1.toList, Nil)
      _ <- EitherT.liftF[SyncIO, WidgetError, Unit](
        mapped.risky(-3)(using loudError, raiseWidgetLazily).value.void
      )
      seen2 <- recorded.get
      _ = assertEquals(seen2.toList, List("loudError:negative:-3"))
    } yield ()).runOrFail
  }

  test("all three instance kinds resolve on one algebra") {
    (for {
      recorder <- RecordingFk[WidgetLazily, Render, Render]
      instrumented = widgetAspect.intercept(widgets[WidgetLazily])(recorder.fk, OnRaise.noop[WidgetLazily, Render])
      _ <- instrumented.show(Widget(4))(using loud)
      w1 <- recorder.weaves
      _ = assertEquals(WeaveRenderer.render(w1.last.weave).domain, List(List("w" -> "loud:4")))
      _ <- instrumented.make(5)(using quiet)
      w2 <- recorder.weaves
      made = w2.last.weave
      madeTarget <- EitherT.liftF[SyncIO, WidgetError, WidgetResult[String]](
        made.codomain.target.map(made.codomain.instance.render).value
      )
      _ = assertEquals(madeTarget, Right("quiet:5"): WidgetResult[String])
      recorded <- Ref.of[WidgetLazily, Vector[String]](Vector.empty)
      mapped = widgetAspect.mapK(widgets[WidgetLazily])(recordingArrow(recorded))
      _ <- EitherT.liftF[SyncIO, WidgetError, Unit](
        mapped.risky(-6)(using loudError, raiseWidgetLazily).value.void
      )
      seen <- recorded.get
      _ = assertEquals(seen.toList, List("loudError:negative:-6"))
    } yield ()).runOrFail
  }

  test("a polymorphic method resolves both Dom and Cod from its own given parameter") {
    (for {
      recorder <- RecordingFk[WidgetLazily, Render, Render]
      instrumented = polyAspect.intercept(poly[WidgetLazily])(recorder.fk, OnRaise.noop[WidgetLazily, Render])
      _ <- instrumented.poly(Widget(7))(using loud)
      w1 <- recorder.weaves
      _ = assertEquals(WeaveRenderer.render(w1.last.weave).domain, List(List("a" -> "loud:7")))
      _ <- instrumented.poly(Widget(8))(using quiet)
      w2 <- recorder.weaves
      out = w2.last.weave
      outTarget <- EitherT.liftF[SyncIO, WidgetError, WidgetResult[String]](
        out.codomain.target.map(out.codomain.instance.render).value
      )
      _ = assertEquals(outTarget, Right("quiet:8"): WidgetResult[String])
    } yield ()).runOrFail
  }

  test("a context-bound method resolves from its synthetic evidence parameter") {
    (for {
      recorder <- RecordingFk[WidgetLazily, Render, Render]
      instrumented = boundedAspect.intercept(bounded[WidgetLazily])(recorder.fk, OnRaise.noop[WidgetLazily, Render])
      _ <- instrumented.bounded(Widget(9))(using loud)
      w1 <- recorder.weaves
      _ = assertEquals(WeaveRenderer.render(w1.last.weave).domain, List(List("a" -> "loud:9")))
      _ <- instrumented.bounded(Widget(9))(using quiet)
      w2 <- recorder.weaves
      _ = assertEquals(WeaveRenderer.render(w2.last.weave).domain, List(List("a" -> "quiet:9")))
    } yield ()).runOrFail
  }

  test("a subtype, an alias, and one conforming instance among several all resolve") {
    val widgetRender: WidgetRender = (w: Widget) => s"widgetRender:${w.id}"

    (for {
      recorder <- RecordingFk[WidgetLazily, Render, Render]
      instrumented = variationsAspect.intercept(variations[WidgetLazily])(recorder.fk, OnRaise.noop[WidgetLazily, Render])
      _ <- instrumented.sub(Widget(10))(using widgetRender)
      w1 <- recorder.weaves
      _ = assertEquals(WeaveRenderer.render(w1.last.weave).domain, List(List("w" -> "widgetRender:10")))
      _ <- instrumented.aliased(Widget(11))(using loud)
      w2 <- recorder.weaves
      _ = assertEquals(WeaveRenderer.render(w2.last.weave).domain, List(List("w" -> "loud:11")))
      _ <- instrumented.several(Widget(12))(using quietError, quiet)
      w3 <- recorder.weaves
      _ = assertEquals(WeaveRenderer.render(w3.last.weave).domain, List(List("w" -> "quiet:12")))
    } yield ()).runOrFail
  }

  test("a conforming given in a non-final using clause resolves") {
    (for {
      recorder <- RecordingFk[WidgetLazily, Render, Render]
      instrumented = multiUsingAspect.intercept(multiUsing[WidgetLazily])(recorder.fk, OnRaise.noop[WidgetLazily, Render])
      _ <- instrumented.multi(Widget(13))(using loud)(using Render[Int])
      w1 <- recorder.weaves
      _ = assertEquals(WeaveRenderer.render(w1.last.weave).domain, List(List("w" -> "loud:13")))
      _ <- instrumented.multi(Widget(13))(using quiet)(using Render[Int])
      w2 <- recorder.weaves
      _ = assertEquals(WeaveRenderer.render(w2.last.weave).domain, List(List("w" -> "quiet:13")))
    } yield ()).runOrFail
  }

  test("a wider contravariant instance stands in for the narrower one the derivation needs") {
    (for {
      recorder <- RecordingFk[WidgetLazily, Contra, Render]
      instrumented = contraAspect.intercept(contra[WidgetLazily])(recorder.fk, OnRaise.noop[WidgetLazily, Render])
      _ <- instrumented.sub(SubThing("x"))(using loudThing)
      w1 <- recorder.weaves
      _ = assertEquals(WeaveRenderer.render(w1.last.weave).domain, List(List("s" -> "loudThing:x")))
      _ <- instrumented.sub(SubThing("x"))(using quietThing)
      w2 <- recorder.weaves
      _ = assertEquals(WeaveRenderer.render(w2.last.weave).domain, List(List("s" -> "quietThing:x")))
    } yield ()).runOrFail
  }

  test("resolution is derivation-site first: a method-local instance does not override one in scope") {
    val shouty: Render[Int] = (i: Int) => s"shouty:$i"

    (for {
      recorder <- RecordingFk[WidgetLazily, Render, Render]
      instrumented = precedenceAspect.intercept(precedence[WidgetLazily])(recorder.fk, OnRaise.noop[WidgetLazily, Render])
      // `Render.renderInt` renders "7"; the method-local `shouty` would render
      // "shouty:7". The fallback only fires when derivation-site search fails.
      _ <- instrumented.pick(7)(using shouty)
      w1 <- recorder.weaves
      _ = assertEquals(WeaveRenderer.render(w1.last.weave).domain, List(List("i" -> "7")))
    } yield ()).runOrFail
  }

  // --- rejections ----------------------------------------------------------

  test("deriving an instance from a method-local one is out of scope and says so") {
    val errors: String = compileErrors(
      """given renderList[A](using R: Render[A]): Render[List[A]] =
  (as: List[A]) => as.map(R.render).mkString(",")
DeriveRaise.aspect[WidgetListAlg, Render, Render, Render]"""
    )
    assert(errors.contains("Not found: given"), errors)
    assert(errors.contains("for parameter ws"), errors)
  }

  test("the same derivation succeeds once the element instance is at the derivation site") {
    // The control for the test above: it proves `renderList` really is in scope
    // there, so the rejection is about `Render[Widget]` being method-local and
    // nothing else.
    assertNoDiff(
      compileErrors(
        """given renderList[A](using R: Render[A]): Render[List[A]] =
  (as: List[A]) => as.map(R.render).mkString(",")
given renderWidget: Render[Widget] = (w: Widget) => "widget:" + w.id
DeriveRaise.aspect[WidgetListAlg, Render, Render, Render]"""
      ),
      ""
    )
  }

  test("an invariant type class does not accept a supertype's instance") {
    val errors: String = compileErrors("DeriveRaise.aspect[WidgetInvariantAlg, Render, Render, Render]")
    assert(errors.contains("Not found: given"), errors)
    assert(errors.contains("for parameter s"), errors)
  }

  test("an instance reachable only through a parameter is not found") {
    val errors: String = compileErrors("DeriveRaise.aspect[WidgetBoxAlg, Render, Render, Render]")
    assert(errors.contains("Not found: given"), errors)
    assert(errors.contains("for parameter w"), errors)
  }

  test("a conforming but non-given parameter is named in the diagnostic") {
    val errors: String = compileErrors("DeriveRaise.aspect[WidgetUnmarkedAlg, Render, Render, Render]")
    assert(errors.contains("Not found: given"), errors)
    assert(errors.contains("for parameter w"), errors)
    assert(errors.contains("Parameter R of method unmarked would conform"), errors)
    assert(errors.contains("only the method's using/implicit parameters are considered"), errors)
  }

  test("two conforming given parameters are an ambiguity, not an arbitrary pick") {
    val errors: String = compileErrors("DeriveRaise.aspect[WidgetAmbiguousAlg, Render, Render, Render]")
    assert(errors.contains("ambiguous method-local givens"), errors)
    assert(errors.contains("for parameter w"), errors)
    assert(errors.contains("R, S"), errors)
  }
