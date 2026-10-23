package com.thinkmorestupidless.ankka.operator

import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.rbac.ClusterRole
import io.fabric8.kubernetes.client.KubernetesClientBuilder

import scala.jdk.CollectionConverters.*

/**
 * The operator's own Deployment as shipped (`components/operator/operator.yaml`): how its JVM is
 * sized against its limit, the probes that restart it when it stops reconciling, and the one grant
 * whose verbs the API server reads in a way the k3s suites' admin credentials never show. No k3s
 * suite deploys this Deployment (they run the operator in-process), so these are the checks it has.
 */
class OperatorManifestSuite extends munit.FunSuite:

  /**
   * What the operator uses beside its heap, measured on kind (2026-10-23): with the flags below,
   * native memory tracking put metaspace, class space, code, symbols, shared classes, threads and
   * the rest at about 113 MiB committed, and the cgroup's anonymous memory was 196 MiB at most
   * beside a 64 MiB heap, about 130 MiB besides the heap with glibc's arenas. With headroom for a
   * larger installation, the limit must leave this much beside the heap.
   */
  private val NonHeapMiB = 160

  private lazy val shipped =
    val client = new KubernetesClientBuilder().build()
    try
      client
        .load(getClass.getResourceAsStream("/ankka/install/operator.yaml"))
        .items()
        .asScala
        .toVector
    finally client.close()

  private lazy val deployment: Deployment =
    shipped
      .collectFirst { case d: Deployment if d.getMetadata.getName == "ankka-operator" => d }
      .getOrElse(fail("no ankka-operator Deployment in operator.yaml"))

  private lazy val role: ClusterRole =
    shipped
      .collectFirst { case r: ClusterRole if r.getMetadata.getName == "ankka-operator" => r }
      .getOrElse(fail("no ankka-operator ClusterRole in operator.yaml"))

  private lazy val container =
    deployment.getSpec.getTemplate.getSpec.getContainers.asScala
      .find(_.getName == "ankka-operator")
      .getOrElse(fail("no ankka-operator container"))

  private def env(name: String): String =
    container.getEnv.asScala
      .find(_.getName == name)
      .map(_.getValue)
      .getOrElse(fail(s"$name is not set on the operator"))

  private def mib(q: Quantity): Long =
    Quantity.getAmountInBytes(q).longValue / (1024L * 1024L)

  private def percentage(flag: String): Double =
    val found = s"-XX:$flag=([0-9.]+)".r.findFirstMatchIn(env("JAVA_OPTS"))
    found.map(_.group(1).toDouble).getOrElse(fail(s"JAVA_OPTS sets no $flag"))

  test("the heap is a share of the limit, and both percentages say the same share") {
    // Under about 500Mi of memory the JVM sizes the heap by MinRAMPercentage (50) and ignores
    // MaxRAMPercentage: at 256Mi the operator's heap could reach 126 MiB whatever the latter said.
    assertEquals(percentage("MinRAMPercentage"), percentage("MaxRAMPercentage"))
  }

  test("the limit holds the heap and the measured rest, and the request is the limit") {
    val resources = container.getResources
    val limit     = mib(resources.getLimits.get("memory"))
    assertEquals(mib(resources.getRequests.get("memory")), limit)
    val heap = (limit * percentage("MaxRAMPercentage") / 100).toLong
    assert(
      limit - heap >= NonHeapMiB,
      s"a ${limit}Mi limit with a ${heap} MiB heap leaves ${limit - heap} MiB, under the $NonHeapMiB MiB measured beside it"
    )
  }

  test("an exhausted heap ends the process; C1 alone; two malloc arenas") {
    val options = env("JAVA_OPTS").split("\\s+").toSet
    assert(options("-XX:+ExitOnOutOfMemoryError"), options)
    assert(options("-XX:TieredStopAtLevel=1"), options)
    assertEquals(env("MALLOC_ARENA_MAX"), "2")
  }

  test("startup and liveness probes ask the health check on its port") {
    val port = container.getPorts.asScala
      .find(_.getName == "health")
      .getOrElse(fail("no port named health"))
    assertEquals(port.getContainerPort.intValue, Settings.default.healthPort)
    for (kind, probe) <- Vector(
        "startup"  -> container.getStartupProbe,
        "liveness" -> container.getLivenessProbe
      )
    do
      assert(probe != null, s"no $kind probe")
      assertEquals(probe.getHttpGet.getPath, HealthCheck.Path, kind)
      assertEquals(probe.getHttpGet.getPort.getStrVal, "health", kind)
    // A liveness probe that fails before the check's own bound would restart an operator that is
    // only slow; the check names a stall only after `stallBound`, so a minute of failures is enough.
    val liveness = container.getLivenessProbe
    assert(liveness.getPeriodSeconds * liveness.getFailureThreshold >= 60, liveness)
  }

  // Exec runs over WebSockets since Kubernetes 1.31, and the API server authorises that path as a
  // `get` on `pods/exec` as well as the `create`; with `create` alone every psql the operator runs
  // in a database's pod was refused 403 on 1.35, and nothing the k3s suites do (admin credentials)
  // would have shown it.
  test("psql in a database's pod is granted as both create and get on pods/exec") {
    val rules = role.getRules.asScala.filter(_.getResources.asScala.contains("pods/exec"))
    assert(rules.nonEmpty, "no pods/exec rule on the operator's ClusterRole")
    val verbs = rules.flatMap(_.getVerbs.asScala).toSet
    assertEquals(verbs, Set("create", "get"))
  }
