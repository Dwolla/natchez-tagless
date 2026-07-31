package com.dwolla.tagless.mtl
package laws

import cats.Functor
import cats.arrow.FunctionK
import cats.mtl.Raise
import cats.tagless.aop.Aspect
import munit.FunSuite

import scala.annotation.experimental
import scala.collection.mutable.ListBuffer

/** M7 — instances the derivation cannot see, because the method supplies them
  * itself.
  *
  * `Widget`, `WidgetError`, `Thing` and `SubThing` deliberately have no `Render`
  * instance anywhere: every instance these algebras use arrives through a
  * method's own `using` clause. Before M7 that was fatal, because both axes
  * summoned `Dom`/`Cod`/`Err` at the derivation site, where those parameters do
  * not exist.
  *
  * Every success case here asserts at ''runtime'' that the woven advice carries
  * the instance the call supplied — the same method is called twice with two
  * different instances and must render differently both times. Compiling is not
  * enough: an implementation that binds some other conforming instance would
  * still compile. That is not hypothetical; the `summonInline` technique this
  * milestone rejected did exactly that.
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

/** All three method-local instance kinds on one algebra, matching the overview
  * appendix's motivating example. This is the shape the milestone's acceptance
  * criterion targets.
  */
trait WidgetAlg[F[_]] extends WidgetShowAlg[F], WidgetMakeAlg[F], WidgetRiskyAlg[F]

/** Scope M7 did not promise. `Render[A]` can ''never'' resolve at the derivation
  * site, because `A` is abstract there; only the method's own clause has it.
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

  val widgets: WidgetAlg[WidgetResult] = new WidgetAlg[WidgetResult]:
    def show(w: Widget)(using R: Render[Widget]): WidgetResult[String] = Right(R.render(w))
    def make(i: Int)(using R: Render[Widget]): WidgetResult[Widget] = Right(Widget(i))
    def risky(i: Int)(using RE: Render[WidgetError], R: Raise[WidgetResult, WidgetError]): WidgetResult[String] =
      if i < 0 then R.raise(WidgetError(s"negative:$i")) else Right(s"ok:$i")

  val poly: WidgetPolyAlg[WidgetResult] = new WidgetPolyAlg[WidgetResult]:
    def poly[A](a: A)(using R: Render[A]): WidgetResult[A] = Right(a)

  val bounded: WidgetBoundedAlg[WidgetResult] = new WidgetBoundedAlg[WidgetResult]:
    def bounded[A: Render](a: A): WidgetResult[A] = Right(a)

  val variations: WidgetVariationsAlg[WidgetResult] = new WidgetVariationsAlg[WidgetResult]:
    def sub(w: Widget)(using R: WidgetRender): WidgetResult[String] = Right(R.render(w))
    def aliased(w: Widget)(using R: AliasedRender): WidgetResult[String] = Right(R.render(w))
    def several(w: Widget)(using S: Render[WidgetError], R: Render[Widget]): WidgetResult[String] =
      Right(R.render(w))

  val multiUsing: WidgetMultiUsingAlg[WidgetResult] = new WidgetMultiUsingAlg[WidgetResult]:
    def multi(w: Widget)(using R: Render[Widget])(using i: Render[Int]): WidgetResult[String] =
      Right(R.render(w))

  val contra: ContraAlg[WidgetResult] = new ContraAlg[WidgetResult]:
    def sub(s: SubThing)(using C: Contra[Thing]): WidgetResult[String] = Right(C.describe(s))

  val precedence: PrecedenceAlg[WidgetResult] = new PrecedenceAlg[WidgetResult]:
    def pick(i: Int)(using R: Render[Int]): WidgetResult[String] = Right(R.render(i))

  /** An arrow whose `pull` renders every raised error through the `Err` evidence
    * the ''derivation'' handed it. That evidence is the only observable trace of
    * which `Err[E]` the macro resolved, since `WeaveArrows.raisePull` ignores it.
    */
  def recordingArrow(recorded: ListBuffer[String]): RaiseArrow[WidgetResult, WidgetResult, Render] =
    RaiseArrow(
      FunctionK.id[WidgetResult],
      new RaisePull[WidgetResult, WidgetResult, Render]:
        def apply[E](rg: Raise[WidgetResult, E])(implicit ev: Render[E]): Raise[WidgetResult, E] =
          new Raise[WidgetResult, E]:
            val functor: Functor[WidgetResult] = rg.functor
            def raise[E2 <: E, A](e: E2): WidgetResult[A] =
              recorded += ev.render(e)
              rg.raise[E2, A](e)
    )

