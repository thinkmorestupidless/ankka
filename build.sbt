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
 * Docker image settings for the processes that actually run in a cluster: the operator, the control
 * plane and, since feature 009, the sidecar. Nothing else gets an image — samples run under
 * `sbt run` (the shopping cart also has one, for the cluster suites), and the CLI is a local
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
/*
 * The template names the platform version it was released with, and that value is written by the
 * release workflow's `template` job, not by this build.
 *
 * There was a `templateVersion` task here that rewrote ankka.g8's default.properties from
 * `version.value`, hung off core's `publish` and `publishLocal`. It broke every release. It only
 * writes for a non-SNAPSHOT version, so it never fired locally and always fired on a tag — and
 * writing to a tracked file makes the tree dirty, which makes sbt-dynver append a timestamp and
 * -SNAPSHOT, which makes ci-release (which reloads the build before publishing) take the snapshot
 * path. A tag published a snapshot, to an endpoint this namespace is not entitled to use, and
 * answered 403. The write was pointless besides: the `template` job checks the repository out
 * afresh, so a file this job modified never reached the template that is pushed.
 */

lazy val templateArtifacts =
  taskKey[Unit](
    "Publishes the six service libraries locally for TemplateSuite, unless template tests are off"
  )

lazy val sampleImageForClusterTests =
  taskKey[Unit](
    "Builds the sample image SampleDeploymentClusterSuite deploys, unless cluster tests are off"
  )

lazy val sidecarImageForClusterTests =
  taskKey[Unit](
    "Builds the sidecar image SidecarClusterSuite deploys, unless cluster tests are off"
  )

lazy val dockerSettings = Seq(
  dockerBaseImage    := "eclipse-temurin:21-jre",
  dockerUpdateLatest := true,
  dockerRepository   := sys.env.get("DOCKER_REPOSITORY"),
  // A Docker tag may not contain '+', and a dynver snapshot version does (`0.2.0+3-sha-SNAPSHOT`).
  // A release version has no '+', so a released image is tagged exactly with its version.
  Docker / version := version.value.replace('+', '-')
)

/**
 * Keycloak, the platform's identity provider (feature 008). Pinned by exact release like CNPG and
 * cert-manager; bump here, in kustomization/components/keycloak/kustomization.yaml (the operator
 * reference), keycloak.yaml is versioned by the operator, and docker-compose.yml — the overlay
 * suite checks they agree.
 */
val keycloakVersion = "26.7.4"

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
  Test / javaOptions ++= Seq(
    "ankka.cluster.tests",
    "ankka.template.tests",
    "ankka.benchmarks",
    // The conformance suite's target (feature 009): a process speaking the sidecar protocol.
    "ankka.conformance.target",
    // Reference pages the JVM generates (the CLI's commands, the control plane's routes): with
    // `true` the suites rewrite the page instead of failing on a stale one.
    "ankka.docs.update"
  )
    .flatMap { key =>
      sys.props.get(key).map(v => s"-D$key=$v")
    },
  // The one place the Keycloak version is written for code: the suites read it from here, so no
  // test names the image by a literal tag (feature 006's lesson). The manifests and compose carry
  // the same string, and RemoteOverlaySuite asserts they agree with this one.
  Test / javaOptions += s"-Dankka.keycloak.version=$keycloakVersion",
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
    // `imageTag` is what `Docker / version` produces (`+` → `-`), so a suite that deploys the
    // sidecar or the sample names the image this sbt session built, never a literal tag.
    buildInfoKeys := Seq[BuildInfoKey](
      version,
      BuildInfoKey("imageTag", version.value.replace('+', '-'))
    ),
    buildInfoPackage := "com.thinkmorestupidless.ankka.core",
    buildInfoObject  := "BuildInfo"
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
 *
 * Published — the seventh library, and the only one that is not for a *service* (feature 011). It
 * is for a client of the control plane outside this repository: the hosted product's provisioner is
 * the first. A client that redefined the wire types by hand would drift from them, which is exactly
 * the disagreement `CliEndToEndSuite` exists to catch for the CLI.
 */
