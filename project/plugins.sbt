addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.19.0")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2")
addSbtPlugin("org.typelevel" % "sbt-typelevel-ci-release" % "0.8.0")
addSbtPlugin("org.typelevel" % "sbt-typelevel-settings" % "0.8.0")
addSbtPlugin("org.typelevel" % "sbt-typelevel-mergify" % "0.8.0")
addSbtPlugin("io.github.sbt-doctest" % "sbt-doctest" % "0.11.3")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.13.1")

libraryDependencySchemes += "com.lihaoyi" %% "geny" % VersionScheme.Always
