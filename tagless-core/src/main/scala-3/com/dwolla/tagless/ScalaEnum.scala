package com.dwolla.tagless

/** Whether a value is a case of a Scala 3 `enum`. */
private[tagless] object ScalaEnum {
  val isEnum: Any => Boolean = _.isInstanceOf[scala.reflect.Enum]
}