lazy val controlPlaneApi = project
  .in(file("controlplane-api"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(name := "ankka-controlplane-api")

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
    libraryDependencies ++= Seq(fabric8, nimbusJoseJwt, testcontainersK3s % Test),
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

/**
 * The sidecar protocol (feature 009): protobuf messages and gRPC service stubs.
 *
 * Depends on nothing of ankka's and nothing published depends on it — the `.proto` files, the
 * encoding document and the fixtures under `protocol/` are the artifact an SDK consumes, not this
 * jar. Generated code is not warning-free under `-Wunused`, so this project has its own flags.
 */
lazy val protocol = project
  .in(file("protocol"))
  .settings(commonSettings)
  .settings(
    name           := "ankka-protocol",
    publish / skip := true,
    // Generated code only: no `-source:3.7` (it warns on every `_` wildcard ScalaPB emits) and no
    // `-Wunused`. The one promise kept is that `sbt compile` stays warning-free.
    scalacOptions := Seq("-encoding", "UTF-8", "-source:3.3"),
    Compile / PB.targets := Seq(
      scalapb.gen(grpc = true) -> (Compile / sourceManaged).value / "scalapb"
    ),
    libraryDependencies ++= Seq(
      scalapbRuntime % "protobuf",
      scalapbRuntime,
      scalapbRuntimeGrpc,
      grpcStub,
      grpcNettyShaded
    )
  )

/**
 * The sidecar (feature 009): ankka's runtime with a `main` that boots from a discovery handshake
 * with a developer's process in another language, instead of from a Scala builder.
 *
 * An application, beside the operator and control plane, because it needs `http` and `agent` and
 * `runtime` must not. It is the thing that gets an image; the remote descriptors and the
 * conversation seam it implements live in `runtime`.
 */
lazy val sidecar = project
  .in(file("sidecar"))
  // operator test->test for ClusterImages and the k3s helpers, and test->compile for the
  // Operator itself: SidecarClusterSuite runs the real operator against k3s with a process-hosted
  // service, the same way the control plane's cluster suites do.
  .dependsOn(runtime, http, agent, protocol, testkit % Test, operator % "test->test;test->compile")
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(commonSettings)
  .settings(dockerSettings)
  .settings(
    name                := "ankka-sidecar",
    publish / skip      := true,
    Compile / mainClass := Some("com.thinkmorestupidless.ankka.sidecar.Main"),
    dockerExposedPorts  := Seq(9000),
    // The schema, as files, at /opt/docker/ddl: the Python integration testkit starts its own
    // Postgres and copies the DDL out of this image, so a test can never pass against a schema
    // the platform does not have — the same rule as AnkkaTestKit, from the other side.
    Universal / mappings ++= {
      val ddl = (runtime / Compile / resourceDirectory).value / "ankka" / "ddl"
      (ddl * "*.sql").get.map(f => f -> s"ddl/${f.getName}")
    },
    libraryDependencies ++= Seq(logback, testcontainersK3s % Test),
    // SidecarClusterSuite deploys this project's own image by the build's version tag, so the
    // image has to come from this sbt session — as sampleImageForClusterTests for the operator's
    // suites. On both test and testOnly, for the same reason as there. A full `buildAll` found it
    // missing: the sidecar tests ran before any image had been built.
    sidecarImageForClusterTests := Def.taskDyn {
      if (sys.props.get("ankka.cluster.tests").contains("off")) Def.task(())
      else Def.task { val _ = (Docker / publishLocal).value }
    }.value,
    Test / test     := (Test / test).dependsOn(sidecarImageForClusterTests).value,
    Test / testOnly := (Test / testOnly).dependsOn(sidecarImageForClusterTests).evaluated
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
    // The documentation this CLI's version was built with, for `ankka mcp` to serve: every public
    // page under docs/ onto the classpath at ankka/docs/, with an index, because a directory inside a
    // jar cannot be listed. docs/design/ holds internal treatments and is left out, as the site does.
    Compile / resourceGenerators += Def.task {
      val docs = (ThisBuild / baseDirectory).value / "docs"
      val out  = (Compile / resourceManaged).value / "ankka" / "docs"
      val pages = (docs ** "*.md").get
        .flatMap(file => IO.relativize(docs, file).map(_ -> file))
        .filterNot { case (relative, _) => relative.startsWith("design/") }
        .sortBy(_._1)
      IO.delete(out)
      val copied = pages.map { case (relative, file) =>
        val target = out / relative
        IO.copyFile(file, target)
        target
      }
      val index = out / "index.txt"
      IO.write(index, pages.map(_._1).mkString("", "\n", "\n"))
      index +: copied
    }.taskValue,
    // TemplateSuite expands the template into a build outside this one, which resolves ankka from
    // ~/.ivy2/local — so the artifacts have to be there first. A build-level task dependency, the
    // same shape as sampleImageForClusterTests; off with -Dankka.template.tests=off. On both
    // `test` and `testOnly`: the second is how a single suite is run, and it does not go through
    // the first.
    templateArtifacts := Def.taskDyn {
      if (sys.props.get("ankka.template.tests").contains("off")) Def.task(())
      else
        // Six of the seven by name: a task dependency on the root's publishLocal runs only the
        // root's own (skipped) publish — aggregation is how the command line fans out, not the task
        // graph. The seventh, controlPlaneApi, is a client's library; the template is a service.
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
    protocol,
    sidecar,
    shoppingCart,
    multiAgentPlanner
  )
  .settings(
    name           := "ankka",
    publish / skip := true
  )
