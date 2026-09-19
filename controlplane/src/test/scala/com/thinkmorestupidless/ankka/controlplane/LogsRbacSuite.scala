package com.thinkmorestupidless.ankka.controlplane

import munit.FunSuite

/**
 * What the shipped ClusterRole grants, and — more importantly — what it withholds.
 *
 * `ankka services logs` required the control plane to read pods. That is a widening, and the
 * argument for it is narrow: reading is not doing, so the property that buys "a control plane that
 * holds no credential able to create a workload" survives. This suite is what stops that argument
 * quietly stopping being true — a later edit that adds `delete` to make a cleanup easier, or
 * `pods/exec` to make debugging easier, breaks the claim and should break a test with it.
 *
 * It reads the manifest rather than a cluster, so it runs in CI in milliseconds. That is
 * deliberately not the whole story: `CLAUDE.md` records that the suites mostly use admin
 * credentials, so a *missing* verb fails silently in CI and loudly on a real deploy. A manifest
 * test cannot catch that either. The complement is the `kubectl auth can-i` block in quickstart.md,
 * run against a real cluster with the control plane's own ServiceAccount, which is the only form
 * that proves the API server agrees.
 */
final class LogsRbacSuite extends FunSuite:

  /**
   * Read from the classpath, not from a path relative to the repository root: forked tests run in
   * the module's own directory. This is the same single copy either way —
   * `src/main/resources/ankka/install/` holds a symlink into `kustomization/components/`, which is
   * the direction kustomize's load restrictor forces and which the JVM follows transparently.
   */
  private val manifest =
    val stream = getClass.getResourceAsStream("/ankka/install/controlplane-rbac.yaml")
    assert(stream != null, "the shipped RBAC manifest is not on the classpath")
    try String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    finally stream.close()

  /** The rule block for one resource, as written in the ClusterRole. */
  private def verbsFor(resource: String): Set[String] =
    val marker = s"""resources: ["$resource"]"""
    manifest.indexOf(marker) match
      case -1 => Set.empty
      case at =>
        val verbsAt = manifest.indexOf("verbs:", at)
        val line    = manifest.substring(verbsAt, manifest.indexOf('\n', verbsAt))
        line
          .dropWhile(_ != '[')
          .drop(1)
          .takeWhile(_ != ']')
          .split(',')
          .map(_.trim.stripPrefix("\"").stripSuffix("\""))
          .filter(_.nonEmpty)
          .toSet

  test("the control plane may read a pod's log") {
    assertEquals(verbsFor("pods/log"), Set("get"), "reading the log, and only reading it")
  }

  test("it may find the pods of a service, and nothing else about them") {
    assertEquals(verbsFor("pods"), Set("get", "list"))
  }

  test("it holds no verb that could change a workload") {
    val mutating = Set("create", "update", "patch", "delete", "deletecollection")
    for resource <- Vector("pods", "pods/log") do
      val granted = verbsFor(resource) intersect mutating
      assert(
        granted.isEmpty,
        s"$resource grants $granted — the control plane holding a mutating verb on a workload is " +
          "the thing the operator split exists to prevent, and it is a bigger change than " +
          "whatever motivated it"
      )
  }

  test("it cannot open a shell in anybody's service") {
    assertEquals(
      verbsFor("pods/exec"),
      Set.empty[String],
      "pods/exec in the control plane would be a shell in every service in the installation"
    )
    assertEquals(verbsFor("pods/attach"), Set.empty[String])
    assertEquals(verbsFor("pods/portforward"), Set.empty[String])
  }

  test("it has no rights over deployments at all") {
    assertEquals(
      verbsFor("deployments"),
      Set.empty[String],
      "the operator owns workloads; that is the whole point of there being two processes"
    )
  }
