package com.dwolla.tagless.mtl
package laws

import cats.Functor
import cats.arrow.FunctionK
import cats.mtl.Raise
import munit.FunSuite

import scala.collection.mutable.ListBuffer

/** M7 — instances the derivation cannot see, because the method supplies them
  * itself.
  *
  * `Widget`, `WidgetError`, `Thing` and `SubThing` deliberately have no `Render`
  * instance anywhere: every instance these algebras use arrives through a
  * method's own implicit clause. Before M7 that was fatal, because both axes
  * summoned `Dom`/`Cod`/`Err` at the derivation site, where those parameters do
  * not exist.
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

/** All three method-local instance kinds on one algebra, matching the overview
  * appendix's motivating example. This is the shape the milestone's acceptance
  * criterion targets.
  */
trait WidgetAlg[F[_]] extends WidgetShowAlg[F] with WidgetMakeAlg[F] with WidgetRiskyAlg[F]

/** Scope M7 did not promise. `Render[A]` can ''never'' resolve at the derivation
  * site, because `A` is abstract there; only the method's own clause has it.
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

  val widgets: WidgetAlg[WidgetResult] = new WidgetAlg[WidgetResult] {
    def show(w: Widget)(implicit R: Render[Widget]): WidgetResult[String] = Right(R.render(w))
    def make(i: Int)(implicit R: Render[Widget]): WidgetResult[Widget] = Right(Widget(i))
    def risky(i: Int)(implicit RE: Render[WidgetError], R: Raise[WidgetResult, WidgetError]): WidgetResult[String] =
      if (i < 0) R.raise(WidgetError(s"negative:$i")) else Right(s"ok:$i")
  }

  val poly: WidgetPolyAlg[WidgetResult] = new WidgetPolyAlg[WidgetResult] {
    def poly[A](a: A)(implicit R: Render[A]): WidgetResult[A] = Right(a)
  }

  val bounded: WidgetBoundedAlg[WidgetResult] = new WidgetBoundedAlg[WidgetResult] {
    def bounded[A: Render](a: A): WidgetResult[A] = Right(a)
  }

  val variations: WidgetVariationsAlg[WidgetResult] = new WidgetVariationsAlg[WidgetResult] {
    def sub(w: Widget)(implicit R: WidgetRender): WidgetResult[String] = Right(R.render(w))
    def aliased(w: Widget)(implicit R: AliasedRender): WidgetResult[String] = Right(R.render(w))
    def several(w: Widget)(implicit S: Render[WidgetError], R: Render[Widget]): WidgetResult[String] =
      Right(R.render(w))
  }

  val contra: ContraAlg[WidgetResult] = new ContraAlg[WidgetResult] {
    def sub(s: SubThing)(implicit C: Contra[Thing]): WidgetResult[String] = Right(C.describe(s))
  }

  val precedence: PrecedenceAlg[WidgetResult] = new PrecedenceAlg[WidgetResult] {
    def pick(i: Int)(implicit R: Render[Int]): WidgetResult[String] = Right(R.render(i))
  }

  /** An arrow whose `pull` renders every raised error through the `Err` evidence
    * the ''derivation'' handed it. That evidence is the only observable trace of
    * which `Err[E]` the macro resolved, since `RaisePull.id` ignores it.
    */
  def recordingArrow(recorded: ListBuffer[String]): RaiseArrow[WidgetResult, WidgetResult, Render] =
    RaiseArrow(
      FunctionK.id[WidgetResult],
      new RaisePull[WidgetResult, WidgetResult, Render] {
        def apply[E](rg: Raise[WidgetResult, E])(implicit ev: Render[E]): Raise[WidgetResult, E] =
          new Raise[WidgetResult, E] {
            val functor: Functor[WidgetResult] = rg.functor
            def raise[E2 <: E, A](e: E2): WidgetResult[A] = {
              recorded += ev.render(e)
              rg.raise[E2, A](e)
            }
          }
      }
    )
}

class MethodLocalInstanceSpec extends FunSuite {
  import LawsInstances.renderableRender
  import MethodLocal._

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

  private val raiseWidget: Raise[WidgetResult, WidgetError] = Raise[WidgetResult, WidgetError]

