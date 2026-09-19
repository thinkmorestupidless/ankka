import Dependencies.*
import com.typesafe.sbt.packager.docker.DockerPlugin
import com.typesafe.sbt.packager.archetypes.JavaAppPackaging

ThisBuild / scalaVersion := V.scala
ThisBuild / organization := "com.thinkmorestupidless"
// No `ThisBuild / version`: sbt-dynver derives it from the nearest tag (`v0.2.0` → `0.2.0`; a
// commit past it → `0.2.0+3-abc1234-SNAPSHOT`; a dirty tree → `…+<timestamp>-SNAPSHOT`). Setting the
// version anywhere silently overrides the tag, which is the one thing a release must not do.
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / homepage      := Some(url("https://github.com/thinkmorestupidless/ankka"))
ThisBuild / licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / developers := List(
  Developer(
    "thinkmorestupidless",
    "Trevor Burton-McCreadie",
    "",
    url("https://github.com/thinkmorestupidless")
  )
)
// The local proof of the release path (feature 006): `-Dankka.release.local=<dir>` points
// `publishSigned` at a Maven-layout directory instead of the Central Portal, so signing, sources,
// javadoc and POM metadata are exercised end to end before the public namespace exists.
ThisBuild / publishTo := sys.props
  .get("ankka.release.local")
  .map(dir =>
    Resolver.file("local-release", file(dir))(Patterns(true, Resolver.mavenStyleBasePattern))
  )
  .orElse((ThisBuild / publishTo).value)

/**
 * Integration suites each start their own Postgres container and run real projections. Letting
 * several of those overlap does not make the build faster — measured at 147s for a suite that takes
 * 6s on its own, because the containers contend for Docker and the connection pools contend for
 * CPU. One test suite at a time.
 */
Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)

/**
 * Docker image settings for the two processes that actually run in a cluster: the operator and the
 * control plane. Nothing else gets an image — samples run under `sbt run`, and the CLI is a local
 * binary.
 *
 * `dockerRepository` is unset until a registry exists — sbt-native-packager then tags the image
 * unqualified (`ankka-operator:0.1.0-SNAPSHOT`), which is exactly what a local cluster needs and
 * exactly wrong for anywhere images have to be pulled from a registry. Setting `DOCKER_REPOSITORY`
 * is the one-line change that flips it over once that need exists.
 *
 * `dockerUpdateLatest` also tags `latest`, which combined with `imagePullPolicy: IfNotPresent` in
 * the install manifests is what makes `sbt operator/docker:publishLocal` followed by deleting the
 * pod the whole local iteration loop — no image tag to bump in any YAML.
 */
/**
 * The template names the platform version it was released with — as the artifact version its
 * generated build resolves and as the runtime version its generated descriptor declares. This task
 * writes that value from the build's own version, so the template can never name a version that was
 * not published alongside it.
 *
 * Only for a release version: a snapshot carries a commit and a timestamp that change on every
 * dirty-tree publish, and rewriting the checked-in file for each would be noise. Locally, `ankka
 * init` passes its own `BuildInfo.version` as `--ankka_version` (the CLI you run is the version you
 * get) and `TemplateSuite` does the same, so the checked-in default matters only to someone running
 * `sbt new thinkmorestupidless/ankka.g8` — who gets the last release, which is right.
 */
lazy val templateVersion =
  taskKey[Unit]("Writes the build's version into ankka.g8's default.properties")

ThisBuild / templateVersion := {
  val file =
    (ThisBuild / baseDirectory).value / "ankka.g8" / "src" / "main" / "g8" / "default.properties"
  val current = IO.read(file)
  val v       = version.value
  val updated = current.linesIterator
    .map(line => if (line.startsWith("ankka_version=")) s"ankka_version=$v" else line)
    .mkString("", "\n", "\n")
  if (updated != current && !v.endsWith("-SNAPSHOT")) {
    IO.write(file, updated)
    streams.value.log.info(s"templateVersion: ankka_version=$v")
  }
}

