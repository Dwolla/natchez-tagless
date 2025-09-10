package com.dwolla.tracing

import cats.*
import cats.syntax.all.*
import io.circe.Encoder
import io.circe.syntax.*
import natchez.{TraceValue, TraceableValue}

import com.dwolla.compat.scala.util.NotGiven

object LowPriorityTraceableValueInstances extends LowPriorityTraceableValueInstances

trait LowPriorityTraceableValueInstances extends LowestPriorityTraceableValueInstances {
  implicit val unitTraceableValue: TraceableValue[Unit] =
    TraceableValue.stringToTraceValue.contramap(_ => "()")

  implicit def optionalTraceValue[A: TraceableValue]: TraceableValue[Option[A]] = {
    case Some(a) => TraceableValue[A].toTraceValue(a)
    case None => TraceValue.StringValue("None")
  }

  @deprecated("use nonPrimitiveTraceValueViaJson to avoid ambiguity with the instances in the TraceableValue companion object", "0.2.7")
  private[tracing] def traceValueViaJson[A: Encoder]: TraceableValue[A] =
    TraceableValue.stringToTraceValue.contramap(_.asJson.noSpaces)

  implicit def nonPrimitiveTraceValueViaJson[A: Encoder](implicit @annotation.unused aNotString: NotGiven[A =:= String],
                                                         @annotation.unused aNotBoolean: NotGiven[A =:= Boolean],
                                                         @annotation.unused aNotInt: NotGiven[A =:= Int],
                                                         @annotation.unused aNotLong: NotGiven[A =:= Long],
                                                         @annotation.unused aNotDouble: NotGiven[A =:= Double],
                                                         @annotation.unused aNotFloat: NotGiven[A =:= Float],
                                                        ): TraceableValue[A] =
    TraceableValue[String].contramap(_.asJson.noSpaces)
}

trait LowestPriorityTraceableValueInstances {
  @deprecated("use nonPrimitiveTraceValueViaJson to avoid ambiguity with the instances in the TraceableValue companion object", "0.2.7")
  private[tracing] def traceValueViaShow[A: Show]: TraceableValue[A] =
    TraceableValue.stringToTraceValue.contramap(_.show)

  implicit def nonPrimitiveTraceValueViaShow[A: Show](implicit @annotation.unused aNotString: NotGiven[A =:= String],
                                                      @annotation.unused aNotBoolean: NotGiven[A =:= Boolean],
                                                      @annotation.unused aNotInt: NotGiven[A =:= Int],
                                                      @annotation.unused aNotLong: NotGiven[A =:= Long],
                                                      @annotation.unused aNotDouble: NotGiven[A =:= Double],
                                                      @annotation.unused aNotFloat: NotGiven[A =:= Float],
                                                     ): TraceableValue[A] =
    TraceableValue[String].contramap(_.show)
}
