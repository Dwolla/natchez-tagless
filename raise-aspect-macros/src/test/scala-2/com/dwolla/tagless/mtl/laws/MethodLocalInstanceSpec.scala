package com.dwolla.tagless.mtl
package laws

import cats.arrow.FunctionK
import cats.effect.{Ref, SyncIO}
import cats.mtl.syntax.all.*
import cats.mtl.Raise
import cats.syntax.all.*
import cats.{Applicative, Functor}
import com.dwolla.tagless.mtl.HandleTestSyntax
import munit.CatsEffectSuite

/** Instances the derivation cannot see, because the method supplies them
  * itself.
  *
  * `Widget`, `WidgetError`, `Thing` and `SubThing` deliberately have no `Render`
  * instance anywhere: every instance these algebras use arrives through a
  * method's own implicit clause, never through `Dom`/`Cod`/`Err` summoned at
  * the derivation site, where these parameters don't exist for these
  * algebras.
  *
  * Every success case here asserts at ''runtime'' that the woven advice carries
  * the instance the call supplied — the same method is called twice with two
  * different instances and must render differently both times. Compiling is not
  * enough: an implementation that binds some other conforming instance would
  * still compile.
  */
final case class Widget(id: Int)
final case class WidgetError(reason: String)

/** Method-local `Dom`: `Render[Widget]` is supplied only by `show`'s own
  * implicit clause, not at the derivation site.
  */
trait WidgetShowAlg[F[_]] {
  def show(w: Widget)(implicit R: Render[Widget]): F[String]
}

/** Method-local `Cod`: same story, but for the codomain advice on `make`'s
  * `F[Widget]` result.
  */
trait WidgetMakeAlg[F[_]] {
  def make(i: Int)(implicit R: Render[Widget]): F[Widget]
}

/** Method-local `Err`: `Render[WidgetError]` is supplied only by `risky`'s own
  * implicit clause, alongside the `Raise[F, WidgetError]` capability.
  */
trait WidgetRiskyAlg[F[_]] {
  def risky(i: Int)(implicit RE: Render[WidgetError], R: Raise[F, WidgetError]): F[String]
}

/** All three method-local instance kinds on one algebra. */
trait WidgetAlg[F[_]] extends WidgetShowAlg[F] with WidgetMakeAlg[F] with WidgetRiskyAlg[F]

/** `Render[A]` can ''never'' resolve at the derivation site, because `A` is
  * abstract there; only the method's own clause has it.
  */
trait WidgetPolyAlg[F[_]] {
  def poly[A](a: A)(implicit R: Render[A]): F[A]
}

/** The same, spelled as a context bound — the implicit parameter is synthetic
  * and named by the compiler, so this proves the fallback does not depend on the
  * parameter having a user-written name.
  */
trait WidgetBoundedAlg[F[_]] {
  def bounded[A: Render](a: A): F[A]
}

/** A conforming candidate is not always the needed type spelled exactly:
  * `WidgetRender` is a strict subtype, `AliasedRender` is the same type behind an
  * alias, and `several` hands over two instances of which only one conforms.
  */
trait WidgetRender extends Render[Widget]

trait WidgetVariationsAlg[F[_]] {
  def sub(w: Widget)(implicit R: WidgetRender): F[String]
  def aliased(w: Widget)(implicit R: MethodLocal.AliasedRender): F[String]
  def several(w: Widget)(implicit S: Render[WidgetError], R: Render[Widget]): F[String]
}

/** Contravariant widening — why the conformance test is `<:<` and not `=:=`.
  * `sub` is handed a `Contra[Thing]` and the derivation needs `Contra[SubThing]`;
  * a describer of every `Thing` can describe a `SubThing`, so accepting it is
  * sound as well as convenient.
  */
trait Contra[-A] extends Serializable {
  def describe(a: A): String
}

class Thing(val label: String)
class SubThing(label: String) extends Thing(label)

trait ContraAlg[F[_]] {
  def sub(s: SubThing)(implicit C: Contra[Thing]): F[String]
}

// --- shapes that must still be rejected ------------------------------------

/** Deriving `Render[List[Widget]]` from a method-local `Render[Widget]` is out
  * of scope: the fallback references what the method is handed, it does not run
  * implicit search.
  */
trait WidgetListAlg[F[_]] {
  def listy(ws: List[Widget])(implicit R: Render[Widget]): F[String]
}