lazy val templateArtifacts =
  taskKey[Unit](
    "Publishes the six library artifacts locally for TemplateSuite, unless template tests are off"
  )

lazy val sampleImageForClusterTests =
  taskKey[Unit](
    "Builds the sample image SampleDeploymentClusterSuite deploys, unless cluster tests are off"
  )

lazy val dockerSettings = Seq(
  dockerBaseImage    := "eclipse-temurin:21-jre",
  dockerUpdateLatest := true,
  dockerRepository   := sys.env.get("DOCKER_REPOSITORY"),
  // A Docker tag may not contain '+', and a dynver snapshot version does (`0.2.0+3-sha-SNAPSHOT`).
  // A release version has no '+', so a released image is tagged exactly with its version.
  Docker / version := version.value.replace('+', '-')
)

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-encoding",
    "UTF-8",
    "-language:implicitConversions",
    "-Wunused:all",
    "-Wvalue-discard",
    "-source:3.7"
  ),
  javacOptions ++= Seq("--release", "21"),
  libraryDependencies ++= commonTest,
  dependencyOverrides ++= pekkoHttpFamily,
  Test / fork              := true,
  Test / parallelExecution := false,
  // Virtual threads carry ComponentClient.invoke; keep the surface honest under test too.
  Test / javaOptions ++= Seq("-XX:+EnableDynamicAgentLoading"),
  // Tests fork, and a forked JVM does not inherit sbt's own -D properties — so without this,
  // `sbt -Dankka.cluster.tests=off test` set the switch in a JVM that runs no tests and the k3s
  // suites ran regardless. It was a documented no-op from feature 001 until feature 003 noticed
  // the "skipped" suites taking seven minutes.
  // Every test switch needs the same forwarding; TemplateSuite ran under `template.tests=off` until
  // its switch was added here too.
  Test / javaOptions ++= Seq("ankka.cluster.tests", "ankka.template.tests").flatMap { key =>
    sys.props.get(key).map(v => s"-D$key=$v")
  },
  testFrameworks += new TestFramework("munit.Framework")
)

/** Effects, identifiers, codecs, component descriptors. No Pekko, no I/O. */
lazy val core = project
  .in(file("modules/core"))
  .enablePlugins(BuildInfoPlugin)
  .settings(commonSettings)
  .settings(
    name := "ankka-core",
    libraryDependencies ++= Seq(jsoniterCore, jsoniterMacros),
    // com.thinkmorestupidless.ankka.core.BuildInfo.version — the one version everything published from a tag shares.
    buildInfoKeys    := Seq[BuildInfoKey](version),
    buildInfoPackage := "com.thinkmorestupidless.ankka.core",
    buildInfoObject  := "BuildInfo",
    // core is the first artifact every publish produces, so this is where the template learns the
    // version being published.
    publishLocal := publishLocal.dependsOn(ThisBuild / templateVersion).value,
    publish      := publish.dependsOn(ThisBuild / templateVersion).value
  )

