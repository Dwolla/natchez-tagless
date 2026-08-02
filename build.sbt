ThisBuild / tlBaseVersion := "0.2"

ThisBuild / organization := "com.dwolla"
ThisBuild / organizationName := "Dwolla"
ThisBuild / startYear := Some(2022)
ThisBuild / licenses := Seq(License.MIT)
ThisBuild / developers := List(
  tlGitHubDev("bpholt", "Brian Holt")
)

val Scala213 = "2.13.18"
ThisBuild / crossScalaVersions := Seq(Scala213, "2.12.21", "3.3.8")
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
ThisBuild / resolvers += Resolver.sonatypeCentralSnapshots

val catsVersion = "2.13.0"
val catsMtlVersion = "1.7.0"
val catsTaglessVersion = "0.16.5"
val disciplineMunitVersion = "2.0.0"
val munitVersion = "1.2.0"

lazy val `natchez-tagless-root` = tlCrossRootProject.aggregate(
  taglessCore,
  core,
  scalacache,
  raiseAspectCore,
  raiseAspectLaws,
  raiseAspectMacros,
  natchezTaglessMtl,
)

lazy val doctestSettings: Seq[Def.Setting[?]] = Seq(
  libraryDependencies ++= Seq(
    "org.scalacheck" %%% "scalacheck" % "1.19.0" % Test,
    "io.monix" %%% "newtypes-core" % "0.2.3" % Test,
  ),
  doctestOnlyCodeBlocksMode := true,
  Test / scalacOptions ~= {
    _.filterNot(_.contains("Wunused"))
  },
)

// WeaveKnot is written against cats and cats-tagless only — it mentions natchez
// nowhere — but it shipped inside the `natchez-tagless` artifact. Extracted here
// so a backend module that isn't natchez (otel4s, M16) can use it without taking
// on natchez, circe and log4cats to get it. `core` depends on this module, so the
// class remains available, at the same fully-qualified name, to everything that
// already had it.
lazy val taglessCore = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("tagless-core"))
  .settings(
    name := "tagless-core",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-core" % catsVersion,
      "org.typelevel" %%% "cats-tagless-core" % catsTaglessVersion,
      "org.scalameta" %%% "munit" % munitVersion % Test,
      "org.scalameta" %%% "munit-scalacheck" % munitVersion % Test,
    ),
    // A brand-new artifact has no previous versions to be compatible with.
    // Matches the four other unpublished modules in this build; note that
    // sbt-typelevel's `tlVersionIntroduced` is the mechanism that would keep
    // MiMa live from this module's first release onward, and all five modules
    // will need that decision made before they are first published.
    mimaPreviousArtifacts := Set.empty,
  )

lazy val core = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .in(file("core"))
  .settings(
    name := "natchez-tagless",
    libraryDependencies ++= Seq(
      "org.tpolecat" %%% "natchez-core" % "0.3.10",
      "org.tpolecat" %%% "natchez-mtl" % "0.3.10",
      "org.typelevel" %%% "cats-tagless-core" % catsTaglessVersion,
      "org.typelevel" %%% "cats-mtl" % catsMtlVersion,
      "org.typelevel" %%% "log4cats-noop" % "2.8.0",
      "io.circe" %%% "circe-core" % "0.14.16",
      "org.typelevel" %%% "scalac-compat-features" % "0.1.5",
      "org.tpolecat" %%% "natchez-testkit" % "0.3.10" % Test,
      "org.typelevel" %%% "munit-cats-effect" % "2.2.0" % Test,
      "org.typelevel" %%% "scalacheck-effect" % "2.1.0" % Test,
      "org.typelevel" %%% "scalacheck-effect-munit" % "2.1.0" % Test,
      "io.circe" %%% "circe-generic" % "0.14.16" % Test,
    ),
  )
  .jvmSettings(
    libraryDependencies ++= Seq(
      "com.dwolla" %% "dwolla-otel-natchez" % "0.2.8" % Test,
    ),
  )
  .settings(doctestSettings *)
  .dependsOn(taglessCore, buildInfoForTests % Test)

