package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{AnkkaService, AnkkaServiceSpec}
import io.fabric8.kubernetes.api.model.gatewayapi.v1.{
  BackendTLSPolicy,
  BackendTLSPolicyBuilder,
  BackendTLSPolicySpecBuilder,
  BackendTLSPolicyValidationBuilder,
  LocalObjectReferenceBuilder,
  LocalPolicyTargetReferenceWithSectionNameBuilder
}
import io.fabric8.kubernetes.api.model.networking.v1.{
  NetworkPolicy,
  NetworkPolicyBuilder,
  NetworkPolicyIngressRuleBuilder,
  NetworkPolicyPeerBuilder,
  NetworkPolicyPortBuilder,
  NetworkPolicySpecBuilder
}
import io.fabric8.kubernetes.api.model.{
  GenericKubernetesResource,
  GenericKubernetesResourceBuilder,
  IntOrString,
  KeyToPathBuilder,
  LabelSelectorBuilder,
  ObjectMeta,
  ObjectMetaBuilder,
  SecretVolumeSourceBuilder,
  Volume,
  VolumeBuilder,
  VolumeMount,
  VolumeMountBuilder
}

import scala.jdk.CollectionConverters.*

/**
 * Everything a workload needs for zero trust, rendered from what the operator already knows about
 * it (feature 014): a certificate per purpose, the mounts that put them where the runtime reads
 * them, the network policies that decide who may connect at all, and the gateway's instruction to
 * reach the service over TLS.
 *
 * Always rendered — there is no switch, in the descriptor or the installation. The operator never
 * reads a private key: it asks cert-manager for a certificate by writing a `Certificate`, and the
 * kubelet mounts the Secret cert-manager writes.
 */
