package com.dwolla.tagless.mtl
package laws

import cats.mtl.{Handle, Raise}
import munit.FunSuite

/** Task 4 — the diagnostics suite.
  *
  * These algebras are legal Scala; it is only the ''derivation'' that must be
  * rejected, so they are declared normally and the derivation is attempted inside
  * `compileErrors`.
  */
object BadAlgebras {

  /** `Handle` consumes `F`, so it is genuinely not transportable. */
  trait HandleAlg[F[_]] {
    def h(i: Int)(implicit H: Handle[F, ErrA]): F[String]
  }

  /** An effectful parameter is not a capability and cannot be transported. */
  trait EffectParamAlg[F[_]] {
    def m(fa: F[Int]): F[String]
  }

  /** `F` nested inside its own return type. */
  trait NestedReturnAlg[F[_]] {
    def m(i: Int): F[F[String]]
  }

  /** `F` buried inside another type constructor in the return position. */
  trait WrappedReturnAlg[F[_]] {
    def m(i: Int): Either[ErrA, F[String]]
  }

  /** `F` in a parameter, and no `F[?]` return at all. */
  trait NoEffectReturnAlg[F[_]] {
    def m(implicit R: Raise[F, ErrA]): Int
  }

  /** A capability behind a type alias — this one must succeed. */
  type ErrARaise[F[_]] = Raise[F, ErrA]

  trait AliasedCapabilityAlg[F[_]] {
    def m(i: Int)(implicit R: ErrARaise[F]): F[String]
  }

  // An abstract `val` returning `F[A]` is deliberately absent.
  //
  // Both our derivation and upstream's skip accessors (`delegateMethods` filters
  // `!member.asMethod.isAccessor`), so the generated anonymous class omits the
  // member and compilation fails with the compiler's own
  // "object creation impossible. Missing implementation for member ... val v".
  // That is parity with upstream, per overview rule 6, but it cannot be asserted
  // here: the error is reported by `c.typecheck` *inside* the macro rather than by
  // the outer typecheck, so `compileErrors` does not observe it and returns "".
  // Verified manually during M3; see the milestone's Status section.
}

class DerivationErrorSpec extends FunSuite {

  test("a Handle parameter is rejected, pointing at Raise plus Handle.allow/rescue") {
    val errors = compileErrors(
      "DeriveRaise.aspect[BadAlgebras.HandleAlg, Render, Render, Render]"
    )
    assert(errors.contains("cats.mtl.Handle"), errors)
    assert(errors.contains("Handle consumes F and cannot be woven"), errors)
    assert(errors.contains("Handle.allow / rescue"), errors)
  }

  test("an effectful parameter is rejected as an unsupported position") {
    val errors = compileErrors(
      "DeriveRaise.aspect[BadAlgebras.EffectParamAlg, Render, Render, Render]"
    )
    assert(errors.contains("mentions the effect type F in an unsupported position"), errors)
    assert(errors.contains("Raise[F, E] parameters"), errors)
  }

  test("a nested F[F[A]] return type is rejected") {
    val errors = compileErrors(
      "DeriveRaise.aspect[BadAlgebras.NestedReturnAlg, Render, Render, Render]"
    )
    assert(errors.contains("top-level return type"), errors)
  }

  test("an F buried in the return type is rejected") {
    val errors = compileErrors(
      "DeriveRaise.aspect[BadAlgebras.WrappedReturnAlg, Render, Render, Render]"
    )
    assert(errors.contains("top-level return type"), errors)
  }

  test("a capability parameter on a method that does not return F is rejected") {
    val errors = compileErrors(
      "DeriveRaise.aspect[BadAlgebras.NoEffectReturnAlg, Render, Render, Render]"
    )
    assert(errors.contains("does not return F[?]"), errors)
  }

  test("a missing Dom instance names the parameter and method") {
    val errors = compileErrors(
      "DeriveRaise.aspect[MissingInstanceAlg, Render, Render, Render]"
    )
    assert(errors.contains("Not found"), errors)
    assert(errors.contains("for parameter flag of method m"), errors)
  }

  test("a missing Cod instance names the method") {
    val errors = compileErrors(
      "DeriveRaise.aspect[MissingCodAlg, Render, Render, Render]"
    )
    assert(errors.contains("Not found"), errors)
    assert(errors.contains("for the result of method m"), errors)
  }

