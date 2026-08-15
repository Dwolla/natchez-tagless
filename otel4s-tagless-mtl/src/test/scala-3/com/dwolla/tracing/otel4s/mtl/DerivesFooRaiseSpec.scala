package com.dwolla.tracing.otel4s.mtl

import cats.data.EitherT
import cats.effect.{Ref, SyncIO}
import cats.mtl.Raise
import cats.syntax.all._
import cats.tagless.aop.Aspect
import cats.{Eval, Functor, ~>}
import com.dwolla.tagless.mtl.{OnRaise, RaiseArrow, RaisePull, RaiseAspect}
import com.dwolla.tracing.otel4s.ToAnyValue

import scala.annotation.experimental

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
class DerivesFooRaiseSpec extends munit.CatsEffectSuite {
  private type F[A] = EitherT[SyncIO, FooError, A]

  private val wide = Foo.fooRaiseAspect
  private val narrow: AnyValueRaiseAspect[Foo] = AnyValueRaiseAspect.fromRaiseAspect(wide)

  private val raiseF: Raise[F, FooError] = Raise[F, FooError]

  /** Turns "raised an error nobody expected" into a failed `SyncIO`, so
    * munit-cats-effect's registered `SyncIO` transform reports it as a test
    * failure with a real stack trace instead of silently succeeding on an
    * unexamined `Left`. Inlined rather than shared: this module's
    * `otel4sTaglessMtl` project depends on `raiseAspectCore` for compile
    * only, not `test->test`, so `SyncIOTestSyntax` (defined in
    * `raise-aspect-core`'s test sources) isn't on this module's test
    * classpath.
    */
  private def runOrFail[A](fa: F[A]): SyncIO[A] =
    fa.value.flatMap {
      case Right(a) => SyncIO.pure(a)
      case Left(e) => SyncIO.raiseError(new AssertionError(s"test raised unexpectedly: $e"))
    }

  /** Records what the interpreter is handed, then behaves like the forgetful
    * arrow — the same technique `TraceableRaiseAspectSpec`'s `Recorder` uses.
    */
  private final class Recorder(seenRef: Ref[F, Vector[String]]) {
    def seen: F[Vector[String]] = seenRef.get

    val fk: Aspect.Weave[F, ToAnyValue, ToAnyValue, *] ~> F =
      new (Aspect.Weave[F, ToAnyValue, ToAnyValue, *] ~> F) {
        def apply[A](w: Aspect.Weave[F, ToAnyValue, ToAnyValue, A]): F[A] =
          seenRef.update(_ :+ s"${w.algebraName}.${w.codomain.name}(${w.domain.flatten.map(_.name).mkString(",")})") *>
            w.codomain.target
      }
  }

  private object Recorder {
    def apply(): F[Recorder] = Ref.of[F, Vector[String]](Vector.empty).map(new Recorder(_))
  }

  test("intercept forwards to the underlying instance, weave for weave") {
    runOrFail {
      for {
        wideRec <- Recorder()
        narrowRec <- Recorder()
        viaWide = wide.intercept(Foo[F])(wideRec.fk, OnRaise.noop[F, ToAnyValue])
        viaNarrow = narrow.intercept(Foo[F])(narrowRec.fk, OnRaise.noop[F, ToAnyValue])
        r1 <- EitherT.liftF[SyncIO, FooError, Either[FooError, String]](viaNarrow.foo(5)(raiseF).value)
        w1 <- EitherT.liftF[SyncIO, FooError, Either[FooError, String]](viaWide.foo(5)(raiseF).value)
        _ = assertEquals(r1, w1)
        r2 <- EitherT.liftF[SyncIO, FooError, Either[FooError, String]](viaNarrow.foo(-1)(raiseF).value)
        w2 <- EitherT.liftF[SyncIO, FooError, Either[FooError, String]](viaWide.foo(-1)(raiseF).value)
        _ = assertEquals(r2, w2)
        narrowSeen <- narrowRec.seen
        wideSeen <- wideRec.seen
        _ = assertEquals(narrowSeen.toList, wideSeen.toList)
        _ = assertEquals(narrowSeen.toList, List("Foo.foo(i)", "Foo.foo(i)"))
      } yield ()
    }
  }

