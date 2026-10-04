package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.AnkkaSerialization
import io.fabric8.kubernetes.client.{Config, KubernetesClientBuilder}
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName

import scala.concurrent.duration.*

/**
 * Whether the `broker` component comes up as written: Strimzi's part through `kubectl apply -k`,
 * the broker's certificate from the service authority, and a Kafka that reaches `Ready` with the
 * listener of research R3. Gated on `-Dankka.spikes=on`; the broker's k3s suite proves the rest.
 *
 * sbt -Dankka.spikes=on 'operator/testOnly *BrokerStackSpike'
 */
class BrokerStackSpike extends munit.FunSuite:

  override def munitIgnore: Boolean = !sys.props.get("ankka.spikes").contains("on")
  override val munitTimeout         = 20.minutes

  test("the broker component comes up Ready in a k3s node with the installation's authorities") {
    val k3s = new K3sContainer(DockerImageName.parse("rancher/k3s:v1.35.1-k3s1"))
    k3s.start()
    try
      val client = new KubernetesClientBuilder()
        .withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml))
        .withKubernetesSerialization(AnkkaSerialization())
        .build()
      PkiStack.install(k3s, client)
      val started = System.nanoTime()
      val broker  = BrokerStack.install(k3s)
      println(s"BrokerStack.install: ${(System.nanoTime() - started) / 1_000_000_000}s")
      assertEquals(broker, BrokerStack.settings)
      // The listener serves the certificate cert-manager issued, not one of Strimzi's own.
      val issued = PkiStack.jsonPath(
        k3s,
        "secret",
        "-n",
        BrokerStack.Namespace,
        "ankka-broker-tls",
        "{.metadata.annotations.cert-manager\\.io/issuer-name}"
      )
      assertEquals(issued, "ankka-service")
    finally k3s.stop()
  }