  test("deriving with an Err type class that has no instance for the error type names the method and the type") {
    val errors = compileErrors(
      """import cats.mtl.Raise
import com.dwolla.tagless.mtl._
trait Unrenderable
trait NoEvidenceAlg[F[_]] {
  def go(i: Int)(implicit R: Raise[F, Unrenderable]): F[String]
}
DeriveRaise.aspect[NoEvidenceAlg, Render, Render, Render]"""
    )
    assert(errors.contains("no evidence for the error type"), errors)
    assert(errors.contains("go"), errors)
    assert(errors.contains("Unrenderable"), errors)
  }

  test("a capability behind a type alias is dealiased and derives successfully") {
    assertNoDiff(
      compileErrors("DeriveRaise.aspect[BadAlgebras.AliasedCapabilityAlg, Render, Render, Render]"),
      ""
    )
  }

  // --- M7 Task 1: method-local Dom/Cod/Err fixtures (red) -------------------
  //
  // `Widget` and `WidgetError` deliberately have no top-level `Render`
  // instance anywhere in this file; each algebra below supplies one only
  // through the method's own `implicit` clause, which is exactly the shape
  // M7 exists to support. Derivation still summons at the derivation site
  // today, so all three must fail. `WidgetShowAlg`/`WidgetMakeAlg`/
  // `WidgetRiskyAlg` isolate one failure apiece — combining them in one
  // algebra would only ever surface whichever method the macro visits
  // first, masking the other two. `WidgetAlg` (below) is the combined
  // shape the milestone doc's acceptance test targets; today it is also
  // red, for whichever one of the three reasons the macro hits first.

  test("M7: a method-local Dom instance is not found at the derivation site") {
    val errors = compileErrors(
      "DeriveRaise.aspect[WidgetShowAlg, Render, Render, Render]"
    )
    assert(errors.contains("Not found: implicit"), errors)
    assert(errors.contains("for parameter w of method show"), errors)
  }

  test("M7: a method-local Cod instance is not found at the derivation site") {
    val errors = compileErrors(
      "DeriveRaise.aspect[WidgetMakeAlg, Render, Render, Render]"
    )
    assert(errors.contains("Not found: implicit"), errors)
    assert(errors.contains("for the result of method make"), errors)
  }

  test("M7: a method-local Err instance is not found at the derivation site") {
    val errors = compileErrors(
      "DeriveRaise.aspect[WidgetRiskyAlg, Render, Render, Render]"
    )
    assert(errors.contains("no evidence for the error type"), errors)
    assert(errors.contains("risky"), errors)
    assert(errors.contains("WidgetError"), errors)
  }

  test("M7: WidgetAlg, combining all three method-local instance kinds, fails to derive today") {
    val errors = compileErrors(
      "DeriveRaise.aspect[WidgetAlg, Render, Render, Render]"
    )
    // Whichever of the three methods the macro visits first wins (it aborts
    // on the first bad method), so this only pins that *some* Not-found/no-evidence
    // diagnostic fires, not which one. The isolated tests above pin all three
    // individually; see the task-1 report for which one fires here.
    assert(errors.contains("Not found: implicit") || errors.contains("no evidence for the error type"), errors)
  }
}

/** No `Render[Boolean]` exists, so `flag` cannot be captured in the domain. */
trait MissingInstanceAlg[F[_]] {
  def m(flag: Boolean): F[String]
}

/** No `Render[Long]` exists, so the result cannot be captured in the codomain. */
trait MissingCodAlg[F[_]] {
  def m(i: Int): F[Long]
}

/** M7 Task 1 fixtures. No top-level `Render[Widget]`/`Render[WidgetError]`
  * instance exists anywhere in this file — their absence at the derivation
  * site is the entire point.
  */
trait Widget
trait WidgetError

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

/** Method-local `Err`: `Render[WidgetError]` is supplied only by `risky`'s
  * own implicit clause, alongside the `Raise[F, WidgetError]` capability.
  */
trait WidgetRiskyAlg[F[_]] {
  def risky(i: Int)(implicit RE: Render[WidgetError], R: Raise[F, WidgetError]): F[String]
}

/** All three method-local instance kinds on one algebra, matching the
  * overview appendix's motivating example (now with four type arguments per
  * M10). This is the shape the milestone's acceptance test targets once M7
  * lands; today it must fail.
  */
trait WidgetAlg[F[_]] extends WidgetShowAlg[F] with WidgetMakeAlg[F] with WidgetRiskyAlg[F]