  test("the Dom advice carries the Render the method itself was handed") {
    val recorder = new RecordingFk[WidgetResult, Render, Render]
    val instrumented = showAspect.intercept(widgets)(recorder.fk, OnRaise.noop[WidgetResult, Render])

    instrumented.show(Widget(1))(loud)
    val rendered = WeaveRenderer.render(recorder.weaves.last.weave)
    assertEquals(rendered.algebraName, "WidgetShowAlg")
    assertEquals(rendered.methodName, "show")
    assertEquals(rendered.domain, List(List("w" -> "loud:1")))

    instrumented.show(Widget(1))(quiet)
    assertEquals(WeaveRenderer.render(recorder.weaves.last.weave).domain, List(List("w" -> "quiet:1")))
  }

  test("the Cod advice carries the Render the method itself was handed") {
    val recorder = new RecordingFk[WidgetResult, Render, Render]
    val instrumented = makeAspect.intercept(widgets)(recorder.fk, OnRaise.noop[WidgetResult, Render])

    instrumented.make(2)(loud)
    val loudly = recorder.weaves.last.weave
    assertEquals(loudly.codomain.name, "make")
    assertEquals(
      loudly.codomain.target.map(loudly.codomain.instance.render),
      Right("loud:2"): WidgetResult[String]
    )

    instrumented.make(2)(quiet)
    val quietly = recorder.weaves.last.weave
    assertEquals(
      quietly.codomain.target.map(quietly.codomain.instance.render),
      Right("quiet:2"): WidgetResult[String]
    )
  }

  test("the Err evidence transported with the capability is the one the method was handed") {
    val recorded = ListBuffer.empty[String]
    val mapped = riskyAspect.mapK(widgets)(recordingArrow(recorded))

    assertEquals(mapped.risky(-1)(loudError, raiseWidget), Left(WidgetError("negative:-1")): WidgetResult[String])
    assertEquals(mapped.risky(-2)(quietError, raiseWidget), Left(WidgetError("negative:-2")): WidgetResult[String])
    assertEquals(recorded.toList, List("loudError:negative:-1", "quietError:negative:-2"))
  }

  test("the intercept hook renders a raise through the method-local Err instance") {
    // Before M12 the hook could only be observed indirectly, through `mapK`
    // and a hand-rolled recording `RaisePull` (the test above): the pre-fusion
    // `weave` had no `onRaise` parameter at all. `intercept` wires the hook in
    // directly — `RaiseAspect.observing($pn, $onRaise)($applyF, $errInstance)`
    // — so this asserts the stronger claim: the *method-local* `Err[WidgetError]`
    // the call was handed is exactly what reaches the hook, not a derivation-site
    // instance and not `toString`.
    val rendered = ListBuffer.empty[String]
    val hook: OnRaise[WidgetResult, Render] = new OnRaise[WidgetResult, Render] {
      def apply[E](e: E)(implicit ev: Render[E]): WidgetResult[Unit] = {
        rendered += ev.render(e)
        Right(())
      }
    }
    val recorder = new RecordingFk[WidgetResult, Render, Render]
    val instrumented = riskyAspect.intercept(widgets)(recorder.fk, hook)

    instrumented.risky(-7)(loudError, raiseWidget)
    assertEquals(rendered.toList, List("loudError:negative:-7"))

    instrumented.risky(-8)(quietError, raiseWidget)
    assertEquals(rendered.toList, List("loudError:negative:-7", "quietError:negative:-8"))
  }

  test("the functorK path resolves Err from the method's own implicit clause too") {
    val recorded = ListBuffer.empty[String]
    val mapped = riskyFunctorK.mapK(widgets)(recordingArrow(recorded))

    assertEquals(mapped.risky(3)(loudError, raiseWidget), Right("ok:3"): WidgetResult[String])
    assertEquals(recorded.toList, Nil)

    mapped.risky(-3)(loudError, raiseWidget)
    assertEquals(recorded.toList, List("loudError:negative:-3"))
  }

