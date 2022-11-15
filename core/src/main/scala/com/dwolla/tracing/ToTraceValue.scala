package com.dwolla.tracing

import cats._
import cats.syntax.all._
import io.circe.Encoder
import io.circe.syntax._
import natchez.TraceValue

trait ToTraceValue[A] { self =>
  def toTraceValue(a: A): TraceValue

  def contramap[B](f: B => A): ToTraceValue[B] =
    b => self.toTraceValue(f(b))
}

object ToTraceValue extends LowPriorityToTraceValueInstances {
  def apply[A: ToTraceValue]: ToTraceValue[A] = implicitly

  implicit val unitTraceValue: ToTraceValue[Unit] = _ => "()"
  implicit def optionalTraceValue[A: ToTraceValue]: ToTraceValue[Option[A]] =
    _.map(ToTraceValue[A].toTraceValue(_)).getOrElse("None")
  implicit val stringTraceValue: ToTraceValue[String] =
    TraceValue.stringToTraceValue
  implicit val booleanTraceValue: ToTraceValue[Boolean] =
    TraceValue.boolToTraceValue
  implicit val numberTraceValue: ToTraceValue[Int] = TraceValue.intToTraceValue
}

trait LowPriorityToTraceValueInstances
    extends LowestPriorityToTraceValueInstances {

  implicit def traceValueViaJson[A: Encoder]: ToTraceValue[A] =
    _.asJson.noSpaces
}

trait LowestPriorityToTraceValueInstances {
  implicit def traceValueViaShow[A: Show]: ToTraceValue[A] = _.show
}
