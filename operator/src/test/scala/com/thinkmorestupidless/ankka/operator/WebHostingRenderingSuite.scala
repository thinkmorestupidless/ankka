package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{AnkkaService, AnkkaServiceSpec, EnvEntry, MountEntry}
import io.fabric8.kubernetes.api.model.{Container, GenericKubernetesResource, ObjectMetaBuilder}
import io.fabric8.kubernetes.api.model.apps.Deployment

import scala.jdk.CollectionConverters.*

/**
 * What the operator renders for `hosting: web` (feature 021): the platform's proxy and the
 * developer's process in one pod, the service certificate alone, no cluster, no database, no grant
 * — and the Deployment otherwise exactly an embedded service's.
 */
class WebHostingRenderingSuite extends munit.FunSuite:

  private val settings = Settings.default.copy(
    sidecarImage = "ankka-sidecar:9.9.9",
    proxyImage = "ankka-proxy:9.9.9",
    baseDomain = Some("example.test")
  )

  private def resource(spec: AnkkaServiceSpec): AnkkaService =
    val r = new AnkkaService
    r.setMetadata(
      new ObjectMetaBuilder()
        .withNamespace("ankka-shop")
        .withName(spec.serviceName)
        .withUid("u")
        .build()
    )
    r.setSpec(spec)
    r

  private val embedded = AnkkaServiceSpec(
    projectId = "shop",
    serviceName = "web",
    generation = 1L,
    image = "shop-web:1.0.0",
    port = Some(9000),
    cpuMillis = 300,
    memoryMiB = 256,
    env = List(
      EnvEntry("GREETING", Some("hi"), None, None),
      EnvEntry("SESSION_KEY", None, Some("web-secrets"), Some("session"))
    )
  )
  private val web = embedded.copy(
    hosting = "web",
    processPort = Some(3000),
    mounts = List(MountEntry("/api/cart", "cart"), MountEntry("/api/orders", "orders")),
    callers = List("orders", "billing/invoices", "*")
  )

  private def actions(
      spec: AnkkaServiceSpec,
      settings: Settings = settings,
      plan: ProvisioningPlan = ProvisioningPlan.NotNeeded
  ): Vector[Action] =
    Rendering.render(resource(spec), settings, plan) match
      case Right(a)       => a
      case Left(problems) => fail(problems.mkString("; "))

  private def deployment(spec: AnkkaServiceSpec, settings: Settings = settings): Deployment =
    actions(spec, settings).collectFirst { case Action.ApplyDeployment(d) => d }.get

  private def pod(spec: AnkkaServiceSpec, settings: Settings = settings) =
    deployment(spec, settings).getSpec.getTemplate.getSpec

  private def containers(spec: AnkkaServiceSpec, settings: Settings = settings) =
    pod(spec, settings).getContainers.asScala.toVector

  private def proxy(spec: AnkkaServiceSpec = web, settings: Settings = settings): Container =
    containers(spec, settings)(0)

  private def process(spec: AnkkaServiceSpec = web): Container = containers(spec)(1)

  private def envOf(c: Container): Map[String, String] =
    c.getEnv.asScala.map(e => e.getName -> Option(e.getValue).getOrElse("<ref>")).toMap

  private def certificates(spec: AnkkaServiceSpec): Map[String, GenericKubernetesResource] =
    actions(spec).collect { case Action.EnsureCertificate(c) => c.getMetadata.getName -> c }.toMap

  private def policies(spec: AnkkaServiceSpec) =
    actions(spec).collect { case Action.EnsureNetworkPolicy(p) => p.getMetadata.getName -> p }.toMap

  // ── The pod ───────────────────────────────────────────────────────────────

  test("two containers: the proxy named as the platform's, the process named -app") {
    val cs = containers(web)
    assertEquals(cs.map(_.getName), Vector("web", "web-app"))
  }

  test(
    "the proxy runs the operator's image and the process the descriptor's, neither pulled anew"
  ) {
    assertEquals(proxy().getImage, "ankka-proxy:9.9.9")
    assertEquals(process().getImage, "shop-web:1.0.0")
    assertEquals(proxy().getImagePullPolicy, "IfNotPresent")
    assertEquals(process().getImagePullPolicy, "IfNotPresent")
  }

  test("the proxy declares the service's port and the probe; the process declares none") {
    assertEquals(
      proxy().getPorts.asScala.map(p => p.getName -> p.getContainerPort.intValue).toMap,
      Map("http" -> 9000, "probe" -> 7627)
    )
    assert(process().getPorts.isEmpty)
  }

  test("the proxy is told its settings: identity, ports, mounts, callers and the prefix") {
    val env = envOf(proxy())
    assertEquals(env("ANKKA_PROXY_PROJECT"), "shop")
    assertEquals(env("ANKKA_PROXY_SERVICE"), "web")
    assertEquals(env("ANKKA_PROXY_PORT"), "9000")
    assertEquals(env("ANKKA_PROXY_PROCESS_PORT"), "3000")
    assertEquals(env("ANKKA_PROXY_MOUNTS"), "/api/cart=cart,/api/orders=orders")
    assertEquals(env("ANKKA_PROXY_CALLERS"), "orders,billing/invoices,*")
    assertEquals(env("ANKKA_NAMESPACE_PREFIX"), "ankka")
    // Nothing of a node's: the proxy forms no cluster and serves no ankka HTTP.
    assert(!env.contains("ANKKA_CLUSTER_MODE") && !env.contains("ANKKA_HTTP_PORT"), env.keys)
    assert(
      !env.contains("GREETING"),
      "the descriptor's variables are the process's, not the proxy's"
    )
  }

  test("mounts and callers are set, and empty, when the descriptor has none") {
    val env = envOf(proxy(web.copy(mounts = Nil, callers = Nil)))
    assertEquals(env.get("ANKKA_PROXY_MOUNTS"), Some(""))
    assertEquals(env.get("ANKKA_PROXY_CALLERS"), Some(""))
  }

  test("an unstated process port is 8080, for the proxy and the process alike") {
    val spec = web.copy(processPort = None)
    assertEquals(envOf(proxy(spec))("ANKKA_PROXY_PROCESS_PORT"), "8080")
    assertEquals(envOf(process(spec))("PORT"), "8080")
  }

  test("the public authority is the hostname, with the HTTPS port when it is not 443") {
    assertEquals(envOf(proxy())("ANKKA_PROXY_PUBLIC_AUTHORITY"), "web-shop.example.test")
    assertEquals(
      envOf(proxy(web, settings.copy(httpsPort = 8443)))("ANKKA_PROXY_PUBLIC_AUTHORITY"),
      "web-shop.example.test:8443"
    )
  }

  test(
    "the public authority is rendered whether or not the service is exposed, so exposing rolls nothing"
  ) {
    assertEquals(
      deployment(web.copy(exposed = true)).getSpec.getTemplate,
      deployment(web.copy(exposed = false)).getSpec.getTemplate
    )
  }

  test("with no base domain the proxy is given no public authority") {
    val env = envOf(proxy(web, settings.copy(baseDomain = None)))
    assert(!env.contains("ANKKA_PROXY_PUBLIC_AUTHORITY"), env.keys)
  }

  test(
    "the process is given the descriptor's whole environment, its port and the calling address"
  ) {
    val env = envOf(process())
    assertEquals(env("GREETING"), "hi")
    assertEquals(env("SESSION_KEY"), "<ref>")
    assertEquals(env("PORT"), "3000")
    assertEquals(env("ANKKA_SERVICES_URL"), "http://127.0.0.1:7630")
    assertEquals(
      env.keySet,
      Set("GREETING", "SESSION_KEY", "PORT", "ANKKA_SERVICES_URL"),
      "nothing of the platform's beyond those two"
    )
    val secret = process().getEnv.asScala.find(_.getName == "SESSION_KEY").get.getValueFrom
    assertEquals(secret.getSecretKeyRef.getName, "web-secrets")
    assertEquals(secret.getSecretKeyRef.getKey, "session")
  }

  test(
    "without mounts the proxy mounts the service certificate alone; the process mounts nothing"
  ) {
    val bare = web.copy(mounts = Nil)
    assertEquals(
      proxy(bare).getVolumeMounts.asScala.map(_.getMountPath).toVector,
      Vector("/var/run/secrets/ankka/service")
    )
    assert(proxy(bare).getVolumeMounts.asScala.forall(_.getReadOnly))
    assert(process(bare).getVolumeMounts.isEmpty)
    val volumes = pod(bare).getVolumes.asScala.toVector
    assertEquals(volumes.map(_.getName), Vector("ankka-service-tls"))
    assertEquals(volumes.head.getSecret.getSecretName, "web-service-tls")
  }

  test(
    "the proxy is sized by the platform, the process by the instance type, requests equal limits"
  ) {
    val p = proxy().getResources
    assertEquals(
      p.getRequests.asScala.view.mapValues(_.toString).toMap,
      Map("cpu" -> "250m", "memory" -> "192Mi")
    )
    assertEquals(p.getRequests, p.getLimits)
    val a = process().getResources
    assertEquals(
      a.getRequests.asScala.view.mapValues(_.toString).toMap,
      Map("cpu" -> "300m", "memory" -> "256Mi")
    )
    assertEquals(a.getRequests, a.getLimits)
  }

  test(
    "readiness is the proxy's probe, every five seconds; the process has none; neither has liveness"
  ) {
    val probe = proxy().getReadinessProbe
    assertEquals(probe.getHttpGet.getPath, "/ready")
    assertEquals(probe.getHttpGet.getPort.getStrVal, "probe")
    assertEquals(probe.getPeriodSeconds.intValue, 5)
    assertEquals(process().getReadinessProbe, null)
    assertEquals(proxy().getLivenessProbe, null)
    assertEquals(process().getLivenessProbe, null)
  }

  test("both containers keep serving for five seconds before they are stopped") {
    assertEquals(proxy().getLifecycle.getPreStop.getSleep.getSeconds.longValue, 5L)
    assertEquals(process().getLifecycle.getPreStop.getSleep.getSeconds.longValue, 5L)
  }

  test("the pod names its account and mounts no token for it") {
    val spec = pod(web)
    assertEquals(spec.getServiceAccountName, "web")
    assertEquals(spec.getAutomountServiceAccountToken, java.lang.Boolean.FALSE)
  }

  test("the pull secret is on the pod when the project has one, and absent otherwise") {
    assertEquals(
      pod(web.copy(imagePullSecret = Some("ankka-registry"))).getImagePullSecrets.asScala
        .map(_.getName)
        .toVector,
      Vector("ankka-registry")
    )
    assert(pod(web).getImagePullSecrets.isEmpty)
  }

  test(
    "no init container and no filesystem group: there is no schema to establish and no key to own"
  ) {
    assert(pod(web).getInitContainers.isEmpty)
    assertEquals(pod(web).getSecurityContext, null)
  }

  test("the pod template carries the transport label and not the formation label") {
    val labels = deployment(web).getSpec.getTemplate.getMetadata.getLabels.asScala.toMap
    assertEquals(labels.get(Labels.TransportKey), Some(Labels.TransportTls))
    assertEquals(labels.get(Labels.FormationKey), None)
    assertEquals(labels.get(Labels.ProjectKey), Some("shop"))
    assertEquals(labels.get(Labels.ServiceKey), Some("web"))
    assert(
      !Transition.needed(Some(labels)),
      "the operator would stop this Deployment on every pass"
    )
  }

  test("the selector, strategy and restart annotation are exactly an embedded service's") {
    val a = deployment(embedded)
    val b = deployment(web)
    assertEquals(a.getSpec.getSelector, b.getSpec.getSelector)
    assertEquals(a.getSpec.getStrategy, b.getSpec.getStrategy)
    assertEquals(
      a.getSpec.getTemplate.getMetadata.getAnnotations,
      b.getSpec.getTemplate.getMetadata.getAnnotations
    )
    assertEquals(a.getMetadata.getAnnotations, b.getMetadata.getAnnotations)
    assertEquals(a.getSpec.getReplicas, b.getSpec.getReplicas)
  }

  // ── The objects around the pod ────────────────────────────────────────────

  test("the identity is the account alone: no role and no binding") {
    val all = actions(web)
    assertEquals(all.count { case Action.EnsureServiceAccount(_) => true; case _ => false }, 1)
    assert(!all.exists { case Action.EnsureRole(_) => true; case _ => false })
    assert(!all.exists { case Action.EnsureRoleBinding(_) => true; case _ => false })
  }

  test("the service certificate is rendered as for any service, and no cluster certificate") {
    val certs = certificates(web.copy(mounts = Nil))
    assertEquals(certs.keySet, Set("web-service"))
    assertEquals(certs("web-service"), certificates(embedded)("web-service"))
  }

  private def certSpec(c: GenericKubernetesResource): Map[String, Any] =
    c.getAdditionalProperties.get("spec").asInstanceOf[java.util.Map[String, Any]].asScala.toMap

  test("with mounts, a mount certificate: the mount identity alone, no name, client auth only") {
    val certs = certificates(web)
    assertEquals(certs.keySet, Set("web-service", "web-mount"))
    val mount = certSpec(certs("web-mount"))
    assertEquals(mount("secretName"), "web-mount-tls")
    assertEquals(
      mount("uris").asInstanceOf[java.util.List[String]].asScala.toList,
      List("ankka://shop/web/mount")
    )
    assertEquals(mount.get("dnsNames"), None)
    assertEquals(
      mount("usages").asInstanceOf[java.util.List[String]].asScala.toList,
      List("client auth")
    )
    assertEquals(
      mount("issuerRef").asInstanceOf[java.util.Map[String, String]].asScala.toMap,
      Map("name" -> "ankka-service", "kind" -> "ClusterIssuer", "group" -> "cert-manager.io")
    )
    assertEquals(mount("duration"), certSpec(certs("web-service"))("duration"))
  }

  test("with mounts, the proxy mounts the mount certificate too, and the process neither") {
    assertEquals(
      proxy().getVolumeMounts.asScala.map(m => m.getName -> m.getMountPath).toMap,
      Map(
        "ankka-service-tls" -> "/var/run/secrets/ankka/service",
        "ankka-mount-tls"   -> "/var/run/secrets/ankka/mount"
      )
    )
    assert(process().getVolumeMounts.isEmpty)
    val volumes = pod(web).getVolumes.asScala.map(v => v.getName -> v.getSecret.getSecretName).toMap
    assertEquals(
      volumes,
      Map("ankka-service-tls" -> "web-service-tls", "ankka-mount-tls" -> "web-mount-tls")
    )
  }

  test("the mount certificate comes before the Deployment that mounts it") {
    val all = actions(web)
    val mount = all.indexWhere {
      case Action.EnsureCertificate(c) => c.getMetadata.getName == "web-mount"
      case _                           => false
    }
    val deployment = all.indexWhere { case Action.ApplyDeployment(_) => true; case _ => false }
    assert(mount >= 0 && mount < deployment, s"$mount, $deployment")
  }

  test("a service whose last mount is removed renders no mount certificate and removes none") {
    val all = actions(web.copy(mounts = Nil))
    assert(!all.exists {
      case Action.EnsureCertificate(c) => c.getMetadata.getName == "web-mount"
      case _                           => false
    })
    // There is no action that removes a certificate: the one it had stays, owned by the resource.
    assert(!all.exists(_.describe.contains("web-mount")), all.map(_.describe))
  }

  test("the http policy, a probe policy of its own, and no cluster policy") {
    val all = policies(web)
    assertEquals(all.keySet, Set("web-http", "web-probe"))
    assertEquals(all("web-http"), policies(embedded)("web-http"))
    val probe = all("web-probe")
    assertEquals(
      probe.getSpec.getPodSelector.getMatchLabels.asScala.toMap,
      Labels.identity("shop", "web")
    )
    assertEquals(probe.getSpec.getPolicyTypes.asScala.toList, List("Ingress"))
    val rules = probe.getSpec.getIngress.asScala.toList
    assertEquals(rules.size, 1)
    assertEquals(rules.head.getPorts.asScala.map(_.getPort.getIntVal.intValue).toList, List(7627))
    assert(
      rules.head.getFrom == null || rules.head.getFrom.isEmpty,
      "the probe port must admit every source"
    )
    assertEquals(
      probe.getSpec.getPodSelector.getMatchLabels,
      deployment(web).getSpec.getSelector.getMatchLabels
    )
  }

  test("no database object of any kind, whatever the plan says") {
    def databaseActions(plan: ProvisioningPlan) = actions(web, plan = plan).filter {
      case Action.EnsureCluster(_) | Action.EnsureCredentials(_) | Action.EnsureDatabaseRole(_) |
          Action.EnsureDatabase(_) | Action.EnsureSchemaConfig(_) | Action.EnsureIssuer(_) =>
        true
      case _ => false
    }
    assertEquals(databaseActions(ProvisioningPlan.NotNeeded), Vector.empty)
    assertEquals(databaseActions(ProvisioningPlan.Supplied), Vector.empty)
  }

  test("certificates and policies come before the Deployment that mounts them") {
    val all        = actions(web)
    val deployment = all.indexWhere { case Action.ApplyDeployment(_) => true; case _ => false }
    all.zipWithIndex.foreach {
      case (Action.EnsureCertificate(_) | Action.EnsureNetworkPolicy(_), i) =>
        assert(i < deployment)
      case _ => ()
    }
  }

  test(
    "an exposed web-hosted service's route is one rule, one backend, no filter, whatever its mounts"
  ) {
    val route = actions(web.copy(exposed = true))
      .collectFirst { case Action.EnsureHttpRoute(r) => r }
      .getOrElse(fail("no route"))
    val rules = route.getSpec.getRules.asScala.toList
    assertEquals(rules.size, 1)
    assertEquals(rules.head.getBackendRefs.asScala.map(_.getName).toList, List("web"))
    assert(rules.head.getFilters == null || rules.head.getFilters.isEmpty, "mounts are the proxy's")
    assert(rules.head.getMatches == null || rules.head.getMatches.isEmpty)
    assertEquals(route.getSpec.getHostnames.asScala.toList, List("web-shop.example.test"))
  }

  // ── Problems ──────────────────────────────────────────────────────────────

  test("web hosting with no proxy image configured is refused, not rendered") {
    val refused = Rendering.render(
      resource(web),
      settings.copy(proxyImage = ""),
      ProvisioningPlan.NotNeeded
    )
    assertEquals(refused, Left(Vector("operator has no proxy image")))
  }

  test("an unknown hosting is a problem, no longer rendered as embedded") {
    val refused =
      Rendering.render(resource(web.copy(hosting = "lambda")), settings, ProvisioningPlan.Supplied)
    assertEquals(refused, Left(Vector("unknown hosting \"lambda\"")))
  }

  test("a web-hosted resource that names no port is a problem") {
    val refused =
      Rendering.render(resource(web.copy(port = None)), settings, ProvisioningPlan.NotNeeded)
    assert(refused.left.exists(_.exists(_.contains("names no port"))), refused)
  }

  // ── Changing hosting ──────────────────────────────────────────────────────

  test("a service changed from embedded to web renders exactly what a fresh web-hosted one does") {
    val changed = embedded.copy(
      hosting = "web",
      processPort = Some(3000),
      mounts = web.mounts,
      callers = web.callers,
      generation = 7L
    )
    assertEquals(actions(changed), actions(web.copy(generation = 7L)))
    // Nothing the embedded service had is removed. The removals left are the gRPC policy and the
    // gRPC peers address, which every service that serves no gRPC renders whatever its hosting.
    val removed = actions(changed).collect {
      case Action.RemoveNetworkPolicy(_, name, _) => name
      case Action.RemoveService(_, name, _)       => name
    }
    assertEquals(
      removed.toSet,
      Set(ZeroTrust.grpcPolicyName(changed.serviceName), Names.grpcPeers(changed.serviceName))
    )
  }

  test("a service changed from web to embedded renders exactly what a fresh embedded one does") {
    val changed = web.copy(hosting = "embedded", processPort = None, mounts = Nil, callers = Nil)
    assertEquals(
      actions(changed, plan = ProvisioningPlan.Supplied),
      actions(embedded, plan = ProvisioningPlan.Supplied)
    )
  }

  test("an embedded service is unchanged: one container, the cluster pair, the formation label") {
    val cs = containers(embedded)
    assertEquals(cs.size, 1)
    assertEquals(
      cs.head.getVolumeMounts.asScala.map(_.getMountPath).toSet,
      Set("/var/run/secrets/ankka/cluster", "/var/run/secrets/ankka/service")
    )
    val labels = deployment(embedded).getSpec.getTemplate.getMetadata.getLabels.asScala.toMap
    assertEquals(labels.get(Labels.FormationKey), Some(Labels.FormationBootstrap))
    assertEquals(certificates(embedded).keySet, Set("web-cluster", "web-service"))
    assertEquals(policies(embedded).keySet, Set("web-cluster", "web-http"))
  }
