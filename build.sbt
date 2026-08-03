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
val otel4sVersion = "1.0.1"

lazy val `natchez-tagless-root` = tlCrossRootProject.aggregate(
  taglessCore,
  core,
  scalacache,
  raiseAspectCore,
  raiseAspectLaws,
  raiseAspectMacros,
  natchezTaglessMtl,
  otel4sTagless,
)

// otel4s publishes no _2.12 artifacts, so `otel4sTagless` is empty on 2.12.
// See the comment on that project for why the module is emptied rather than
// dropped from `crossScalaVersions`.
lazy val isOtel4sScalaVersion: Def.Initialize[Boolean] = Def.setting {
  scalaBinaryVersion.value != "2.12"
}

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
// nowhere — but it lived in `core`, whose artifact is `natchez-tagless`. Extracted
// here so a backend module that isn't natchez (otel4s, M16) can use it without
// taking on natchez, circe and log4cats to get it. `core` still depends on this
// module, so `com.dwolla.tagless.WeaveKnot` keeps its fully-qualified name and
// stays reachable from `natchez-tagless`. That edge is a design choice, not a
// compatibility rescue: WeaveKnot was added after v0.2.6, appears in none of the
// published artifacts, and has no caller in this repository — so nothing in the
// test suite would notice if the edge were dropped. M16's otel4s module will be
// its first real user. See
// docs/plans/raise-aspect/28-milestone-M15-tagless-core-module.md.
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
    // A brand-new artifact has no previous versions to be compatible with —
    // but unlike the other five `Set.empty` modules below, this one is a
    // regression, not a fresh gap: WeaveKnot lived in `core`, which has MiMa
    // live, so it was on track to gain coverage automatically once `core`'s
    // next release shipped it. Moving it here under `Set.empty` took that
    // protection away rather than never granting it.
    //
    // `Set.empty` is a live hazard, not a permanent no-op: nothing suppresses
    // publishing, so this module WILL be published with a real API at the
    // next release, and MiMa will not catch a breaking change after that. The
    // fix is `tlVersionIntroduced := Map(...)` in place of `Set.empty`, which
    // keeps MiMa live from the module's first release onward — one decision
    // covering all six `Set.empty` modules, needed before the next publish,
    // not made here. See "Anything a later milestone needs" in
    // docs/plans/raise-aspect/28-milestone-M15-tagless-core-module.md.
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
    // `Set.empty` is a live hazard, not a permanent no-op: nothing suppresses
    // publishing, so this module WILL be published with a real API at the
    // next release, and MiMa will not catch a breaking change after that. The
    // fix is `tlVersionIntroduced := Map(...)` in place of `Set.empty`, which
    // keeps MiMa live from the module's first release onward — one decision
    // covering all six `Set.empty` modules, needed before the next publish,
    // not made here. See "Anything a later milestone needs" in
    // docs/plans/raise-aspect/28-milestone-M15-tagless-core-module.md.
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
    // `Set.empty` is a live hazard, not a permanent no-op: nothing suppresses
    // publishing, so this module WILL be published with a real API at the
    // next release, and MiMa will not catch a breaking change after that. The
    // fix is `tlVersionIntroduced := Map(...)` in place of `Set.empty`, which
    // keeps MiMa live from the module's first release onward — one decision
    // covering all six `Set.empty` modules, needed before the next publish,
    // not made here. See "Anything a later milestone needs" in
    // docs/plans/raise-aspect/28-milestone-M15-tagless-core-module.md.
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
    // `Set.empty` is a live hazard, not a permanent no-op: nothing suppresses
    // publishing, so this module WILL be published with a real API at the
    // next release, and MiMa will not catch a breaking change after that. The
    // fix is `tlVersionIntroduced := Map(...)` in place of `Set.empty`, which
    // keeps MiMa live from the module's first release onward — one decision
    // covering all six `Set.empty` modules, needed before the next publish,
    // not made here. See "Anything a later milestone needs" in
    // docs/plans/raise-aspect/28-milestone-M15-tagless-core-module.md.
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
    // `Set.empty` is a live hazard, not a permanent no-op: nothing suppresses
    // publishing, so this module WILL be published with a real API at the
    // next release, and MiMa will not catch a breaking change after that. The
    // fix is `tlVersionIntroduced := Map(...)` in place of `Set.empty`, which
    // keeps MiMa live from the module's first release onward — one decision
    // covering all six `Set.empty` modules, needed before the next publish,
    // not made here. See "Anything a later milestone needs" in
    // docs/plans/raise-aspect/28-milestone-M15-tagless-core-module.md.
    mimaPreviousArtifacts := Set.empty,
  )
  .settings(doctestSettings *)
  // test->test reuses core's InMemorySuite harness (Kleisli/IOLocal Trace wiring)
  // for the integration test, rather than re-deriving it.
  .dependsOn(core % "compile->compile;test->test", raiseAspectCore, raiseAspectMacros)

