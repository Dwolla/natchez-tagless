package com.dwolla.tagless

/** Scala 2 has no `enum`, so no value is one. */
private[tagless] object ScalaEnum {
  val isEnum: Any => Boolean = _ => false
}
