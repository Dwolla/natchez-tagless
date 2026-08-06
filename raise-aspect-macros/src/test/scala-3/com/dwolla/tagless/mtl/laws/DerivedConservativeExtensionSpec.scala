package com.dwolla.tagless.mtl
package laws

import cats.arrow.FunctionK
import cats.data.EitherT
import cats.effect.SyncIO
import cats.mtl.Raise
import cats.syntax.all._
import cats.tagless.Derive as CatsTaglessDerive
import cats.tagless.aop.Aspect
import munit.CatsEffectSuite

import scala.annotation.experimental

import LawsInstances._
import SyncIOTestSyntax._

/** Law L9 on Scala 3 — our derivation is a conservative extension of upstream's on
  * a capability-free algebra. Additive, like the Scala 2 spec, because M2's
  * `ConservativeExtensionSuite` hardcodes the hand-written reference as "ours".
  */
@experimental
class DerivedConservativeExtensionSpec extends CatsEffectSuite:

  private val ours: RaiseAspect[PlainAlg, Render, Render, Render] =
    DeriveRaise.aspect[PlainAlg, Render, Render, Render]

  private val upstream: Aspect[PlainAlg, Render, Render] =
    CatsTaglessDerive.aspect[PlainAlg, Render, Render]

  private val impl: PlainAlg[Lazily] = new GenericPlainAlg[Lazily]

  /** Upstream's `Aspect` still returns an `Alg[Weave[…]]`; our fused derivation
    * hands each weave to `fk` instead. The comparison runs through a recorder on
    * our side, and renders inside the helper so no test needs a cast to line up
    * the existentially-quantified result type the recorder holds.
    */
  private def ourRendered(inputs: List[Int]): Lazily[List[RenderedWeave]] =
    for {
      recorder <- RecordingFk[Lazily, Render, Render]
      instrumented = ours.intercept(impl)(recorder.fk, OnRaise.noop[Lazily, Render])
      _ <- inputs.traverse_(i => EitherT.liftF[SyncIO, TestError, Unit](instrumented.p(i).value.void))
      weaves <- recorder.weaves
    } yield weaves.map(r => WeaveRenderer.render(r.weave)).toList

  test("L9 the derived woven structure matches upstream's, rendered") {
    val theirWoven = upstream.weave(impl)
    val inputs = exhaustiveInt.allValues.toList

    (for {
      ours <- ourRendered(inputs)
    } yield assertEquals(ours, inputs.map(i => WeaveRenderer.render(theirWoven.p(i))))).runOrFail
  }

  test("L9 the derived woven codomain targets match upstream's") {
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

  test("L9 the derived mapK agrees with upstream's FunctorK.mapK for any pull") {
    val unusablePull = new RaisePull[Lazily, Lazily, Render]:
      def apply[E](rg: Raise[Lazily, E])(implicit ev: Render[E]): Raise[Lazily, E] =
        fail("mapK must not consult the pull for a capability-free algebra")

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

  test("L9 the derived instance also matches the hand-written PlainAlg reference") {
    val inputs = exhaustiveInt.allValues.toList

    (for {
      referenceRecorder <- RecordingFk[Lazily, Render, Render]
      referenceInstrumented = PlainAlgReference
        .referenceRaiseAspect[Render, Render, Render]
        .intercept(impl)(referenceRecorder.fk, OnRaise.noop[Lazily, Render])
      _ <- inputs.traverse_(i => EitherT.liftF[SyncIO, TestError, Unit](referenceInstrumented.p(i).value.void))
      ours <- ourRendered(inputs)
      referenceWeaves <- referenceRecorder.weaves
    } yield assertEquals(ours, referenceWeaves.map(r => WeaveRenderer.render(r.weave)).toList)).runOrFail
  }
