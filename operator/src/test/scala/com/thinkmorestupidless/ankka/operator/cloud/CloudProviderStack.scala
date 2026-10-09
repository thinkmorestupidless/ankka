package com.thinkmorestupidless.ankka.operator.cloud

import com.thinkmorestupidless.ankka.crd.AnkkaSerialization
import io.fabric8.kubernetes.client.{ConfigBuilder, KubernetesClient, KubernetesClientBuilder}
import org.testcontainers.k3s.K3sContainer

import scala.jdk.CollectionConverters.*

/**
 * What a k3s suite installs for a cloud provider (feature 044): the `CloudResource` type and the
 * `cloud-provider` component's identity and grant, both from the shipped files, and a client that
 * acts as that identity and nothing more. The scripted provider is then started on that client, so
 * a verb the grant withholds is refused by the API server, not merely left unused.
 */
object CloudProviderStack:

  val Namespace: String      = "ankka-cloud-provider"
  val ServiceAccount: String = "ankka-cloud-provider"

  /** The CRD and the provider's namespace, ServiceAccount and grant, applied by the admin. */
  def install(k3s: K3sContainer, admin: KubernetesClient): Unit =
    admin.load(resource("/ankka/crd/cloudresource.yaml")).serverSideApply(): Unit
    for file <- Vector("namespace.yaml", "rbac.yaml") do
      admin
        .load(resource(s"/ankka/install/cloud-provider/$file"))
        .items()
        .asScala
        .foreach(o => admin.resource(o).serverSideApply(): Unit)
    val established = k3s.execInContainer(
      "kubectl",
      "wait",
      "--for=condition=Established",
      "--timeout=60s",
      "crd/cloudresources.ankka.thinkmorestupidless.com"
    )
    require(established.getExitCode == 0, s"the CloudResource type: ${established.getStderr}")

  /** A token for the provider's ServiceAccount, as `OperatorClusterSuite` mints the operator's. */
  def token(k3s: K3sContainer): String =
    val result = k3s.execInContainer(
      "kubectl",
      "create",
      "token",
      ServiceAccount,
      "-n",
      Namespace,
      "--duration=60m"
    )
    require(result.getExitCode == 0, result.getStderr)
    result.getStdout.trim

  /** A client that is the provider's identity, on the admin's API server. */
  def client(admin: KubernetesClient, token: String): KubernetesClient =
    new KubernetesClientBuilder()
      .withConfig(
        new ConfigBuilder()
          .withMasterUrl(admin.getConfiguration.getMasterUrl)
          .withTrustCerts(true)
          .withOauthToken(token)
          .build()
      )
      .withKubernetesSerialization(AnkkaSerialization())
      .build()

  private def resource(path: String) =
    val stream = getClass.getResourceAsStream(path)
    require(stream != null, s"$path is not on the test classpath: check the symlink")
    stream
