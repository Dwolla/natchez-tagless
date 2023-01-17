package com.dwolla.tracing

import cats._
import cats.syntax.all._
import io.circe.Encoder
import io.circe.syntax._
import natchez.{TraceValue, TraceableValue}

object LowPriorityTraceableValueInstances extends LowPriorityTraceableValueInstances

trait LowPriorityTraceableValueInstances extends LowestPriorityTraceableValueInstances {
  implicit val unitTraceableValue: TraceableValue[Unit] = TraceableValue[String].contramap(_ => "()")

  implicit def optionalTraceValue[A: TraceableValue]: TraceableValue[Option[A]] = new TraceableValue[Option[A]] {
    override def toTraceValue(a: Option[A]): TraceValue = a match {
      case Some(a) => TraceableValue[A].toTraceValue(a)
      case None => TraceValue.StringValue("None")
    }
  }

  implicit def traceValueViaJson[A: Encoder]: TraceableValue[A] = _.asJson.noSpaces
}

trait LowestPriorityTraceableValueInstances {
  implicit def traceValueViaShow[A: Show]: TraceableValue[A] = _.show
}
