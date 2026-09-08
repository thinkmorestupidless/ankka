import Dependencies.*

ThisBuild / scalaVersion  := V.scala
ThisBuild / organization  := "com.thinkmorestupidless"
ThisBuild / version       := "0.1.0-SNAPSHOT"
ThisBuild / versionScheme := Some("early-semver")

/**
 * Integration suites each start their own Postgres container and run real projections.
 * Letting several of those overlap does not make the build faster — measured at 147s for
 * a suite that takes 6s on its own, because the containers contend for Docker and the
 * connection pools contend for CPU. One test suite at a time.
 */
Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-encoding", "UTF-8",
    "-language:implicitConversions",
    "-Wunused:all",
    "-Wvalue-discard",
    "-source:3.7"
  ),
  javacOptions ++= Seq("--release", "21"),
  libraryDependencies ++= commonTest,
  Test / fork              := true,
  Test / parallelExecution := false,
  // Virtual threads carry ComponentClient.invoke; keep the surface honest under test too.
  Test / javaOptions ++= Seq("-XX:+EnableDynamicAgentLoading"),
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
 * projections. Depends on `sdk` because a runtime that cannot see a component's
 * descriptor has nothing to host.
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

lazy val shoppingCart = project
  .in(file("samples/shopping-cart"))
  .dependsOn(sdk, runtime, http, testkit % Test)
  .settings(commonSettings)
  .settings(name := "sample-shopping-cart", publish / skip := true)

lazy val multiAgentPlanner = project
  .in(file("samples/multi-agent-planner"))
  .dependsOn(sdk, runtime, http, agent, testkit % Test)
  .settings(commonSettings)
  .settings(name := "sample-multi-agent-planner", publish / skip := true)

lazy val root = project
  .in(file("."))
  .aggregate(core, sdk, runtime, http, agent, testkit, shoppingCart, multiAgentPlanner)
  .settings(
    name           := "nakka",
    publish / skip := true
  )
