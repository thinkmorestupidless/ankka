package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.crd.{
  AnkkaProject,
  AnkkaProjectSpec,
  AnkkaSerialization,
  AnkkaService,
  AnkkaServiceSpec,
  CloudResource,
  EnvEntry,
  ProjectSecretEntry
}
import com.thinkmorestupidless.ankka.operator.{
  CloudSettings,
  Operator,
  PkiStack,
  ServiceReconciler,
  Settings as OperatorSettings
}
import com.thinkmorestupidless.ankka.operator.cloud.{
  CloudProviderStack,
  ScriptedCloudProvider,
  ScriptedFulfilment
}
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.fabric8.kubernetes.api.model.{NamespaceBuilder, ObjectMetaBuilder}
import io.fabric8.kubernetes.client.{Config, KubernetesClient, KubernetesClientBuilder}
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * A service on Secret Manager on a real Kubernetes, with the installation's cloud provider (feature
 * 038 on feature 044's requests): the operator in this JVM, told the backend is `secret-manager`
 * and the provider is `gcp`, and 044's scripted provider answering on a client minted from the
 * provider's own ServiceAccount — started and stopped by the cases, so what the operator does
 * before an answer can be seen.
 *
 * A service is `pause`, with no database and no port: what is proved is what the operator asks of
 * the provider and when it applies the Deployment, not that a program runs. No control plane: the
 * resources are applied as the control plane would project them.
 *
 * Disable with `-Dankka.cluster.tests=off`.
 */
class SecretsCloudClusterSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout: FiniteDuration = 20.minutes

  override def munitIgnore: Boolean = sys.props.get("ankka.cluster.tests").contains("off")

  private val K3sImage  = "rancher/k3s:v1.35.1-k3s1"
  private val Project   = "shop"
  private val Namespace = s"ankka-$Project"
  private val cloud =
    CloudSettings("gcp", "scripted-account", "scripted-location", None, 30.seconds, 20.seconds)

  private var k3s: K3sContainer                       = null
  private var k8s: KubernetesClient                   = null
  private var providerClient: KubernetesClient        = null
  private var operator: Operator                      = null
  private var fulfilment: ScriptedFulfilment          = null
  private var provider: Option[ScriptedCloudProvider] = None

  override def beforeAll(): Unit =
    if !munitIgnore then
      k3s = new K3sContainer(DockerImageName.parse(K3sImage))
      k3s.start()
      k8s = new KubernetesClientBuilder()
        .withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml))
        .withKubernetesSerialization(AnkkaSerialization())
        .build()
      for crd <- Vector("ankkaservice.yaml", "ankkaproject.yaml") do
        k8s.load(getClass.getResourceAsStream(s"/ankka/crd/$crd")).serverSideApply(): Unit
      CloudProviderStack.install(k3s, k8s)
      PkiStack.install(k3s, k8s)
      fulfilment = ScriptedFulfilment(cloud.provider, cloud.account, cloud.location, 20.seconds)
      providerClient = CloudProviderStack.client(k8s, CloudProviderStack.token(k3s))
      k8s
        .namespaces()
        .resource(
          new NamespaceBuilder()
            .withMetadata(new ObjectMetaBuilder().withName(Namespace).build())
            .build()
        )
        .serverSideApply(): Unit
      val settings = OperatorSettings.default.copy(
        resyncInterval = 2.seconds,
        cloud = Some(cloud),
        secretStore = OperatorSettings.SecretStore(backend = Some("secret-manager"))
      )
      operator = new Operator(k8s, settings, ServiceReconciler(k8s, settings))
      operator.start()

  override def afterAll(): Unit =
    if operator != null then operator.close()
    provider.foreach(_.stop())
    if providerClient != null then providerClient.close()
    if k8s != null then k8s.close()
    if k3s != null then k3s.stop()

  private def startProvider(): Unit =
    if provider.isEmpty then
      val p = ScriptedCloudProvider(providerClient, fulfilment)
      p.start()
      provider = Some(p)

  private def stopProvider(): Unit =
    provider.foreach(_.stop())
    provider = None

  private def waitFor(timeout: FiniteDuration, what: String)(check: => Boolean): Unit =
    val deadline = System.nanoTime() + timeout.toNanos
    var passed   = false
    while !passed && System.nanoTime() < deadline do
      passed =
        try check
        catch case _: Throwable => false
      if !passed then Thread.sleep(500)
    if !passed then fail(s"$what did not happen within $timeout")

  private def apply(name: String, env: List[EnvEntry] = Nil): Unit =
    val resource = AnkkaService(
      Namespace,
      name,
      AnkkaServiceSpec(
        projectId = Project,
        serviceName = name,
        generation = 1L,
        image = "registry.k8s.io/pause:3.9",
        env = env,
        provisionDatabase = false,
        database = "none"
      )
    )
    k8s.resource(resource).serverSideApply(): Unit

  private def request(name: String): Option[CloudResource] =
    Option(k8s.resources(classOf[CloudResource]).inNamespace(Namespace).withName(name).get())

  private def deployed(name: String): Boolean =
    k8s.apps().deployments().inNamespace(Namespace).withName(name).get() != null

  private def detail(name: String): String =
    Option(k8s.resources(classOf[AnkkaService]).inNamespace(Namespace).withName(name).get())
      .flatMap(r => Option(r.getStatus))
      .flatMap(_.detail)
      .getOrElse("")

  test("a service is not rolled out until its access to its secrets is granted") {
    stopProvider()
    apply("payments")
    waitFor(60.seconds, "the identity request")(request("payments-identity").isDefined)
    // Nothing has answered: no access asked yet, since it must name the identity, and no Deployment.
    Thread.sleep(5000)
    assertEquals(request("payments-secret-access"), None)
    assert(!deployed("payments"), "rolled out before its access was granted")
    waitFor(60.seconds, "the status to say why it waits")(
      detail("payments").contains("waiting on the cloud provider")
    )

    startProvider()
    waitFor(60.seconds, "the access request")(request("payments-secret-access").isDefined)
    val asked = request("payments-secret-access").get.getSpec.parameters
    // The identity the provider made for the service's ServiceAccount, as its answer names it.
    val made = request("payments-identity").flatMap(r => Option(r.getStatus)).map(_.outputs)
    assertEquals(Some(asked("identity")), made.flatMap(_.get("identity")))
    assertEquals(asked("own"), "s_shop_payments_")
    assertEquals(asked("read"), "p_shop_")
    waitFor(120.seconds, "the Deployment, once access is granted")(deployed("payments"))
  }

  test("a project secret's entry reaches the pod once it is synced to Secret Manager") {
    stopProvider()
    val project = AnkkaProject(
      Namespace,
      Project,
      AnkkaProjectSpec(
        projectId = Project,
        secrets = List(ProjectSecretEntry("checkout", List("STRIPE_KEY"), 1700L))
      )
    )
    k8s.resource(project).serverSideApply(): Unit
    apply(
      "orders",
      List(EnvEntry("STRIPE_KEY", secretName = Some("checkout"), secretKey = Some("STRIPE_KEY")))
    )
    waitFor(60.seconds, "the sync request")(request("shop.secret-sync.checkout").isDefined)
    val asked = request("shop.secret-sync.checkout").get.getSpec.parameters
    assertEquals(asked("entries"), "STRIPE_KEY=p_shop_checkout__STRIPE_uKEY")
    assertEquals(asked("entryGeneration"), "1700")
    Thread.sleep(5000)
    assert(!deployed("orders"), "rolled out before its project secret was in step")

    startProvider()
    waitFor(120.seconds, "the Deployment, once the secret is in step")(deployed("orders"))
    // The value the provider kept in the project's Secret is the one the pod's variable names.
    val secret = k8s.secrets().inNamespace(Namespace).withName("checkout").get()
    assertEquals(
      String(Base64.getDecoder.decode(secret.getData.get("STRIPE_KEY")), StandardCharsets.UTF_8),
      "scripted-value-of-p_shop_checkout__STRIPE_uKEY"
    )
    val variable = k8s
      .apps()
      .deployments()
      .inNamespace(Namespace)
      .withName("orders")
      .get()
      .getSpec
      .getTemplate
      .getSpec
      .getContainers
      .asScala
      .flatMap(_.getEnv.asScala)
      .find(_.getName == "STRIPE_KEY")
      .getOrElse(fail("the pod has no STRIPE_KEY"))
    assertEquals(variable.getValueFrom.getSecretKeyRef.getName, "checkout")
    assertEquals(variable.getValueFrom.getSecretKeyRef.getKey, "STRIPE_KEY")
  }