// otel4s versions of core's three tracing interpreters. Deliberately *not* a
// natchez module: it depends on otel4s-core-trace and taglessCore and nothing
// else of ours, so an application on otel4s never pulls natchez in to get it.
//
// The module has no content on 2.12, and that is arranged by emptying it
// rather than by narrowing `crossScalaVersions`. otel4s has never published a
// _2.12 artifact at any version (otel4s's own build.sbt sets
// crossScalaVersions := Seq("2.13.18", "3.3.8"), and Maven Central 404s for
// otel4s-core-trace_2.12), so on 2.12 the otel4s dependency is dropped, both
// source directories are emptied, and publishing is skipped: the project
// cross-builds along with everything else, but it compiles nothing, tests
// nothing and ships nothing.
//
// `crossScalaVersions := Seq(Scala213, "3.3.8")` is the obvious alternative and
// it does not work. sbt's `++` excludes such a project from the version switch
// but does *not* drop it from the root aggregate — sbt.Cross.switchScalaVersion
// only touches the included projects — so `natchez-tagless-rootJVM/test` under
// `++ 2.12` still runs `otel4sTaglessJVM/update` while this project sits at
// 2.13 and taglessCore has moved to 2.12, and it dies resolving
// `tagless-core_2.13`. The one filter sbt does have, in
// Cross.switchCommandImpl, is defeated because the root aggregate is itself
// switched and re-aggregates everything under it. sbt-typelevel's
// `tlSkipIrrelevantScalas` has been deprecated and inert since 0.5.0. Making
// the narrow form work would mean giving rootJVM/rootJS
// `crossScalaVersions := Nil` *and* rewriting every generated CI sbt step into
// the attached `++ <version> <task>` form — a build-wide change fighting
// sbt-typelevel's own CrossRootProject. This keeps the blast radius inside the
// module.
//
// So `crossScalaVersions` still lists 2.12, but nothing observable claims 2.12
// support: `publish / skip` means no `otel4s-tagless_2.12` artifact is ever
// produced, and the published artifact list is the only thing users can see.
lazy val otel4sTagless = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("otel4s-tagless"))
  .settings(
    name := "otel4s-tagless",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-core" % catsVersion,
      "org.typelevel" %%% "cats-tagless-core" % catsTaglessVersion,
      "org.scalameta" %%% "munit" % munitVersion % Test,
    ),
    libraryDependencies ++= {
      // core-trace dependsOn core-common, which carries AnyValue, Attribute,
      // AttributeKey and Attributes, so this one coordinate brings both the
      // tracing API and the attribute model. otel4s-core is an umbrella that
      // would also drag in logs+metrics.
      if (isOtel4sScalaVersion.value) Seq("org.typelevel" %%% "otel4s-core-trace" % otel4sVersion)
      else Seq.empty
    },
    Compile / unmanagedSourceDirectories := {
      if (isOtel4sScalaVersion.value) (Compile / unmanagedSourceDirectories).value else Seq.empty
    },
    Test / unmanagedSourceDirectories := {
      if (isOtel4sScalaVersion.value) (Test / unmanagedSourceDirectories).value else Seq.empty
    },
    publish / skip := !isOtel4sScalaVersion.value,
    // `Set.empty` is a live hazard, not a permanent no-op: nothing suppresses
    // publishing, so this module WILL be published with a real API at the
    // next release, and MiMa will not catch a breaking change after that. The
    // fix is `tlVersionIntroduced := Map(...)` in place of `Set.empty`, which
    // keeps MiMa live from the module's first release onward — one decision
    // covering all six `Set.empty` modules, needed before the next publish,
    // not made here. See "Anything a later milestone needs" in
    // docs/plans/raise-aspect/28-milestone-M15-tagless-core-module.md.
    mimaPreviousArtifacts := Set.empty,
  )
  // Span *content* can only be asserted with a testkit: every otel4s span type
  // is sealed and its Unsealed variant is private[otel4s], so a recording
  // Tracer cannot be hand-rolled. The cross-platform testkit
  // (otel4s-sdk-trace-testkit) has not been released at 1.0.x — it stops at
  // 0.19.0 — so at otel4s 1.0.1 the only option is the JVM one. Cross-platform
  // coverage lives in TracerTransparencySpec, which needs no testkit.
  //
  // `%%` is correct here and only here: .jvmSettings has no JS artifact to
  // resolve. Everything in the shared settings block above uses `%%%`.
  //
  // Both the dependencies and the source directory are gated on
  // `isOtel4sScalaVersion` for the same reason the shared block is: otel4s
  // publishes no _2.12 artifact, so an ungated coordinate here 404s at
  // `update` under `++ 2.12` even though this module compiles nothing there.
  // The source directory needs the gate independently — `.jvmSettings` are
  // appended after the shared block, so an unconditional `+=` would put
  // scala-jvm back onto the 2.12 source path that the shared `:=` just
  // emptied.
  .jvmSettings(
    libraryDependencies ++= {
      if (isOtel4sScalaVersion.value)
        Seq(
          "org.typelevel" %% "otel4s-oteljava-trace-testkit" % otel4sVersion % Test,
          // AttributeConverters, for decoding a span's Java Attributes back
          // into the otel4s model. The testkit pulls it in transitively and
          // exposes its types in its own signatures, but SpanContentSpec names
          // it directly, so it is declared directly.
          "org.typelevel" %% "otel4s-oteljava-common" % otel4sVersion % Test,
          "org.typelevel" %% "munit-cats-effect" % "2.2.0" % Test,
        )
      else Seq.empty
    },
    Test / unmanagedSourceDirectories ++= {
      if (isOtel4sScalaVersion.value) Seq(baseDirectory.value.getParentFile / "src" / "test" / "scala-jvm")
      else Seq.empty
    },
  )
  .settings(doctestSettings *)
  .dependsOn(taglessCore)

// sbt-buildinfo can't be enabled only for the test scope, so this is the workaround to use it only in tests
lazy val buildInfoForTests = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .in(file("buildInfoForTests"))
  .settings(
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "com.dwolla.buildinfo",
  )
  .enablePlugins(NoPublishPlugin, BuildInfoPlugin)