object ZeroTrust:

  /**
   * The installation's authorities, from the `pki` component. Cluster-scoped, so every project uses
   * them.
   */
  val ClusterIssuer: String = "ankka-cluster"
  val ServiceIssuer: String = "ankka-service"

  /**
   * The ConfigMap trust-manager writes into every ankka namespace: the service authority's root.
   */
  val ServiceCaConfigMap: String = "ankka-service-ca"

  /**
   * Where the runtime reads each identity — fixed, so neither a descriptor nor an image names them.
   */
  val ClusterMount: String    = "/var/run/secrets/ankka/cluster"
  val ServiceMount: String    = "/var/run/secrets/ankka/service"
  val DatabaseMount: String   = "/var/run/secrets/ankka/database"
  val DatabaseCaMount: String = "/var/run/secrets/ankka/database-ca"
  val MountMount: String      = "/var/run/secrets/ankka/mount"

  /**
   * A plain HTTP listener answering `GET /ready` and nothing else. The kubelet cannot present a
   * client certificate, and client authentication belongs to a listener, so once the management
   * port requires one, readiness needs a port of its own.
   */
  val ProbePort: Int        = 7627
  val ProbePortName: String = "probe"

  /**
   * The mutual TLS listener the control plane reads a deployed instance's topology over (feature
   * 019). Rendered on every workload beside the cluster ports; its network rule admits the control
   * plane's pods and nobody else, and the listener itself refuses any other identity.
   */
  val ObservePort: Int        = 7628
  val ObservePortName: String = "observe"

  /**
   * The control plane's namespace and pod label, as `kustomization/components/controlplane` has
   * them.
   */
  val ControlPlaneNamespace: String = "ankka-controlplane"
  val ControlPlanePodLabels: Map[String, String] =
    Map("app.kubernetes.io/name" -> "ankka-controlplane")

  /**
   * A day's validity renewed every eight hours: the certificate being replaced stays valid for
   * sixteen more, far beyond the runtime's one-minute reload and the kubelet's Secret propagation,
   * and a leaked key is useful for a day rather than for the life of a deployment.
   */
  val Duration: String    = "24h"
  val RenewBefore: String = "16h"

  def identityUri(spec: AnkkaServiceSpec): String = s"ankka://${spec.projectId}/${spec.serviceName}"

  def clusterCertificateName(service: String): String = s"$service-cluster"
  def clusterSecretName(service: String): String      = s"$service-cluster-tls"
  def serviceCertificateName(service: String): String = s"$service-service"
  def serviceSecretName(service: String): String      = s"$service-service-tls"
  def mountCertificateName(service: String): String   = s"$service-mount"
  def mountSecretName(service: String): String        = s"$service-mount-tls"

  /**
   * `ankka://<project>/<service>/mount`: the identity requests under a mount are passed on with.
   */
  def mountUri(spec: AnkkaServiceSpec): String = s"${identityUri(spec)}/mount"

  /**
   * The certificate a web-hosted service's proxy passes requests under its mounts on with (feature
   * 021). A client certificate only, with no DNS name: nothing is served under it. A runtime reads
   * it as the internet when the request comes from its own project and refuses it otherwise; a
   * runtime from before web hosting refuses it outright.
   */
  def mountCertificate(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String
  ): GenericKubernetesResource =
    certificate(
      metadata(resource, spec, namespace, mountCertificateName(spec.serviceName)),
      Map(
        "secretName" -> mountSecretName(spec.serviceName),
        "uris"       -> List(mountUri(spec)).asJava,
        "usages"     -> List("client auth").asJava,
        "issuerRef"  -> issuer(ServiceIssuer, "ClusterIssuer")
      )
    )

  /**
   * The certificate a service's instances present to each other, for remoting and management. Every
   * instance holds the same one, which is what Pekko's remoting and ankka's management both check:
   * a peer is a node of this service exactly when its certificate names this service.
   */
  def clusterCertificate(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String
  ): GenericKubernetesResource =
    certificate(
      metadata(resource, spec, namespace, clusterCertificateName(spec.serviceName)),
      Map(
        "secretName" -> clusterSecretName(spec.serviceName),
        "uris"       -> List(identityUri(spec)).asJava,
        "dnsNames"   -> List(s"${spec.serviceName}.$namespace.svc").asJava,
        "usages"     -> List("server auth", "client auth").asJava,
        "issuerRef"  -> issuer(ClusterIssuer, "ClusterIssuer")
      )
    )

  /**
   * The certificate a service serves HTTP with and calls other services with. Its DNS names are the
   * service's in-cluster addresses, so a caller can verify it reached the service it asked for; its
   * URI is the caller identity every service it calls reads.
   */
  def serviceCertificate(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String,
      commonName: Option[String] = None
  ): GenericKubernetesResource =
    certificate(
      metadata(resource, spec, namespace, serviceCertificateName(spec.serviceName)),
      // A common name only where the installation has a broker (feature 027): Kafka knows a TLS
      // client by its certificate's subject and nothing else, and the service's user is named for it.
      commonName.map("commonName" -> _).toMap ++ Map(
        "secretName" -> serviceSecretName(spec.serviceName),
        "uris"       -> List(identityUri(spec)).asJava,
        "dnsNames"   -> serviceDnsNames(spec, namespace).asJava,
        "usages"     -> List("server auth", "client auth").asJava,
        "issuerRef"  -> issuer(ServiceIssuer, "ClusterIssuer")
      )
    )

  def serviceDnsNames(spec: AnkkaServiceSpec, namespace: String): List[String] =
    List(
      spec.serviceName,
      s"${spec.serviceName}.$namespace.svc",
      s"${spec.serviceName}.$namespace.svc.cluster.local"
    )

  /**
   * Who may connect to a service's cluster ports: its own pods, and nobody else. The probe port is
   * open to every source, because it discloses one bit and a policy cannot name the node the
   * kubelet probes from.
   */
  def clusterPolicy(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String
  ): NetworkPolicy =
    val identity = Labels.identity(spec.projectId, spec.serviceName)
    new NetworkPolicyBuilder()
      .withMetadata(metadata(resource, spec, namespace, clusterPolicyName(spec.serviceName)))
      .withSpec(
        new NetworkPolicySpecBuilder()
          .withPodSelector(new LabelSelectorBuilder().withMatchLabels(identity.asJava).build())
          .withPolicyTypes("Ingress")
          .withIngress(
            new NetworkPolicyIngressRuleBuilder()
              .withPorts(tcp(Rendering.RemotingPort), tcp(Rendering.ManagementPort))
              .withFrom(
                new NetworkPolicyPeerBuilder()
                  .withPodSelector(
                    new LabelSelectorBuilder().withMatchLabels(identity.asJava).build()
                  )
                  .build()
              )
              .build(),
            new NetworkPolicyIngressRuleBuilder().withPorts(tcp(ProbePort)).build(),
            // The observe port: the control plane's pods, in the control plane's namespace, and
            // nothing else — not this service's own pods, not the gateway, not another project.
            new NetworkPolicyIngressRuleBuilder()
              .withPorts(tcp(ObservePort))
              .withFrom(
                new NetworkPolicyPeerBuilder()
                  .withNamespaceSelector(
                    new LabelSelectorBuilder()
                      .withMatchLabels(
                        Map("kubernetes.io/metadata.name" -> ControlPlaneNamespace).asJava
                      )
                      .build()
                  )
                  .withPodSelector(
                    new LabelSelectorBuilder().withMatchLabels(ControlPlanePodLabels.asJava).build()
                  )
                  .build()
              )
              .build()
          )
          .build()
      )
      .build()

  /** Where Envoy Gateway runs the proxy for every Gateway it implements. */
  val GatewayProxyNamespace: String = "envoy-gateway-system"

  /** The labels Envoy Gateway puts on the proxy pods of the installation's one Gateway. */
  val GatewayProxyLabels: Map[String, String] = Map(
    "gateway.envoyproxy.io/owning-gateway-name"      -> "ankka",
    "gateway.envoyproxy.io/owning-gateway-namespace" -> Rendering.GatewayNamespace
  )

  def clusterPolicyName(service: String): String = s"$service-cluster"
  def httpPolicyName(service: String): String    = s"$service-http"
  def grpcPolicyName(service: String): String    = s"$service-grpc"
  def probePolicyName(service: String): String   = s"$service-probe"

  /**
   * Who may connect to a web-hosted service's probe port: anyone (feature 021). Every other service
   * has that rule in its cluster policy, and a web-hosted service forms no cluster and has none, so
   * the one bit the probe discloses is opened by a policy of its own.
   */
  def probePolicy(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String
  ): NetworkPolicy =
    val identity = Labels.identity(spec.projectId, spec.serviceName)
    new NetworkPolicyBuilder()
      .withMetadata(metadata(resource, spec, namespace, probePolicyName(spec.serviceName)))
      .withSpec(
        new NetworkPolicySpecBuilder()
          .withPodSelector(new LabelSelectorBuilder().withMatchLabels(identity.asJava).build())
          .withPolicyTypes("Ingress")
          .withIngress(new NetworkPolicyIngressRuleBuilder().withPorts(tcp(ProbePort)).build())
          .build()
      )
      .build()

  /**
   * Who may connect to a service's HTTP port: the gateway's proxies, and any workload of this
   * installation, in any project. The network does not decide which project may call which — the
   * callee's ACL does, from the caller's certificate. What the network refuses is everything that
   * carries no platform identity at all, which in a cluster shared with other workloads is most
   * things.
   */
  def httpPolicy(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String,
      port: Int
  ): NetworkPolicy =
    workloadsAndGateway(resource, spec, namespace, httpPolicyName(spec.serviceName), port)

  /**
   * Who may connect to a service's gRPC port: the same peers as its HTTP port, and for the same
   * reason. A policy of its own rather than a second port on the HTTP one, so that a service that
   * declares no gRPC keeps the policy object it had, unchanged.
   */
  def grpcPolicy(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String,
      port: Int
  ): NetworkPolicy =
    workloadsAndGateway(resource, spec, namespace, grpcPolicyName(spec.serviceName), port)

  private def workloadsAndGateway(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String,
      name: String,
      port: Int
  ): NetworkPolicy =
    val identity = Labels.identity(spec.projectId, spec.serviceName)
    val managed  = Map(Labels.ManagedByKey -> Labels.ManagedByAnkka).asJava
    new NetworkPolicyBuilder()
      .withMetadata(metadata(resource, spec, namespace, name))
      .withSpec(
        new NetworkPolicySpecBuilder()
          .withPodSelector(new LabelSelectorBuilder().withMatchLabels(identity.asJava).build())
          .withPolicyTypes("Ingress")
          .withIngress(
            new NetworkPolicyIngressRuleBuilder()
              .withPorts(tcp(port))
              .withFrom(
                new NetworkPolicyPeerBuilder()
                  .withNamespaceSelector(
                    new LabelSelectorBuilder().withMatchLabels(managed).build()
                  )
                  .withPodSelector(new LabelSelectorBuilder().withMatchLabels(managed).build())
                  .build(),
                // The gateway's proxy pods, which Envoy Gateway runs in its own namespace rather
                // than the Gateway's: admitting the Gateway's namespace admits nothing that routes.
                new NetworkPolicyPeerBuilder()
                  .withNamespaceSelector(
                    new LabelSelectorBuilder()
                      .withMatchLabels(
                        Map("kubernetes.io/metadata.name" -> GatewayProxyNamespace).asJava
                      )
                      .build()
                  )
                  .withPodSelector(
                    new LabelSelectorBuilder().withMatchLabels(GatewayProxyLabels.asJava).build()
                  )
                  .build()
              )
              .build()
          )
          .build()
      )
      .build()

  /**
   * The gateway's instruction to reach this service over TLS: verify its certificate against the
   * service authority and require the name the service's certificate carries. The certificate the
   * gateway presents in return is the installation's, set once on the gateway's own proxy.
   */
  def backendTlsPolicy(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String
  ): BackendTLSPolicy =
    new BackendTLSPolicyBuilder()
      .withMetadata(metadata(resource, spec, namespace, spec.serviceName))
      .withSpec(
        new BackendTLSPolicySpecBuilder()
          // One per port the service has, so the gateway reaches each over TLS.
          .withTargetRefs(
            (spec.port.map(_ => "http").toVector ++ spec.grpcPort.map(_ => Rendering.GrpcPortName))
              .map { section =>
                new LocalPolicyTargetReferenceWithSectionNameBuilder()
                  .withGroup("")
                  .withKind("Service")
                  .withName(Names.service(spec.serviceName))
                  .withSectionName(section)
                  .build()
              }*
          )
          .withValidation(
            new BackendTLSPolicyValidationBuilder()
              .withCaCertificateRefs(
                new LocalObjectReferenceBuilder()
                  .withGroup("")
                  .withKind("ConfigMap")
                  .withName(ServiceCaConfigMap)
                  .build()
              )
              .withHostname(s"${spec.serviceName}.$namespace.svc.cluster.local")
              .build()
          )
          .build()
      )
      .build()

  /**
   * Which identities a pod holds. A node holds the cluster and service certificates, and the
   * database pair when its database was provisioned; a web-hosted pod holds the service certificate
   * alone, since it forms no cluster and has no database (feature 021).
   */
  final case class Held(
      cluster: Boolean,
      service: Boolean,
      database: Boolean,
      mount: Boolean = false
  )

  /** One volume per identity the pod holds. */
  def volumes(held: Held, spec: AnkkaServiceSpec, clusterName: String): Vector[Volume] =
    Option
      .when(held.cluster)(
        secretVolume("ankka-cluster-tls", clusterSecretName(spec.serviceName), None)
      )
      .toVector ++
      Option.when(held.service)(
        secretVolume("ankka-service-tls", serviceSecretName(spec.serviceName), None)
      ) ++
      Option.when(held.database)(
        secretVolume(
          "ankka-database-tls",
          Database.certificateSecret(spec.serviceName),
          None,
          Some(Database.KeyMode)
        )
      ) ++
      // Only `ca.crt` from CNPG's own server authority: that Secret also holds its private key,
      // and nothing in the pod has any business with it.
      Option.when(held.database)(
        secretVolume("ankka-database-ca", s"$clusterName-ca", Some("ca.crt"))
      ) ++
      Option.when(held.mount)(
        secretVolume("ankka-mount-tls", mountSecretName(spec.serviceName), None)
      )

  /** Where the platform's container reads each identity the pod holds. */
  def mounts(held: Held): Vector[VolumeMount] =
    Option.when(held.cluster)(mount("ankka-cluster-tls", ClusterMount)).toVector ++
      Option.when(held.service)(mount("ankka-service-tls", ServiceMount)) ++
      Option.when(held.database)(mount("ankka-database-tls", DatabaseMount)) ++
      Option.when(held.database)(mount("ankka-database-ca", DatabaseCaMount)) ++
      Option.when(held.mount)(mount("ankka-mount-tls", MountMount))

  /**
   * The database half: each provisioned service authenticates to its project's Postgres with a
   * certificate issued by the project's own database authority, whose common name is the role.
   */
  object Database:
    val Issuer: String = "ankka-database"

    /** The pod's filesystem group, which owns the mounted database key. */
    val FsGroup: java.lang.Long = java.lang.Long.valueOf(2000L)

    /**
     * `u=r,g=r`: libpq refuses a key anyone else can read, and a non-root runtime reads it by
     * group.
     */
    val KeyMode: Integer = Integer.valueOf(0x120) // 0440

    def certificateName(service: String): String   = s"$service-database"
    def certificateSecret(service: String): String = s"$service-database-tls"

    /**
     * Where a service switched to another of its project's clusters connects (feature 041): the
     * cluster's own address, over the credential Secret's, which is written once and says the
     * project database's; and the line of history it is on. Nothing for a service on the project
     * database, so its pod is what it was before the feature and an upgrade rolls nothing.
     */
    def switched(cluster: Option[String]): Vector[(String, String)] =
      cluster.toVector.flatMap(c => Vector("ANKKA_DB_HOST" -> s"$c-rw", "ANKKA_DB_LINE" -> c))

    /** The variables that tell the runtime to connect with a certificate: paths, never secrets. */
    val Environment: Vector[(String, String)] = Vector(
      "ANKKA_DB_SSL_MODE"      -> "verify-full",
      "ANKKA_DB_SSL_ROOT_CERT" -> s"$DatabaseCaMount/ca.crt",
      "ANKKA_DB_SSL_CERT"      -> s"$DatabaseMount/tls.crt",
      "ANKKA_DB_SSL_KEY"       -> s"$DatabaseMount/tls.key"
    )

    def clientCertificate(
        resource: AnkkaService,
        spec: AnkkaServiceSpec,
        namespace: String
    ): GenericKubernetesResource =
      certificate(
        metadata(resource, spec, namespace, certificateName(spec.serviceName)),
        Map(
          "secretName" -> certificateSecret(spec.serviceName),
          // Postgres's `cert` method matches the common name to the role, and nothing else.
          "commonName" -> spec.serviceName,
          "usages"     -> List("client auth").asJava,
          "issuerRef"  -> issuer(Issuer, "Issuer")
        )
      )

  private def certificate(
      meta: ObjectMeta,
      fields: Map[String, AnyRef]
  ): GenericKubernetesResource =
    val spec = fields ++ Map(
      "duration"    -> Duration,
      "renewBefore" -> RenewBefore,
      // RSA and PKCS#8: Pekko's remoting engine reads RSA only, and both the JDK and Netty read PKCS#8.
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
      .withAdditionalProperties(Map[String, AnyRef]("spec" -> spec.asJava).asJava)
      .build()

  private def issuer(name: String, kind: String): java.util.Map[String, String] =
    Map("name" -> name, "kind" -> kind, "group" -> "cert-manager.io").asJava

  private def metadata(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String,
      name: String
  ): ObjectMeta =
    new ObjectMetaBuilder()
      .withName(name)
      .withNamespace(namespace)
      .withLabels(Labels.merged(spec.projectId, spec.serviceName, spec.labels).asJava)
      .withOwnerReferences(Labels.ownerReference(resource))
      .build()

  private def tcp(port: Int) =
    new NetworkPolicyPortBuilder().withProtocol("TCP").withPort(new IntOrString(port)).build()

  // Never `subPath`: the kubelet does not update a subPath mount when a Secret changes, and rotation
  // is the point.
  private def secretVolume(
      name: String,
      secret: String,
      onlyKey: Option[String],
      mode: Option[Integer] = None
  ): Volume =
    val source = new SecretVolumeSourceBuilder().withSecretName(secret)
    mode.foreach(m => source.withDefaultMode(m): Unit)
    onlyKey.foreach(key =>
      source.withItems(new KeyToPathBuilder().withKey(key).withPath(key).build()): Unit
    )
    new VolumeBuilder().withName(name).withSecret(source.build()).build()

  private def mount(name: String, path: String): VolumeMount =
    new VolumeMountBuilder().withName(name).withMountPath(path).withReadOnly(true).build()
