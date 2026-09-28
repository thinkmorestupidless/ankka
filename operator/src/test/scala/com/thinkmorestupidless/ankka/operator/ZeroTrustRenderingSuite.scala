package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{AnkkaService, AnkkaServiceSpec}
import io.fabric8.kubernetes.api.model.{GenericKubernetesResource, ObjectMetaBuilder}

import scala.jdk.CollectionConverters.*

/**
 * What zero trust renders for a service (feature 014): a certificate per purpose, the policies that
 * decide who may connect, the gateway's backend TLS policy, and the pod's mounts — asserted on the
 * rendered objects, since a string that merely appears somewhere is how a patch that renders the
 * wrong shape passes.
 */
class ZeroTrustRenderingSuite extends munit.FunSuite:

  private val base = Settings.default.copy(baseDomain = Some("example.test"))

  private def resource(spec: AnkkaServiceSpec): AnkkaService =
    val r = new AnkkaService
    r.setMetadata(
      new ObjectMetaBuilder()
        .withNamespace("ankka-checkout")
        .withName(spec.serviceName)
        .withUid("uid-1")
        .build()
    )
    r.setSpec(spec)
    r

  private val spec = AnkkaServiceSpec(
    projectId = "checkout",
    serviceName = "cart",
    generation = 1L,
    image = "cart:1.0.0",
    cpuMillis = 500,
    memoryMiB = 512,
    port = Some(9000)
  )

  private def actions(s: AnkkaServiceSpec): Vector[Action] =
    Rendering.render(resource(s), base, ProvisioningPlan.Supplied) match
      case Right(a)       => a
      case Left(problems) => fail(problems.mkString("; "))

  private def certificates(s: AnkkaServiceSpec): Map[String, GenericKubernetesResource] =
    actions(s).collect { case Action.EnsureCertificate(c) => c.getMetadata.getName -> c }.toMap

  private def certSpec(c: GenericKubernetesResource): Map[String, Any] =
    c.getAdditionalProperties.get("spec").asInstanceOf[java.util.Map[String, Any]].asScala.toMap

  private def list(v: Any): List[String] = v.asInstanceOf[java.util.List[String]].asScala.toList

  test(
    "a service gets a cluster certificate naming it, from the cluster authority, rotating daily"
  ) {
    val cluster = certSpec(certificates(spec)("cart-cluster"))
    assertEquals(cluster("secretName"), "cart-cluster-tls")
    assertEquals(list(cluster("uris")), List("ankka://checkout/cart"))
    assertEquals(
      cluster("issuerRef").asInstanceOf[java.util.Map[String, String]].asScala.toMap,
      Map("name" -> "ankka-cluster", "kind" -> "ClusterIssuer", "group" -> "cert-manager.io")
    )
    assertEquals((cluster("duration"), cluster("renewBefore")), ("24h", "16h"))
    val key = cluster("privateKey").asInstanceOf[java.util.Map[String, Any]].asScala.toMap
    // RSA and PKCS#8: Pekko's remoting engine reads nothing else.
    assertEquals(
      (key("algorithm"), key("encoding"), key("rotationPolicy")),
      ("RSA", "PKCS8", "Always")
    )
    assertEquals(list(cluster("usages")).toSet, Set("server auth", "client auth"))
  }

  test("a service that serves HTTP gets a service certificate for its in-cluster names") {
    val service = certSpec(certificates(spec)("cart-service"))
    assertEquals(list(service("uris")), List("ankka://checkout/cart"))
    assertEquals(
      list(service("dnsNames")),
      List("cart", "cart.ankka-checkout.svc", "cart.ankka-checkout.svc.cluster.local")
    )
    assertEquals(
      service("issuerRef").asInstanceOf[java.util.Map[String, String]].get("name"),
      "ankka-service"
    )
  }

  test("every certificate is owned by the resource, so it goes with it") {
    for (_, c) <- certificates(spec) do
      assertEquals(c.getMetadata.getOwnerReferences.asScala.map(_.getUid).toList, List("uid-1"))
  }

  test(
    "a service with no HTTP port keeps its identity, and gets no http policy and no backend TLS"
  ) {
    val a = actions(spec.copy(port = None, exposed = true))
    // Its service certificate is still issued: it is who the service is when it calls another,
    // and the runtime's HTTP server starts whether or not the port is published.
    assertEquals(certificates(spec.copy(port = None)).keySet, Set("cart-cluster", "cart-service"))
    assert(
      a.contains(Action.RemoveNetworkPolicy("ankka-checkout", "cart-http", "uid-1")),
      a.toString
    )
    assert(a.exists { case Action.RemoveBackendTlsPolicy(_, "cart", _) => true; case _ => false })
  }

  test("the cluster ports admit the service's own pods only, and the probe port anyone") {
    val policy = actions(spec)
      .collectFirst {
        case Action.EnsureNetworkPolicy(p) if p.getMetadata.getName == "cart-cluster" => p
      }
      .getOrElse(fail("no cluster policy"))
    val identity = Labels.identity("checkout", "cart")
    assertEquals(policy.getSpec.getPodSelector.getMatchLabels.asScala.toMap, identity)
    assertEquals(policy.getSpec.getPolicyTypes.asScala.toList, List("Ingress"))
    val rules   = policy.getSpec.getIngress.asScala.toList
    val cluster = rules.find(_.getPorts.asScala.exists(_.getPort.getIntVal == 17355)).get
    assertEquals(cluster.getPorts.asScala.map(_.getPort.getIntVal.intValue).toSet, Set(17355, 7626))
    assertEquals(
      cluster.getFrom.asScala.map(_.getPodSelector.getMatchLabels.asScala.toMap).toList,
      List(identity)
    )
    val probe = rules.find(_.getPorts.asScala.exists(_.getPort.getIntVal == 7627)).get
    assert(probe.getFrom == null || probe.getFrom.isEmpty, "the probe port must admit every source")
    // The policy selects exactly what the Deployment selects, so it cannot miss a pod.
    val deployment = actions(spec).collectFirst { case Action.ApplyDeployment(d) => d }.get
    assertEquals(
      policy.getSpec.getPodSelector.getMatchLabels,
      deployment.getSpec.getSelector.getMatchLabels
    )
  }

  test("the HTTP port admits ankka workloads in any project and the gateway, nothing else") {
    val policy = actions(spec)
      .collectFirst {
        case Action.EnsureNetworkPolicy(p) if p.getMetadata.getName == "cart-http" => p
      }
      .getOrElse(fail("no http policy"))
    val rule = policy.getSpec.getIngress.asScala.toList match
      case List(only) => only
      case other      => fail(s"expected one rule, got $other")
    assertEquals(rule.getPorts.asScala.map(_.getPort.getIntVal.intValue).toList, List(9000))
    val peers   = rule.getFrom.asScala.toList
    val managed = Map(Labels.ManagedByKey -> Labels.ManagedByAnkka)
    assert(
      peers.exists(p =>
        p.getNamespaceSelector.getMatchLabels.asScala.toMap == managed &&
          p.getPodSelector.getMatchLabels.asScala.toMap == managed
      ),
      peers.toString
    )
    // The proxy pods, in Envoy Gateway's namespace: the Gateway's own namespace runs no proxy, so
    // admitting it would admit nothing that routes, and every exposed request would be refused.
    assert(
      peers.exists(p =>
        Option(p.getNamespaceSelector).exists(
          _.getMatchLabels.asScala
            .get("kubernetes.io/metadata.name")
            .contains("envoy-gateway-system")
        ) && p.getPodSelector.getMatchLabels.asScala.toMap == Map(
          "gateway.envoyproxy.io/owning-gateway-name"      -> "ankka",
          "gateway.envoyproxy.io/owning-gateway-namespace" -> "ankka-gateway"
        )
      ),
      peers.toString
    )
  }

  test("an exposed service gets a backend TLS policy whose hostname its certificate carries") {
    val exposed = spec.copy(exposed = true)
    val policy = actions(exposed)
      .collectFirst { case Action.EnsureBackendTlsPolicy(p) => p }
      .getOrElse(fail("no backend TLS policy"))
    val target = policy.getSpec.getTargetRefs.asScala.toList match
      case List(t) => t
      case other   => fail(other.toString)
    assertEquals(
      (target.getKind, target.getName, target.getSectionName),
      ("Service", "cart", "http")
    )
    val validation = policy.getSpec.getValidation
    assertEquals(
      validation.getCaCertificateRefs.asScala.map(r => r.getKind -> r.getName).toList,
      List("ConfigMap" -> "ankka-service-ca")
    )
    assert(
      list(certSpec(certificates(exposed)("cart-service"))("dnsNames"))
        .contains(validation.getHostname),
      s"${validation.getHostname} is not a name the service's certificate carries"
    )
  }

  test("an unexposed service's backend TLS policy is removed, not rendered") {
    assert(!actions(spec).exists { case Action.EnsureBackendTlsPolicy(_) => true; case _ => false })
  }

  test("certificates and policies come before the Deployment that mounts them") {
    val a          = actions(spec)
    val deployment = a.indexWhere { case Action.ApplyDeployment(_) => true; case _ => false }
    val lastTls = a.lastIndexWhere {
      case _: Action.EnsureCertificate | _: Action.EnsureNetworkPolicy => true
      case _                                                           => false
    }
    assert(lastTls < deployment, a.map(_.describe).mkString("\n"))
  }

  test("the identities are mounted whole — never by subPath, which a rotation would never reach") {
    val pod = actions(spec)
      .collectFirst { case Action.ApplyDeployment(d) => d }
      .get
      .getSpec
      .getTemplate
      .getSpec
    val mounts = pod.getContainers.get(0).getVolumeMounts.asScala.toList
    assertEquals(
      mounts.map(m => m.getName -> m.getMountPath).toSet,
      Set(
        "ankka-cluster-tls" -> "/var/run/secrets/ankka/cluster",
        "ankka-service-tls" -> "/var/run/secrets/ankka/service"
      )
    )
    assert(mounts.forall(m => m.getSubPath == null && m.getReadOnly), mounts.toString)
    assertEquals(
      pod.getVolumes.asScala.map(v => v.getName -> v.getSecret.getSecretName).toSet,
      Set("ankka-cluster-tls" -> "cart-cluster-tls", "ankka-service-tls" -> "cart-service-tls")
    )
  }

  test(
    "the transport label is on the pod template and the contact-point selector, not the selector"
  ) {
    val d        = actions(spec).collectFirst { case Action.ApplyDeployment(d) => d }.get
    val template = d.getSpec.getTemplate.getMetadata.getLabels.asScala
    assertEquals(template.get(Labels.TransportKey), Some(Labels.TransportTls))
    assert(!d.getSpec.getSelector.getMatchLabels.containsKey(Labels.TransportKey))
    val selector = d.getSpec.getTemplate.getSpec.getContainers
      .get(0)
      .getEnv
      .asScala
      .find(_.getName == "ANKKA_CLUSTER_POD_SELECTOR")
      .map(_.getValue)
      .get
    assert(selector.contains(s"${Labels.TransportKey}=${Labels.TransportTls}"), selector)
  }

  test("a workload is told how project ids become namespaces") {
    val env = actions(spec)
      .collectFirst { case Action.ApplyDeployment(d) => d }
      .get
      .getSpec
      .getTemplate
      .getSpec
      .getContainers
      .get(0)
      .getEnv
      .asScala
    assertEquals(env.find(_.getName == "ANKKA_NAMESPACE_PREFIX").map(_.getValue), Some("ankka"))
  }

  // ── The database (feature 014, user story 4) ─────────────────────────────────────────────

  private val provisioned = spec.copy(provisionDatabase = true)

  private def rendered(plan: ProvisioningPlan): Vector[Action] =
    Rendering.render(resource(provisioned), base, plan) match
      case Right(a)       => a
      case Left(problems) => fail(problems.mkString("; "))

  private val ready = ProvisioningPlan.Ready(recovered = false)

  test("a provisioned service gets a client certificate whose common name is its role") {
    val cert = rendered(ready)
      .collectFirst {
        case Action.EnsureCertificate(c) if c.getMetadata.getName == "cart-database" => c
      }
      .getOrElse(fail("no database certificate"))
    val s = certSpec(cert)
    assertEquals(s("commonName"), "cart")
    assertEquals(list(s("usages")), List("client auth"))
    assertEquals(
      s("issuerRef").asInstanceOf[java.util.Map[String, String]].asScala.toMap,
      Map("name" -> "ankka-database", "kind" -> "Issuer", "group" -> "cert-manager.io")
    )
  }

  test("the database admits this project's workloads, its own instances and the operator only") {
    for plan <- Vector(ready, ProvisioningPlan.Waiting(true, true, true, true, None)) do
      val policy = rendered(plan)
        .collectFirst {
          case Action.EnsureNetworkPolicy(p) if p.getMetadata.getName == "ankka-db-database" => p
        }
        .getOrElse(fail(s"no database policy for $plan"))
      val instances = Map("cnpg.io/cluster" -> "ankka-db")
      assertEquals(policy.getSpec.getPodSelector.getMatchLabels.asScala.toMap, instances)
      val rules = policy.getSpec.getIngress.asScala.toList
      def ports(r: io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyIngressRule) =
        r.getPorts.asScala.map(_.getPort.getIntVal.intValue).toSet
      val workloads = rules.find(ports(_) == Set(5432)).getOrElse(fail(rules.toString))
      // A pod selector with no namespace selector is this namespace only: the project boundary.
      assertEquals(
        workloads.getFrom.asScala.toList.map(p =>
          (Option(p.getNamespaceSelector), p.getPodSelector.getMatchLabels.asScala.toMap)
        ),
        List((None, Map(Labels.ManagedByKey -> Labels.ManagedByAnkka)))
      )
      val platform = rules.find(ports(_) == Set(5432, 8000)).getOrElse(fail(rules.toString))
      assert(
        platform.getFrom.asScala.exists(p =>
          Option(p.getNamespaceSelector).exists(
            _.getMatchLabels.asScala.get("kubernetes.io/metadata.name").contains("cnpg-system")
          )
        ),
        platform.toString
      )
    assert(!rendered(ProvisioningPlan.Supplied).exists {
      case Action.EnsureNetworkPolicy(p) => p.getMetadata.getName == "ankka-db-database"
      case _                             => false
    })
  }

  test("the project's database authority and the cluster's TLS fields are ensured on every pass") {
    for plan <- Vector(ready, ProvisioningPlan.Waiting(true, true, true, true, None)) do
      val a = rendered(plan)
      assert(
        a.exists {
          case Action.EnsureIssuer(i) => i.getMetadata.getName == "ankka-database"; case _ => false
        },
        plan.toString
      )
      val names = a.collect { case Action.EnsureCertificate(c) => c.getMetadata.getName }.toSet
      assert(Set("ankka-db-client-ca", "ankka-db-replication").subsetOf(names), names.toString)
      val cluster = a
        .collectFirst { case Action.EnsureCluster(c) => c.getSpec }
        .getOrElse(fail(s"no cluster for $plan"))
      assertEquals(cluster.certificates.map(_.clientCASecret), Some("ankka-db-client-ca"))
      assertEquals(cluster.postgresql.map(_.pgHba), Some(Vector(CnpgRendering.CertificateRule)))
      assert(cluster.managed.exists(_.roles.exists(_.name == "ankka_tls")))
    // The authority comes before the cluster that names it, and the Issuer before what it issues.
    val order = rendered(ready).map(_.describe)
    assert(
      order.indexWhere(_.contains("ankka-db-client-ca")) < order.indexWhere(
        _.contains("ensure cluster")
      ),
      order.mkString("\n")
    )
  }

  test("a role that still has a password is re-applied without one, once; a steady role is not") {
    def roles(plan: ProvisioningPlan) = rendered(plan).collect {
      case Action.EnsureDatabaseRole(r) => r
    }
    assertEquals(roles(ready), Vector.empty)
    val migrated = roles(ProvisioningPlan.Ready(recovered = false, migrateRole = true))
    assertEquals(migrated.map(_.getSpec.disablePassword), Vector(Some(true)))
    assertEquals(migrated.map(_.getSpec.inRoles), Vector(Vector("ankka_tls")))
    // And the credential Secret and the database are never rewritten by the migration.
    assert(!rendered(ProvisioningPlan.Ready(false, true)).exists {
      case _: Action.EnsureCredentials | _: Action.EnsureDatabase => true
      case _                                                      => false
    })
  }

  test("the pod mounts its database identity privately, and is told to connect with it") {
    val d       = rendered(ready).collectFirst { case Action.ApplyDeployment(d) => d }.get
    val pod     = d.getSpec.getTemplate.getSpec
    val volumes = pod.getVolumes.asScala.map(v => v.getName -> v).toMap
    assertEquals(volumes("ankka-database-tls").getSecret.getSecretName, "cart-database-tls")
    assertEquals(
      volumes("ankka-database-tls").getSecret.getDefaultMode.intValue,
      Integer.parseInt("440", 8)
    )
    // Only the certificate from CNPG's server authority, never its key.
    val ca = volumes("ankka-database-ca").getSecret
    assertEquals(ca.getSecretName, "ankka-db-ca")
    assertEquals(ca.getItems.asScala.map(_.getKey).toList, List("ca.crt"))
    assertEquals(pod.getSecurityContext.getFsGroup.longValue, 2000L)
    for container <- Vector(pod.getContainers.get(0), pod.getInitContainers.get(0)) do
      val env = container.getEnv.asScala.map(e => e.getName -> e.getValue).toMap
      assertEquals(env.get("ANKKA_DB_SSL_MODE"), Some("verify-full"), container.getName)
      assertEquals(
        env.get("ANKKA_DB_SSL_KEY"),
        Some("/var/run/secrets/ankka/database/tls.key"),
        container.getName
      )
      assertEquals(
        container.getVolumeMounts.asScala
          .map(_.getMountPath)
          .toSet
          .intersect(Set("/var/run/secrets/ankka/database", "/var/run/secrets/ankka/database-ca")),
        Set("/var/run/secrets/ankka/database", "/var/run/secrets/ankka/database-ca"),
        container.getName
      )
  }

  test("a supplied database gets none of it") {
    val a = Rendering.render(resource(spec), base, ProvisioningPlan.Supplied).toOption.get
    assert(!a.exists {
      case Action.EnsureCertificate(c) => c.getMetadata.getName == "cart-database"; case _ => false
    })
    assert(!a.exists { case _: Action.EnsureIssuer => true; case _ => false })
  }
