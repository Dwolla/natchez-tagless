package com.dwolla.tagless.mtl
package laws

import cats.Functor
import cats.arrow.FunctionK
import cats.mtl.Raise
import cats.tagless.aop.Aspect
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

  /** An arrow whose `pull` renders every raised error through the `Err` evidence
    * the ''derivation'' handed it. That evidence is the only observable trace of
    * which `Err[E]` the macro resolved, since `WeaveArrows.raisePull` ignores it.
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
  private val contraAspect: RaiseAspect[ContraAlg, Contra, Render, Render] =
    DeriveRaise.aspect[ContraAlg, Contra, Render, Render]
  private val riskyFunctorK: RaiseFunctorK[WidgetRiskyAlg, Render] =
    DeriveRaise.functorK[WidgetRiskyAlg, Render]

  private val raiseWidget: Raise[WidgetResult, WidgetError] = Raise[WidgetResult, WidgetError]

  test("the Dom advice carries the Render the method itself was handed") {
    val woven: WidgetShowAlg[Woven] = showAspect.weave(widgets)(Functor[WidgetResult])

    val rendered = WeaveRenderer.render(woven.show(Widget(1))(loud))
    assertEquals(rendered.algebraName, "WidgetShowAlg")
    assertEquals(rendered.methodName, "show")
    assertEquals(rendered.domain, List(List("w" -> "loud:1")))

    assertEquals(WeaveRenderer.render(woven.show(Widget(1))(quiet)).domain, List(List("w" -> "quiet:1")))
  }

  test("the Cod advice carries the Render the method itself was handed") {
    val woven: WidgetMakeAlg[Woven] = makeAspect.weave(widgets)(Functor[WidgetResult])

    val loudly = woven.make(2)(loud)
    assertEquals(loudly.codomain.name, "make")
    assertEquals(
      loudly.codomain.target.map(loudly.codomain.instance.render),
      Right("loud:2"): WidgetResult[String]
    )

    val quietly = woven.make(2)(quiet)
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

  test("the functorK path resolves Err from the method's own implicit clause too") {
    val recorded = ListBuffer.empty[String]
    val mapped = riskyFunctorK.mapK(widgets)(recordingArrow(recorded))

    assertEquals(mapped.risky(3)(loudError, raiseWidget), Right("ok:3"): WidgetResult[String])
    assertEquals(recorded.toList, Nil)

    mapped.risky(-3)(loudError, raiseWidget)
    assertEquals(recorded.toList, List("loudError:negative:-3"))
  }

  test("all three instance kinds resolve on one algebra") {
    val woven: WidgetAlg[Woven] = widgetAspect.weave(widgets)(Functor[WidgetResult])

    assertEquals(WeaveRenderer.render(woven.show(Widget(4))(loud)).domain, List(List("w" -> "loud:4")))

    val made = woven.make(5)(quiet)
    assertEquals(made.codomain.target.map(made.codomain.instance.render), Right("quiet:5"): WidgetResult[String])

    val recorded = ListBuffer.empty[String]
    val mapped = widgetAspect.mapK(widgets)(recordingArrow(recorded))
    mapped.risky(-6)(loudError, raiseWidget)
    assertEquals(recorded.toList, List("loudError:negative:-6"))
  }

  test("a polymorphic method resolves both Dom and Cod from its own implicit parameter") {
    val woven: WidgetPolyAlg[Woven] = polyAspect.weave(poly)(Functor[WidgetResult])

    assertEquals(WeaveRenderer.render(woven.poly(Widget(7))(loud)).domain, List(List("a" -> "loud:7")))

    val out = woven.poly(Widget(8))(quiet)
    assertEquals(out.codomain.target.map(out.codomain.instance.render), Right("quiet:8"): WidgetResult[String])
  }

  test("a context-bound method resolves from its synthetic evidence parameter") {
    val woven: WidgetBoundedAlg[Woven] = boundedAspect.weave(bounded)(Functor[WidgetResult])

    assertEquals(WeaveRenderer.render(woven.bounded(Widget(9))(loud)).domain, List(List("a" -> "loud:9")))
    assertEquals(WeaveRenderer.render(woven.bounded(Widget(9))(quiet)).domain, List(List("a" -> "quiet:9")))
  }

  test("a subtype, an alias, and one conforming instance among several all resolve") {
    val woven: WidgetVariationsAlg[Woven] = variationsAspect.weave(variations)(Functor[WidgetResult])

    val widgetRender: WidgetRender = (w: Widget) => s"widgetRender:${w.id}"
    assertEquals(WeaveRenderer.render(woven.sub(Widget(10))(widgetRender)).domain, List(List("w" -> "widgetRender:10")))
    assertEquals(WeaveRenderer.render(woven.aliased(Widget(11))(loud)).domain, List(List("w" -> "loud:11")))
    assertEquals(
      WeaveRenderer.render(woven.several(Widget(12))(quietError, quiet)).domain,
      List(List("w" -> "quiet:12"))
    )
  }

  test("a wider contravariant instance stands in for the narrower one the derivation needs") {
    val woven: ContraAlg[Aspect.Weave[WidgetResult, Contra, Render, *]] =
      contraAspect.weave(contra)(Functor[WidgetResult])

    assertEquals(
      WeaveRenderer.render(woven.sub(new SubThing("x"))(loudThing)).domain,
      List(List("s" -> "loudThing:x"))
    )
    assertEquals(
      WeaveRenderer.render(woven.sub(new SubThing("x"))(quietThing)).domain,
      List(List("s" -> "quietThing:x"))
    )
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
