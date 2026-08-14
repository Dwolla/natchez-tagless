package com.dwolla.tagless.mtl
package laws

import cats.arrow.FunctionK
import cats.data.EitherT
import cats.effect.*
import cats.effect.syntax.all.*
import cats.mtl.syntax.all.*
import cats.mtl.*
import cats.syntax.all.*
import cats.tagless.Derive as CatsTaglessDerive
import cats.tagless.aop.Aspect
import munit.{CatsEffectSuite, Location, TestOptions}
import LawsInstances.*
import cats.{ApplicativeError, Monad}

/** Law L9 for the ''derived'' instance — our derivation is a conservative
  * extension of upstream's on a capability-free algebra.
  *
  * `ConservativeExtensionSuite` hardcodes the hand-written
  * `PlainAlgReference` as "ours" and only exposes `upstream` as a seam, so it
  * cannot be reused here. The laws module is frozen, so rather than widen that
  * seam this spec restates the three comparisons against the derived instance.
  * (That missing seam is worth fixing whenever the freeze is next lifted.)
  */
class DerivedConservativeExtensionSpec extends CatsEffectSuite {

  private val ours: RaiseAspect[PlainAlg, Render, Render, Render] =
    DeriveRaise.aspect[PlainAlg, Render, Render, Render]

  private val upstream: Aspect[PlainAlg, Render, Render] =
    CatsTaglessDerive.aspect[PlainAlg, Render, Render]

  private def impl[F[_]](implicit H: Handle[F, TestError]): PlainAlg[F] = new GenericPlainAlg[F]

  def testWithHandle[F[_] : cats.ApplicativeThrow, E](options: TestOptions)
                                                     (f: cats.mtl.Handle[F, E] => F[Unit])
                                                     (implicit loc: Location): Unit =
    test(options) {
      Handle.allowF[F, E](f).rescue { testError =>
        new AssertionError(s"test raised unexpectedly: $testError").raiseError[F, Unit]
      }
    }

  implicit def applicativeErrorGivenHandle[F[_], E](implicit H: Handle[F, E]): ApplicativeError[F, E] =
    new ApplicativeError[F, E] {
      override def raiseError[A](e: E): F[A] = H.raise(e)
      override def handleErrorWith[A](fa: F[A])(f: E => F[A]): F[A] = H.handleWith(fa)(f)
      override def pure[A](x: A): F[A] = H.applicative.pure(x)
      override def ap[A, B](ff: F[A => B])(fa: F[A]): F[B] = H.applicative.ap(ff)(fa)
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

  testWithHandle[SyncIO, TestError]("L9 the derived mapK agrees with upstream's FunctorK.mapK for any pull") { implicit H =>
    // PlainAlg has no capability parameters, so the pull must never be consulted.
    val unusablePull = new RaisePull[SyncIO, SyncIO, Render] {
      def apply[E](rg: Raise[SyncIO, E])(implicit ev: Render[E]): Raise[SyncIO, E] =
        fail("mapK must not consult the pull for a capability-free algebra")
    }

    val ourMapped = ours.mapK(impl)(RaiseArrow(FunctionK.id[SyncIO], unusablePull))
    val theirMapped = upstream.mapK(impl)(FunctionK.id[SyncIO])

    for {
      _ <- exhaustiveInt.allValues.traverse_ { i =>
        for {
          ours <- ourMapped.p(i).attemptHandle
          theirs <- theirMapped.p(i).attemptHandle
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
}
