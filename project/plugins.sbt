addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.17.0")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2")
addSbtPlugin("org.typelevel" % "sbt-typelevel-ci-release" % "0.7.4")
addSbtPlugin("org.typelevel" % "sbt-typelevel-settings" % "0.7.4")
addSbtPlugin("org.typelevel" % "sbt-typelevel-mergify" % "0.7.4")
addSbtPlugin("com.github.tkawachi" % "sbt-doctest" % "0.10.0")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.12.0")

libraryDependencySchemes += "com.lihaoyi" %% "geny" % VersionScheme.Always
