package nakka.operator

import io.fabric8.kubernetes.client.KubernetesClient
import org.testcontainers.k3s.K3sContainer

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Base64
import scala.concurrent.duration.*

/**
 * The routing stack a suite needs to prove exposure end to end: cert-manager and Envoy Gateway from
 * the same pinned manifests `kustomization/components/{certmanager,envoy-gateway}` reference, then
 * the platform's `gateway` component and the local CA from the overlay, with the base domain
 * placeholder filled in — the same files `deploy-local.sh` applies, so a manifest this suite passes
 * with is the manifest that ships (feature 005).
 *
 * Shared by the control plane's suites through `test->test`, like `ClusterImages`.
 */
object GatewayStack:

  val CertManager: String =
    "https://github.com/cert-manager/cert-manager/releases/download/v1.21.2/cert-manager.yaml"
  val EnvoyGateway: String =
    "https://github.com/envoyproxy/gateway/releases/download/v1.9.1/install.yaml"

  /**
   * The NodePort the gateway's HTTPS listener is pinned to (components/gateway/envoyproxy.yaml).
   */
  val HttpsNodePort: Int = 30443
  val HttpNodePort: Int  = 30080

  /**
   * @param k3s
   *   the node, whose own `kubectl` applies the two install manifests — as `deploy-local.sh` does.
   *   Not the fabric8 client: its typed round trip of Envoy Gateway's experimental `xbackends` CRD
   *   produces a schema the API server rejects, while the raw manifest is fine.
   */
  def install(k3s: K3sContainer, k8s: KubernetesClient, repoRoot: Path, baseDomain: String): Unit =
    for url <- Vector(CertManager, EnvoyGateway) do
      val result =
        k3s.execInContainer("kubectl", "apply", "--server-side", "--force-conflicts", "-f", url)
      if result.getExitCode != 0 then
        throw new AssertionError(s"applying $url failed: ${result.getStderr}")
    waitForRollout(k8s, "cert-manager", "cert-manager-webhook")
    waitForRollout(k8s, "envoy-gateway-system", "envoy-gateway")

    val component = repoRoot.resolve("kustomization/components/gateway")
    val files = Vector(
      component.resolve("namespace.yaml"),
      component.resolve("gatewayclass.yaml"),
      component.resolve("envoyproxy.yaml"),
      component.resolve("gateway.yaml"),
      component.resolve("redirect-route.yaml"),
      repoRoot.resolve("kustomization/overlays/local/local-ca.yaml")
    )
    // Through kubectl on the node, not the fabric8 client: `selfSigned: {}` is an empty object,
    // and a typed client that omits empty values turns the Issuer into one with no type — the
    // certificate then never issues. The manifests are applied as written, as deploy-local.sh does.
    val combined = files
      .map(file => Files.readString(file).replace("BASE_DOMAIN", baseDomain))
      .mkString("\n---\n")
    k3s.copyFileToContainer(
      org.testcontainers.images.builder.Transferable.of(combined.getBytes(StandardCharsets.UTF_8)),
      "/tmp/nakka-gateway.yaml"
    )
    val applied = k3s.execInContainer(
      "kubectl",
      "apply",
      "--server-side",
      "--force-conflicts",
      "-f",
      "/tmp/nakka-gateway.yaml"
    )
    if applied.getExitCode != 0 then
      throw new AssertionError(s"applying the gateway component failed: ${applied.getStderr}")

    // The same waits deploy-local.sh does, with the same tool: kubectl on the node.
    waitFor(120.seconds, "the wildcard certificate is Ready") {
      nodeJsonPath(
        k3s,
        "-n",
        "nakka-gateway",
        "certificate",
        "nakka-wildcard",
        """{.status.conditions[?(@.type=="Ready")].status}"""
      ) == "True"
    }
    waitFor(120.seconds, "the https listener is Programmed") {
      nodeJsonPath(
        k3s,
        "-n",
        "nakka-gateway",
        "gateway",
        "nakka",
        """{.status.listeners[?(@.name=="https")].conditions[?(@.type=="Programmed")].status}"""
      ) == "True"
    }

  /** The local CA's root, written to a temp file for `curl --cacert` and `config set ca`. */
  def exportCa(k8s: KubernetesClient): Path =
    val secret = k8s.secrets().inNamespace("nakka-gateway").withName("nakka-root-ca").get()
    val pem    = Base64.getDecoder.decode(secret.getData.get("ca.crt"))
    val file   = Files.createTempFile("nakka-local-ca", ".crt")
    Files.write(file, pem)
    file

  private def nodeJsonPath(k3s: K3sContainer, args: String*): String =
    val result = k3s.execInContainer(
      (Vector("kubectl", "get") ++ args.dropRight(1) ++ Vector("-o", s"jsonpath=${args.last}"))*
    )
    result.getStdout.trim

  private def waitForRollout(k8s: KubernetesClient, namespace: String, name: String): Unit =
    waitFor(180.seconds, s"$namespace/$name rolls out") {
      Option(k8s.apps().deployments().inNamespace(namespace).withName(name).get())
        .flatMap(d => Option(d.getStatus))
        .flatMap(s => Option(s.getReadyReplicas))
        .exists(_ > 0)
    }

  private def waitFor(timeout: FiniteDuration, what: String)(check: => Boolean): Unit =
    val deadline = System.nanoTime() + timeout.toNanos
    var passed   = false
    while !passed && System.nanoTime() < deadline do
      passed =
        try check
        catch case _: Exception => false
      if !passed then Thread.sleep(1000)
    if !passed then throw new AssertionError(s"$what did not happen within $timeout")
