package com.thinkmorestupidless.ankka.operator

import org.testcontainers.images.builder.Transferable
import org.testcontainers.k3s.K3sContainer

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/**
 * The installation's broker in a test cluster: the `broker` component's own files, applied through
 * the node's `kubectl` in the order `deploy-local.sh` applies them, with one difference — the node
 * pool's storage is ephemeral, since a test cluster outlives no restart.
 *
 * The component's patch onto the operator's Deployment is not applied: a suite runs the operator
 * in-process and gives it the settings this returns, which are the ones that patch writes.
 *
 * Assumes `PkiStack.install` has run: the broker's certificate is issued by the service authority.
 * About a minute and a half on top of the suite's other installs (research R1).
 */
object BrokerStack:

  val Namespace: String = "ankka-broker"
  val Cluster: String   = "ankka"

  /** What the component's patch tells the operator. */
  val settings: BrokerSettings =
    BrokerSettings(s"$Cluster-kafka-bootstrap.$Namespace.svc:9093", Namespace, Cluster)

  def install(k3s: K3sContainer): BrokerSettings =
    val component = PkiStack.repoRoot.resolve("kustomization/components/broker")
    apply(k3s, component.resolve("namespace.yaml"))
    // Strimzi's operator and CRDs: a Kustomization of their own, rendered by the node's kubectl.
    copy(
      k3s,
      component.resolve("strimzi/kustomization.yaml"),
      "/tmp/broker-strimzi/kustomization.yaml"
    )
    PkiStack.kubectl(
      k3s,
      "apply",
      "-k",
      "/tmp/broker-strimzi",
      "--server-side",
      "--force-conflicts"
    ): Unit
    waitFor(300.seconds, "Strimzi's cluster operator is ready") {
      PkiStack.jsonPath(
        k3s,
        "deployment",
        "-n",
        Namespace,
        "strimzi-cluster-operator",
        "{.status.readyReplicas}"
      ) == "1"
    }
    apply(k3s, component.resolve("certificate.yaml"))
    apply(k3s, component.resolve("operator-role.yaml"))
    val persistent =
      """        type: persistent-claim
        |        size: 2Gi
        |        deleteClaim: false
        |""".stripMargin
    val kafka = Files.readString(component.resolve("kafka.yaml"))
    if !kafka.contains(persistent) then
      throw new AssertionError("kafka.yaml's storage is not the block BrokerStack makes ephemeral")
    applyText(k3s, kafka.replace(persistent, "        type: ephemeral\n"), "/tmp/broker-kafka.yaml")
    waitFor(600.seconds, "the installation's Kafka is Ready") {
      PkiStack.jsonPath(
        k3s,
        "kafka",
        "-n",
        Namespace,
        Cluster,
        """{.status.conditions[?(@.type=="Ready")].status}"""
      ) == "True"
    }
    settings

  private def apply(k3s: K3sContainer, file: Path): Unit =
    applyText(k3s, Files.readString(file), s"/tmp/broker-${file.getFileName}")

  private def applyText(k3s: K3sContainer, text: String, target: String): Unit =
    k3s.copyFileToContainer(Transferable.of(text.getBytes(StandardCharsets.UTF_8)), target)
    PkiStack.kubectl(k3s, "apply", "--server-side", "--force-conflicts", "-f", target): Unit

  private def copy(k3s: K3sContainer, file: Path, target: String): Unit =
    k3s.copyFileToContainer(Transferable.of(Files.readAllBytes(file)), target)

  private def waitFor(timeout: FiniteDuration, what: String)(check: => Boolean): Unit =
    val deadline = timeout.fromNow
    var passed   = false
    while !passed && deadline.hasTimeLeft() do
      passed =
        try check
        catch case _: Exception => false
      if !passed then Thread.sleep(2000)
    if !passed then throw new AssertionError(s"$what did not happen within $timeout")
