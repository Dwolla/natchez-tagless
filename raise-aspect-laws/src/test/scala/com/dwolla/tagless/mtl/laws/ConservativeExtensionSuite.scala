package com.dwolla.tagless.mtl
package laws

import cats.*
import cats.arrow.FunctionK
import cats.effect.*
import cats.mtl.*
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import cats.tagless.aop.Aspect
import com.dwolla.tagless.mtl.laws.LawsInstances.*
import com.dwolla.tagless.mtl.{HandleApplicativeErrorInstances, HandleTestSyntax}

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
abstract class ConservativeExtensionSuite extends munit.CatsEffectSuite with HandleTestSyntax with HandleApplicativeErrorInstances {

  /** `cats.tagless.Derive.aspect[PlainAlg, Render, Render]`, derived at a
    * version-appropriate call site.
    */
  def upstream: Aspect[PlainAlg, Render, Render]

  private val ours: RaiseAspect[PlainAlg, Render, Render, Render] =
    PlainAlgReference.referenceRaiseAspect[Render, Render, Render]

  private def impl[F[_]](implicit H: Handle[F, TestError]): PlainAlg[F] = {
    implicit val ae: ApplicativeError[F, TestError] = applicativeErrorGivenHandle[F, TestError]
    new GenericPlainAlg[F]
  }

  /** Upstream `Aspect` still returns an `Alg[Weave[…]]`; our fused derivation
    * hands each weave to `fk` instead. The comparison therefore runs through a
    * recorder on our side, and renders inside the helper so neither test needs
    * a cast to line up the existentially-quantified result types the recorder
    * necessarily holds.
    */
  private def ourRendered[F[_]: Sync](inputs: List[Int])
                                     (implicit H: Handle[F, TestError]): F[List[RenderedWeave]] =
    for {
      recorder <- RecordingFk[F, Render, Render]
      instrumented = ours.intercept(impl[F])(recorder.fk, OnRaise.noop[F, Render])
      _ <- inputs.traverse_(i => instrumented.p(i).void.attemptHandle)
      weaves <- recorder.weaves
    } yield weaves.map(r => WeaveRenderer.render(r.weave)).toList

  testWithHandle[SyncIO, TestError]("L9 our woven structure matches upstream's, rendered") { implicit H =>
    val theirWoven = upstream.weave(impl)
    val inputs = exhaustiveInt.allValues

    for {
      ours <- ourRendered[SyncIO](inputs)
    } yield assertEquals(ours, inputs.map(i => WeaveRenderer.render(theirWoven.p(i))))
  }

  testWithHandle[SyncIO, TestError]("L9 our intercepted results match upstream's woven codomain targets") { implicit H =>
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

  testWithHandle[SyncIO, TestError]("L9 our mapK agrees with upstream's FunctorK.mapK for any pull") { implicit H =>
    // PlainAlg has no capability parameters, so the pull must never be
    // consulted. This one blows up if it ever is.
    val unusablePull = new RaisePull[SyncIO, SyncIO, Render] {
      def apply[E](rg: Raise[SyncIO, E])(implicit ev: Render[E]): Raise[SyncIO, E] =
        fail("mapK must not consult the pull for a capability-free algebra")
    }

    val ourMapped = ours.mapK(impl)(RaiseArrow(FunctionK.id[SyncIO], unusablePull))
    val theirMapped = upstream.mapK(impl)(FunctionK.id[SyncIO])

    exhaustiveInt.allValues.traverse_ { i =>
      for {
        ours <- ourMapped.p(i).attemptHandle
        theirs <- theirMapped.p(i).attemptHandle
      } yield assertEquals(ours, theirs)
    }
  }
}
