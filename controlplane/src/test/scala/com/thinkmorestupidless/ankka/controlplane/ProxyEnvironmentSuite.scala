package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.ServiceSpec
import com.thinkmorestupidless.ankka.crd.{AnkkaService, AnkkaServiceSpec, MountEntry}
import com.thinkmorestupidless.ankka.operator.{
  Action,
  CnpgRendering,
  ProvisioningPlan,
  Rendering,
  Settings
}
import com.thinkmorestupidless.ankka.proxy.core.{Admitted, ProxySettings}
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.apps.Deployment

import scala.jdk.CollectionConverters.*

/**
 * The two ends of the proxy's environment, held to each other (feature 021). The operator writes
 * the proxy container's variables and the proxy parses them; neither module can see the other, and
 * this suite has both. It also holds the descriptor's rule about Secrets to the operator's names:
 * every Secret the platform asks cert-manager to write, or mounts into a pod, is one a descriptor
 * may not read a variable from.
 */
class ProxyEnvironmentSuite extends munit.FunSuite:

  private val settings = Settings.default.copy(
    proxyImage = "ankka-proxy:test",
    baseDomain = Some("example.test"),
    httpsPort = 8443
  )

  private def resource(spec: AnkkaServiceSpec): AnkkaService =
    val r = new AnkkaService
    r.setMetadata(
      new ObjectMetaBuilder()
        .withNamespace(s"ankka-${spec.projectId}")
        .withName(spec.serviceName)
        .withUid("u")
        .build()
    )
    r.setSpec(spec)
    r

  private val web = AnkkaServiceSpec(
    projectId = "shop",
    serviceName = "web",
    generation = 1L,
    image = "shop-web:1.0.0",
    port = Some(9000),
    hosting = Rendering.WebHosting,
    processPort = Some(3000),
    mounts = List(MountEntry("/api/cart", "cart"), MountEntry("/api/orders", "orders")),
    callers = List("orders", "billing/invoices", "*")
  )

  private def actions(spec: AnkkaServiceSpec, plan: ProvisioningPlan, s: Settings = settings) =
    Rendering.render(resource(spec), s, plan).fold(p => fail(p.mkString("; ")), identity)

  private def deployment(spec: AnkkaServiceSpec, s: Settings = settings): Deployment =
    actions(spec, ProvisioningPlan.NotNeeded, s).collectFirst { case Action.ApplyDeployment(d) =>
      d
    }.get

  /** The proxy container's environment, as the proxy would read it. */
  private def proxyEnvironment(
      spec: AnkkaServiceSpec,
      s: Settings = settings
  ): String => Option[String] =
    val proxy = deployment(spec, s).getSpec.getTemplate.getSpec.getContainers.asScala
      .find(_.getName == spec.serviceName)
      .getOrElse(fail("no proxy container"))
    val env = proxy.getEnv.asScala.map(e => e.getName -> e.getValue).toMap
    env.get

  test(
    "what the operator writes for the proxy parses to the same mounts, callers, ports and identity"
  ) {
    val parsed = ProxySettings.fromEnvironment(proxyEnvironment(web)) match
      case Right(s)       => s
      case Left(problems) => fail(problems.mkString("; "))
    assertEquals(parsed.project, "shop")
    assertEquals(parsed.service, "web")
    assertEquals(parsed.port, 9000)
    assertEquals(parsed.processPort, 3000)
    assertEquals(parsed.mounts, Vector("/api/cart" -> "cart", "/api/orders" -> "orders"))
    assertEquals(
      parsed.callers,
      Vector(
        Admitted.Service("shop", "orders"),
        Admitted.Service("billing", "invoices"),
        Admitted.AnyInProject
      )
    )
    assertEquals(parsed.publicAuthority, Some("web-shop.example.test:8443"))
    assertEquals(parsed.namespacePrefix, "ankka")
  }

  test(
    "with no mounts, no callers, no base domain and the default process port, the proxy reads none of each"
  ) {
    val bare = web.copy(mounts = Nil, callers = Nil, processPort = None)
    val parsed =
      ProxySettings.fromEnvironment(proxyEnvironment(bare, settings.copy(baseDomain = None))) match
        case Right(s)       => s
        case Left(problems) => fail(problems.mkString("; "))
    assertEquals(parsed.mounts, Vector.empty)
    assertEquals(parsed.callers, Vector.empty)
    assertEquals(parsed.publicAuthority, None)
    assertEquals(parsed.processPort, Rendering.DefaultProcessPort)
  }

  test("the operator's variable names are the proxy's") {
    import ProxySettings.Variables as P
    assertEquals(
      Vector(
        Rendering.ProxyEnv.Project,
        Rendering.ProxyEnv.Service,
        Rendering.ProxyEnv.Port,
        Rendering.ProxyEnv.ProcessPort,
        Rendering.ProxyEnv.Mounts,
        Rendering.ProxyEnv.Callers,
        Rendering.ProxyEnv.PublicAuthority
      ),
      Vector(P.Project, P.Service, P.Port, P.ProcessPort, P.Mounts, P.Callers, P.PublicAuthority)
    )
    assertEquals(Rendering.CallingPort, ProxySettings.CallingPort)
  }

  /**
   * Every Secret a rendering names: what each certificate asks cert-manager to write, what each pod
   * mounts, and the database cluster's own, which the CNPG objects reference.
   */
  private def issuedSecrets(all: Vector[Action]): Set[String] =
    val certificates = all.collect { case Action.EnsureCertificate(c) =>
      c.getAdditionalProperties
        .get("spec")
        .asInstanceOf[java.util.Map[String, Any]]
        .get("secretName")
        .toString
    }
    val volumes = all.collect { case Action.ApplyDeployment(d) =>
      d.getSpec.getTemplate.getSpec.getVolumes.asScala
        .flatMap(v => Option(v.getSecret))
        .map(_.getSecretName)
    }.flatten
    (certificates ++ volumes).toSet

  test(
    "every Secret the platform issues for a provisioned service is one a descriptor may not read"
  ) {
    val embedded = web.copy(hosting = "embedded", mounts = Nil, callers = Nil, processPort = None)
    val names = issuedSecrets(actions(embedded, ProvisioningPlan.Ready(recovered = false))) ++
      Set(
        CnpgRendering.projectClusterName + "-ca",
        CnpgRendering.clientCaName,
        CnpgRendering.replicationName
      )
    assert(names.size >= 6, names)
    for name <- names do
      assert(
        ServiceSpec.isPlatformSecret(name),
        s"'$name' is issued by the platform and the rule lets a descriptor read it"
      )
  }

  test(
    "every Secret the platform issues for a web-hosted service with mounts is one a descriptor may not read"
  ) {
    val names = issuedSecrets(actions(web, ProvisioningPlan.NotNeeded))
    assert(names.contains("web-service-tls") && names.contains("web-mount-tls"), names)
    for name <- names do
      assert(
        ServiceSpec.isPlatformSecret(name),
        s"'$name' is issued by the platform and the rule lets a descriptor read it"
      )
  }
