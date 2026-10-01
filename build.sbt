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

// Fail CI on compiler warnings. sbt-typelevel's full TypelevelPlugin sets this
// automatically; this build enables only its ci-release and settings plugins,
// so it has to be set explicitly.
ThisBuild / tlFatalWarnings := githubIsWorkflowBuild.value

// `sbt ciLocal`: CI's build job, run locally, with fatal warnings on, as in
// CI, for every Scala version and root project in the CI matrix. The steps
// are read from `githubWorkflowBuild`, the same setting that generates
// .github/workflows/ci.yml, so the commands stay close to CI's — but the
// alias skips `githubWorkflowBuildPreamble`/`Postamble` and any step that
// isn't a `WorkflowStep.Sbt`, and `stepsFor` below recognizes only the two
// exact command lists it filters on, so it's not a byte-for-byte guarantee.
// One deliberate difference: Scala.js tests are linked but not run, because
// running them needs Node, which CI provides and developer machines may not.
//
// An alias rather than a task because it has to switch Scala versions (`++`),
// which only commands can do.
Global / tlCommandAliases += {
  val scalaVersions = (ThisBuild / githubWorkflowScalaVersions).value.toList
  val rootProjects = (ThisBuild / githubWorkflowBuildMatrixAdditions).value.getOrElse("project", Nil)
  val buildSteps = (ThisBuild / githubWorkflowBuild).value.toList.collect { case step: WorkflowStep.Sbt => step.commands }

  // `Test/scalaJSLinkerResult` only resolves on the JS root (CI itself gates
  // that step on `matrix.project == 'natchez-tagless-rootJS'`, a condition
  // lost once WorkflowStep.Sbt is collapsed to its bare commands above), and
  // `test` on the JS root needs Node, which local machines may not have.
  def stepsFor(project: String): List[String] =
    buildSteps.filterNot { commands =>
      (commands == List("Test/scalaJSLinkerResult") && !project.endsWith("JS")) ||
        (commands == List("test") && project.endsWith("JS"))
    }.flatten

  "ciLocal" -> (
    List("githubWorkflowCheck", "set ThisBuild / tlFatalWarnings := true") ++
      (for {
        scala <- scalaVersions
        project <- rootProjects
        command <- s"project $project" :: s"++ $scala" :: stepsFor(project)
      } yield command) ++
      List("project /")
  )
}

val catsVersion = "2.13.0"
val catsEffectVersion = "3.7.1"
val catsMtlVersion = "1.7.0"
val catsTaglessVersion = "0.16.5"
// Must track discipline-munit's discipline-core dependency; bump them together.
val disciplineCoreVersion = "1.7.0"
val disciplineMunitVersion = "2.0.0"
val munitVersion = "1.3.1"
val otel4sVersion = "1.1.0"

lazy val `natchez-tagless-root` = tlCrossRootProject.aggregate(
  taglessCore,
  core,
  scalacache,
  raiseAspect,
  raiseAspectLaws,
  natchezTaglessMtl,
  otel4sTagless,
  otel4sTaglessMtl,
  otel4sTaglessMetrics,
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
      "org.typelevel" %%% "cats-mtl" % catsMtlVersion % Test,
      "org.typelevel" %%% "cats-effect" % catsEffectVersion % Test,
      "org.typelevel" %%% "munit-cats-effect" % "2.2.0" % Test,
    ),
    tlVersionIntroduced := Map("2.12" -> "0.2.7", "2.13" -> "0.2.7", "3" -> "0.2.7"),
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