/** The user-facing component API: entities, views, workflows, consumers, timers. */
lazy val sdk = project
  .in(file("modules/sdk"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(name := "ankka-sdk")

/**
 * Interprets the effects the SDK describes: sharding hosts, persistence, ComponentClient,
 * projections. Depends on `sdk` because a runtime that cannot see a component's descriptor has
 * nothing to host.
 */
lazy val runtime = project
  .in(file("modules/runtime"))
  .dependsOn(core, sdk)
  .settings(commonSettings)
  .settings(
    name := "ankka-runtime",
    // The Pekko HTTP family as *direct* dependencies, not only the build-wide
    // `dependencyOverrides` in commonSettings: an override never reaches a POM, so an application
    // resolving the published ankka-runtime would still get pekko-http-spray-json 1.1.0 from
    // pekko-management beside pekko-http 1.4.0 — and Pekko HTTP refuses to start on a mixed
    // family. Found by the first build outside this repository (feature 006). A direct dependency
    // at the family version is what a consumer's eviction honours.
    libraryDependencies ++= pekkoHttpFamily.filterNot(_.name == "pekko-http-testkit"),
    libraryDependencies ++= Seq(
      pekkoActorTyped,
      pekkoStream,
      pekkoSlf4j,
      pekkoClusterTyped,
      pekkoClusterShardingTyped,
      pekkoPersistenceTyped,
      pekkoPersistenceQuery,
      pekkoDiscovery,
      pekkoManagement,
      pekkoManagementClusterHttp,
      pekkoManagementBootstrap,
      pekkoDiscoveryKubernetesApi,
      pekkoR2dbc,
      r2dbcPostgres,
      pekkoProjection,
      pekkoProjectionEs,
      pekkoProjectionR2dbc,
      pekkoSerializationJackson,
      pekkoKafka,
      logback,
      pekkoPersistenceTestkit % Test
    )
  )

/** HTTP endpoint DSL and server. */
lazy val http = project
  .in(file("modules/http"))
  .dependsOn(core, sdk, runtime)
  .settings(commonSettings)
  .settings(
    name := "ankka-http",
    libraryDependencies ++= Seq(pekkoHttp, pekkoHttpTestkit % Test)
  )

/** Agents: model providers, session memory, function tools, the tool loop. */
lazy val agent = project
  .in(file("modules/agent"))
  .dependsOn(core, sdk, runtime)
  .settings(commonSettings)
  .settings(
    name := "ankka-agent",
    libraryDependencies ++= Seq(anthropicJava, pekkoHttp, pekkoStreamTyped)
  )

/** Unit and integration test support, plus TestModelProvider. */
lazy val testkit = project
  .in(file("modules/testkit"))
  .dependsOn(core, sdk, runtime, http, agent)
  .settings(commonSettings)
  .settings(
    name := "ankka-testkit",
    libraryDependencies ++= Seq(
      munit,
      pekkoActorTestkit,
      pekkoStreamTestkit,
      pekkoHttpTestkit,
      pekkoPersistenceTestkit,
      testcontainersPg,
      testcontainersKafka
    )
  )

/**
 * Wire types shared by the control plane and the CLI.
 *
 * Deliberately depends on nothing but a JSON codec: the CLI needs to know what a service descriptor
 * looks like, and should not drag Pekko, a Postgres driver and a Kubernetes client onto its
 * classpath to find out.
 */
lazy val controlPlaneApi = project
  .in(file("controlplane-api"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(name := "ankka-controlplane-api", publish / skip := true)

/**
 * The `AnkkaService` custom resource: the contract between the control plane and the operator.
 *
 * Depends on no ankka module, by the same reasoning that keeps `controlplane-api` free of Pekko. It
 * is a wire format, and both ends have to hold it without inheriting the other's world — the
 * control plane drags in Pekko and a Postgres driver, the operator must not.
 */
lazy val crd = project
  .in(file("crd"))
  .settings(commonSettings)
  .settings(
    name           := "ankka-crd",
    publish / skip := true,
    libraryDependencies ++= Seq(fabric8, jacksonScala)
  )

/**
 * The Kubernetes operator.
 *
 * Deliberately not an ankka application. It has no entities, no journal, no sharding and no views,
 * so hosting it on ankka would give it a cluster to form and a database not to use — and a process
 * whose whole job is to keep working while other things are broken should depend on as little as
 * possible. Its only ankka dependency is the resource contract.
 */
lazy val operator = project
  .in(file("operator"))
  .dependsOn(crd)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(commonSettings)
  .settings(dockerSettings)
  .settings(
    name           := "ankka-operator",
    publish / skip := true,
    // Explicit rather than auto-discovered: sbt-native-packager needs exactly one entry
    // point, and leaving it to discovery is one new `@main` away from an ambiguous-main
    // build failure that has nothing to do with what changed.
    Compile / mainClass := Some("com.thinkmorestupidless.ankka.operator.Main"),
    libraryDependencies ++= Seq(fabric8, logback, testcontainersK3s % Test),
    // As for controlPlane below: OperatorClusterSuite deploys the real sample since feature 004,
    // because only a real ankka image can be Ready now that readiness is cluster membership.
    sampleImageForClusterTests := Def.taskDyn {
      if (sys.props.get("ankka.cluster.tests").contains("off")) Def.task(())
      else Def.task { val _ = (shoppingCart / Docker / publishLocal).value }
    }.value,
    // Both, as for templateArtifacts: `testOnly` is how one k3s suite is run, and since the suites
    // name the sample by the build's own version tag, an image from an earlier sbt session (a
    // different dynver timestamp on a dirty tree) is not the one they ask for.
    Test / test     := (Test / test).dependsOn(sampleImageForClusterTests).value,
    Test / testOnly := (Test / testOnly).dependsOn(sampleImageForClusterTests).evaluated
  )

/**
 * The control plane, built as an ankka application.
 *
 * Tenancy is entities, listings are views, and desired state is projected into an `AnkkaService`
 * resource for the operator to act on — the control plane itself never writes a workload.
 *
 * A perpetual reconcile loop is deliberately *not* modelled as a workflow: a workflow writes a step
 * transition per cycle, so a steady-state service would grow its journal forever — the failure the
 * "refuse an identical observation" guard already exists to prevent.
 *
 * `operator % Test` is there for the same reason as `cli % Test`: a custom resource is a wire
 * format with two ends, and only a test running both of them catches the two disagreeing.
 */
lazy val controlPlane = project
  .in(file("controlplane"))
  .dependsOn(
    controlPlaneApi,
    crd,
    sdk,
    runtime,
    http,
    cli % Test,
    // test->test as well: the cluster suites share the image-import helper, and since feature
    // 004 both modules' suites must deploy a real ankka image to see a service go Ready.
    operator % "test->test;test->compile",
    testkit  % Test
  )
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(commonSettings)
  .settings(dockerSettings)
  .settings(
    name                := "ankka-controlplane",
    publish / skip      := true,
    Compile / mainClass := Some("com.thinkmorestupidless.ankka.controlplane.runControlPlane"),
    dockerExposedPorts  := Seq(9000),
    libraryDependencies ++= Seq(fabric8, testcontainersK3s % Test),
    // SampleDeploymentClusterSuite deploys the *real* shopping cart, so something has to build its
    // image before the suite starts, and `sbt test` has to keep working with no preparatory step.
    //
    // A build-level task dependency, NOT a classpath one: controlPlane gains no dependency on
    // shoppingCart in any compilation scope, and the module graph is unchanged.
    //
    // The taskDyn is on a helper and not on `Test / test` itself on purpose. A reference to
    // `Test / test` inside its own taskDyn resolves against the *final* settings at run time, not
    // the previous definition — that is a cycle, not an override.
    sampleImageForClusterTests := Def.taskDyn {
      // Read in sbt's own JVM, at task-graph time. A switch that skips the suite but still spends
      // a minute building an image it will not use is not skipping it.
      if (sys.props.get("ankka.cluster.tests").contains("off")) Def.task(())
      else
        Def.task {
          // ControlPlaneClusterSuite (feature 004) deploys the control plane itself into k3s.
          (shoppingCart / Docker / publishLocal).value
          (Docker / publishLocal).value // this project's own image, unscoped to avoid self-reference
          ()
        }
    }.value,
    // Both, as for templateArtifacts: `testOnly` is how one k3s suite is run, and since the suites
    // name the sample by the build's own version tag, an image from an earlier sbt session (a
    // different dynver timestamp on a dirty tree) is not the one they ask for.
    Test / test     := (Test / test).dependsOn(sampleImageForClusterTests).value,
    Test / testOnly := (Test / testOnly).dependsOn(sampleImageForClusterTests).evaluated
  )

/** The `ankka` command-line client. */
lazy val cli = project
  .in(file("cli"))
  .dependsOn(controlPlaneApi)
  .settings(commonSettings)
  .enablePlugins(JavaAppPackaging)
  .settings(
    name           := "ankka-cli",
    publish / skip := true,
    // `sbt cli/stage` is how the CLI is run as a program: target/universal/stage/bin/ankka.
    executableScriptName := "ankka",
    // JavaAppPackaging drags DockerPlugin in, and root's `docker:publishLocal` aggregates to every
    // project that has the task. The CLI is a local binary, never an image: make the task a no-op
    // here rather than let it build one.
    Docker / publishLocal := (),
    Docker / publish      := (),
    libraryDependencies ++= Seq(decline, munit % Test),
    // TemplateSuite expands the template into a build outside this one, which resolves ankka from
    // ~/.ivy2/local — so the artifacts have to be there first. A build-level task dependency, the
    // same shape as sampleImageForClusterTests; off with -Dankka.template.tests=off. On both
    // `test` and `testOnly`: the second is how a single suite is run, and it does not go through
    // the first.
    templateArtifacts := Def.taskDyn {
      if (sys.props.get("ankka.template.tests").contains("off")) Def.task(())
      else
        // The six by name: a task dependency on the root's publishLocal runs only the root's own
        // (skipped) publish — aggregation is how the command line fans out, not the task graph.
        Def.task {
          (core / publishLocal).value
          (sdk / publishLocal).value
          (runtime / publishLocal).value
          (http / publishLocal).value
          (agent / publishLocal).value
          (testkit / publishLocal).value
          ()
        }
    }.value,
    Test / test     := (Test / test).dependsOn(templateArtifacts).value,
    Test / testOnly := (Test / testOnly).dependsOn(templateArtifacts).evaluated
  )

/**
 * The one sample packaged as an image, because it is the one that can prove the platform end to end
 * with no credentials: `multiAgentPlanner` needs a model API key to do anything at all.
 */
lazy val shoppingCart = project
  .in(file("samples/shopping-cart"))
  .dependsOn(sdk, runtime, http, testkit % Test)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(commonSettings)
  .settings(dockerSettings)
  .settings(
    name           := "sample-shopping-cart",
    publish / skip := true,
    // `@main def runShoppingCart()` compiles to a top-level class of exactly this name. Explicit
    // for the reason on the operator's setting: discovery is one new @main from an ambiguous-main
    // failure unrelated to whatever changed.
    Compile / mainClass := Some("runShoppingCart"),
    dockerExposedPorts  := Seq(9000)
  )

lazy val multiAgentPlanner = project
  .in(file("samples/multi-agent-planner"))
  .dependsOn(sdk, runtime, http, agent, testkit % Test)
  .settings(commonSettings)
  .settings(name := "sample-multi-agent-planner", publish / skip := true)

/**
 * The whole build, one command: `sbt buildAll`.
 *
 * Format check first because it is nearly free and should fail before anything slower runs;
 * `docker:publishLocal` last because it only does useful work once compilation and tests have
 * already passed. Root aggregation means this needs no per-module wiring — `docker:publishLocal` at
 * root already builds exactly the two images that have `DockerPlugin` enabled (`operator`,
 * `controlPlane`) and silently skips every other project, the same way `compile` and `test` already
 * do.
 */
addCommandAlias(
  "buildAll",
  "; scalafmtCheckAll ; scalafmtSbtCheck ; compile ; test ; docker:publishLocal"
)

lazy val root = project
  .in(file("."))
  .aggregate(
    core,
    sdk,
    runtime,
    http,
    agent,
    testkit,
    controlPlaneApi,
    crd,
    operator,
    controlPlane,
    cli,
    shoppingCart,
    multiAgentPlanner
  )
  .settings(
    name           := "ankka",
    publish / skip := true
  )
