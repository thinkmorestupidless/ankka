package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{AnkkaService, AnkkaServiceSpec}
import io.fabric8.kubernetes.api.model.gatewayapi.v1.{
  AllowedRoutesBuilder,
  ListenerEntryBuilder,
  ListenerSet,
  ListenerSetBuilder,
  ListenerSetSpecBuilder,
  ListenerTLSConfigBuilder,
  ParentGatewayReferenceBuilder,
  ParentReference,
  ParentReferenceBuilder,
  RouteNamespacesBuilder,
  SecretObjectReferenceBuilder
}
import io.fabric8.kubernetes.api.model.{
  GenericKubernetesResource,
  GenericKubernetesResourceBuilder,
  ObjectMetaBuilder
}

import scala.jdk.CollectionConverters.*

/**
 * What serves an exposed service's custom hostnames (feature 045): a certificate for each, asked of
 * the issuer the installation names, and one ListenerSet in the service's own namespace with a
 * listener for each, attached to the installation's Gateway. The route carries the names and the
 * set as a second parent (`Rendering.httpRoute`).
 *
 * The set is the only thing the operator attaches to the Gateway, and it can attach nothing else:
 * it holds no verb on the Gateway, and the Gateway admits sets only from managed namespaces. The
 * names are read from the resource, unlike the derived hostname; the control plane refuses one the
 * project has not proved or another service holds. The operator trusts no writer of the resource,
 * though, and the Gateway's own listeners win only an *equal* hostname: a set's listener for
 * `api.<base>` is more specific than the Gateway's `*.<base>`, is accepted, and would serve the
 * control plane's name (seen in spike S1). So no name under the base domain is ever rendered here,
 * whatever the resource says; it is reported rejected instead (`HostnameRules`).
 */
