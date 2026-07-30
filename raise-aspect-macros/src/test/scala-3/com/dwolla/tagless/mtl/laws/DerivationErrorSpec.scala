package com.dwolla.tagless.mtl
package laws

import cats.mtl.{Handle, Raise}
import munit.FunSuite

import scala.annotation.experimental

/** Task 3 — the Scala 3 diagnostics suite, mirroring M3's plus the Scala 3-only
  * context-function rejection.
  */
object BadAlgebras:

  trait HandleAlg[F[_]]:
    def h(i: Int)(using H: Handle[F, ErrA]): F[String]

  trait EffectParamAlg[F[_]]:
    def m(fa: F[Int]): F[String]

  trait NestedReturnAlg[F[_]]:
    def m(i: Int): F[F[String]]

  trait WrappedReturnAlg[F[_]]:
    def m(i: Int): Either[ErrA, F[String]]

  trait NoEffectReturnAlg[F[_]]:
    def m(using R: Raise[F, ErrA]): Int

  /** A capability delivered by a context function rather than a parameter. */
  trait ContextFunctionAlg[F[_]]:
    def m(i: Int): Raise[F, ErrA] ?=> F[String]

  /** A capability behind a type alias — this one must succeed. */
  type ErrARaise[F[_]] = Raise[F, ErrA]

  trait AliasedCapabilityAlg[F[_]]:
    def m(i: Int)(using R: ErrARaise[F]): F[String]

/** No `Render[Boolean]`, so `flag` cannot be captured in the domain. */
trait MissingInstanceAlg[F[_]]:
  def m(flag: Boolean): F[String]

/** No `Render[Long]`, so the result cannot be captured in the codomain. */
trait MissingCodAlg[F[_]]:
  def m(i: Int): F[Long]

/** M7 Task 1 fixtures. No top-level `Render[Widget]`/`Render[WidgetError]`
  * instance exists anywhere in this file — their absence at the derivation
  * site is the entire point.
  */
trait Widget
trait WidgetError

/** Method-local `Dom`: `Render[Widget]` is supplied only by `show`'s own
  * `using` clause, not at the derivation site.
  */
trait WidgetShowAlg[F[_]]:
  def show(w: Widget)(using R: Render[Widget]): F[String]

/** Method-local `Cod`: same story, but for the codomain advice on `make`'s
  * `F[Widget]` result.
  */
trait WidgetMakeAlg[F[_]]:
  def make(i: Int)(using R: Render[Widget]): F[Widget]

/** Method-local `Err`: `Render[WidgetError]` is supplied only by `risky`'s
  * own `using` clause, alongside the `Raise[F, WidgetError]` capability.
  */
trait WidgetRiskyAlg[F[_]]:
  def risky(i: Int)(using RE: Render[WidgetError], R: Raise[F, WidgetError]): F[String]

/** All three method-local instance kinds on one algebra, matching the
  * overview appendix's motivating example (now with four type arguments per
  * M10). This is the shape the milestone's acceptance test targets once M7
  * lands; today it must fail.
  */
trait WidgetAlg[F[_]] extends WidgetShowAlg[F], WidgetMakeAlg[F], WidgetRiskyAlg[F]

