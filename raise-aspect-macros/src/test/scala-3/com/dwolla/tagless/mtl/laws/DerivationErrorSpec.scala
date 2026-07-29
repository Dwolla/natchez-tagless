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

@experimental
class DerivationErrorSpec extends FunSuite:

  test("a Handle parameter is rejected, pointing at Raise plus Handle.allow/rescue") {
    val errors: String = compileErrors("DeriveRaise.aspect[BadAlgebras.HandleAlg, Render, Render]")
    assert(errors.contains("cats.mtl.Handle"), errors)
    assert(errors.contains("Handle consumes F and cannot be woven"), errors)
    assert(errors.contains("Handle.allow / rescue"), errors)
  }

  test("an effectful parameter is rejected as an unsupported position") {
    val errors: String = compileErrors("DeriveRaise.aspect[BadAlgebras.EffectParamAlg, Render, Render]")
    assert(errors.contains("mentions the effect type F in an unsupported position"), errors)
    assert(errors.contains("Raise[F, E] parameters"), errors)
  }

  test("a nested F[F[A]] return type is rejected") {
    val errors: String = compileErrors("DeriveRaise.aspect[BadAlgebras.NestedReturnAlg, Render, Render]")
    assert(errors.contains("top-level return type"), errors)
  }

  test("an F buried in the return type is rejected") {
    val errors: String = compileErrors("DeriveRaise.aspect[BadAlgebras.WrappedReturnAlg, Render, Render]")
    assert(errors.contains("top-level return type"), errors)
  }

  test("a capability parameter on a method that does not return F is rejected") {
    val errors: String = compileErrors("DeriveRaise.aspect[BadAlgebras.NoEffectReturnAlg, Render, Render]")
    assert(errors.contains("does not return F[?]"), errors)
  }

  test("a context-function return type is rejected, suggesting a using parameter") {
    val errors: String = compileErrors("DeriveRaise.aspect[BadAlgebras.ContextFunctionAlg, Render, Render]")
    assert(errors.contains("context function"), errors)
    assert(errors.contains("using parameter"), errors)
  }

  test("a missing Dom instance names the parameter") {
    val errors: String = compileErrors("DeriveRaise.aspect[MissingInstanceAlg, Render, Render]")
    assert(errors.contains("Not found"), errors)
    assert(errors.contains("for parameter flag"), errors)
  }

  test("a missing Cod instance names the method") {
    val errors: String = compileErrors("DeriveRaise.aspect[MissingCodAlg, Render, Render]")
    assert(errors.contains("Not found"), errors)
    assert(errors.contains("for the result of method m"), errors)
  }

  test("a capability behind a type alias is dealiased and derives successfully") {
    assertNoDiff(compileErrors("DeriveRaise.aspect[BadAlgebras.AliasedCapabilityAlg, Render, Render]"), "")
  }
