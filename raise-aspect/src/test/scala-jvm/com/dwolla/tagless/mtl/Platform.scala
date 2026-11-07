package com.dwolla.tagless.mtl

/** Distinguishes JVM from Scala.js and Scala Native at compile time, so
  * tests that need JVM-only APIs (e.g. `java.io.ObjectOutputStream`) can
  * guard themselves with a constant-folded branch rather than referencing
  * those symbols unconditionally. See `cats-kernel-laws`'
  * `cats.platform.Platform` for the pattern this follows.
  */
private[mtl] object Platform {
  // `final val` makes scalac constant-fold any use of this value, so the
  // Scala.js and Scala Native linkers never see the branch that's eliminated
  // on those platforms.
  final val isJvm = true
}
