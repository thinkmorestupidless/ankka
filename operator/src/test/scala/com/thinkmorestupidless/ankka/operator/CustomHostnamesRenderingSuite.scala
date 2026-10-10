package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{AnkkaService, AnkkaServiceSpec}
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder

import scala.jdk.CollectionConverters.*

/** What the operator renders for a service's custom hostnames (feature 045). */
class CustomHostnamesRenderingSuite extends munit.FunSuite:

  private val settings =
    Settings.default.copy(baseDomain = Some("example.test"), hostnameIssuer = Some("pebble"))

  private def resource(spec: AnkkaServiceSpec): AnkkaService =
    val r = new AnkkaService
    r.setMetadata(
      new ObjectMetaBuilder()
        .withNamespace("ankka-checkout")
        .withName("cart")
        .withUid("uid-1")
        .build()
    )
    r.setSpec(spec)
    r

  private val spec = AnkkaServiceSpec(
    projectId = "checkout",
    serviceName = "cart",
    generation = 4L,
    image = "registry.example.com/acme/cart:1.4.2",
    port = Some(9000),
    exposed = true,
    customHostnames = List("app.example.com", "example.com")
  )

  private def actions(s: AnkkaServiceSpec, with_ : Settings = settings): Vector[Action] =
    Rendering.render(resource(s), with_, ProvisioningPlan.Supplied) match
      case Right(a)       => a
      case Left(problems) => fail(problems.mkString("; "))

  private def route(as: Vector[Action]) =
    as.collectFirst { case Action.EnsureHttpRoute(r) => r }.getOrElse(fail(s"no route: $as"))

  private def certificates(as: Vector[Action]) =
    as.collect {
      case Action.EnsureCertificate(c)
          if c.getMetadata.getLabels.containsKey(HostnameRendering.CertificateLabel) =>
        c
    }

  test("a certificate per custom hostname, from the installation's issuer, owned and labelled") {
    val certs = certificates(actions(spec))
    assertEquals(certs.map(_.getMetadata.getName), Vector("app.example.com", "example.com"))
    val c = certs.head
    val body =
      c.getAdditionalProperties.get("spec").asInstanceOf[java.util.Map[String, AnyRef]].asScala
    assertEquals(body("secretName"), "app.example.com")
    assertEquals(
      body("dnsNames").asInstanceOf[java.util.List[String]].asScala.toList,
      List("app.example.com")
    )
    assertEquals(
      body("issuerRef").asInstanceOf[java.util.Map[String, String]].asScala.toMap,
      Map("name" -> "pebble", "kind" -> "ClusterIssuer", "group" -> "cert-manager.io")
    )
    assertEquals(
      body("privateKey").asInstanceOf[java.util.Map[String, AnyRef]].get("rotationPolicy"),
      "Always"
    )
    assertEquals(c.getMetadata.getNamespace, "ankka-checkout")
    assertEquals(c.getMetadata.getOwnerReferences.get(0).getUid, "uid-1")
    assertEquals(c.getMetadata.getLabels.get(HostnameRendering.CertificateLabel), "true")
  }

  test("one listener set, a listener per hostname in the spec's order, attached to the Gateway") {
    val set =
      actions(spec).collectFirst { case Action.EnsureListenerSet(s) => s }.getOrElse(fail("no set"))
    assertEquals(set.getMetadata.getName, "cart-hostnames")
    assertEquals(set.getMetadata.getNamespace, "ankka-checkout")
    assertEquals(set.getMetadata.getOwnerReferences.get(0).getUid, "uid-1")
    val parent = set.getSpec.getParentRef
    assertEquals(
      (parent.getKind, parent.getName, parent.getNamespace),
      ("Gateway", "ankka", "ankka-gateway")
    )
    val listeners = set.getSpec.getListeners.asScala.toVector
    assertEquals(listeners.map(_.getHostname), Vector("app.example.com", "example.com"))
    assertEquals(listeners.map(_.getName), Vector("app.example.com", "example.com"))
    val l = listeners.head
    assertEquals((l.getPort.intValue, l.getProtocol, l.getTls.getMode), (443, "HTTPS", "Terminate"))
    assertEquals(l.getTls.getCertificateRefs.get(0).getName, "app.example.com")
    assertEquals(l.getAllowedRoutes.getNamespaces.getFrom, "Same")
  }

  test(
    "the route carries the derived hostname first, then each custom one, and the set as a second parent"
  ) {
    val r = route(actions(spec))
    assertEquals(
      r.getSpec.getHostnames.asScala.toVector,
      Vector("cart-checkout.example.test", "app.example.com", "example.com")
    )
    val parents = r.getSpec.getParentRefs.asScala.toVector
    assertEquals(
      parents.map(p => (p.getKind, p.getName)),
      Vector("Gateway" -> "ankka", "ListenerSet" -> "cart-hostnames")
    )
    assertEquals(parents.head.getSectionName, "https")
  }

  test("the certificates and the set come before the route, the prune after it") {
    val as    = actions(spec)
    val route = as.indexWhere(_.isInstanceOf[Action.EnsureHttpRoute])
    val set   = as.indexWhere(_.isInstanceOf[Action.EnsureListenerSet])
    val prune = as.indexWhere(_.isInstanceOf[Action.PruneHostnameCertificates])
    assert(set >= 0 && set < route && route < prune, as.map(_.describe).mkString("\n"))
    assertEquals(
      as.collectFirst { case p: Action.PruneHostnameCertificates => p: Action },
      Some(
        Action.PruneHostnameCertificates(
          "ankka-checkout",
          "uid-1",
          Vector("app.example.com", "example.com")
        )
      )
    )
    assert(!as.exists(_.isInstanceOf[Action.RemoveListenerSet]))
  }

  test(
    "a service with no custom hostname renders the route it rendered before, and removes any set"
  ) {
    val plain = spec.copy(customHostnames = Nil)
    val as    = actions(plain)
    val r     = route(as)
    assertEquals(r.getSpec.getHostnames.asScala.toVector, Vector("cart-checkout.example.test"))
    assertEquals(r.getSpec.getParentRefs.size, 1)
    assertEquals(
      route(
        Rendering
          .render(
            resource(plain),
            Settings.default.copy(baseDomain = Some("example.test")),
            ProvisioningPlan.Supplied
          )
          .toOption
          .get
      ),
      r
    )
    assert(as.contains(Action.RemoveListenerSet("ankka-checkout", "cart-hostnames", "uid-1")))
    assert(as.contains(Action.PruneHostnameCertificates("ankka-checkout", "uid-1", Vector.empty)))
    assert(certificates(as).isEmpty)
  }

  test("a service that is not exposed keeps its names on the resource and renders none of them") {
    val as = actions(spec.copy(exposed = false))
    assert(certificates(as).isEmpty)
    assert(!as.exists(_.isInstanceOf[Action.EnsureListenerSet]))
    assert(as.contains(Action.RemoveListenerSet("ankka-checkout", "cart-hostnames", "uid-1")))
    assert(as.contains(Action.PruneHostnameCertificates("ankka-checkout", "uid-1", Vector.empty)))
  }

  test("a hostname dropped from the spec is dropped from the set and pruned") {
    val as  = actions(spec.copy(customHostnames = List("app.example.com")))
    val set = as.collectFirst { case Action.EnsureListenerSet(s) => s }.get
    assertEquals(
      set.getSpec.getListeners.asScala.map(_.getHostname).toVector,
      Vector("app.example.com")
    )
    assert(
      as.contains(
        Action.PruneHostnameCertificates("ankka-checkout", "uid-1", Vector("app.example.com"))
      )
    )
  }

  test("an operator that names no issuer renders nothing for a custom hostname") {
    val as = actions(spec, settings.copy(hostnameIssuer = None))
    assert(certificates(as).isEmpty)
    assert(!as.exists(_.isInstanceOf[Action.EnsureListenerSet]))
    assertEquals(
      route(as).getSpec.getHostnames.asScala.toVector,
      Vector("cart-checkout.example.test")
    )
  }

  // features/exposure/tenancy.feature: the operator attaches to the gateway only what serves a
  // hostname a service holds. A name under the base domain is the platform's; a set's listener for
  // `api.<base>` would outrank the Gateway's wildcard (spike S1), so a resource naming one, which the
  // control plane would have refused, renders nothing for it.
  test("a name under the base domain is never rendered, whoever wrote the resource") {
    val hostile = spec.copy(customHostnames =
      List("api.example.test", "example.test", "*.example.com", "app.example.com")
    )
    val as  = actions(hostile)
    val set = as.collectFirst { case Action.EnsureListenerSet(s) => s }.get
    assertEquals(
      set.getSpec.getListeners.asScala.map(_.getHostname).toVector,
      Vector("app.example.com")
    )
    assertEquals(certificates(as).map(_.getMetadata.getName), Vector("app.example.com"))
    assertEquals(
      route(as).getSpec.getHostnames.asScala.toVector,
      Vector("cart-checkout.example.test", "app.example.com")
    )
  }

  test("every action's description names the hostname and no object carries a key") {
    val described = actions(spec).map(_.describe).mkString("\n")
    assert(described.contains("app.example.com"), described)
    assert(!described.toLowerCase.contains("private key"), described)
  }