@experimental
class MethodLocalInstanceSpec extends FunSuite:
  import LawsInstances.renderableRender
  import MethodLocal.{*, given}

  private type Woven[A] = Aspect.Weave[WidgetResult, Render, Render, A]

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

  private val raiseWidget: Raise[WidgetResult, WidgetError] = Raise[WidgetResult, WidgetError]

  test("the Dom advice carries the Render the method itself was handed") {
    val woven: WidgetShowAlg[Woven] = showAspect.weave(widgets)(Functor[WidgetResult])

    val rendered = WeaveRenderer.render(woven.show(Widget(1))(using loud))
    assertEquals(rendered.algebraName, "WidgetShowAlg")
    assertEquals(rendered.methodName, "show")
    assertEquals(rendered.domain, List(List("w" -> "loud:1")))

    assertEquals(WeaveRenderer.render(woven.show(Widget(1))(using quiet)).domain, List(List("w" -> "quiet:1")))
  }

  test("the Cod advice carries the Render the method itself was handed") {
    val woven: WidgetMakeAlg[Woven] = makeAspect.weave(widgets)(Functor[WidgetResult])

    val loudly = woven.make(2)(using loud)
    assertEquals(loudly.codomain.name, "make")
    assertEquals(
      loudly.codomain.target.map(loudly.codomain.instance.render),
      Right("loud:2"): WidgetResult[String]
    )

    val quietly = woven.make(2)(using quiet)
    assertEquals(
      quietly.codomain.target.map(quietly.codomain.instance.render),
      Right("quiet:2"): WidgetResult[String]
    )
  }

  test("the Err evidence transported with the capability is the one the method was handed") {
    val recorded = ListBuffer.empty[String]
    val mapped = riskyAspect.mapK(widgets)(recordingArrow(recorded))

    assertEquals(
      mapped.risky(-1)(using loudError, raiseWidget),
      Left(WidgetError("negative:-1")): WidgetResult[String]
    )
    assertEquals(
      mapped.risky(-2)(using quietError, raiseWidget),
      Left(WidgetError("negative:-2")): WidgetResult[String]
    )
    assertEquals(recorded.toList, List("loudError:negative:-1", "quietError:negative:-2"))
  }

  test("the functorK path resolves Err from the method's own using clause too") {
    val recorded = ListBuffer.empty[String]
    val mapped = riskyFunctorK.mapK(widgets)(recordingArrow(recorded))

    assertEquals(mapped.risky(3)(using loudError, raiseWidget), Right("ok:3"): WidgetResult[String])
    assertEquals(recorded.toList, Nil)

    mapped.risky(-3)(using loudError, raiseWidget)
    assertEquals(recorded.toList, List("loudError:negative:-3"))
  }

  test("all three instance kinds resolve on one algebra") {
    val woven: WidgetAlg[Woven] = widgetAspect.weave(widgets)(Functor[WidgetResult])

    assertEquals(WeaveRenderer.render(woven.show(Widget(4))(using loud)).domain, List(List("w" -> "loud:4")))

    val made = woven.make(5)(using quiet)
    assertEquals(made.codomain.target.map(made.codomain.instance.render), Right("quiet:5"): WidgetResult[String])

    val recorded = ListBuffer.empty[String]
    val mapped = widgetAspect.mapK(widgets)(recordingArrow(recorded))
    mapped.risky(-6)(using loudError, raiseWidget)
    assertEquals(recorded.toList, List("loudError:negative:-6"))
  }

  test("a polymorphic method resolves both Dom and Cod from its own given parameter") {
    val woven: WidgetPolyAlg[Woven] = polyAspect.weave(poly)(Functor[WidgetResult])

    assertEquals(WeaveRenderer.render(woven.poly(Widget(7))(using loud)).domain, List(List("a" -> "loud:7")))

    val out = woven.poly(Widget(8))(using quiet)
    assertEquals(out.codomain.target.map(out.codomain.instance.render), Right("quiet:8"): WidgetResult[String])
  }

  test("a context-bound method resolves from its synthetic evidence parameter") {
    val woven: WidgetBoundedAlg[Woven] = boundedAspect.weave(bounded)(Functor[WidgetResult])

    assertEquals(WeaveRenderer.render(woven.bounded(Widget(9))(using loud)).domain, List(List("a" -> "loud:9")))
    assertEquals(WeaveRenderer.render(woven.bounded(Widget(9))(using quiet)).domain, List(List("a" -> "quiet:9")))
  }

  test("a subtype, an alias, and one conforming instance among several all resolve") {
    val woven: WidgetVariationsAlg[Woven] = variationsAspect.weave(variations)(Functor[WidgetResult])

    val widgetRender: WidgetRender = (w: Widget) => s"widgetRender:${w.id}"
    assertEquals(
      WeaveRenderer.render(woven.sub(Widget(10))(using widgetRender)).domain,
      List(List("w" -> "widgetRender:10"))
    )
    assertEquals(WeaveRenderer.render(woven.aliased(Widget(11))(using loud)).domain, List(List("w" -> "loud:11")))
    assertEquals(
      WeaveRenderer.render(woven.several(Widget(12))(using quietError, quiet)).domain,
      List(List("w" -> "quiet:12"))
    )
  }

  test("a conforming given in a non-final using clause resolves") {
    val woven: WidgetMultiUsingAlg[Woven] = multiUsingAspect.weave(multiUsing)(Functor[WidgetResult])

    assertEquals(
      WeaveRenderer.render(woven.multi(Widget(13))(using loud)(using Render[Int])).domain,
      List(List("w" -> "loud:13"))
    )
    assertEquals(
      WeaveRenderer.render(woven.multi(Widget(13))(using quiet)(using Render[Int])).domain,
      List(List("w" -> "quiet:13"))
    )
  }

  test("a wider contravariant instance stands in for the narrower one the derivation needs") {
    val woven: ContraAlg[Aspect.Weave[WidgetResult, Contra, Render, *]] =
      contraAspect.weave(contra)(Functor[WidgetResult])

    assertEquals(
      WeaveRenderer.render(woven.sub(SubThing("x"))(using loudThing)).domain,
      List(List("s" -> "loudThing:x"))
    )
    assertEquals(
      WeaveRenderer.render(woven.sub(SubThing("x"))(using quietThing)).domain,
      List(List("s" -> "quietThing:x"))
    )
  }

  test("resolution is derivation-site first: a method-local instance does not override one in scope") {
    val woven: PrecedenceAlg[Woven] = precedenceAspect.weave(precedence)(Functor[WidgetResult])
    val shouty: Render[Int] = (i: Int) => s"shouty:$i"

    // `Render.renderInt` renders "7"; the method-local `shouty` would render
    // "shouty:7". The fallback only fires when derivation-site search fails.
    assertEquals(WeaveRenderer.render(woven.pick(7)(using shouty)).domain, List(List("i" -> "7")))
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
