package com.thinkmorestupidless.ankka.operator

import io.fabric8.kubernetes.client.KubernetesClient
import org.testcontainers.images.builder.Transferable
import org.testcontainers.k3s.K3sContainer

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.concurrent.duration.*

/**
 * The installation's authorities in a test cluster (feature 014): cert-manager, trust-manager, and
 * the `pki` component — exactly the files the overlays apply, through the node's own `kubectl`, as
 * `deploy-local.sh` does. Every suite that runs the operator needs it, since the operator asks
 * cert-manager for every workload's certificates and a pod starts only once they exist.
 *
 * Idempotent: `GatewayStack.install` calls it too, and a suite may call both.
 */
object PkiStack:

  def repoRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.isDirectory(p.resolve("kustomization")))
      .getOrElse(throw new AssertionError("could not find the repository root"))

  def install(k3s: K3sContainer, k8s: KubernetesClient): Unit =
    val root = repoRoot
    kubectl(k3s, "apply", "--server-side", "--force-conflicts", "-f", GatewayStack.CertManager)
    waitFor(180.seconds, "cert-manager's webhook is ready") {
      readyReplicas(k8s, "cert-manager", "cert-manager-webhook") > 0
    }
    applyFile(k3s, root.resolve("kustomization/components/trust-manager/trust-manager.yaml"))
    waitFor(180.seconds, "trust-manager is ready") {
      readyReplicas(k8s, "cert-manager", "trust-manager") > 0
    }
    // The Bundle is admitted by trust-manager's webhook, which may take a moment after its
    // Deployment reports ready; retried rather than slept on.
    waitFor(120.seconds, "the pki component applies") {
      applyFileOnce(k3s, root.resolve("kustomization/components/pki/authorities.yaml")) &&
      applyFileOnce(k3s, root.resolve("kustomization/components/pki/service-ca-bundle.yaml"))
    }
    for issuer <- Vector("ankka-cluster", "ankka-service") do
      waitFor(120.seconds, s"ClusterIssuer $issuer is Ready") {
        jsonPath(
          k3s,
          "clusterissuer",
          issuer,
          """{.status.conditions[?(@.type=="Ready")].status}"""
        ) == "True"
      }

  /** Runs `kubectl` on the node; fails loudly with its stderr. */
  def kubectl(k3s: K3sContainer, args: String*): String =
    val result = k3s.execInContainer(("kubectl" +: args)*)
    if result.getExitCode != 0 then
      throw new AssertionError(s"kubectl ${args.mkString(" ")} failed: ${result.getStderr}")
    result.getStdout

  def jsonPath(k3s: K3sContainer, args: String*): String =
    k3s
      .execInContainer(
        (Vector("kubectl", "get") ++ args.dropRight(1) ++ Vector("-o", s"jsonpath=${args.last}"))*
      )
      .getStdout
      .trim

  private def applyFile(k3s: K3sContainer, file: Path): Unit =
    if !applyFileOnce(k3s, file) then throw new AssertionError(s"applying $file failed")

  private def applyFileOnce(k3s: K3sContainer, file: Path): Boolean =
    val target = s"/tmp/${file.getFileName}"
    k3s.copyFileToContainer(
      Transferable.of(Files.readString(file).getBytes(StandardCharsets.UTF_8)),
      target
    )
    k3s
      .execInContainer("kubectl", "apply", "--server-side", "--force-conflicts", "-f", target)
      .getExitCode == 0

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

/**
 * Requests to a deployed service the way the platform admits them: from inside one of the service's
 * own pods, presenting that pod's certificate. Since feature 014 every port a service has is mutual
 * TLS and admits only the sources its network policy names, so a test's plain `wget` from the node
 * reaches nothing — which is the point.
 *
 * The images ankka deploys are built on `eclipse-temurin`, which carries `curl`.
 */
