// https://typelevel.org/sbt-typelevel/faq.html#what-is-a-base-version-anyway
ThisBuild / tlBaseVersion := "0.2" // your current series x.y

ThisBuild / organization := "com.dwolla"
ThisBuild / organizationName := "Dwolla"
ThisBuild / startYear := Some(2022)
ThisBuild / licenses := Seq(License.MIT)
ThisBuild / developers := List(
  tlGitHubDev("bpholt", "Brian Holt")
)
ThisBuild / tlSonatypeUseLegacyHost := true

val Scala213 = "2.13.11"
ThisBuild / crossScalaVersions := Seq(Scala213, "2.12.18")
ThisBuild / scalaVersion := Scala213 // the default Scala
ThisBuild / githubWorkflowScalaVersions := Seq("2.13", "2.12")
ThisBuild / tlJdkRelease := Some(8)
ThisBuild / libraryDependencySchemes += "io.circe" %% "circe-core" % "always"
ThisBuild / tlCiReleaseBranches := Seq("main")
ThisBuild / mergifyStewardConfig ~= { _.map(_.copy(
  author = "dwolla-oss-scala-steward[bot]",
  mergeMinors = true,
))}
ThisBuild / resolvers ++= Resolver.sonatypeOssRepos("snapshots")

lazy val `natchez-tagless-root` = tlCrossRootProject.aggregate(
  core,
  scalacache,
)

lazy val doctestSettings: Seq[Def.Setting[_]] = Seq(
  libraryDependencies ++= Seq(
    "org.scalacheck" %%% "scalacheck" % "1.17.0" % Test,
    "io.monix" %%% "newtypes-core" % "0.2.3" % Test,
  ),
  doctestOnlyCodeBlocksMode := true,
  Test / scalacOptions ~= {
    _.filterNot(_.contains("Wunused"))
  },
)

lazy val core = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .in(file("core"))
  .settings(
    name := "natchez-tagless",
    libraryDependencies ++= Seq(
      "org.tpolecat" %%% "natchez-core" % "0.3.3",
      "org.tpolecat" %%% "natchez-mtl" % "0.3.3",
      "org.tpolecat" %%% "natchez-testkit" % "0.3.3" % Test,
      "org.typelevel" %%% "cats-tagless-core" % "0.15.0",
      "org.typelevel" %%% "cats-mtl" % "1.3.1",
      "io.circe" %%% "circe-core" % "0.14.5",
      "org.typelevel" %%% "cats-tagless-macros" % "0.15.0" % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.0.0-M3" % Test,
      "org.typelevel" %% "scalacheck-effect" % "2.0.0-M2" % Test,
      "org.typelevel" %% "scalacheck-effect-munit" % "2.0.0-M2" % Test,
    ),
  )
  .jvmSettings(
    libraryDependencies ++= Seq(
      "com.dwolla" %% "dwolla-otel-natchez" % "0.2.1" % Test,
    ),
  )
  .settings(doctestSettings: _*)

lazy val scalacache = crossProject(JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("scalacache"))
  .settings(
    name := "natchez-tagless-scalacache",
    libraryDependencies ++= Seq(
      "com.github.cb372" %%% "scalacache-core" % "1.0.0-M6",
      "io.circe" %%% "circe-generic" % "0.14.5",
      "org.typelevel" %%% "cats-tagless-macros" % "0.15.0",
    ),
  )
  .settings(doctestSettings: _*)
  .dependsOn(core)
