package com.dwolla.tagless.mtl
package laws

import cats.arrow.FunctionK
import cats.data.EitherT
import cats.effect.SyncIO
import cats.mtl.Raise
import cats.syntax.all._
import cats.tagless.aop.Aspect

import LawsInstances._
import SyncIOTestSyntax._

/** Law L9 — conservative extension.
  *
  * On a capability-free algebra our derivation must agree with upstream
  * `cats.tagless.Derive.aspect`: structurally equal rendered weaves, `Eq`-equal
  * codomain targets, and `mapK` agreeing with `FunctorK.mapK` for ''any'' pull.
  *
  * The upstream instance is abstract because deriving it is version-specific:
  * on Scala 2 it comes from cats-tagless-macros, and on Scala 3 `Derive` is
  * annotated `@experimental`, so the call site must be too.
  */
abstract class ConservativeExtensionSuite extends munit.CatsEffectSuite {

  /** `cats.tagless.Derive.aspect[PlainAlg, Render, Render]`, derived at a
    * version-appropriate call site.
    */
  def upstream: Aspect[PlainAlg, Render, Render]

  private val ours: RaiseAspect[PlainAlg, Render, Render, Render] =
    PlainAlgReference.referenceRaiseAspect[Render, Render, Render]

  private val impl: PlainAlg[Lazily] = new GenericPlainAlg[Lazily]

  /** Upstream `Aspect` still returns an `Alg[Weave[…]]`; our fused derivation
    * hands each weave to `fk` instead. The comparison therefore runs through a
    * recorder on our side, and renders inside the helper so neither test needs
    * a cast to line up the existentially-quantified result types the recorder
    * necessarily holds.
    */
  private def ourRendered(inputs: List[Int]): Lazily[List[RenderedWeave]] =
    for {
      recorder <- RecordingFk[Lazily, Render, Render]
      instrumented = ours.intercept(impl)(recorder.fk, OnRaise.noop[Lazily, Render])
      _ <- inputs.traverse_(i => EitherT.liftF[SyncIO, TestError, Unit](instrumented.p(i).value.void))
      weaves <- recorder.weaves
    } yield weaves.map(r => WeaveRenderer.render(r.weave)).toList

  test("L9 our woven structure matches upstream's, rendered") {
    val theirWoven = upstream.weave(impl)
    val inputs = exhaustiveInt.allValues.toList

    (for {
      ours <- ourRendered(inputs)
    } yield assertEquals(ours, inputs.map(i => WeaveRenderer.render(theirWoven.p(i))))).runOrFail
  }

  test("L9 our intercepted results match upstream's woven codomain targets") {
    (for {
      recorder <- RecordingFk[Lazily, Render, Render]
      instrumented = ours.intercept(impl)(recorder.fk, OnRaise.noop[Lazily, Render])
      theirWoven = upstream.weave(impl)
      _ <- exhaustiveInt.allValues.toList.traverse_ { i =>
        for {
          ours <- EitherT.liftF[SyncIO, TestError, Either[TestError, String]](instrumented.p(i).value)
          theirs <- EitherT.liftF[SyncIO, TestError, Either[TestError, String]](theirWoven.p(i).codomain.target.value)
        } yield assertEquals(ours, theirs)
      }
    } yield ()).runOrFail
  }

  test("L9 our mapK agrees with upstream's FunctorK.mapK for any pull") {
    // PlainAlg has no capability parameters, so the pull must never be
    // consulted. This one blows up if it ever is.
    val unusablePull = new RaisePull[Lazily, Lazily, Render] {
      def apply[E](rg: Raise[Lazily, E])(implicit ev: Render[E]): Raise[Lazily, E] =
        fail("mapK must not consult the pull for a capability-free algebra")
    }

    val ourMapped = ours.mapK(impl)(RaiseArrow(FunctionK.id[Lazily], unusablePull))
    val theirMapped = upstream.mapK(impl)(FunctionK.id[Lazily])

    (for {
      _ <- exhaustiveInt.allValues.toList.traverse_ { i =>
        for {
          ours <- EitherT.liftF[SyncIO, TestError, Either[TestError, String]](ourMapped.p(i).value)
          theirs <- EitherT.liftF[SyncIO, TestError, Either[TestError, String]](theirMapped.p(i).value)
        } yield assertEquals(ours, theirs)
      }
    } yield ()).runOrFail
  }
}
