package com.thinkmorestupidless.ankka.operator

import io.fabric8.kubernetes.client.KubernetesClient
import org.testcontainers.k3s.K3sContainer

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * What a backup suite needs on a k3s node beside the operator (feature 041): CloudNativePG at the
 * release `kustomization/components/cnpg` pins, and the Barman Cloud plugin at the release
 * `kustomization/components/cnpg-barman` pins, so a manifest a suite passes with is the manifest
 * that ships.
 *
 * Needs cert-manager first, for the plugin's own certificates: call `PkiStack.install` before this.
 * The object store is `ObjectStoreStack`'s. Shared with the control plane's suites through
 * `test->test`, like the other stacks.
 */
object BackupStack:

  val Cnpg: String =
    "https://raw.githubusercontent.com/cloudnative-pg/cloudnative-pg/release-1.30/releases/cnpg-1.30.0.yaml"

  val BarmanPlugin: String =
    "https://github.com/cloudnative-pg/plugin-barman-cloud/releases/download/v0.15.1/manifest.yaml"

  /** CNPG's operator and the plugin, each rolled out, and the ObjectStore type established. */
  def install(k3s: K3sContainer, k8s: KubernetesClient): Unit =
    PkiStack.kubectl(k3s, "apply", "--server-side", "--force-conflicts", "-f", Cnpg): Unit
    // CNPG's release limits its operator to a tenth of a CPU. With the plugin and a cluster per
    // scenario on one node, its health check timed out and the kubelet killed it every minute,
    // so every write to a CNPG resource found no webhook to admit it. Room on the test node only.
    PkiStack.kubectl(
      k3s,
      "-n",
      "cnpg-system",
      "set",
      "resources",
      "deployment/cnpg-controller-manager",
      "--requests=cpu=200m,memory=200Mi",
      "--limits=cpu=2,memory=1Gi"
    ): Unit
    waitFor(180.seconds, "CNPG's operator is ready") {
      readyReplicas(k8s, "cnpg-system", "cnpg-controller-manager") > 0
    }
    PkiStack.kubectl(k3s, "apply", "--server-side", "--force-conflicts", "-f", BarmanPlugin): Unit
    waitFor(240.seconds, "the Barman Cloud plugin is ready") {
      readyReplicas(k8s, "cnpg-system", "barman-cloud") > 0
    }
    waitFor(60.seconds, "the ObjectStore type is established") {
      k8s
        .apiextensions()
        .v1()
        .customResourceDefinitions()
        .withName("objectstores.barmancloud.cnpg.io")
        .get()
        .getStatus
        .getConditions
        .asScala
        .exists(c => c.getType == "Established" && c.getStatus == "True")
    }

  private def readyReplicas(k8s: KubernetesClient, namespace: String, name: String): Int =
    Option(k8s.apps().deployments().inNamespace(namespace).withName(name).get())
      .flatMap(d => Option(d.getStatus))
      .flatMap(s => Option(s.getReadyReplicas))
      .map(_.intValue)
      .getOrElse(0)

  private def waitFor(timeout: FiniteDuration, what: String)(check: => Boolean): Unit =
    val deadline = timeout.fromNow
    var passed   = false
    while !passed && deadline.hasTimeLeft() do
      passed =
        try check
        catch case _: Exception => false
      if !passed then Thread.sleep(1000)
    if !passed then throw new AssertionError(s"$what did not happen within $timeout")