object InPod:

  /**
   * A client that outlives every pod of a service, named `prober`: a curl pod in `namespace`,
   * labelled as a platform workload so the service's HTTP policy admits it, holding `service`'s own
   * service certificate so its HTTP port accepts it. A request from inside one of the service's
   * pods stops measuring the platform the moment that pod rolls or crashes — the exec fails
   * (`container not found`), not the service — and the node cannot call at all, since it holds no
   * identity. Idempotent: applying it twice leaves one pod. Answers the pod's name, for `curl`.
   */
  def prober(k3s: K3sContainer, namespace: String, service: String): String =
    val manifest =
      s"""apiVersion: v1
         |kind: Pod
         |metadata:
         |  name: prober
         |  namespace: $namespace
         |  labels: { app.kubernetes.io/managed-by: ankka }
         |spec:
         |  containers:
         |    - name: curl
         |      image: curlimages/curl:8.11.1
         |      command: ["sleep", "infinity"]
         |      volumeMounts:
         |        - { name: service, mountPath: /var/run/secrets/ankka/service, readOnly: true }
         |  volumes:
         |    - name: service
         |      secret: { secretName: $service-service-tls }
         |""".stripMargin
    k3s.copyFileToContainer(
      Transferable.of(manifest.getBytes(StandardCharsets.UTF_8)),
      s"/tmp/prober-$namespace.yaml"
    )
    // A pod's volumes are immutable, so a prober holding another service's certificate cannot be
    // re-applied as this one: it is removed first, and a prober for the same service is kept.
    val holding =
      PkiStack.jsonPath(
        k3s,
        "pod",
        "prober",
        "-n",
        namespace,
        "{.spec.volumes[0].secret.secretName}"
      )
    if holding.nonEmpty && holding != s"$service-service-tls" then
      PkiStack.kubectl(k3s, "delete", "pod", "prober", "-n", namespace, "--wait=true"): Unit
    PkiStack.kubectl(k3s, "apply", "-f", s"/tmp/prober-$namespace.yaml"): Unit
    PkiStack.kubectl(
      k3s,
      "wait",
      "-n",
      namespace,
      "--for=condition=Ready",
      "pod/prober",
      "--timeout=180s"
    ): Unit
    "prober"

  /**
   * `curl` inside `pod`'s node container. `identity` is `cluster` (management, remoting's peer
   * certificate) or `service` (HTTP). Answers the status and body; a TLS or connection failure is
   * status 0 with curl's message.
   *
   * `timings` adds curl's connect, TLS, first-byte and total times to the body, so a failure says
   * where the time went: a stalled connection, or a server that accepted and never answered.
   */
  def curl(
      k3s: K3sContainer,
      namespace: String,
      pod: String,
      url: String,
      identity: String = "service",
      method: String = "GET",
      body: Option[String] = None,
      verifyHost: Boolean = true,
      container: Option[String] = None,
      headers: Seq[String] = Nil,
      maxSeconds: Int = 10,
      timings: Boolean = false
  ): (Int, String) =
    val dir = s"/var/run/secrets/ankka/$identity"
    val tls =
      Vector("--cert", s"$dir/tls.crt", "--key", s"$dir/tls.key", "--cacert", s"$dir/ca.crt") ++
        // A pod is reached by IP, which no certificate names; the identity check that matters is the
        // server's, which requires this pod's certificate.
        (if verifyHost then Vector.empty else Vector("--insecure"))
    val payload =
      body.toVector.flatMap(b => Vector("-H", "Content-Type: application/json", "--data", b)) ++
        headers.toVector.flatMap(h => Vector("-H", h))
    val args = Vector("kubectl", "exec", "-n", namespace, pod) ++
      container.toVector.flatMap(c => Vector("-c", c)) ++
      Vector(
        "--",
        "curl",
        "-sS",
        "-m",
        maxSeconds.toString,
        "-X",
        method,
        "-w",
        if timings then s"\n%{http_code} $Timings" else "\n%{http_code}"
      ) ++ tls ++ payload :+ url
    val result = k3s.execInContainer(args*)
    val out    = result.getStdout
    val lines  = out.split("\n", -1).toVector
    val last   = lines.lastOption.map(_.trim).getOrElse("")
    val code   = last.takeWhile(_ != ' ').toIntOption.getOrElse(0)
    val times  = if timings then " (" + last.dropWhile(_ != ' ').trim + ")" else ""
    if result.getExitCode != 0 then (0, (out + result.getStderr).trim)
    else (code, lines.dropRight(1).mkString("\n") + times)

  private val Timings =
    "connect=%{time_connect}s tls=%{time_appconnect}s first-byte=%{time_starttransfer}s total=%{time_total}s"
