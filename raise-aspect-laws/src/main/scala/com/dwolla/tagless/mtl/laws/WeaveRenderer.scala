package com.dwolla.tagless.mtl
package laws

import cats.Eq
import cats.tagless.aop.Aspect

/** Turns a type class instance plus a value of its type into a comparable
  * string.
  *
  * This exists because [[WeaveRenderer]] has to render an
  * `Aspect.Advice`'s target using the `Dom`/`Cod` instance the advice carries,
  * and that instance's type is existential from the outside.
  */
trait Renderable[G[_]] extends Serializable {
  def render[A](instance: G[A])(a: A): String
}

object Renderable {
  def apply[G[_]](implicit ev: Renderable[G]): Renderable[G] = ev
}

/** A structurally comparable projection of an `Aspect.Weave`.
  *
  * `Aspect.Advice` is a trait that overrides `toString` but not `equals`, so
  * two identically-constructed `Weave`s are never `==` — they compare by
  * reference. Every law that needs to compare weave ''structure'' goes through
  * this renderer instead. The codomain's `F[A]` is deliberately not rendered;
  * it is compared separately with an `Eq[F[A]]`.
  */
final case class RenderedWeave(
    algebraName: String,
    methodName: String,
    domain: List[List[(String, String)]]
)

object RenderedWeave {
  implicit val renderedWeaveEq: Eq[RenderedWeave] = Eq.fromUniversalEquals
}

object WeaveRenderer {

  /** Renders everything about a weave except the codomain target. */
  def render[F[_], Dom[_], Cod[_], A](
      weave: Aspect.Weave[F, Dom, Cod, A]
  )(implicit dom: Renderable[Dom]): RenderedWeave =
    RenderedWeave(
      weave.algebraName,
      weave.codomain.name,
      weave.domain.map(_.map(advice => advice.name -> dom.render(advice.instance)(advice.target.value)))
    )

  /** The advice names of each parameter clause, in order — the cheap check that
    * parameter-list shape is preserved and capability parameters are absent.
    */
  def domainNames[F[_], Dom[_], Cod[_], A](weave: Aspect.Weave[F, Dom, Cod, A]): List[List[String]] =
    weave.domain.map(_.map(_.name))
}