/** `Render` is invariant, so a `Render[Thing]` is not a `Render[SubThing]`. */
trait WidgetInvariantAlg[F[_]] {
  def inv(s: SubThing)(implicit R: Render[Thing]): F[String]
}

/** The instance is reachable only by projecting out of the parameter. */
final case class Box[A](unbox: Render[A])

trait WidgetBoxAlg[F[_]] {
  def boxed(w: Widget)(implicit B: Box[Widget]): F[String]
}

/** A conforming instance handed as an ordinary parameter — a very plausible
  * mistake, and the one the diagnostic has to point at.
  */
trait WidgetUnmarkedAlg[F[_]] {
  def unmarked(w: Widget, R: Render[Widget]): F[String]
}

/** Two conforming implicit parameters. Silently taking the first would be a
  * wrong-instance bug waiting to happen.
  */
trait WidgetAmbiguousAlg[F[_]] {
  def ambiguous(w: Widget)(implicit R: Render[Widget], S: Render[Widget]): F[String]
}

/** Both sources are available for `Render[Int]`: `Render.renderInt` at the
  * derivation site and the method's own implicit parameter. The hybrid is
  * derivation-site-first, so the fallback must not fire.
  */
trait PrecedenceAlg[F[_]] {
  def pick(i: Int)(implicit R: Render[Int]): F[String]
}

object MethodLocal {
  type WidgetResult[A] = Either[WidgetError, A]
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

  implicit val renderableContra: Renderable[Contra] =
    new Renderable[Contra] {
      def render[A](instance: Contra[A])(a: A): String = instance.describe(a)
    }

  def widgets[F[_]](implicit F: Applicative[F]): WidgetAlg[F] = new WidgetAlg[F] {
    def show(w: Widget)(implicit R: Render[Widget]): F[String] = R.render(w).pure[F]
    def make(i: Int)(implicit R: Render[Widget]): F[Widget] = Widget(i).pure[F]
    def risky(i: Int)(implicit RE: Render[WidgetError], R: Raise[F, WidgetError]): F[String] =
      if (i < 0) R.raise(WidgetError(s"negative:$i")) else s"ok:$i".pure[F]
  }

  def poly[F[_]](implicit F: Applicative[F]): WidgetPolyAlg[F] = new WidgetPolyAlg[F] {
    def poly[A](a: A)(implicit R: Render[A]): F[A] = a.pure[F]
  }

  def bounded[F[_]](implicit F: Applicative[F]): WidgetBoundedAlg[F] = new WidgetBoundedAlg[F] {
    def bounded[A: Render](a: A): F[A] = a.pure[F]
  }

  def variations[F[_]](implicit F: Applicative[F]): WidgetVariationsAlg[F] = new WidgetVariationsAlg[F] {
    def sub(w: Widget)(implicit R: WidgetRender): F[String] = R.render(w).pure[F]
    def aliased(w: Widget)(implicit R: AliasedRender): F[String] = R.render(w).pure[F]
    def several(w: Widget)(implicit S: Render[WidgetError], R: Render[Widget]): F[String] =
      R.render(w).pure[F]
  }

  def contra[F[_]](implicit F: Applicative[F]): ContraAlg[F] = new ContraAlg[F] {
    def sub(s: SubThing)(implicit C: Contra[Thing]): F[String] = C.describe(s).pure[F]
  }

  def precedence[F[_]](implicit F: Applicative[F]): PrecedenceAlg[F] = new PrecedenceAlg[F] {
    def pick(i: Int)(implicit R: Render[Int]): F[String] = R.render(i).pure[F]
  }

  /** An arrow whose `pull` renders every raised error through the `Err` evidence
    * the ''derivation'' handed it. That evidence is the only observable trace of
    * which `Err[E]` the macro resolved, since `RaisePull.id` ignores it.
    */
  def recordingArrow[F[_]](recorded: Ref[F, Vector[String]])(implicit F: Applicative[F]): RaiseArrow[F, F, Render] =
    RaiseArrow(
      FunctionK.id[F],
      new RaisePull[F, F, Render] {
        def apply[E](rg: Raise[F, E])(implicit ev: Render[E]): Raise[F, E] =
          new Raise[F, E] {
            val functor: Functor[F] = rg.functor
            def raise[E2 <: E, A](e: E2): F[A] =
              recorded.update(_ :+ ev.render(e)) *> rg.raise[E2, A](e)
          }
      }
    )
}

