import Dependencies.*
import com.typesafe.sbt.packager.docker.DockerPlugin
import com.typesafe.sbt.packager.archetypes.JavaAppPackaging

ThisBuild / scalaVersion  := V.scala
ThisBuild / organization  := "com.thinkmorestupidless"
ThisBuild / version       := "0.1.0-SNAPSHOT"
ThisBuild / versionScheme := Some("early-semver")

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
 * unqualified (`nakka-operator:0.1.0-SNAPSHOT`), which is exactly what a local cluster needs and
 * exactly wrong for anywhere images have to be pulled from a registry. Setting `DOCKER_REPOSITORY`
 * is the one-line change that flips it over once that need exists.
 *
 * `dockerUpdateLatest` also tags `latest`, which combined with `imagePullPolicy: IfNotPresent` in
 * the install manifests is what makes `sbt operator/docker:publishLocal` followed by deleting the
 * pod the whole local iteration loop — no image tag to bump in any YAML.
 */
lazy val sampleImageForClusterTests =
  taskKey[Unit](
    "Builds the sample image SampleDeploymentClusterSuite deploys, unless cluster tests are off"
  )

lazy val dockerSettings = Seq(
  dockerBaseImage    := "eclipse-temurin:21-jre",
  dockerUpdateLatest := true,
  dockerRepository   := sys.env.get("DOCKER_REPOSITORY")
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
  // `sbt -Dnakka.cluster.tests=off test` set the switch in a JVM that runs no tests and the k3s
  // suites ran regardless. It was a documented no-op from feature 001 until feature 003 noticed
  // the "skipped" suites taking seven minutes.
  Test / javaOptions ++= sys.props
    .get("nakka.cluster.tests")
    .map(v => s"-Dnakka.cluster.tests=$v")
    .toSeq,
  testFrameworks += new TestFramework("munit.Framework")
)

/** Effects, identifiers, codecs, component descriptors. No Pekko, no I/O. */
lazy val core = project
  .in(file("modules/core"))
  .settings(commonSettings)
  .settings(
    name := "nakka-core",
    libraryDependencies ++= Seq(jsoniterCore, jsoniterMacros)
  )

/** The user-facing component API: entities, views, workflows, consumers, timers. */
lazy val sdk = project
  .in(file("modules/sdk"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(name := "nakka-sdk")

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
    name := "nakka-runtime",
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
    name := "nakka-http",
    libraryDependencies ++= Seq(pekkoHttp, pekkoHttpTestkit % Test)
  )

/** Agents: model providers, session memory, function tools, the tool loop. */
lazy val agent = project
  .in(file("modules/agent"))
  .dependsOn(core, sdk, runtime)
  .settings(commonSettings)
  .settings(
    name := "nakka-agent",
    libraryDependencies ++= Seq(anthropicJava, pekkoHttp, pekkoStreamTyped)
  )

/** Unit and integration test support, plus TestModelProvider. */
lazy val testkit = project
  .in(file("modules/testkit"))
  .dependsOn(core, sdk, runtime, http, agent)
  .settings(commonSettings)
  .settings(
    name := "nakka-testkit",
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
  .settings(name := "nakka-controlplane-api")

/**
 * The `NakkaService` custom resource: the contract between the control plane and the operator.
 *
 * Depends on no nakka module, by the same reasoning that keeps `controlplane-api` free of Pekko. It
 * is a wire format, and both ends have to hold it without inheriting the other's world — the
 * control plane drags in Pekko and a Postgres driver, the operator must not.
 */
lazy val crd = project
  .in(file("crd"))
  .settings(commonSettings)
  .settings(
    name := "nakka-crd",
    libraryDependencies ++= Seq(fabric8, jacksonScala)
  )

/**
 * The Kubernetes operator.
 *
 * Deliberately not a nakka application. It has no entities, no journal, no sharding and no views,
 * so hosting it on nakka would give it a cluster to form and a database not to use — and a process
 * whose whole job is to keep working while other things are broken should depend on as little as
 * possible. Its only nakka dependency is the resource contract.
 */
lazy val operator = project
  .in(file("operator"))
  .dependsOn(crd)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(commonSettings)
  .settings(dockerSettings)
  .settings(
    name := "nakka-operator",
    // Explicit rather than auto-discovered: sbt-native-packager needs exactly one entry
    // point, and leaving it to discovery is one new `@main` away from an ambiguous-main
    // build failure that has nothing to do with what changed.
    Compile / mainClass := Some("nakka.operator.Main"),
    libraryDependencies ++= Seq(fabric8, logback, testcontainersK3s % Test),
    // As for controlPlane below: OperatorClusterSuite deploys the real sample since feature 004,
    // because only a real nakka image can be Ready now that readiness is cluster membership.
    sampleImageForClusterTests := Def.taskDyn {
      if (sys.props.get("nakka.cluster.tests").contains("off")) Def.task(())
      else Def.task { val _ = (shoppingCart / Docker / publishLocal).value }
    }.value,
    Test / test := (Test / test).dependsOn(sampleImageForClusterTests).value
  )

/**
 * The control plane, built as a nakka application.
 *
 * Tenancy is entities, listings are views, and desired state is projected into a `NakkaService`
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
    // 004 both modules' suites must deploy a real nakka image to see a service go Ready.
    operator % "test->test;test->compile",
    testkit  % Test
  )
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(commonSettings)
  .settings(dockerSettings)
  .settings(
    name                := "nakka-controlplane",
    Compile / mainClass := Some("nakka.controlplane.runControlPlane"),
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
      if (sys.props.get("nakka.cluster.tests").contains("off")) Def.task(())
      else
        Def.task {
          // ControlPlaneClusterSuite (feature 004) deploys the control plane itself into k3s.
          (shoppingCart / Docker / publishLocal).value
          (Docker / publishLocal).value // this project's own image, unscoped to avoid self-reference
          ()
        }
    }.value,
    Test / test := (Test / test).dependsOn(sampleImageForClusterTests).value
  )

/** The `nakka` command-line client. */
lazy val cli = project
  .in(file("cli"))
  .dependsOn(controlPlaneApi)
  .settings(commonSettings)
  .settings(
    name := "nakka-cli",
    libraryDependencies ++= Seq(decline, munit % Test)
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
    name           := "nakka",
    publish / skip := true
  )
