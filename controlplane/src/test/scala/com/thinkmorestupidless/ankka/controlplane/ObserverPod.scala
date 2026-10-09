package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.api.{
  InstanceStatus,
  InstanceTopology,
  InstanceTopologyDocument
}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.deploy.{InstanceTopologies, TopologyReader}
import com.thinkmorestupidless.ankka.operator.{InPod, PkiStack}
import io.fabric8.kubernetes.client.KubernetesClient
import org.testcontainers.images.builder.Transferable
import org.testcontainers.k3s.K3sContainer

import java.nio.charset.StandardCharsets
import scala.util.Try

/**
 * The control plane's view of a deployed service's topology, for a suite whose control plane runs
 * in the test JVM. The observe port admits exactly `ankka://platform/controlplane`, from the
 * control plane's namespace and pods, so the reads are made from a pod that is all three: in
 * `ankka-controlplane`, labelled as the control plane, holding a certificate the service authority
 * issued for that identity. The documents it reads are the instances' own, decoded as the shipped
 * reader decodes them.
 */
object ObserverPod:

  private val Namespace = "ankka-controlplane"
  private val Name      = "observer"

  /** Makes the pod and its certificate, and waits for both. Once per cluster. */
  def install(k3s: K3sContainer): Unit =
    val manifest =
      s"""apiVersion: v1
         |kind: Namespace
         |metadata: { name: $Namespace }
         |---
         |apiVersion: cert-manager.io/v1
         |kind: Certificate
         |metadata: { name: $Name, namespace: $Namespace }
         |spec:
         |  secretName: $Name-tls
         |  duration: 24h
         |  privateKey: { algorithm: RSA, size: 2048 }
         |  uris: ["ankka://platform/controlplane"]
         |  usages: ["client auth", "digital signature", "key encipherment"]
         |  issuerRef: { name: ankka-service, kind: ClusterIssuer, group: cert-manager.io }
         |---
         |apiVersion: v1
         |kind: Pod
         |metadata:
         |  name: $Name
         |  namespace: $Namespace
         |  labels: { app.kubernetes.io/name: ankka-controlplane }
         |spec:
         |  containers:
         |    - name: caller
         |      image: curlimages/curl:8.11.1
         |      command: ["sleep", "infinity"]
         |      volumeMounts:
         |        - { name: service, mountPath: /var/run/secrets/ankka/service, readOnly: true }
         |  volumes:
         |    - name: service
         |      secret: { secretName: $Name-tls }
         |""".stripMargin
    k3s.copyFileToContainer(
      Transferable.of(manifest.getBytes(StandardCharsets.UTF_8)),
      "/tmp/observer.yaml"
    )
    PkiStack.kubectl(k3s, "apply", "-f", "/tmp/observer.yaml"): Unit
    PkiStack.kubectl(
      k3s,
      "wait",
      "-n",
      Namespace,
      "--for=condition=Ready",
      s"pod/$Name",
      "--timeout=240s"
    ): Unit

  /** A reader over the instances of `prefix-<project>`, through the observer pod. */
  def reader(k3s: K3sContainer, k8s: KubernetesClient, prefix: String): TopologyReader =
    new InstanceTopologies(
      InstanceTopologies.runningPods(k8s, prefix),
      (_, _, pod, ip) =>
        val (code, body) = Try(
          InPod.curl(
            k3s,
            Namespace,
            Name,
            s"https://$ip:${InstanceTopologies.ObservePort}/observability/topology",
            verifyHost = false
          )
        ).getOrElse((0, ""))
        if code == 200 then
          Try(readFromString[InstanceTopologyDocument](body)).toOption match
            case Some(document) => InstanceTopology(pod, InstanceStatus.Ok) -> Some(document)
            case None =>
              InstanceTopology(pod, InstanceStatus.Unreachable, Some("undecodable")) -> None
        else InstanceTopology(pod, InstanceStatus.Unreachable, Some(s"answered $code")) -> None
      ,
      perInstance = scala.concurrent.duration.DurationInt(15).seconds
    )
