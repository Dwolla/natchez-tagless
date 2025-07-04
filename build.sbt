ThisBuild / tlBaseVersion := "0.2"

ThisBuild / organization := "com.dwolla"
ThisBuild / organizationName := "Dwolla"
ThisBuild / startYear := Some(2022)
ThisBuild / licenses := Seq(License.MIT)
ThisBuild / developers := List(
  tlGitHubDev("bpholt", "Brian Holt")
)

val Scala213 = "2.13.16"
ThisBuild / crossScalaVersions := Seq(Scala213, "2.12.20", "3.3.6")
ThisBuild / scalaVersion := Scala213 // the default Scala
ThisBuild / githubWorkflowScalaVersions := Seq("2.13", "2.12", "3")
ThisBuild / tlVersionIntroduced := Map("3" -> "0.2.4")
ThisBuild / tlJdkRelease := Some(8)
ThisBuild / libraryDependencySchemes += "io.circe" %% "circe-core" % "always"
ThisBuild / tlCiReleaseBranches := Seq("main")
ThisBuild / mergifyStewardConfig ~= { _.map {
  _.withAuthor("dwolla-oss-scala-steward[bot]")
    .withMergeMinors(true)
}}
ThisBuild / resolvers ++= Resolver.sonatypeOssRepos("snapshots")

lazy val `natchez-tagless-root` = tlCrossRootProject.aggregate(
  core,
  scalacache,
)

lazy val doctestSettings: Seq[Def.Setting[?]] = Seq(
  libraryDependencies ++= Seq(
    "org.scalacheck" %%% "scalacheck" % "1.18.1" % Test,
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
      "org.tpolecat" %%% "natchez-core" % "0.3.8",
      "org.tpolecat" %%% "natchez-mtl" % "0.3.8",
      "org.typelevel" %%% "cats-tagless-core" % "0.16.3",
      "org.typelevel" %%% "cats-mtl" % "1.5.0",
      "org.typelevel" %%% "log4cats-noop" % "2.7.1",
      "io.circe" %%% "circe-core" % "0.14.14",
      "org.tpolecat" %%% "natchez-testkit" % "0.3.8" % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.1.0" % Test,
      "org.typelevel" %% "scalacheck-effect" % "2.0.0-M2" % Test,
      "org.typelevel" %% "scalacheck-effect-munit" % "2.0.0-M2" % Test,
    ),
  )
  .jvmSettings(
    libraryDependencies ++= Seq(
      "com.dwolla" %% "dwolla-otel-natchez" % "0.2.6" % Test,
    ),
  )
  .settings(doctestSettings *)
  .dependsOn(buildInfoForTests % Test)

lazy val scalacache = crossProject(JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("scalacache"))
  .settings(
    name := "natchez-tagless-scalacache",
    libraryDependencies ++= Seq(
      "com.github.cb372" %%% "scalacache-core" % "1.0.0-M6",
      "io.circe" %%% "circe-generic" % "0.14.14",
    ),
    libraryDependencies ++= {
      if (scalaBinaryVersion.value.startsWith("2")) Seq("org.typelevel" %%% "cats-tagless-macros" % "0.16.3")
      else Seq.empty
    },
  )
  .settings(doctestSettings *)
  .dependsOn(core)

// sbt-buildinfo can't be enabled only for the test scope, so this is the workaround to use it only in tests
lazy val buildInfoForTests = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .in(file("buildInfoForTests"))
  .settings(
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "com.dwolla.buildinfo",
  )
  .enablePlugins(NoPublishPlugin, BuildInfoPlugin)