lazy val raiseAspect = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("raise-aspect"))
  .settings(
    name := "raise-aspect",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-core" % catsVersion,
      "org.typelevel" %%% "cats-mtl" % catsMtlVersion,
      "org.typelevel" %%% "cats-tagless-core" % catsTaglessVersion,
      "org.typelevel" %%% "cats-effect" % catsEffectVersion % Test,
      "org.typelevel" %%% "cats-effect-testkit" % catsEffectVersion % Test,
      "org.typelevel" %%% "munit-cats-effect" % "2.2.0" % Test,
      "org.scalameta" %%% "munit" % munitVersion % Test,
      "org.scalameta" %%% "munit-scalacheck" % munitVersion % Test,
    ),
    // The Scala 2 def-macro implementation needs the compiler APIs at compile
    // time only; `Provided` keeps them off downstream classpaths.
    libraryDependencies ++= {
      if (scalaBinaryVersion.value.startsWith("2"))
        Seq("scala-compiler", "scala-reflect").map("org.scala-lang" % _ % scalaVersion.value % Provided)
      else Seq.empty
    },
    tlVersionIntroduced := Map("2.12" -> "0.2.7", "2.13" -> "0.2.7", "3" -> "0.2.7"),
  )
  // Test-only, additive split so a `Platform.isJvm` compile-time constant
  // (see OnRaiseSpec/ObservingCapabilitySpec) can differ between the JVM and
  // JS builds without moving raiseAspect to CrossType.Full. Mirrors this
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
      // Main code needs only `org.typelevel.discipline.Laws`; users pick their
      // own test framework.
      "org.typelevel" %%% "discipline-core" % disciplineCoreVersion,
      "org.typelevel" %%% "discipline-munit" % disciplineMunitVersion % Test,
      "org.typelevel" %%% "cats-effect" % catsEffectVersion % Test,
      "org.typelevel" %%% "cats-effect-testkit" % catsEffectVersion % Test,
      "org.typelevel" %%% "munit-cats-effect" % "2.2.0" % Test,
    ),
    // law L9 compares our derivation against upstream's on capability-free
    // algebras. On Scala 2 that lives in cats-tagless-macros; on Scala 3 it is
    // `Derive` in cats-tagless-core, which raise-aspect already provides.
    libraryDependencies ++= {
      if (scalaBinaryVersion.value.startsWith("2"))
        Seq("org.typelevel" %%% "cats-tagless-macros" % catsTaglessVersion % Test)
      else Seq.empty
    },
    tlVersionIntroduced := Map("2.12" -> "0.2.7", "2.13" -> "0.2.7", "3" -> "0.2.7"),
  )
  .dependsOn(raiseAspect % "compile->compile;test->test")

