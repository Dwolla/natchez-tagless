package com.dwolla.tracing

import cats.*
import cats.syntax.all.*
import io.circe.Encoder
import io.circe.syntax.*
import natchez.{TraceValue, TraceableValue}

object LowPriorityTraceableValueInstances extends LowPriorityTraceableValueInstances

trait LowPriorityTraceableValueInstances extends TraceableValueInstancesPlatform {
  implicit val unitTraceableValue: TraceableValue[Unit] =
    TraceableValue.stringToTraceValue.contramap(_ => "()")

  implicit def optionalTraceValue[A: TraceableValue]: TraceableValue[Option[A]] = {
    case Some(a) => TraceableValue[A].toTraceValue(a)
    case None => TraceValue.StringValue("None")
  }

  @deprecated("use nonPrimitiveTraceValueViaJson to avoid ambiguity with the instances in the TraceableValue companion object", "0.2.7")
  private[tracing] def traceValueViaJson[A: Encoder]: TraceableValue[A] =
    TraceableValue.stringToTraceValue.contramap(_.asJson.noSpaces)
}

trait LowestPriorityTraceableValueInstances {
  implicit def traceValueViaShow[A: Show]: TraceableValue[A] =
    TraceableValue.stringToTraceValue.contramap(_.show)
}