object HostnameRendering:

  /**
   * On every certificate this renders, so a removed hostname's can be found and pruned. A marker,
   * not the hostname: a label value is at most 63 characters and a hostname up to 253.
   */
  val CertificateLabel: String = "ankka.thinkmorestupidless.com/hostname-certificate"

  /** The service's one set; its listeners are applied whole, so a removed one is simply gone. */
  def setName(serviceName: String): String = s"$serviceName-hostnames"

  /**
   * The certificate and its Secret are named by the hostname: a valid object name, unique across
   * the installation as the hostname is, and readable in `kubectl get certificates`. No other
   * Secret in a project has a dot in its name, which is how the descriptor's rules and a project
   * secret's name refuse it (`ReservedSecretNamesSuite`).
   */
  def certificateName(hostname: String): String = hostname
  def secretName(hostname: String): String      = hostname

  /**
   * The custom hostnames that are rendered: the spec's, while the service is exposed, except any
   * the operator refuses on its own account (`refusal`).
   */
  def rendered(spec: AnkkaServiceSpec, settings: Settings): Vector[String] =
    if spec.exposed && spec.port.orElse(spec.grpcPort).isDefined && settings.baseDomain.isDefined &&
      settings.hostnameIssuer.isDefined
    then spec.customHostnames.toVector.distinct.filter(h => refusal(h, settings).isEmpty)
    else Vector.empty

  /**
   * Why the operator will not serve this name whatever the resource says: the base domain and every
   * name under it are the platform's, and a listener for one would outrank the Gateway's wildcard.
   * The control plane refuses the same names first; this holds if something else wrote the
   * resource.
   */
  def refusal(hostname: String, settings: Settings): Option[String] =
    val h = hostname.toLowerCase(java.util.Locale.ROOT).stripSuffix(".")
    settings.baseDomain.map(_.toLowerCase(java.util.Locale.ROOT)).flatMap { base =>
      Option.when(h == base || h.endsWith(s".$base") || h.contains('*'))(
        s"a custom hostname cannot be under the base domain '$base' or a wildcard; nothing is " +
          "rendered for it"
      )
    }

  /**
   * Before the route: each certificate, then the set that serves them. Nothing when none is
   * rendered, so a service without custom hostnames renders what it rendered before.
   */
  def ensure(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String,
      settings: Settings
  ): Vector[Action] =
    val hostnames = rendered(spec, settings)
    settings.hostnameIssuer match
      case Some(issuer) if hostnames.nonEmpty =>
        hostnames.map(h =>
          Action.EnsureCertificate(certificate(resource, spec, namespace, h, issuer))
        ) :+
          Action.EnsureListenerSet(listenerSet(resource, spec, namespace, hostnames))
      case _ => Vector.empty

  /**
   * After the route: the set when no hostname is rendered, and every certificate this service no
   * longer names. Rendered for every service, as the route's removal is, so a hostname removed in
   * the same apply that unexposed the service leaves nothing behind.
   */
  def prune(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String,
      settings: Settings
  ): Vector[Action] =
    val hostnames = rendered(spec, settings)
    val ownerUid  = Option(resource.getMetadata).flatMap(m => Option(m.getUid)).getOrElse("")
    Option
      .when(hostnames.isEmpty)(
        Action.RemoveListenerSet(namespace, setName(spec.serviceName), ownerUid)
      )
      .toVector :+
      Action.PruneHostnameCertificates(namespace, ownerUid, hostnames.map(certificateName))

  /** The route's second parent, when the service has a set. */
  def parent(spec: AnkkaServiceSpec, settings: Settings): Option[ParentReference] =
    Option.when(rendered(spec, settings).nonEmpty)(
      new ParentReferenceBuilder()
        .withGroup("gateway.networking.k8s.io")
        .withKind("ListenerSet")
        .withName(setName(spec.serviceName))
        .build()
    )

  def certificate(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String,
      hostname: String,
      issuer: String
  ): GenericKubernetesResource =
    val meta = new ObjectMetaBuilder()
      .withName(certificateName(hostname))
      .withNamespace(namespace)
      .withLabels(
        (Labels.merged(
          spec.projectId,
          spec.serviceName,
          spec.labels
        ) + (CertificateLabel -> "true")).asJava
      )
      .withOwnerReferences(Labels.ownerReference(resource))
      .build()
    val fields = Map[String, AnyRef](
      "secretName" -> secretName(hostname),
      "dnsNames"   -> List(hostname).asJava,
      "issuerRef" -> Map(
        "name"  -> issuer,
        "kind"  -> "ClusterIssuer",
        "group" -> "cert-manager.io"
      ).asJava,
      // As every workload's: RSA in PKCS#8, a new key at each renewal. Duration and renewal are the
      // issuer's: Let's Encrypt fixes them, and the local authority takes cert-manager's defaults.
      "privateKey" -> Map(
        "algorithm"      -> "RSA",
        "size"           -> Integer.valueOf(2048),
        "encoding"       -> "PKCS8",
        "rotationPolicy" -> "Always"
      ).asJava
    )
    new GenericKubernetesResourceBuilder()
      .withApiVersion("cert-manager.io/v1")
      .withKind("Certificate")
      .withMetadata(meta)
      .withAdditionalProperties(Map[String, AnyRef]("spec" -> fields.asJava).asJava)
      .build()

  def listenerSet(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String,
      hostnames: Vector[String]
  ): ListenerSet =
    val listeners = hostnames.map { hostname =>
      new ListenerEntryBuilder()
        // A listener's name is a section name a route may target, and a hostname is a valid one.
        .withName(hostname)
        .withHostname(hostname)
        .withPort(443)
        .withProtocol("HTTPS")
        .withTls(
          new ListenerTLSConfigBuilder()
            .withMode("Terminate")
            .withCertificateRefs(
              new SecretObjectReferenceBuilder()
                .withKind("Secret")
                .withName(secretName(hostname))
                .build()
            )
            .build()
        )
        // Only this namespace's routes, which in a project namespace are the operator's own.
        .withAllowedRoutes(
          new AllowedRoutesBuilder()
            .withNamespaces(new RouteNamespacesBuilder().withFrom("Same").build())
            .build()
        )
        .build()
    }
    new ListenerSetBuilder()
      .withMetadata(
        new ObjectMetaBuilder()
          .withName(setName(spec.serviceName))
          .withNamespace(namespace)
          .withLabels(Labels.merged(spec.projectId, spec.serviceName, spec.labels).asJava)
          .withOwnerReferences(Labels.ownerReference(resource))
          .build()
      )
      .withSpec(
        new ListenerSetSpecBuilder()
          .withParentRef(
            new ParentGatewayReferenceBuilder()
              .withGroup("gateway.networking.k8s.io")
              .withKind("Gateway")
              .withName(Rendering.GatewayName)
              .withNamespace(Rendering.GatewayNamespace)
              .build()
          )
          .withListeners(listeners*)
          .build()
      )
      .build()