class MethodLocalInstanceSpec extends CatsEffectSuite with HandleTestSyntax {
  import LawsInstances.renderableRender
  import MethodLocal.*

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
  private val contraAspect: RaiseAspect[ContraAlg, Contra, Render, Render] =
    DeriveRaise.aspect[ContraAlg, Contra, Render, Render]
  private val precedenceAspect: RaiseAspect[PrecedenceAlg, Render, Render, Render] =
    DeriveRaise.aspect[PrecedenceAlg, Render, Render, Render]
  private val riskyFunctorK: RaiseFunctorK[WidgetRiskyAlg, Render] =
    DeriveRaise.functorK[WidgetRiskyAlg, Render]

  test("the Dom advice carries the Render the method itself was handed") {
    for {
      recorder <- RecordingFk[SyncIO, Render, Render]
      instrumented = showAspect.intercept(widgets[SyncIO])(recorder.fk, OnRaise.noop[SyncIO, Render])
      _ <- instrumented.show(Widget(1))(loud)
      w1 <- recorder.weaves
      rendered1 = WeaveRenderer.render(w1.last.weave)
      _ = assertEquals(rendered1.algebraName, "WidgetShowAlg")
      _ = assertEquals(rendered1.methodName, "show")
      _ = assertEquals(rendered1.domain, List(List("w" -> "loud:1")))
      _ <- instrumented.show(Widget(1))(quiet)
      w2 <- recorder.weaves
    } yield {
      assertEquals(WeaveRenderer.render(w2.last.weave).domain, List(List("w" -> "quiet:1")))
    }
  }

  testWithHandle[SyncIO, WidgetError]("the Cod advice carries the Render the method itself was handed") { implicit H =>
    for {
      recorder <- RecordingFk[SyncIO, Render, Render]
      instrumented = makeAspect.intercept(widgets[SyncIO])(recorder.fk, OnRaise.noop[SyncIO, Render])
      _ <- instrumented.make(2)(loud)
      w1 <- recorder.weaves
      loudly = w1.last.weave
      _ = assertEquals(loudly.codomain.name, "make")
      loudTarget <- loudly.codomain.target.map(loudly.codomain.instance.render).attemptHandle
      _ = assertEquals(loudTarget, Right("loud:2"))
      _ <- instrumented.make(2)(quiet)
      w2 <- recorder.weaves
      quietly = w2.last.weave
      quietTarget <- quietly.codomain.target.map(quietly.codomain.instance.render).attemptHandle
      _ = assertEquals(quietTarget, Right("quiet:2"))
    } yield ()
  }

  testWithHandle[SyncIO, WidgetError]("the Err evidence transported with the capability is the one the method was handed") { implicit H =>
    for {
      recorded <- Ref.of[SyncIO, Vector[String]](Vector.empty)
      mapped = riskyAspect.mapK(widgets[SyncIO])(recordingArrow(recorded))
      r1 <- mapped.risky(-1)(loudError, H).attemptHandle
      _ = assertEquals(r1, Left(WidgetError("negative:-1")))
      r2 <- mapped.risky(-2)(quietError, H).attemptHandle
      _ = assertEquals(r2, Left(WidgetError("negative:-2")))
      seen <- recorded.get
      _ = assertEquals(seen.toList, List("loudError:negative:-1", "quietError:negative:-2"))
    } yield ()
  }

  testWithHandle[SyncIO, WidgetError]("the intercept hook renders a raise through the method-local Err instance") { implicit H =>
    // `intercept` wires the hook in directly —
    // `RaiseAspect.observing($pn, $onRaise)($applyF, $errInstance)` — so the
    // *method-local* `Err[WidgetError]` the call was handed is exactly what
    // reaches the hook: not a derivation-site instance, and not `toString`.
    for {
      rendered <- Ref.of[SyncIO, Vector[String]](Vector.empty)
      hook = new OnRaise[SyncIO, Render] {
        def apply[E](e: E)(implicit ev: Render[E]): SyncIO[Unit] = rendered.update(_ :+ ev.render(e))
      }
      recorder <- RecordingFk[SyncIO, Render, Render]
      instrumented = riskyAspect.intercept(widgets[SyncIO])(recorder.fk, hook)
      r1 <- instrumented.risky(-7)(loudError, H).attemptHandle
      _ = assertEquals(r1, Left(WidgetError("negative:-7")))
      seen1 <- rendered.get
      _ = assertEquals(seen1.toList, List("loudError:negative:-7"))
      r2 <- instrumented.risky(-8)(quietError, H).attemptHandle
      _ = assertEquals(r2, Left(WidgetError("negative:-8")))
      seen2 <- rendered.get
      _ = assertEquals(seen2.toList, List("loudError:negative:-7", "quietError:negative:-8"))
    } yield ()
  }

