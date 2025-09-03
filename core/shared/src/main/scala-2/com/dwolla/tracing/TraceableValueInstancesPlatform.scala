package com.dwolla.tracing

import io.circe.Encoder
import io.circe.syntax.*
import natchez.TraceableValue

private[tracing] trait TraceableValueInstancesPlatform extends LowestPriorityTraceableValueInstances {
  implicit def nonPrimitiveTraceValueViaJson[A: Encoder](implicit @annotation.unused aNotString: A =:!= String,
                                                         @annotation.unused aNotBoolean: A =:!= Boolean,
                                                         @annotation.unused aNotInt: A =:!= Int,
                                                         @annotation.unused aNotLong: A =:!= Long,
                                                         @annotation.unused aNotDouble: A =:!= Double,
                                                         @annotation.unused aNotFloat: A =:!= Float,
                                                        ): TraceableValue[A] =
    TraceableValue[String].contramap(_.asJson.noSpaces)

  private def unexpected: Nothing = sys.error("Unexpected invocation")

  sealed trait =:!=[A, B] extends Serializable

  implicit def neq[A, B]: A =:!= B = new =:!=[A, B] {}
  implicit def neqAmbig1[A]: A =:!= A = unexpected
  implicit def neqAmbig2[A]: A =:!= A = unexpected
}