lazy val natchezTaglessMtl = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("natchez-tagless-mtl"))
  .settings(
    name := "natchez-tagless-mtl",
    libraryDependencies ++= Seq(
      "org.scalameta" %%% "munit" % munitVersion % Test,
      "org.typelevel" %%% "munit-cats-effect" % "2.2.0" % Test,
    ),
    tlVersionIntroduced := Map("2.12" -> "0.2.7", "2.13" -> "0.2.7", "3" -> "0.2.7"),
  )
  .settings(doctestSettings *)
  // test->test reuses core's InMemorySuite harness (Kleisli/IOLocal Trace wiring)
  // for the integration test, rather than re-deriving it.
  .dependsOn(core % "compile->compile;test->test", raiseAspect)

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
      "io.circe" %%% "circe-core" % "0.14.16",
      "org.scalameta" %%% "munit" % munitVersion % Test,
      "org.scalameta" %%% "munit-scalacheck" % munitVersion % Test,
      "org.typelevel" %%% "munit-cats-effect" % "2.2.0" % Test,
      "org.typelevel" %%% "cats-laws" % catsVersion % Test,
      "org.typelevel" %%% "discipline-munit" % disciplineMunitVersion % Test,
      "io.circe" %%% "circe-testing" % "0.14.16" % Test,
    ),
    libraryDependencies ++= {
      // core-trace dependsOn core-common, which carries AnyValue, Attribute,
      // AttributeKey and Attributes, so this one coordinate brings both the
      // tracing API and the attribute model. otel4s-core is an umbrella that
      // would also drag in logs+metrics.
      if (isOtel4sScalaVersion.value)
        Seq(
          "org.typelevel" %%% "otel4s-core-trace" % otel4sVersion,
          // stable semconv (depends only on otel4s-core-common): code.function.name
          "org.typelevel" %%% "otel4s-semconv" % otel4sVersion,
        )
      else Seq.empty
    },
    Compile / unmanagedSourceDirectories := {
      if (isOtel4sScalaVersion.value) (Compile / unmanagedSourceDirectories).value else Seq.empty
    },
    Test / unmanagedSourceDirectories := {
      if (isOtel4sScalaVersion.value) (Test / unmanagedSourceDirectories).value else Seq.empty
    },
    publish / skip := !isOtel4sScalaVersion.value,
    // sbt-typelevel-mima decides whether to check previous artifacts from
    // `publishArtifact`, not `publish / skip`; without this, 2.12 would look
    // for `_2.12` artifacts that were never published once a release is tagged.
    publishArtifact := isOtel4sScalaVersion.value,
    tlVersionIntroduced := Map("2.12" -> "0.2.7", "2.13" -> "0.2.7", "3" -> "0.2.7"),
  )
  // Span *content* can only be asserted with a testkit: every otel4s span type
  // is sealed and its Unsealed variant is private[otel4s], so a recording
  // Tracer cannot be hand-rolled. A cross-platform testkit exists
  // (otel4s-sdk-trace-testkit; otel4s-sdk is pre-1.0 and versioned separately,
  // and 0.19.4 is built against otel4s-core-trace 1.1.0), but using it would add
  // an otel4s-sdk backend, so these modules assert span content with the JVM
  // oteljava testkit instead; adopting the sdk testkit is a possible follow-up.
  // Cross-platform coverage lives in TracerTransparencySpec, which needs no
  // testkit.
  //
  // `%%` is correct for both coordinates below: neither `otel4s-oteljava-*`
  // artifact is published for JS, so `.jvmSettings` is the only place they can
  // resolve. `munit-cats-effect`, used cross-platform (`TracerTransparencySpec`,
  // `WeaveAttributesOpsSpec`), is declared `%%%` in the shared block above
  // instead.
  //
  // Both the dependencies and the source directory are gated on
  // `isOtel4sScalaVersion` for the same reason the shared block is: otel4s
  // publishes no _2.12 artifact, so an ungated coordinate here 404s at
  // `update` under `++ 2.12` even though this module compiles nothing there.
  // The source directory needs the gate independently — `.jvmSettings` are
  // appended after the shared block, so an unconditional `+=` would put
  // scala-jvm back onto the 2.12 source path that the shared `:=` just
  // emptied. The second test-source directory added further below, on
  // scala-3-jvm, gates on `scalaBinaryVersion.value == "3"` instead; that
  // already excludes 2.12, so it needs no `isOtel4sScalaVersion` of its own.
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
        )
      else Seq.empty
    },
    Test / unmanagedSourceDirectories ++= {
      if (isOtel4sScalaVersion.value) Seq(baseDirectory.value.getParentFile / "src" / "test" / "scala-jvm")
      else Seq.empty
    },
    // Test sources that need *both* axes: `derives AnyValueAspect` is Scala 3
    // only, and asserting the span it produces needs the JVM-only oteljava
    // testkit. Neither `src/test/scala-3` (also compiled for JS, which has no
    // testkit) nor `src/test/scala-jvm` (also compiled on 2.13, where
    // `derives` is a syntax error) can hold such a file alone. The `== "3"`
    // test already excludes 2.12, so no separate `isOtel4sScalaVersion` gate
    // is needed here — mirrors the identical addition on `otel4sTaglessMtl`
    // below.
    Test / unmanagedSourceDirectories ++= {
      if (scalaBinaryVersion.value == "3")
        Seq(baseDirectory.value.getParentFile / "src" / "test" / "scala-3-jvm")
      else Seq.empty
    },
  )
  .settings(doctestSettings *)
  .dependsOn(taglessCore)

