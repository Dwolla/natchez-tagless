package com.dwolla.tracing.otel4s.mtl

import cats.data.EitherT
import cats.mtl.Raise
import cats.syntax.all._
import cats.tagless.aop.Aspect
import cats.{Eval, Functor, ~>}
import com.dwolla.tagless.mtl.{OnRaise, RaiseArrow, RaisePull, RaiseAspect}
import com.dwolla.tracing.otel4s.ToAnyValue
import munit.FunSuite

import scala.annotation.experimental
import scala.collection.mutable.ListBuffer

/** The otel4s parallel of `natchez-tagless-mtl`'s `TraceableRaiseAspectSpec`: an
  * algebra that says `derives AnyValueRaiseAspect` and nothing else must
  * produce the same observable result — on both the success and the raise
  * path — as `Foo`'s hand-written `RaiseAspect[Foo, ToAnyValue, ToAnyValue,
  * ToAnyValue]` instance (`Foo.fooRaiseAspect`, `FooRaiseFixture.scala`).
  *
  * Cross-platform, like `TraceableRaiseAspectSpec`: nothing here touches a
  * `Tracer` or a testkit, so this runs on both `otel4sTaglessMtlJVM` and
  * `otel4sTaglessMtlJS` — unlike a span-content assertion, which otel4s's
  * sealed span types confine to the JVM testkit (see `SpanContentSpec`).
  */
@experimental
class DerivesFooRaiseSpec extends FunSuite {
  private type F[A] = Either[FooError, A]

  private val wide = Foo.fooRaiseAspect
  private val narrow: AnyValueRaiseAspect[Foo] = AnyValueRaiseAspect.fromRaiseAspect(wide)

  private val raiseF: Raise[F, FooError] = Raise[F, FooError]

  /** Records what the interpreter is handed, then behaves like the forgetful
    * arrow — the same technique `TraceableRaiseAspectSpec`'s `Recorder` uses.
    */
  private final class Recorder {
    val seen: ListBuffer[String] = ListBuffer.empty

    val fk: Aspect.Weave[F, ToAnyValue, ToAnyValue, *] ~> F =
      new (Aspect.Weave[F, ToAnyValue, ToAnyValue, *] ~> F) {
        def apply[A](w: Aspect.Weave[F, ToAnyValue, ToAnyValue, A]): F[A] = {
          val _ = seen += s"${w.algebraName}.${w.codomain.name}(${w.domain.flatten.map(_.name).mkString(",")})"
          w.codomain.target
        }
      }
  }

  test("intercept forwards to the underlying instance, weave for weave") {
    val wideRec = new Recorder
    val narrowRec = new Recorder

    val viaWide = wide.intercept(Foo[F])(wideRec.fk, OnRaise.noop[F, ToAnyValue])
    val viaNarrow = narrow.intercept(Foo[F])(narrowRec.fk, OnRaise.noop[F, ToAnyValue])

    assertEquals(viaNarrow.foo(5)(raiseF), viaWide.foo(5)(raiseF))
    assertEquals(viaNarrow.foo(-1)(raiseF), viaWide.foo(-1)(raiseF))
    assertEquals(narrowRec.seen.toList, wideRec.seen.toList)
    assertEquals(narrowRec.seen.toList, List("Foo.foo(i)", "Foo.foo(i)"))
  }

  test("intercept forwards the hook, so a raise is still observed exactly once") {
    val rendered = ListBuffer.empty[String]

    val hook: OnRaise[F, ToAnyValue] = new OnRaise[F, ToAnyValue] {
      def apply[E](e: E)(implicit ev: ToAnyValue[E]): F[Unit] = {
        val _ = rendered += ev.toAnyValue(e).toString
        Right(())
      }
    }

    val rec = new Recorder
    val intercepted = narrow.intercept(Foo[F])(rec.fk, hook)

    assertEquals(intercepted.foo(5)(raiseF), "foo:5".asRight[FooError])
    assertEquals(rendered.toList, List.empty[String], "no raise, so no hook firing")

    assertEquals(intercepted.foo(-1)(raiseF), FooError.Negative(-1).asLeft[String])
    assertEquals(rendered.size, 1, "the hook must fire exactly once per raise")
    assert(rendered.head.contains("negative:-1"), s"rendered through ToAnyValue, got ${rendered.head}")
  }