@experimental
class DerivationErrorSpec extends FunSuite:

  test("a Handle parameter is rejected, pointing at Raise plus Handle.allow/rescue") {
    val errors: String = compileErrors("DeriveRaise.aspect[BadAlgebras.HandleAlg, Render, Render, Render]")
    assert(errors.contains("cats.mtl.Handle"), errors)
    assert(errors.contains("Handle consumes F and cannot be woven"), errors)
    assert(errors.contains("Handle.allow / rescue"), errors)
  }

  test("an effectful parameter is rejected as an unsupported position") {
    val errors: String = compileErrors("DeriveRaise.aspect[BadAlgebras.EffectParamAlg, Render, Render, Render]")
    assert(errors.contains("mentions the effect type F in an unsupported position"), errors)
    assert(errors.contains("Raise[F, E] parameters"), errors)
  }

  test("a nested F[F[A]] return type is rejected") {
    val errors: String = compileErrors("DeriveRaise.aspect[BadAlgebras.NestedReturnAlg, Render, Render, Render]")
    assert(errors.contains("top-level return type"), errors)
  }

  test("an F buried in the return type is rejected") {
    val errors: String = compileErrors("DeriveRaise.aspect[BadAlgebras.WrappedReturnAlg, Render, Render, Render]")
    assert(errors.contains("top-level return type"), errors)
  }

  test("a capability parameter on a method that does not return F is rejected") {
    val errors: String = compileErrors("DeriveRaise.aspect[BadAlgebras.NoEffectReturnAlg, Render, Render, Render]")
    assert(errors.contains("does not return F[?]"), errors)
  }

  test("a context-function return type is rejected, suggesting a using parameter") {
    val errors: String = compileErrors("DeriveRaise.aspect[BadAlgebras.ContextFunctionAlg, Render, Render, Render]")
    assert(errors.contains("context function"), errors)
    assert(errors.contains("using parameter"), errors)
  }

  test("a missing Dom instance names the parameter") {
    val errors: String = compileErrors("DeriveRaise.aspect[MissingInstanceAlg, Render, Render, Render]")
    assert(errors.contains("Not found"), errors)
    assert(errors.contains("for parameter flag"), errors)
  }

  test("a missing Cod instance names the method") {
    val errors: String = compileErrors("DeriveRaise.aspect[MissingCodAlg, Render, Render, Render]")
    assert(errors.contains("Not found"), errors)
    assert(errors.contains("for the result of method m"), errors)
  }

  test("deriving with an Err type class that has no instance for the error type names the method and the type") {
    val errors: String = compileErrors(
      """import cats.mtl.Raise
import com.dwolla.tagless.mtl._
trait Unrenderable
trait NoEvidenceAlg[F[_]] {
  def go(i: Int)(using R: Raise[F, Unrenderable]): F[String]
}
DeriveRaise.aspect[NoEvidenceAlg, Render, Render, Render]"""
    )
    assert(errors.contains("no evidence for the error type"), errors)
    assert(errors.contains("go"), errors)
    assert(errors.contains("Unrenderable"), errors)
  }

  test("a capability behind a type alias is dealiased and derives successfully") {
    assertNoDiff(compileErrors("DeriveRaise.aspect[BadAlgebras.AliasedCapabilityAlg, Render, Render, Render]"), "")
  }

  // --- M7 Task 1: method-local Dom/Cod/Err fixtures (red) -------------------
  //
  // `WidgetShowAlg`/`WidgetMakeAlg`/`WidgetRiskyAlg` isolate one failure
  // apiece — combining them in one algebra would only ever surface whichever
  // method the macro visits first, masking the other two. `WidgetAlg` is the
  // combined shape the milestone doc's acceptance test targets; today it is
  // also red, for whichever one of the three reasons the macro hits first.

  test("M7: a method-local Dom instance is not found at the derivation site") {
    val errors: String = compileErrors("DeriveRaise.aspect[WidgetShowAlg, Render, Render, Render]")
    assert(errors.contains("Not found: given"), errors)
    assert(errors.contains("for parameter w"), errors)
  }

  test("M7: a method-local Cod instance is not found at the derivation site") {
    val errors: String = compileErrors("DeriveRaise.aspect[WidgetMakeAlg, Render, Render, Render]")
    assert(errors.contains("Not found: given"), errors)
    assert(errors.contains("for the result of method make"), errors)
  }

  test("M7: a method-local Err instance is not found at the derivation site") {
    val errors: String = compileErrors("DeriveRaise.aspect[WidgetRiskyAlg, Render, Render, Render]")
    assert(errors.contains("no evidence for the error type"), errors)
    assert(errors.contains("risky"), errors)
    assert(errors.contains("WidgetError"), errors)
  }

  test("M7: WidgetAlg, combining all three method-local instance kinds, fails to derive today") {
    val errors: String = compileErrors("DeriveRaise.aspect[WidgetAlg, Render, Render, Render]")
    // Whichever of the three methods the macro visits first wins (`validate`/
    // `deriveWeave` abort on the first bad method), so this only pins that
    // *some* Not-found/no-evidence diagnostic fires, not which one. The
    // isolated tests above pin all three individually; see the task-1 report
    // for which one fires here.
    assert(errors.contains("Not found: given") || errors.contains("no evidence for the error type"), errors)
  }