// The mtl counterpart of otel4s-tagless: traces algebras whose methods take
// cats.mtl.Raise capability parameters, which plain Aspect cannot weave.
//
// 2.12 containment is identical to otel4sTagless's, for the identical reason —
// see that project's comment above for why this is done by emptying the module
// rather than by narrowing crossScalaVersions. All six `isOtel4sScalaVersion`
// gates are required: the otel4s-core-trace coordinate, the Compile and Test
// source directories, `publish / skip`, and — separately, because .jvmSettings
// are appended after the shared `:=` that empties the list — the .jvmSettings
// testkit coordinates and the .jvmSettings scala-jvm test-source directory.
// The seventh gate below, on scala-3-jvm, tests `== "3"` instead; that already
// excludes 2.12, so it needs no `isOtel4sScalaVersion` of its own.
lazy val otel4sTaglessMtl = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("otel4s-tagless-mtl"))
  .settings(
    name := "otel4s-tagless-mtl",
    libraryDependencies ++= Seq(
      "org.scalameta" %%% "munit" % munitVersion % Test,
      "org.typelevel" %%% "munit-cats-effect" % "2.2.0" % Test,
    ),
    libraryDependencies ++= {
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
    // sbt-typelevel-mima decides whether to check previous artifacts from
    // `publishArtifact`, not `publish / skip`; without this, 2.12 would look
    // for `_2.12` artifacts that were never published once a release is tagged.
    publishArtifact := isOtel4sScalaVersion.value,
    tlVersionIntroduced := Map("2.12" -> "0.2.7", "2.13" -> "0.2.7", "3" -> "0.2.7"),
  )
  .jvmSettings(
    libraryDependencies ++= {
      if (isOtel4sScalaVersion.value)
        Seq(
          "org.typelevel" %% "otel4s-oteljava-trace-testkit" % otel4sVersion % Test,
          "org.typelevel" %% "otel4s-oteljava-common" % otel4sVersion % Test,
        )
      else Seq.empty
    },
    Test / unmanagedSourceDirectories ++= {
      if (isOtel4sScalaVersion.value) Seq(baseDirectory.value.getParentFile / "src" / "test" / "scala-jvm")
      else Seq.empty
    },
    // Test sources that need *both* axes: `derives AnyValueRaiseAspect` is
    // Scala 3 only, and asserting the span it produces needs the JVM-only
    // oteljava testkit. Neither `src/test/scala-3` (also compiled for JS,
    // which has no testkit) nor `src/test/scala-jvm` (also compiled on 2.13,
    // where `derives` is a syntax error) can hold such a file alone. The `== "3"`
    // test already excludes 2.12, so no separate `isOtel4sScalaVersion` gate is
    // needed — but this is still a `.jvmSettings` source-directory addition and
    // so carries the same appended-after-the-shared-`:=` hazard the comment
    // above describes; that is why it is guarded rather than unconditional.
    Test / unmanagedSourceDirectories ++= {
      if (scalaBinaryVersion.value == "3")
        Seq(baseDirectory.value.getParentFile / "src" / "test" / "scala-3-jvm")
      else Seq.empty
    },
  )
  .settings(doctestSettings *)
  .dependsOn(otel4sTagless, raiseAspect)

// Duration metrics for any cats-tagless `Instrument` algebra, recorded through
// an otel4s `Meter`. A sibling of otel4sTagless rather than part of it, so an
// application that only traces never pulls in otel4s-core-metrics and one that
// only measures never pulls in otel4s-core-trace. Main scope is
// otel4s-core-metrics plus the stable otel4s-semconv (which depends only on
// otel4s-core-common): no backend, no experimental semconv, and of our modules
// only tagless-core (cats + cats-tagless-core).
//
// 2.12 is contained exactly as in otel4sTagless, for the identical reason — see
// the comment on that project. Every `isOtel4sScalaVersion` gate below is
// required, as there.
lazy val otel4sTaglessMetrics = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("otel4s-tagless-metrics"))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    name := "otel4s-tagless-metrics",
    // The library names its own instrumentation scope and version (see
    // CallDuration.meter); BuildInfo supplies the version and isn't published API.
    buildInfoKeys := Seq[BuildInfoKey](version),
    buildInfoPackage := "com.dwolla.metrics.otel4s",
    buildInfoOptions += BuildInfoOption.PackagePrivate,
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-core" % catsVersion,
      "org.typelevel" %%% "cats-tagless-core" % catsTaglessVersion,
      "org.scalameta" %%% "munit" % munitVersion % Test,
      "org.scalameta" %%% "munit-scalacheck" % munitVersion % Test,
      "org.typelevel" %%% "munit-cats-effect" % "2.2.0" % Test,
      "org.typelevel" %%% "scalacheck-effect-munit" % "2.1.0" % Test,
      "org.typelevel" %%% "cats-effect-testkit" % catsEffectVersion % Test,
    ),
    libraryDependencies ++= {
      if (isOtel4sScalaVersion.value)
        Seq(
          "org.typelevel" %%% "otel4s-core-metrics" % otel4sVersion,
          "org.typelevel" %%% "otel4s-semconv" % otel4sVersion,
          // Experimental semconv makes no binary-compatibility promise, so main
          // code inlines the RPC names it needs; RpcSemanticConventionsSpec
          // checks them against this.
          "org.typelevel" %%% "otel4s-semconv-metrics-experimental" % otel4sVersion % Test,
          // otel4s-sdk is pre-1.0 and versioned separately: 0.19.4 is the
          // release built against otel4s-core 1.1.0. Move it together with
          // `otel4sVersion`.
          "org.typelevel" %%% "otel4s-sdk-metrics-testkit" % "0.19.4" % Test,
        )
      else Seq.empty
    },
    Compile / unmanagedSourceDirectories := {
      if (isOtel4sScalaVersion.value) (Compile / unmanagedSourceDirectories).value else Seq.empty
    },
    Test / unmanagedSourceDirectories := {
      if (isOtel4sScalaVersion.value) (Test / unmanagedSourceDirectories).value else Seq.empty
    },
    publish / skip := !isOtel4sScalaVersion.value,
    // sbt-typelevel-mima decides whether to check previous artifacts from
    // `publishArtifact`, not `publish / skip`; without this, 2.12 would look
    // for `_2.12` artifacts that were never published once a release is tagged.
    publishArtifact := isOtel4sScalaVersion.value,
    tlVersionIntroduced := Map("2.12" -> "0.2.7", "2.13" -> "0.2.7", "3" -> "0.2.7"),
  )
  // Metric *content* needs an SDK. The content properties live in the shared,
  // backend-agnostic MeasurementContentSuite, which runs against otel4s-sdk on
  // every platform (OtelSdkMeasurementContentSpec) and against oteljava on the
  // JVM (OtelJavaMeasurementContentSpec, hence `%%` and `.jvmSettings` for its
  // testkit).
  .jvmSettings(
    libraryDependencies ++= {
      if (isOtel4sScalaVersion.value)
        Seq(
          "org.typelevel" %% "otel4s-oteljava-metrics-testkit" % otel4sVersion % Test,
          // AttributeConverters, for decoding a point's Java Attributes back
          // into the otel4s model.
          "org.typelevel" %% "otel4s-oteljava-common" % otel4sVersion % Test,
        )
      else Seq.empty
    },
    Test / unmanagedSourceDirectories ++= {
      if (isOtel4sScalaVersion.value) Seq(baseDirectory.value.getParentFile / "src" / "test" / "scala-jvm")
      else Seq.empty
    },
  )
  .settings(doctestSettings *)
  // Test only: WithMetricsSyntaxSpec and the doctest pin that `withMetrics(...)`
  // stacks with otel4s-tagless's `instrumentAndTrace`. Never a Compile dependency.
  .dependsOn(taglessCore, otel4sTagless % Test)

// sbt-buildinfo can't be enabled only for the test scope, so this is the workaround to use it only in tests
lazy val buildInfoForTests = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .in(file("buildInfoForTests"))
  .settings(
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "com.dwolla.buildinfo",
  )
  .enablePlugins(NoPublishPlugin, BuildInfoPlugin)