  test("mapK forwards to the underlying instance") {
    type G[A] = EitherT[Eval, FooError, A]

    val arrow: RaiseArrow[F, G, ToAnyValue] =
      RaiseArrow(
        new (F ~> G) { def apply[A](fa: F[A]): G[A] = EitherT(Eval.now(fa)) },
        new RaisePull[G, F, ToAnyValue] {
          def apply[E](rg: Raise[G, E])(implicit ev: ToAnyValue[E]): Raise[F, E] =
            new Raise[F, E] {
              val functor: Functor[F] = Functor[F]
              def raise[E2 <: E, A](e: E2): F[A] = rg.raise[E2, A](e).value.value
            }
        }
      )

    val raiseG: Raise[G, FooError] = Raise[G, FooError]

    assertEquals(
      narrow.mapK(Foo[F])(arrow).foo(5)(raiseG).value.value,
      wide.mapK(Foo[F])(arrow).foo(5)(raiseG).value.value
    )
    assertEquals(
      narrow.mapK(Foo[F])(arrow).foo(-1)(raiseG).value.value,
      FooError.Negative(-1).asLeft[String]
    )
  }

  test("the narrow instance is accepted wherever the wide one is") {
    val asWide: RaiseAspect[Foo, ToAnyValue, ToAnyValue, ToAnyValue] = narrow
    assert(asWide ne null)
  }

  test("the derives clause produces an instance, and it is the narrow type") {
    val derived: AnyValueRaiseAspect[DerivesFoo] = summon[AnyValueRaiseAspect[DerivesFoo]]
    assert(derived ne null)
  }

  test("the derived instance agrees with a hand-written one on intercept") {
    val derivedRec = new Recorder
    val handRec = new Recorder

    // Same algebra shape, so the hand-written Foo reference is a valid oracle
    // for DerivesFoo once the algebra name is accounted for.
    val derivedAlg =
      summon[AnyValueRaiseAspect[DerivesFoo]]
        .intercept(DerivesFoo[F])(derivedRec.fk, OnRaise.noop[F, ToAnyValue])
    val handAlg =
      Foo.fooRaiseAspect
        .intercept(Foo[F])(handRec.fk, OnRaise.noop[F, ToAnyValue])

    assertEquals(derivedAlg.foo(5)(using raiseF), handAlg.foo(5)(raiseF))
    assertEquals(derivedAlg.foo(-1)(using raiseF), handAlg.foo(-1)(raiseF))

    // identical but for the algebra name, which is the only thing that differs
    assertEquals(
      derivedRec.seen.toList,
      handRec.seen.toList.map(_.replace("Foo.foo", "DerivesFoo.foo"))
    )
    assertEquals(derivedRec.seen.toList, List("DerivesFoo.foo(i)", "DerivesFoo.foo(i)"))
  }

  test("...and fromRaiseAspect is how you get one anyway") {
    val fixed: AnyValueRaiseAspect[Foo] = AnyValueRaiseAspect.fromRaiseAspect(Foo.fooRaiseAspect)
    assert(fixed ne null)
  }

  /** The narrowing contract is the entire reason `AnyValueRaiseAspect` exists
    * (see its own scaladoc): a wide `RaiseAspect[Alg, ToAnyValue, ToAnyValue,
    * ToAnyValue]` must not satisfy a demand for the narrow
    * `AnyValueRaiseAspect[Alg]` on its own — `fromRaiseAspect` is the only
    * crossing. Mirrors `TraceableRaiseAspectSpec`'s equivalent test, but
    * asserts only that ''some'' error was reported, not its exact text:
    * diagnostic wording differs across compiler versions, and the property
    * that matters is that the summon fails at all. If the wide instance did
    * satisfy the narrow demand, `compileErrors` would return `""` and this
    * assertion would fail — so the test is genuinely failable, not
    * vacuously true.
    */
  test("a wide RaiseAspect does not satisfy a demand for the narrow type") {
    // needs an explicit `String` annotation on Scala 3, or a cyclic-reference
    // check fires and captures that error instead of the snippet's diagnostics
    val errors: String = compileErrors(
      "summon[AnyValueRaiseAspect[Foo]](using Foo.fooRaiseAspect)"
    )
    assert(errors.nonEmpty, "expected a compile error demanding AnyValueRaiseAspect[Foo], got none")
  }
}
