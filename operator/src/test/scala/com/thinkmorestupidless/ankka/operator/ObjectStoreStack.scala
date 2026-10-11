package com.thinkmorestupidless.ankka.operator

import io.fabric8.kubernetes.client.{KubernetesClient, LocalPortForward}
import org.testcontainers.images.builder.Transferable
import org.testcontainers.k3s.K3sContainer

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/**
 * The installation's object store on a k3s node: the files of `kustomization/components/garage`,
 * applied with the node's own `kubectl` as `GatewayStack` applies the gateway's, so a manifest a
 * suite passes with is the manifest that ships (feature 034).
 *
 * Everything but the operator's half: the patch that names the store to the operator's Deployment,
 * and the token's copy in the operator's namespace, belong to a deployed operator, and a suite runs
 * its operator in the test's own JVM. That operator reaches the administration API through a port
 * forward, which this returns; a pod reaches the S3 API at the in-cluster address.
 *
 * Shared by the control plane's suites through `test->test`, like `GatewayStack`.
 */
object ObjectStoreStack:

  val Namespace: String = "garage-system"
  val Service: String   = "garage"

  /** The address a workload in the cluster is given. */
  val InClusterEndpoint: String = s"http://$Service.$Namespace.svc.cluster.local:3900"

  /** The token in the component's development Secret. */
  val AdminToken: String = "ankka-dev-object-store-admin"

  /** The store, installed: the operator's settings for it, and the forward that carries them. */
  final case class Installed(settings: ObjectStoreSettings, adminForward: LocalPortForward):
    def close(): Unit = adminForward.close()

  private val Files_ = Vector(
    "namespace.yaml",
    "config.yaml",
    "statefulset.yaml",
    "service.yaml",
    "zero-trust.yaml",
    "grants.yaml"
  )

  def install(k3s: K3sContainer, k8s: KubernetesClient, repoRoot: Path): Installed =
    val component = repoRoot.resolve("kustomization/components/garage")
    // The store's Secret only: the operator's copy lives in a namespace a test JVM's operator does
    // not have.
    val storeSecret = Files
      .readString(component.resolve("secrets.yaml"))
      .split("\n---\n")
      .find(_.contains(s"namespace: $Namespace"))
      .getOrElse(throw new AssertionError("secrets.yaml has no Secret for the store's namespace"))
    val combined =
      (Files_.map(f => Files.readString(component.resolve(f))) :+ storeSecret).mkString("\n---\n")
    k3s.copyFileToContainer(
      Transferable.of(combined.getBytes(StandardCharsets.UTF_8)),
      "/tmp/ankka-object-store.yaml"
    )
    val applied = k3s.execInContainer(
      "kubectl",
      "apply",
      "--server-side",
      "--force-conflicts",
      "-f",
      "/tmp/ankka-object-store.yaml"
    )
    if applied.getExitCode != 0 then
      throw new AssertionError(s"applying the object store failed: ${applied.getStderr}")
    waitFor(180.seconds, "the object store is ready") {
      PkiStack.jsonPath(
        k3s,
        "-n",
        Namespace,
        "statefulset",
        Service,
        "{.status.readyReplicas}"
      ) == "1"
    }
    val forward = k8s.services().inNamespace(Namespace).withName(Service).portForward(3903)
    Installed(
      ObjectStoreSettings(
        adminUrl = s"http://127.0.0.1:${forward.getLocalPort}",
        adminToken = AdminToken,
        endpoint = InClusterEndpoint,
        region = "garage",
        service = ObjectStoreSettings.ServiceRef(Namespace, Service, 3900)
      ),
      forward
    )

  /**
   * The store on three nodes (feature 041): `garage` with `garage-replicated`'s changes, as an
   * installation in a cluster lists them, but with no anti-affinity, since a k3s suite has one
   * machine. Waits for the layout loop to make the three one store.
   */
  def installReplicated(k3s: K3sContainer, k8s: KubernetesClient, repoRoot: Path): Installed =
    val component  = repoRoot.resolve("kustomization/components/garage")
    val replicated = repoRoot.resolve("kustomization/components/garage-replicated")
    val storeSecret = Files
      .readString(component.resolve("secrets.yaml"))
      .split("\n---\n")
      .find(_.contains(s"namespace: $Namespace"))
      .getOrElse(throw new AssertionError("secrets.yaml has no Secret for the store's namespace"))
    val statefulSet = Files
      .readString(component.resolve("statefulset.yaml"))
      .replace("replicas: 1", "replicas: 3")
      .replace(
        """args: ["/garage", "server", "--single-node"]""",
        """args: ["/garage", "server"]"""
      )
    if !statefulSet.contains("replicas: 3") || statefulSet.contains("--single-node") then
      throw new AssertionError("the StatefulSet is no longer shaped as this stack expects")
    val parts = Vector(
      Files.readString(component.resolve("namespace.yaml")),
      Files.readString(replicated.resolve("config-patch.yaml")),
      statefulSet,
      Files.readString(component.resolve("service.yaml")),
      Files.readString(component.resolve("zero-trust.yaml")),
      Files.readString(component.resolve("grants.yaml")),
      Files.readString(replicated.resolve("peers.yaml")),
      Files.readString(replicated.resolve("layout.yaml")),
      storeSecret
    )
    k3s.copyFileToContainer(
      Transferable.of(parts.mkString("\n---\n").getBytes(StandardCharsets.UTF_8)),
      "/tmp/ankka-object-store.yaml"
    )
    val applied = k3s.execInContainer(
      "kubectl",
      "apply",
      "--server-side",
      "--force-conflicts",
      "-f",
      "/tmp/ankka-object-store.yaml"
    )
    if applied.getExitCode != 0 then
      throw new AssertionError(s"applying the object store failed: ${applied.getStderr}")
    waitFor(180.seconds, "three nodes ready") {
      PkiStack.jsonPath(
        k3s,
        "-n",
        Namespace,
        "statefulset",
        Service,
        "{.status.readyReplicas}"
      ) == "3"
    }
    val forward = k8s.services().inNamespace(Namespace).withName(Service).portForward(3903)
    // Three nodes laid out are one store only once each holds the layout: until then an
    // administration call can be refused for a quorum, which reads like an outage.
    var health = ""
    try
      waitFor(300.seconds, "the store healthy") {
        val response = java.net.http.HttpClient
          .newHttpClient()
          .send(
            java.net.http.HttpRequest
              .newBuilder(
                java.net.URI.create(s"http://127.0.0.1:${forward.getLocalPort}/v2/GetClusterHealth")
              )
              .header("Authorization", s"Bearer $AdminToken")
              .GET()
              .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString()
          )
        health = response.body()
        response.statusCode() == 200 && health.contains("\"healthy\"")
      }
    catch
      case e: AssertionError =>
        val job =
          k3s.execInContainer("kubectl", "logs", "-n", Namespace, "deployment/garage-layout")
        throw new AssertionError(
          s"${e.getMessage}: $health\nthe layout loop said:\n${job.getStdout}${job.getStderr}"
        )
    Installed(
      ObjectStoreSettings(
        adminUrl = s"http://127.0.0.1:${forward.getLocalPort}",
        adminToken = AdminToken,
        endpoint = InClusterEndpoint,
        region = "garage",
        service = ObjectStoreSettings.ServiceRef(Namespace, Service, 3900)
      ),
      forward
    )

  private def waitFor(timeout: FiniteDuration, what: String)(check: => Boolean): Unit =
    val deadline = System.nanoTime() + timeout.toNanos
    var passed   = false
    while !passed && System.nanoTime() < deadline do
      passed =
        try check
        catch case _: Exception => false
      if !passed then Thread.sleep(1000)
    if !passed then throw new AssertionError(s"$what did not happen within $timeout")
