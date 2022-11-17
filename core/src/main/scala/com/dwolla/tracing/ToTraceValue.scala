package com.dwolla.tracing

import cats._
import cats.syntax.all._
import io.circe.Encoder
import io.circe.syntax._
import natchez.TraceValue

/**
 * A typeclass implementation of Natchez's `TraceValue` ADT.
 *
 * Natchez provides implicit conversions to `TraceValue`, which
 * suffices when explicitly adding attributes to spans. This
 * typeclass encoding exists to support the `Aspect` typeclass from
 * cats-tagless, which demands a typeclass for which instances exist
 * for all parameter and return types on the algebra being woven.
 */
trait ToTraceValue[A] { self =>
  def toTraceValue(a: A): TraceValue

  def contramap[B](f: B => A): ToTraceValue[B] =
    b => self.toTraceValue(f(b))
}

object ToTraceValue extends LowPriorityToTraceValueInstances {
  def apply[A : ToTraceValue]: ToTraceValue[A] = implicitly

  implicit val unitTraceValue: ToTraceValue[Unit] = _ => "()"
  implicit def optionalTraceValue[A : ToTraceValue]: ToTraceValue[Option[A]] =
    _.map(ToTraceValue[A].toTraceValue(_)).getOrElse("None")
  implicit val stringTraceValue: ToTraceValue[String] = TraceValue.stringToTraceValue
  implicit val booleanTraceValue: ToTraceValue[Boolean] = TraceValue.boolToTraceValue
  implicit val numberTraceValue: ToTraceValue[Int] = TraceValue.intToTraceValue
}

trait LowPriorityToTraceValueInstances extends LowestPriorityToTraceValueInstances {

  implicit def traceValueViaJson[A: Encoder]: ToTraceValue[A] = _.asJson.noSpaces
}

trait LowestPriorityToTraceValueInstances {
  implicit def traceValueViaShow[A: Show]: ToTraceValue[A] = _.show
}