  test("intercept forwards the hook, so a raise is still observed exactly once") {
    runOrFail {
      for {
        rendered <- Ref.of[F, Vector[String]](Vector.empty)
        hook = new OnRaise[F, ToAnyValue] {
          def apply[E](e: E)(implicit ev: ToAnyValue[E]): F[Unit] =
            rendered.update(_ :+ ev.toAnyValue(e).toString)
        }
        rec <- Recorder()
        intercepted = narrow.intercept(Foo[F])(rec.fk, hook)
        r1 <- EitherT.liftF[SyncIO, FooError, Either[FooError, String]](intercepted.foo(5)(raiseF).value)
        _ = assertEquals(r1, "foo:5".asRight[FooError])
        seen1 <- rendered.get
        _ = assertEquals(seen1.toList, List.empty[String], "no raise, so no hook firing")
        r2 <- EitherT.liftF[SyncIO, FooError, Either[FooError, String]](intercepted.foo(-1)(raiseF).value)
        _ = assertEquals(r2, FooError.Negative(-1).asLeft[String])
        seen2 <- rendered.get
        _ = assertEquals(seen2.size, 1, "the hook must fire exactly once per raise")
        _ = assert(seen2.head.contains("negative:-1"), s"rendered through ToAnyValue, got ${seen2.head}")
      } yield ()
    }
  }

  test("mapK forwards to the underlying instance") {
    // Strict predates the file-level F's migration to EitherT[SyncIO, ...];
    // this test needs no Sync capability, so it keeps the original Either
    // carrier rather than bridging back through F with unsafeRunSync().
    type Strict[A] = Either[FooError, A]
    type G[A] = EitherT[Eval, FooError, A]

    val arrow: RaiseArrow[Strict, G, ToAnyValue] =
      RaiseArrow(
        new (Strict ~> G) { def apply[A](fa: Strict[A]): G[A] = EitherT(Eval.now(fa)) },
        new RaisePull[G, Strict, ToAnyValue] {
          def apply[E](rg: Raise[G, E])(implicit ev: ToAnyValue[E]): Raise[Strict, E] =
            new Raise[Strict, E] {
              val functor: Functor[Strict] = Functor[Strict]
              def raise[E2 <: E, A](e: E2): Strict[A] = rg.raise[E2, A](e).value.value
            }
        }
      )

    val raiseG: Raise[G, FooError] = Raise[G, FooError]

    assertEquals(
      narrow.mapK(Foo[Strict])(arrow).foo(5)(raiseG).value.value,
      wide.mapK(Foo[Strict])(arrow).foo(5)(raiseG).value.value
    )
    assertEquals(
      narrow.mapK(Foo[Strict])(arrow).foo(-1)(raiseG).value.value,
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
    runOrFail {
      for {
        derivedRec <- Recorder()
        handRec <- Recorder()
        // Same algebra shape, so the hand-written Foo reference is a valid oracle
        // for DerivesFoo once the algebra name is accounted for.
        derivedAlg = summon[AnyValueRaiseAspect[DerivesFoo]]
          .intercept(DerivesFoo[F])(derivedRec.fk, OnRaise.noop[F, ToAnyValue])
        handAlg = Foo.fooRaiseAspect
          .intercept(Foo[F])(handRec.fk, OnRaise.noop[F, ToAnyValue])
        d1 <- EitherT.liftF[SyncIO, FooError, Either[FooError, String]](derivedAlg.foo(5)(using raiseF).value)
        h1 <- EitherT.liftF[SyncIO, FooError, Either[FooError, String]](handAlg.foo(5)(raiseF).value)
        _ = assertEquals(d1, h1)
        d2 <- EitherT.liftF[SyncIO, FooError, Either[FooError, String]](derivedAlg.foo(-1)(using raiseF).value)
        h2 <- EitherT.liftF[SyncIO, FooError, Either[FooError, String]](handAlg.foo(-1)(raiseF).value)
        _ = assertEquals(d2, h2)
        derivedSeen <- derivedRec.seen
        handSeen <- handRec.seen
        // identical but for the algebra name, which is the only thing that differs
        _ = assertEquals(
          derivedSeen.toList,
          handSeen.toList.map(_.replace("Foo.foo", "DerivesFoo.foo"))
        )
        _ = assertEquals(derivedSeen.toList, List("DerivesFoo.foo(i)", "DerivesFoo.foo(i)"))
      } yield ()
    }
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
