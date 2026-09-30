package com.dwolla.tagless.mtl

import cats.ApplicativeError
import cats.mtl.Handle
import cats.syntax.all.*
import munit.{FunSuite, Location, TestOptions}

/** A `munit` test whose body raises through an ambient `cats.mtl.Handle`. An
  * unexpected raise escaping the body becomes a test failure rather than an
  * unhandled error, matching the `.rescue` half of cats-mtl's
  * `Handle.allow`…`.rescue` idiom.
  */
trait HandleTestSyntax { self: FunSuite =>
  def testWithHandle[F[_] : cats.ApplicativeThrow, E](options: TestOptions)
                                                     (f: cats.mtl.Handle[F, E] => F[Unit])
                                                     (implicit loc: Location): Unit =
    test(options) {
      Handle.allowF[F, E](f).rescue { testError =>
        new AssertionError(s"test raised unexpectedly: $testError").raiseError[F, Unit]
      }
    }
}

/** An `ApplicativeError[F, E]` built from an ambient `Handle[F, E]`, for
  * fixtures that need an `F[_]`-polymorphic capability constraint but only
  * have a `Handle` in scope.
  *
  * Deliberately not `implicit`: mixed broadly into a class, an implicit
  * conversion this generic becomes a spurious candidate anywhere else in the
  * same class an `Applicative[F]`/`Apply[F]` is sought (e.g. from an ambient
  * `Sync[F]`), which Scala 2.12 reports as an ambiguity that 2.13/3 accept.
  * Callers materialize it as a `implicit val` scoped to just the call site
  * that needs it.
  */
trait HandleApplicativeErrorInstances {
  def applicativeErrorGivenHandle[F[_], E](implicit H: Handle[F, E]): ApplicativeError[F, E] =
    new ApplicativeError[F, E] {
      override def raiseError[A](e: E): F[A] = H.raise(e)
      override def handleErrorWith[A](fa: F[A])(f: E => F[A]): F[A] = H.handleWith(fa)(f)
      override def pure[A](x: A): F[A] = H.applicative.pure(x)
      override def ap[A, B](ff: F[A => B])(fa: F[A]): F[B] = H.applicative.ap(ff)(fa)
    }
}
