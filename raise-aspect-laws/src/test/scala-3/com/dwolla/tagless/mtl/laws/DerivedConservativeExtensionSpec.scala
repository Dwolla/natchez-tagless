package com.dwolla.tagless.mtl
package laws

import cats.ApplicativeError
import cats.effect.{Sync, SyncIO}
import cats.mtl.syntax.all.*
import cats.mtl.Handle
import cats.syntax.all.*
import cats.tagless.Derive as CatsTaglessDerive
import cats.tagless.aop.Aspect
import munit.CatsEffectSuite

import scala.annotation.experimental

import LawsInstances.*

/** Law L9 on Scala 3 — our derivation is a conservative extension of upstream's on
  * a capability-free algebra. Additive, like the Scala 2 spec, because
  * `ConservativeExtensionSuite` hardcodes the hand-written reference as "ours".
  */
@experimental
class DerivedConservativeExtensionSpec extends CatsEffectSuite with HandleTestSyntax with HandleApplicativeErrorInstances:

  private val ours: RaiseAspect[PlainAlg, Render, Render, Render] =
    DeriveRaise.aspect[PlainAlg, Render, Render, Render]

  private val upstream: Aspect[PlainAlg, Render, Render] =
    CatsTaglessDerive.aspect[PlainAlg, Render, Render]

  private def impl[F[_]](implicit H: Handle[F, TestError]): PlainAlg[F] = {
    implicit val ae: ApplicativeError[F, TestError] = applicativeErrorGivenHandle[F, TestError]
    new GenericPlainAlg[F]
  }

  /** Upstream's `Aspect` still returns an `Alg[Weave[…]]`; our fused derivation
    * hands each weave to `fk` instead. The comparison runs through a recorder on
    * our side, and renders inside the helper so no test needs a cast to line up
    * the existentially-quantified result type the recorder holds.
    */
  private def ourRendered[F[_] : Sync](inputs: List[Int])(implicit H: Handle[F, TestError]): F[List[RenderedWeave]] =
    for {
      recorder <- RecordingFk[F, Render, Render]
      instrumented = ours.intercept(impl[F])(recorder.fk, OnRaise.noop[F, Render])
      _ <- inputs.traverse_(i => instrumented.p(i).attemptHandle.void)
      weaves <- recorder.weaves
    } yield weaves.map(r => WeaveRenderer.render(r.weave)).toList

  testWithHandle[SyncIO, TestError]("L9 the derived woven structure matches upstream's, rendered") { implicit H =>
    val theirWoven = upstream.weave(impl)
    val inputs = exhaustiveInt.allValues

    for {
      ours <- ourRendered[SyncIO](inputs)
    } yield assertEquals(ours, inputs.map(i => WeaveRenderer.render(theirWoven.p(i))))
  }

  testWithHandle[SyncIO, TestError]("L9 the derived woven codomain targets match upstream's") { implicit H =>
    for {
      recorder <- RecordingFk[SyncIO, Render, Render]
      instrumented = ours.intercept(impl)(recorder.fk, OnRaise.noop[SyncIO, Render])
      theirWoven = upstream.weave(impl)
      _ <- exhaustiveInt.allValues.traverse_ { i =>
        for {
          ours <- instrumented.p(i).attemptHandle
          theirs <- theirWoven.p(i).codomain.target.attemptHandle
        } yield assertEquals(ours, theirs)
      }
    } yield ()
  }

  testWithHandle[SyncIO, TestError]("L9 the derived instance also matches the hand-written PlainAlg reference") { implicit H =>
    val inputs = exhaustiveInt.allValues

    for {
      referenceRecorder <- RecordingFk[SyncIO, Render, Render]
      referenceInstrumented = PlainAlgReference
        .referenceRaiseAspect[Render, Render, Render]
        .intercept(impl)(referenceRecorder.fk, OnRaise.noop[SyncIO, Render])
      _ <- inputs.traverse_(referenceInstrumented.p(_).attemptHandle.void)
      ours <- ourRendered[SyncIO](inputs)
      referenceWeaves <- referenceRecorder.weaves
    } yield assertEquals(ours, referenceWeaves.map(r => WeaveRenderer.render(r.weave)).toList)
  }
