package com.dwolla.tagless.mtl

import cats.data.EitherT
import cats.effect.SyncIO
import cats.syntax.all._

/** Every migrated test body ends up with a `Lazily[Unit]` (or
  * `EitherT[SyncIO, WidgetError, Unit]`) describing the whole test —
  * `runOrFail` is the one place that turns "raised an error nobody expected"
  * into a failed `SyncIO`, so munit-cats-effect's registered `SyncIO`
  * transform reports it as a test failure with a real stack trace, instead of
  * silently succeeding on an unexamined `Left`.
  */
object SyncIOTestSyntax {
  implicit class RunOrFailSyncIOOps[E, A](private val fa: EitherT[SyncIO, E, A]) {
    def runOrFail: SyncIO[A] =
      fa.value.flatMap {
        case Right(a) => SyncIO.pure(a)
        case Left(e) => SyncIO.raiseError(new AssertionError(s"test raised unexpectedly: $e"))
      }
  }
}