  testWithHandle[SyncIO, WidgetError]("the functorK path resolves Err from the method's own implicit clause too") { implicit H =>
    for {
      recorded <- Ref.of[SyncIO, Vector[String]](Vector.empty)
      mapped = riskyFunctorK.mapK(widgets[SyncIO])(recordingArrow(recorded))
      r1 <- mapped.risky(3)(loudError, H).attemptHandle
      _ = assertEquals(r1, Right("ok:3"))
      seen1 <- recorded.get
      _ = assertEquals(seen1.toList, Nil)
      _ <- mapped.risky(-3)(loudError, H).attemptHandle.void
      seen2 <- recorded.get
      _ = assertEquals(seen2.toList, List("loudError:negative:-3"))
    } yield ()
  }

  testWithHandle[SyncIO, WidgetError]("all three instance kinds resolve on one algebra") { implicit H =>
    for {
      rendered <- Ref.of[SyncIO, Vector[String]](Vector.empty)
      hook = new OnRaise[SyncIO, Render] {
        def apply[E](e: E)(implicit ev: Render[E]): SyncIO[Unit] = rendered.update(_ :+ ev.render(e))
      }
      recorder <- RecordingFk[SyncIO, Render, Render]
      instrumented = widgetAspect.intercept(widgets[SyncIO])(recorder.fk, hook)
      _ <- instrumented.show(Widget(4))(loud)
      w1 <- recorder.weaves
      _ = assertEquals(WeaveRenderer.render(w1.last.weave).domain, List(List("w" -> "loud:4")))
      _ <- instrumented.make(5)(quiet)
      w2 <- recorder.weaves
      made = w2.last.weave
      madeTarget <- made.codomain.target.map(made.codomain.instance.render).attemptHandle
      _ = assertEquals(madeTarget, Right("quiet:5"))
      risked <- instrumented.risky(-6)(loudError, H).attemptHandle
      _ = assertEquals(risked, Left(WidgetError("negative:-6")))
      seen <- rendered.get
      _ = assertEquals(seen.toList, List("loudError:negative:-6"))
    } yield ()
  }

  testWithHandle[SyncIO, WidgetError]("a polymorphic method resolves both Dom and Cod from its own implicit parameter") { implicit H =>
    for {
      recorder <- RecordingFk[SyncIO, Render, Render]
      instrumented = polyAspect.intercept(poly[SyncIO])(recorder.fk, OnRaise.noop[SyncIO, Render])
      _ <- instrumented.poly(Widget(7))(loud)
      w1 <- recorder.weaves
      _ = assertEquals(WeaveRenderer.render(w1.last.weave).domain, List(List("a" -> "loud:7")))
      _ <- instrumented.poly(Widget(8))(quiet)
      w2 <- recorder.weaves
      out = w2.last.weave
      outTarget <- out.codomain.target.map(out.codomain.instance.render).attemptHandle
      _ = assertEquals(outTarget, Right("quiet:8"))
    } yield ()
  }

  test("a context-bound method resolves from its synthetic evidence parameter") {
    for {
      recorder <- RecordingFk[SyncIO, Render, Render]
      instrumented = boundedAspect.intercept(bounded[SyncIO])(recorder.fk, OnRaise.noop[SyncIO, Render])
      _ <- instrumented.bounded(Widget(9))(loud)
      w1 <- recorder.weaves
      _ = assertEquals(WeaveRenderer.render(w1.last.weave).domain, List(List("a" -> "loud:9")))
      _ <- instrumented.bounded(Widget(9))(quiet)
      w2 <- recorder.weaves
      _ = assertEquals(WeaveRenderer.render(w2.last.weave).domain, List(List("a" -> "quiet:9")))
    } yield ()
  }

  test("a subtype, an alias, and one conforming instance among several all resolve") {
    val widgetRender: WidgetRender = (w: Widget) => s"widgetRender:${w.id}"

    for {
      recorder <- RecordingFk[SyncIO, Render, Render]
      instrumented = variationsAspect.intercept(variations[SyncIO])(recorder.fk, OnRaise.noop[SyncIO, Render])
      _ <- instrumented.sub(Widget(10))(widgetRender)
      w1 <- recorder.weaves
      _ = assertEquals(WeaveRenderer.render(w1.last.weave).domain, List(List("w" -> "widgetRender:10")))
      _ <- instrumented.aliased(Widget(11))(loud)
      w2 <- recorder.weaves
      _ = assertEquals(WeaveRenderer.render(w2.last.weave).domain, List(List("w" -> "loud:11")))
      _ <- instrumented.several(Widget(12))(quietError, quiet)
      w3 <- recorder.weaves
      _ = assertEquals(WeaveRenderer.render(w3.last.weave).domain, List(List("w" -> "quiet:12")))
    } yield ()
  }

