package com.thinkmorestupidless.ankka.operator

import io.fabric8.kubernetes.client.{KubernetesClient, LocalPortForward}
import org.testcontainers.images.builder.Transferable
import org.testcontainers.k3s.K3sContainer

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/**
 * The installation's model server on a k3s node: the files of `kustomization/components/models`,
 * applied with the node's own `kubectl` as `ObjectStoreStack` applies the object store's, so a
 * manifest a suite passes with is the manifest that ships (feature 053).
 *
 * Everything but the two patches: a suite runs its operator and its control plane in the test's own
 * JVM, so the operator is handed `settings` and the control plane reads the door through `door`.
 * Two things differ from the component, each checked to be where this expects it: the keeper's
 * image is the tag this build made, and the certificate also names `localhost`, the one name a
 * forward from the test JVM can be reached by while the control plane's reader checks the server's
 * name.
 *
 * Ollama's image is pulled by the node from Docker Hub, and the declared models from Ollama's
 * registry, so the first install takes minutes.
 *
 * Shared by the control plane's suites through `test->test`, like `ObjectStoreStack`.
 */
object ModelServerStack:

  val Namespace: String = "models-system"
  val Name: String      = "models"

  /** The address a service is given, which the certificate names. */
  val InClusterAddress: String = s"https://$Name.$Namespace.svc:8443"

  /** The server, installed: the operator's settings, and the forwards that carry them. */
  final case class Installed(
      settings: ModelServerSettings,
      statusForward: LocalPortForward,
      doorForward: LocalPortForward
  ):
    /** The door as the test JVM reaches it, under a name the certificate carries. */
    def door: String = s"https://localhost:${doorForward.getLocalPort}"
    def close(): Unit =
      statusForward.close()
      doorForward.close()

  private val Files_ = Vector(
    "namespace.yaml",
    "declaration.yaml",
    "certificate.yaml",
    "statefulset.yaml",
    "service.yaml",
    "zero-trust.yaml"
  )

  /** The declaration's ConfigMap, naming `models`. */
  def declaration(models: Vector[String]): String =
    val listed = models.map(m => s"\"$m\"").mkString(", ")
    s"""apiVersion: v1
       |kind: ConfigMap
       |metadata:
       |  name: models-declaration
       |  namespace: $Namespace
       |data:
       |  models.json: |
       |    {"models": [$listed]}
       |""".stripMargin

  def install(
      k3s: K3sContainer,
      k8s: KubernetesClient,
      repoRoot: Path,
      keeperImage: String,
      models: Vector[String] = Vector("qwen3:0.6b")
  ): Installed =
    val component = repoRoot.resolve("kustomization/components/models")
    val parts = Files_.map { file =>
      val text = Files.readString(component.resolve(file))
      file match
        case "declaration.yaml" => declaration(models)
        case "statefulset.yaml" =>
          expect(text, "image: ankka-model-keeper:latest", file)
          text.replace("image: ankka-model-keeper:latest", s"image: $keeperImage")
        case "certificate.yaml" =>
          expect(text, "    - models.models-system.svc.cluster.local\n", file)
          text.replace(
            "    - models.models-system.svc.cluster.local\n",
            "    - models.models-system.svc.cluster.local\n    - localhost\n"
          )
        case _ => text
    }
    applyText(k3s, parts.mkString("\n---\n"), "/tmp/ankka-models.yaml")
    awaitReady(k3s, 900.seconds)
    val status = forward(k8s, 8080)
    Installed(
      ModelServerSettings(
        address = InClusterAddress,
        statusUrl = s"http://127.0.0.1:${status.getLocalPort}/ankka/models",
        namespace = Namespace
      ),
      status,
      forward(k8s, 8443)
    )

  /** Replaces the declaration, as an administrator editing the ConfigMap does. */
  def redeclare(k3s: K3sContainer, models: Vector[String]): Unit =
    applyText(k3s, declaration(models), "/tmp/ankka-models-declaration.yaml")

  /** Restarts the server's one pod and waits for it to be ready again. */
  def restart(k3s: K3sContainer): Unit =
    PkiStack.kubectl(k3s, "-n", Namespace, "delete", "pod", s"$Name-0", "--wait=true"): Unit
    awaitReady(k3s, 600.seconds)

  /** The keeper's log in the server's current pod. */
  def keeperLog(k3s: K3sContainer): String =
    PkiStack.kubectl(k3s, "-n", Namespace, "logs", s"$Name-0", "-c", "keeper")

  private def awaitReady(k3s: K3sContainer, timeout: FiniteDuration): Unit =
    waitFor(timeout, "the model server is ready", state(k3s)) {
      PkiStack.jsonPath(
        k3s,
        "-n",
        Namespace,
        "statefulset",
        Name,
        "{.status.readyReplicas}"
      ) == "1"
    }

  /**
   * A forward to the pod, not the Service: a forward never goes through a Service, and the pod is
   * the one replica. Port forwards also pass the network policy, which no test here relies on.
   */
  private def forward(k8s: KubernetesClient, port: Int): LocalPortForward =
    k8s.pods().inNamespace(Namespace).withName(s"$Name-0").portForward(port)

  private def applyText(k3s: K3sContainer, text: String, at: String): Unit =
    k3s.copyFileToContainer(Transferable.of(text.getBytes(StandardCharsets.UTF_8)), at)
    val applied =
      k3s.execInContainer("kubectl", "apply", "--server-side", "--force-conflicts", "-f", at)
    if applied.getExitCode != 0 then
      throw new AssertionError(s"applying the model server failed: ${applied.getStderr}")

  private def expect(text: String, line: String, file: String): Unit =
    if !text.contains(line) then
      throw new AssertionError(s"$file no longer holds '${line.trim}', which this stack rewrites")

  /** What the node says about the server's pod, for a wait that ran out. */
  private def state(k3s: K3sContainer): () => String = () =>
    def run(args: String*): String =
      val result = k3s.execInContainer(("kubectl" +: args)*)
      s"$$ kubectl ${args.mkString(" ")}\n${result.getStdout}${result.getStderr}"
    Vector(
      run("-n", Namespace, "get", "pods,pvc", "-o", "wide"),
      run("-n", Namespace, "describe", "pod", s"$Name-0"),
      run("-n", Namespace, "logs", s"$Name-0", "-c", "keeper", "--tail=60"),
      run("-n", Namespace, "logs", s"$Name-0", "-c", "ollama", "--tail=30")
    ).mkString("\n")

  private def waitFor(timeout: FiniteDuration, what: String, state: () => String)(
      check: => Boolean
  ): Unit =
    val deadline = System.nanoTime() + timeout.toNanos
    var passed   = false
    while !passed && System.nanoTime() < deadline do
      passed =
        try check
        catch case _: Exception => false
      if !passed then Thread.sleep(2000)
    if !passed then
      val seen =
        try state()
        catch case e: Exception => s"(the pod's state could not be read: ${e.getMessage})"
      throw new AssertionError(s"$what did not happen within $timeout\n$seen")
