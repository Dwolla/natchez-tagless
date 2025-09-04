package com.dwolla.tracing

import io.circe.Encoder
import io.circe.syntax.*
import natchez.TraceableValue
import scala.util.NotGiven

private[tracing] trait TraceableValueInstancesPlatform extends LowestPriorityTraceableValueInstances:
  implicit def nonPrimitiveTraceValueViaJson[A](using Encoder[A],
                                                NotGiven[A =:= String],
                                                NotGiven[A =:= Boolean],
                                                NotGiven[A =:= Int],
                                                NotGiven[A =:= Long],
                                                NotGiven[A =:= Double],
                                                NotGiven[A =:= Float],
                                                ): TraceableValue[A] =
    TraceableValue[String].contramap(_.asJson.noSpaces)