lazy val scalacache = crossProject(JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("scalacache"))
  .settings(
    name := "natchez-tagless-scalacache",
    libraryDependencies ++= Seq(
      "com.github.cb372" %%% "scalacache-core" % "1.0.0-M6",
      "io.circe" %%% "circe-generic" % "0.14.16",
    ),
    libraryDependencies ++= {
      if (scalaBinaryVersion.value.startsWith("2")) Seq("org.typelevel" %%% "cats-tagless-macros" % catsTaglessVersion)
      else Seq.empty
    },
  )
  .settings(doctestSettings *)
  .dependsOn(core)

lazy val raiseAspectCore = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("raise-aspect-core"))
  .settings(
    name := "raise-aspect-core",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-core" % catsVersion,
      "org.typelevel" %%% "cats-mtl" % catsMtlVersion,
      "org.typelevel" %%% "cats-tagless-core" % catsTaglessVersion,
      "org.scalameta" %%% "munit" % munitVersion % Test,
      "org.scalameta" %%% "munit-scalacheck" % munitVersion % Test,
    ),
    mimaPreviousArtifacts := Set.empty,
  )
  // Test-only, additive split so a `Platform.isJvm` compile-time constant
  // (see OnRaiseSpec/WeaveArrowsOnRaiseSpec) can differ between the JVM and
  // JS builds without moving raiseAspectCore to CrossType.Full. Mirrors this
  // project's existing scala-2/scala-3 source-directory convention, and the
  // `cats-kernel-laws` Platform.isJvm pattern it's modeled on.
  .jvmSettings(
    Test / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "src" / "test" / "scala-jvm",
  )
  .jsSettings(
    Test / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "src" / "test" / "scala-js",
  )

lazy val raiseAspectLaws = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("raise-aspect-laws"))
  .settings(
    name := "raise-aspect-laws",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-laws" % catsVersion,
      "org.typelevel" %%% "discipline-munit" % disciplineMunitVersion,
    ),
    // law L9 compares our derivation against upstream's on capability-free
    // algebras. On Scala 2 that lives in cats-tagless-macros; on Scala 3 it is
    // `Derive` in cats-tagless-core, which raise-aspect-core already provides.
    libraryDependencies ++= {
      if (scalaBinaryVersion.value.startsWith("2"))
        Seq("org.typelevel" %%% "cats-tagless-macros" % catsTaglessVersion % Test)
      else Seq.empty
    },
    mimaPreviousArtifacts := Set.empty,
  )
  .dependsOn(raiseAspectCore % "compile->compile;test->test")

lazy val raiseAspectMacros = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("raise-aspect-macros"))
  .settings(
    name := "raise-aspect-macros",
    libraryDependencies ++= {
      if (scalaBinaryVersion.value.startsWith("2"))
        Seq("scala-compiler", "scala-reflect").map("org.scala-lang" % _ % scalaVersion.value % Provided)
      else Seq.empty
    },
    // A macro bundle manipulates trees the compiler cannot see through, which
    // provokes spurious unused warnings. Upstream cats-tagless drops the same
    // options in its macros module.
    scalacOptions ~= {
      _.filterNot(o => o.startsWith("-Wunused") || o.startsWith("-Ywarn-unused"))
    },
    mimaPreviousArtifacts := Set.empty,
  )
  .dependsOn(raiseAspectCore % "compile->compile;test->test", raiseAspectLaws % "test->test")

lazy val natchezTaglessMtl = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("natchez-tagless-mtl"))
  .settings(
    name := "natchez-tagless-mtl",
    libraryDependencies ++= Seq(
      "org.scalameta" %%% "munit" % munitVersion % Test,
      "org.typelevel" %%% "munit-cats-effect" % "2.2.0" % Test,
    ),
    mimaPreviousArtifacts := Set.empty,
  )
  .settings(doctestSettings *)
  // test->test reuses core's InMemorySuite harness (Kleisli/IOLocal Trace wiring)
  // for the integration test, rather than re-deriving it.
  .dependsOn(core % "compile->compile;test->test", raiseAspectCore, raiseAspectMacros)

// sbt-buildinfo can't be enabled only for the test scope, so this is the workaround to use it only in tests
lazy val buildInfoForTests = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .in(file("buildInfoForTests"))
  .settings(
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "com.dwolla.buildinfo",
  )
  .enablePlugins(NoPublishPlugin, BuildInfoPlugin)