  test("all three instance kinds resolve on one algebra") {
    val recorder = new RecordingFk[WidgetResult, Render, Render]
    val instrumented = widgetAspect.intercept(widgets)(recorder.fk, OnRaise.noop[WidgetResult, Render])

    instrumented.show(Widget(4))(loud)
    assertEquals(WeaveRenderer.render(recorder.weaves.last.weave).domain, List(List("w" -> "loud:4")))

    instrumented.make(5)(quiet)
    val made = recorder.weaves.last.weave
    assertEquals(made.codomain.target.map(made.codomain.instance.render), Right("quiet:5"): WidgetResult[String])

    val recorded = ListBuffer.empty[String]
    val mapped = widgetAspect.mapK(widgets)(recordingArrow(recorded))
    mapped.risky(-6)(loudError, raiseWidget)
    assertEquals(recorded.toList, List("loudError:negative:-6"))
  }

  test("a polymorphic method resolves both Dom and Cod from its own implicit parameter") {
    val recorder = new RecordingFk[WidgetResult, Render, Render]
    val instrumented = polyAspect.intercept(poly)(recorder.fk, OnRaise.noop[WidgetResult, Render])

    instrumented.poly(Widget(7))(loud)
    assertEquals(WeaveRenderer.render(recorder.weaves.last.weave).domain, List(List("a" -> "loud:7")))

    instrumented.poly(Widget(8))(quiet)
    val out = recorder.weaves.last.weave
    assertEquals(out.codomain.target.map(out.codomain.instance.render), Right("quiet:8"): WidgetResult[String])
  }

  test("a context-bound method resolves from its synthetic evidence parameter") {
    val recorder = new RecordingFk[WidgetResult, Render, Render]
    val instrumented = boundedAspect.intercept(bounded)(recorder.fk, OnRaise.noop[WidgetResult, Render])

    instrumented.bounded(Widget(9))(loud)
    assertEquals(WeaveRenderer.render(recorder.weaves.last.weave).domain, List(List("a" -> "loud:9")))

    instrumented.bounded(Widget(9))(quiet)
    assertEquals(WeaveRenderer.render(recorder.weaves.last.weave).domain, List(List("a" -> "quiet:9")))
  }

  test("a subtype, an alias, and one conforming instance among several all resolve") {
    val recorder = new RecordingFk[WidgetResult, Render, Render]
    val instrumented = variationsAspect.intercept(variations)(recorder.fk, OnRaise.noop[WidgetResult, Render])

    val widgetRender: WidgetRender = (w: Widget) => s"widgetRender:${w.id}"
    instrumented.sub(Widget(10))(widgetRender)
    assertEquals(WeaveRenderer.render(recorder.weaves.last.weave).domain, List(List("w" -> "widgetRender:10")))

    instrumented.aliased(Widget(11))(loud)
    assertEquals(WeaveRenderer.render(recorder.weaves.last.weave).domain, List(List("w" -> "loud:11")))

    instrumented.several(Widget(12))(quietError, quiet)
    assertEquals(WeaveRenderer.render(recorder.weaves.last.weave).domain, List(List("w" -> "quiet:12")))
  }

  test("a wider contravariant instance stands in for the narrower one the derivation needs") {
    val recorder = new RecordingFk[WidgetResult, Contra, Render]
    val instrumented = contraAspect.intercept(contra)(recorder.fk, OnRaise.noop[WidgetResult, Render])

    instrumented.sub(new SubThing("x"))(loudThing)
    assertEquals(WeaveRenderer.render(recorder.weaves.last.weave).domain, List(List("s" -> "loudThing:x")))

    instrumented.sub(new SubThing("x"))(quietThing)
    assertEquals(WeaveRenderer.render(recorder.weaves.last.weave).domain, List(List("s" -> "quietThing:x")))
  }

  test("resolution is derivation-site first: a method-local instance does not override one in scope") {
    val recorder = new RecordingFk[WidgetResult, Render, Render]
    val instrumented = precedenceAspect.intercept(precedence)(recorder.fk, OnRaise.noop[WidgetResult, Render])
    val shouty: Render[Int] = (i: Int) => s"shouty:$i"

    // `Render.renderInt` renders "7"; the method-local `shouty` would render
    // "shouty:7". The fallback only fires when derivation-site search fails.
    instrumented.pick(7)(shouty)
    assertEquals(WeaveRenderer.render(recorder.weaves.last.weave).domain, List(List("i" -> "7")))
  }

  // --- rejections ----------------------------------------------------------

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