  test("a wider contravariant instance stands in for the narrower one the derivation needs") {
    for {
      recorder <- RecordingFk[SyncIO, Contra, Render]
      instrumented = contraAspect.intercept(contra[SyncIO])(recorder.fk, OnRaise.noop[SyncIO, Render])
      _ <- instrumented.sub(new SubThing("x"))(loudThing)
      w1 <- recorder.weaves
      _ = assertEquals(WeaveRenderer.render(w1.last.weave).domain, List(List("s" -> "loudThing:x")))
      _ <- instrumented.sub(new SubThing("x"))(quietThing)
      w2 <- recorder.weaves
      _ = assertEquals(WeaveRenderer.render(w2.last.weave).domain, List(List("s" -> "quietThing:x")))
    } yield ()
  }

  test("resolution is derivation-site first: a method-local instance does not override one in scope") {
    val shouty: Render[Int] = (i: Int) => s"shouty:$i"

    for {
      recorder <- RecordingFk[SyncIO, Render, Render]
      instrumented = precedenceAspect.intercept(precedence[SyncIO])(recorder.fk, OnRaise.noop[SyncIO, Render])
      // `Render.renderInt` renders "7"; the method-local `shouty` would render
      // "shouty:7". The fallback only fires when derivation-site search fails.
      _ <- instrumented.pick(7)(shouty)
      w1 <- recorder.weaves
      _ = assertEquals(WeaveRenderer.render(w1.last.weave).domain, List(List("i" -> "7")))
    } yield ()
  }

  test("deriving an instance from a method-local one is out of scope and says so") {
    val errors = compileErrors(
      """implicit def renderList[A](implicit R: Render[A]): Render[List[A]] =
  (as: List[A]) => as.map(R.render).mkString(",")
DeriveRaise.aspect[WidgetListAlg, Render, Render, Render]"""
    )
    assert(errors.contains("Not found: implicit"), errors)
    assert(errors.contains("for parameter ws of method listy"), errors)
  }

  test("the same derivation succeeds once the element instance is at the derivation site") {
    // The control for the test above: it proves `renderList` really is in scope
    // there, so the rejection is about `Render[Widget]` being method-local and
    // nothing else.
    assertNoDiff(
      compileErrors(
        """implicit def renderList[A](implicit R: Render[A]): Render[List[A]] =
  (as: List[A]) => as.map(R.render).mkString(",")
implicit val renderWidget: Render[Widget] = (w: Widget) => "widget:" + w.id
DeriveRaise.aspect[WidgetListAlg, Render, Render, Render]"""
      ),
      ""
    )
  }

  test("an invariant type class does not accept a supertype's instance") {
    val errors = compileErrors("DeriveRaise.aspect[WidgetInvariantAlg, Render, Render, Render]")
    assert(errors.contains("Not found: implicit"), errors)
    assert(errors.contains("for parameter s of method inv"), errors)
  }

  test("an instance reachable only through a parameter is not found") {
    val errors = compileErrors("DeriveRaise.aspect[WidgetBoxAlg, Render, Render, Render]")
    assert(errors.contains("Not found: implicit"), errors)
    assert(errors.contains("for parameter w of method boxed"), errors)
  }

  test("a conforming but non-implicit parameter is named in the diagnostic") {
    val errors = compileErrors("DeriveRaise.aspect[WidgetUnmarkedAlg, Render, Render, Render]")
    assert(errors.contains("Not found: implicit"), errors)
    assert(errors.contains("for parameter w of method unmarked"), errors)
    assert(errors.contains("Parameter R of method unmarked would conform"), errors)
    assert(errors.contains("only the method's implicit parameters are considered"), errors)
  }

  test("two conforming implicit parameters are an ambiguity, not an arbitrary pick") {
    val errors = compileErrors("DeriveRaise.aspect[WidgetAmbiguousAlg, Render, Render, Render]")
    assert(errors.contains("ambiguous method-local implicits"), errors)
    assert(errors.contains("for parameter w of method ambiguous"), errors)
    assert(errors.contains("R, S"), errors)
  }
}
