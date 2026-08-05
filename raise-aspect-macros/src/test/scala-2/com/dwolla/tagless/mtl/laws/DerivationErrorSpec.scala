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
}

/** No `Render[Boolean]` exists, so `flag` cannot be captured in the domain. */
trait MissingInstanceAlg[F[_]] {
  def m(flag: Boolean): F[String]
}

/** No `Render[Long]` exists, so the result cannot be captured in the codomain. */
trait MissingCodAlg[F[_]] {
  def m(i: Int): F[Long]
}
