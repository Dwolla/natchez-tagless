package com.dwolla.tagless.mtl

import cats.mtl.Raise
import cats.tagless.aop.Aspect
import cats.~>
import munit.FunSuite

import scala.collection.mutable.ListBuffer

import TestError._

class WeaveInterpreterSpec extends FunSuite {
  private type F[A] = Either[TestError, A]
  private type W[A] = Aspect.Weave[F, Render, Render, A]

  // The library's own forgetful arrow, rather than a hand-rolled one — the
  // interpreter a caller supplies in production is this shape.
  private val erase: W ~> F = WeaveArrows.codomainTarget[F, Render, Render]

  // `Synthetic`'s companion supplies only `Synthetic[Trivial]`, so `Cod = Render`
  // needs a local instance for `WeaveInterpreter.fromRaiseAspect` to resolve.
  // The module's other specs each define this same private val; match them.
  // (M10's Task 2 lost time to this exact omission in a spec source — the
  // constraint is on the instance, so without it the test fails to compile
  // rather than failing to pass.)
  private implicit val syntheticRender: Synthetic[Render] =
    new Synthetic[Render] {
      def apply[A]: Render[A] = (_: A) => "<synthetic>"
    }

  test("an Aspect instance outranks a RaiseAspect instance for the same algebra") {
    import WeaveInterpreterFixtures._

    val interpreted =
      WeaveInterpreter[PlainAlg, Render, Render, Render, F]
        .apply(EitherPlainAlg)(erase, OnRaise.noop[F, Render])

    // The poison RaiseAspect throws on any use, so reaching a result at all
    // proves the Aspect path ran.
    assertEquals(interpreted.p(2), Right("p:2"))
  }

  test("a RaiseAspect-only algebra resolves to the RaiseAspect instance and runs the hook") {
    implicit val reference: RaiseAspect[TestAlg, Render, Render, Render] =
      TestAlgReference.referenceRaiseAspect[Render, Render, Render]

    val rendered = ListBuffer.empty[String]
    val hook: OnRaise[F, Render] = new OnRaise[F, Render] {
      def apply[E](e: E)(implicit ev: Render[E]): F[Unit] = {
        rendered += ev.render(e)
        Right(())
      }
    }

    val interpreted =
      WeaveInterpreter[TestAlg, Render, Render, Render, F]
        .apply(new EitherTestAlg(0))(erase, hook)

    assertEquals(interpreted.a(3)(Raise[F, ErrA]), Right("a:3"))
    assertEquals(rendered.toList, Nil)

    assertEquals(interpreted.a(-3)(Raise[F, ErrA]), Left(NegativeInput(-3)): F[String])
    assertEquals(rendered.toList, List("errA:NegativeInput(-3)"))
  }
}
