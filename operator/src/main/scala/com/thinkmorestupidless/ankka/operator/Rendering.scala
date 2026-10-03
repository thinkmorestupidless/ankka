package com.thinkmorestupidless.ankka.operator

import io.fabric8.kubernetes.api.model.gatewayapi.v1.{
  HTTPBackendRefBuilder,
  HTTPRoute,
  HTTPRouteBuilder,
  HTTPRouteRuleBuilder,
  HTTPRouteSpecBuilder,
  ParentReferenceBuilder
}
import io.fabric8.kubernetes.api.model.apps.{
  Deployment,
  DeploymentBuilder,
  DeploymentSpecBuilder,
  DeploymentStrategyBuilder,
  RollingUpdateDeploymentBuilder
}
import io.fabric8.kubernetes.api.model.rbac.{
  PolicyRuleBuilder,
  Role,
  RoleBinding,
  RoleBindingBuilder,
  RoleBuilder,
  RoleRefBuilder,
  SubjectBuilder
}
import io.fabric8.kubernetes.api.model.{
  Container,
  ContainerBuilder,
  ContainerPortBuilder,
  HTTPGetActionBuilder,
  Lifecycle,
  LifecycleBuilder,
  LifecycleHandlerBuilder,
  SleepActionBuilder,
  IntOrString,
  ObjectFieldSelectorBuilder,
  ProbeBuilder,
  Service,
  ServiceBuilder,
  ServiceAccount,
  ServiceAccountBuilder,
  ServicePortBuilder,
  ServiceSpecBuilder,
  EnvFromSourceBuilder,
  EnvVar,
  EnvVarBuilder,
  EnvVarSourceBuilder,
  SecretEnvSourceBuilder,
  SecretKeySelectorBuilder,
  LabelSelectorBuilder,
  LocalObjectReference,
  ObjectMetaBuilder,
  PodSpecBuilder,
  PodTemplateSpecBuilder,
  Quantity,
  ResourceRequirementsBuilder,
  Volume,
  VolumeBuilder,
  VolumeMountBuilder
}
import com.thinkmorestupidless.ankka.crd.{EnvEntry, Hostnames, AnkkaService, AnkkaServiceSpec}

import scala.jdk.CollectionConverters.*

/**
 * A resource becomes a set of cluster objects.
 *
 * Total, pure and deterministic: no clock, no client, no randomness, so the same resource at the
 * same generation renders byte-identically every time. That is not a nicety — comparing desired
 * against observed is only meaningful if rendering is reproducible, and an accidental
 * `Instant.now()` would make every pass look like a change.
 */
object Rendering:

  /**
   * The instance count is the descriptor's `autoscaling.minInstances`, honoured since feature 004.
   *
   * Before that, one replica always — a correctness constraint, because each pod joined *itself*
   * and two replicas were two clusters writing one journal. Nodes now find each other (see
   * `ClusterFormation` and the Kubernetes overlay), so the count is the descriptor's. Still no
   * HorizontalPodAutoscaler: scaling a sharded cluster on a load signal means every scale-in is a
   * member leaving under pressure, and that needs draining proven first.
   */
  def replicas(spec: AnkkaServiceSpec): Int =
    if spec.paused then 0 else spec.autoscaling.minInstances

  /**
   * How many discovered peers a starting node must see before any of them will form a cluster. One
   * instance must be able to form alone; at `nr = instances` a single unschedulable pod would hold
   * the whole service down; and the documentation warns against 1 for more than one — so 2. Changes
   * only when crossing between one instance and several, which is why scaling 3 → 5 touches no pod
   * that stays (research R4, R5).
   */
  def requiredContactPoints(spec: AnkkaServiceSpec): Int =
    math.min(spec.autoscaling.minInstances, 2)

  /** How long a stopping pod keeps serving while endpoints catch up; see the preStop hook. */
  val PreStopSeconds: Long = 5L

  /**
   * `AnkkaServiceSpec.hosting`'s value for a developer's process beside the sidecar (feature 009).
   */
  val ProcessHosting: String = "process"

  /**
   * `AnkkaServiceSpec.hosting`'s value for a WebAssembly module loaded into the runtime (feature
   * 016): one container, the runtime's image, with the module delivered by running the service's
   * own image once, as an init container that copies it into a volume the two share.
   */
  val WasmHosting: String = "wasm"

  /**
   * `AnkkaServiceSpec.hosting`'s value for any program that serves HTTP, beside the platform's
   * proxy (feature 021): no database, no cluster, two containers.
   */
  val WebHosting: String = "web"

  /** `AnkkaServiceSpec.hosting`'s value, and its default, for an image that is an ankka node. */
  val EmbeddedHosting: String = "embedded"

  /**
   * The proxy's settings, as the operator writes them into the proxy container's environment
   * (feature 021). Mirrors `ProxySettings.Variables` in proxy-core, which the operator must not
   * depend on; `ProxyEnvironmentSuite` in the control plane's tests, which has both, holds the two
   * to each other.
   */
  object ProxyEnv:
    val Project: String         = "ANKKA_PROXY_PROJECT"
    val Service: String         = "ANKKA_PROXY_SERVICE"
    val Port: String            = "ANKKA_PROXY_PORT"
    val ProcessPort: String     = "ANKKA_PROXY_PROCESS_PORT"
    val Mounts: String          = "ANKKA_PROXY_MOUNTS"
    val Callers: String         = "ANKKA_PROXY_CALLERS"
    val PublicAuthority: String = "ANKKA_PROXY_PUBLIC_AUTHORITY"

  /** What a web-hosted service's process is told: where to listen, and where to call services. */
  val ProcessPortEnvVar: String = "PORT"
  val ServicesUrlEnvVar: String = "ANKKA_SERVICES_URL"
  val DefaultProcessPort: Int   = 8080
  val CallingPort: Int          = 7630

  /**
   * The proxy's own allotment (feature 021, research R19). It holds no request, so memory is small;
   * CPU is not, because a JVM throttled to 100m took about ten seconds to start and, on a busy
   * node, missed its readiness deadline. At 250m it serves within two seconds, using under 100Mi.
   */
  private val ProxyQuantities =
    Map("cpu" -> new Quantity("250m"), "memory" -> new Quantity("192Mi")).asJava

  /** Every hosting the operator knows; the CRD's enum is held to exactly these. */
  val Hostings: Set[String] = Set(EmbeddedHosting, ProcessHosting, WasmHosting, WebHosting)

  /** The volume a module is copied into, where the init container writes and the runtime reads. */
  val ModuleVolume: String = "ankka-module"
  val ModuleMount: String  = "/ankka/module"

  /** Where the runtime loads the module from: what the module image's one job is to write. */
  val ModuleFile: String = s"$ModuleMount/service.wasm"

  /** A module is a few megabytes; this is the ceiling the volume and the copy are held to. */
  private val ModuleVolumeLimit = new Quantity("64Mi")

  /** The module image copies one file and exits: small, and bounded, like schema-init. */
  private val ModuleInitRequests =
    Map("cpu" -> new Quantity("10m"), "memory" -> new Quantity("16Mi")).asJava
  private val ModuleInitLimits = Map("memory" -> new Quantity("64Mi")).asJava

  /** Where the two containers of a process-hosted pod find each other, on the pod's loopback. */
  val ProcessPort: Int = 9010
  val SidecarPort: Int = 9011

  /**
   * Mirrors `ServiceSpec.SidecarEnvPrefixes` in controlplane-api; see `containersFor`. A supplied
   * database's variables are the sidecar's too: it is the sidecar that has a journal.
   */
  val SidecarEnvPrefixes: Vector[String] = Vector("ANTHROPIC_", "ANKKA_MODEL_", "ANKKA_DB_")

  /** The developer's container, until the descriptor can size it: small, and bounded. */
  private val AppQuantities =
    Map("cpu" -> new Quantity("100m"), "memory" -> new Quantity("128Mi")).asJava

  val ManagementPort: Int = 7626
  val RemotingPort: Int   = 17355

  /**
   * The installation's one Gateway, which every exposed service's route attaches to (feature 005,
   * `kustomization/components/gateway`). Never rendered here: the operator holds RBAC for routes
   * and nothing else about how traffic enters.
   */
  val GatewayName: String      = "ankka"
  val GatewayNamespace: String = "ankka-gateway"
  val GatewaySection: String   = "https"

  /**
   * @param databasePlan
   *   already decided by the caller (`ServiceReconciler`, from `Provisioning.decide`) — `render`
   *   stays a pure function of its arguments and never reads the cluster itself.
   */
  def render(
      resource: AnkkaService,
      settings: Settings,
      databasePlan: ProvisioningPlan
  ): Either[Vector[String], Vector[Action]] =
    val spec      = Option(resource.getSpec).getOrElse(AnkkaServiceSpec())
    val namespace = Names.namespace(settings.namespacePrefix, spec.projectId)

    val problems =
      Names.namespaceProblems(settings.namespacePrefix, spec.projectId) ++
        Names.serviceNameProblems(spec.serviceName) ++
        (if spec.image.isEmpty then Vector("image must not be empty") else Vector.empty) ++
        (if (spec.hosting == ProcessHosting || spec.hosting == WasmHosting) &&
           settings.sidecarImage.isEmpty
         then Vector("operator has no sidecar image")
         else Vector.empty) ++
        (if spec.hosting == WebHosting && settings.proxyImage.isEmpty
         then Vector("operator has no proxy image")
         else Vector.empty) ++
        (if spec.hosting == WebHosting && spec.port.isEmpty
         then Vector("a web-hosted service's proxy serves HTTP; the resource names no port")
         else Vector.empty) ++
        // Before this feature an unknown value rendered as embedded. A mode the operator does not
        // know is a problem it reports, so the next mode added cannot be rendered as the wrong one.
        (if !Hostings(spec.hosting) then Vector(s"unknown hosting \"${spec.hosting}\"")
         else Vector.empty)

    if problems.nonEmpty then Left(problems)
    else
      Right(
        (Action.EnsureNamespace(namespace) +:
          databaseActions(resource, spec, namespace, settings, databasePlan)) ++
          identityActions(resource, spec, namespace) ++
          zeroTrustActions(resource, spec, namespace) :+
          Action.ApplyDeployment(
            deployment(
              resource,
              spec,
              namespace,
              databasePlan,
              settings.sidecarImage,
              settings.namespacePrefix,
              settings.proxyImage,
              settings.baseDomain,
              settings.httpsPort
            )
          ) :+
          addressAction(resource, spec, namespace) :+
          routeAction(resource, spec, namespace, settings.baseDomain) :+
          backendTlsAction(resource, spec, namespace, settings.baseDomain)
      )

  /**
   * The certificates a service's pods mount and the policies that decide who may connect to them
   * (feature 014) — before the Deployment, so the Secrets exist by the time a pod asks the kubelet
   * for them. A pod scheduled first waits on its volume and starts once cert-manager has issued.
   */
  private def zeroTrustActions(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String
  ): Vector[Action] =
    val ownerUid = Option(resource.getMetadata).flatMap(m => Option(m.getUid)).getOrElse("")
    val http = spec.port match
      case Some(port) =>
        Vector(Action.EnsureNetworkPolicy(ZeroTrust.httpPolicy(resource, spec, namespace, port)))
      case None =>
        Vector(
          Action
            .RemoveNetworkPolicy(namespace, ZeroTrust.httpPolicyName(spec.serviceName), ownerUid)
        )
    if spec.hosting == WebHosting then
      // No cluster certificate and no cluster policy: a web-hosted service forms no cluster. The
      // probe port, which the cluster policy opens for every other service, gets a policy of its
      // own (feature 021).
      Action.EnsureCertificate(ZeroTrust.serviceCertificate(resource, spec, namespace)) +:
        http :+
        Action.EnsureNetworkPolicy(ZeroTrust.probePolicy(resource, spec, namespace))
    else
      // The service certificate always, not only with a port: it is the identity the service calls
      // others with, and the runtime's HTTP server starts in every ankka service, exposed or not.
      Vector(
        Action.EnsureCertificate(ZeroTrust.clusterCertificate(resource, spec, namespace)),
        Action.EnsureCertificate(ZeroTrust.serviceCertificate(resource, spec, namespace)),
        Action.EnsureNetworkPolicy(ZeroTrust.clusterPolicy(resource, spec, namespace))
      ) ++ http

  /**
   * Beside the route, with the same three conditions: the gateway reaches an exposed service over
   * TLS.
   */
  private def backendTlsAction(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String,
      baseDomain: Option[String]
  ): Action =
    (spec.exposed, spec.port, baseDomain) match
      case (true, Some(_), Some(_)) =>
        Action.EnsureBackendTlsPolicy(ZeroTrust.backendTlsPolicy(resource, spec, namespace))
      case _ =>
        Action.RemoveBackendTlsPolicy(
          namespace,
          spec.serviceName,
          Option(resource.getMetadata).flatMap(m => Option(m.getUid)).getOrElse("")
        )

  /**
   * The identity a service's pods run as, and the one thing it may do: read the pods of its own
   * project, so its nodes can find each other. A Role, never a ClusterRole, so the grant cannot
   * reach another project; one ServiceAccount per service, so it is granted to a service and not to
   * a project's workloads at large. Owned by the resource, so all three go with it.
   */
  private def identityActions(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String
  ): Vector[Action] =
    // A web-hosted pod names the account and mounts no token for it; it has no peers to find, so
    // it is granted nothing (feature 021).
    if spec.hosting == WebHosting then
      Vector(Action.EnsureServiceAccount(serviceAccount(resource, spec, namespace)))
    else
      Vector(
        Action.EnsureServiceAccount(serviceAccount(resource, spec, namespace)),
        Action.EnsureRole(peersRole(resource, spec, namespace)),
        Action.EnsureRoleBinding(peersRoleBinding(resource, spec, namespace))
      )

  private def identityMeta(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String,
      name: String
  ) =
    new ObjectMetaBuilder()
      .withName(name)
      .withNamespace(namespace)
      .withLabels(Labels.merged(spec.projectId, spec.serviceName, spec.labels).asJava)
      .withOwnerReferences(Labels.ownerReference(resource))
      .build()

  def serviceAccount(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String
  ): ServiceAccount =
    new ServiceAccountBuilder()
      .withMetadata(identityMeta(resource, spec, namespace, Names.serviceAccount(spec.serviceName)))
      .build()

  def peersRole(resource: AnkkaService, spec: AnkkaServiceSpec, namespace: String): Role =
    new RoleBuilder()
      .withMetadata(identityMeta(resource, spec, namespace, Names.peersRole(spec.serviceName)))
      .withRules(
        new PolicyRuleBuilder()
          .withApiGroups("")
          .withResources("pods")
          .withVerbs("get", "list", "watch")
          .build()
      )
      .build()

  def peersRoleBinding(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String
  ): RoleBinding =
    new RoleBindingBuilder()
      .withMetadata(identityMeta(resource, spec, namespace, Names.peersRole(spec.serviceName)))
      .withRoleRef(
        new RoleRefBuilder()
          .withApiGroup("rbac.authorization.k8s.io")
          .withKind("Role")
          .withName(Names.peersRole(spec.serviceName))
          .build()
      )
      .withSubjects(
        new SubjectBuilder()
          .withKind("ServiceAccount")
          .withName(Names.serviceAccount(spec.serviceName))
          .withNamespace(namespace)
          .build()
      )
      .build()

  /**
   * Exactly one of these per pass: the address exists when there is a port, and does not when there
   * is not. After the Deployment, since a Service in front of nothing is only noise.
   */
  private def addressAction(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String
  ): Action =
    spec.port match
      case Some(port) => Action.EnsureService(service(resource, spec, namespace, port))
      case None =>
        Action.RemoveService(
          namespace,
          Names.service(spec.serviceName),
          Option(resource.getMetadata).flatMap(m => Option(m.getUid)).getOrElse("")
        )

  /**
   * The route, after the address it points at. Rendered only when the service is exposed, has a
   * port, and the operator knows the base domain; in every other case the route is removed if this
   * resource owns one — so unexposing, or dropping to `http: false`, takes the route away without
   * touching the service.
   */
  private def routeAction(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String,
      baseDomain: Option[String]
  ): Action =
    (spec.exposed, spec.port, baseDomain) match
      case (true, Some(port), Some(base)) =>
        Action.EnsureHttpRoute(httpRoute(resource, spec, namespace, port, base))
      case _ =>
        Action.RemoveHttpRoute(
          namespace,
          Names.httpRoute(spec.serviceName),
          Option(resource.getMetadata).flatMap(m => Option(m.getUid)).getOrElse("")
        )

  /**
   * One HTTPRoute: this service's hostname to this service's Service. The hostname is derived here,
   * never read from the resource, so no writer of the resource can point a route at a name the
   * service does not own; the backend carries no namespace, so the API itself forbids it reaching
   * another project's service (contracts/route-object.md).
   */
  def httpRoute(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String,
      port: Int,
      baseDomain: String
  ): HTTPRoute =
    new HTTPRouteBuilder()
      .withMetadata(identityMeta(resource, spec, namespace, Names.httpRoute(spec.serviceName)))
      .withSpec(
        new HTTPRouteSpecBuilder()
          .withParentRefs(
            new ParentReferenceBuilder()
              .withGroup("gateway.networking.k8s.io")
              .withKind("Gateway")
              .withName(GatewayName)
              .withNamespace(GatewayNamespace)
              .withSectionName(GatewaySection)
              .build()
          )
          .withHostnames(Hostnames.of(spec.serviceName, spec.projectId, baseDomain))
          .withRules(
            new HTTPRouteRuleBuilder()
              .withBackendRefs(
                new HTTPBackendRefBuilder()
                  .withName(Names.service(spec.serviceName))
                  .withPort(port)
                  .build()
              )
              .build()
          )
          .build()
      )
      .build()

  /** Exposed so tests can assert on the object rather than on an action wrapper. */
  def service(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String,
      port: Int
  ): Service =
    val labels      = Labels.merged(spec.projectId, spec.serviceName, spec.labels)
    val annotations = spec.annotations + (Labels.GenerationKey -> spec.generation.toString)

    new ServiceBuilder()
      .withMetadata(
        new ObjectMetaBuilder()
          .withName(Names.service(spec.serviceName))
          .withNamespace(namespace)
          .withLabels(labels.asJava)
          .withAnnotations(annotations.asJava)
          // Owned, unlike the CNPG objects and credential secrets, which deliberately are not.
          // Those hold data and must outlive the resource; this holds none, and an orphaned
          // address routing nowhere is strictly worse than no address.
          .withOwnerReferences(Labels.ownerReference(resource))
          .build()
      )
      .withSpec(
        new ServiceSpecBuilder()
          .withType("ClusterIP")
          // The very same function the Deployment's selector is built from, below. A second
          // computation that merely looks equivalent is how a Service ends up with no endpoints
          // behind a workload reporting Ready — ServiceRenderingSuite compares the two rendered
          // objects rather than trusting this comment.
          .withSelector(selectorLabels(spec).asJava)
          .withPorts(
            new ServicePortBuilder()
              .withName(PortName)
              .withProtocol("TCP")
              // The same value twice. A Service is not a place to translate between an outer
              // and an inner port; nothing here has an outer one.
              .withPort(port)
              .withTargetPort(new IntOrString(port))
              .build()
          )
          .build()
      )
      .build()

  /** What both the Deployment and the Service select on. Immutable for the life of a service. */
  private def selectorLabels(spec: AnkkaServiceSpec): Map[String, String] =
    Labels.identity(spec.projectId, spec.serviceName)

  private val PortName = "http"

  /** What the runtime reads its port from — `modules/http`'s `reference.conf`. */
  private val PortEnvVar = "ANKKA_HTTP_PORT"

  /**
   * The CNPG objects this pass needs to ensure, ahead of the Deployment that depends on them.
   *
   * Since feature 014 every provisioned pass also ensures the project's database authority, the
   * cluster's TLS fields and the service's client certificate: a project or service provisioned
   * before certificates must gain them, and an unchanged server-side apply changes nothing. The
   * credential Secret, the role and the database keep their own rule — written only when needed —
   * except that a role still holding a password is re-applied without one, once.
   */
  private def databaseActions(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String,
      settings: Settings,
      plan: ProvisioningPlan
  ): Vector[Action] =
    def tls: Vector[Action] =
      CnpgRendering.projectAuthority(namespace).map {
        case issuer if issuer.getKind == "Issuer" => Action.EnsureIssuer(issuer)
        case certificate                          => Action.EnsureCertificate(certificate)
      } ++ Vector(
        Action.EnsureCluster(CnpgRendering.projectCluster(spec.projectId, settings)),
        Action.EnsureNetworkPolicy(CnpgRendering.databasePolicy(namespace)),
        Action.EnsureCertificate(ZeroTrust.Database.clientCertificate(resource, spec, namespace))
      )
    plan match
      case ProvisioningPlan.NotNeeded | ProvisioningPlan.Supplied => Vector.empty
      case ProvisioningPlan.Waiting(_, needsCredentials, needsRole, needsDatabase, _) =>
        tls ++ Vector(
          Option.when(needsCredentials)(
            Action.EnsureCredentials(
              CnpgRendering.credentialSecret(spec, namespace, CnpgRendering.projectClusterName)
            )
          ),
          Option.when(needsRole)(
            Action.EnsureDatabaseRole(CnpgRendering.databaseRole(spec, namespace))
          ),
          Option.when(needsDatabase)(
            Action.EnsureDatabase(CnpgRendering.database(spec, namespace))
          ),
          Some(Action.EnsureSchemaConfig(CnpgRendering.schemaConfigMap(namespace)))
        ).flatten
      case ProvisioningPlan.Ready(_, migrateRole) =>
        // A deliberate, narrow exception to "steady state writes nothing" (contracts/schema-init.md):
        // the schema ConfigMap is kept current on every pass so a schema change reaches an
        // existing project namespace automatically. Never a credential or a database; a role only
        // for the one migration to certificates.
        tls ++
          Option.when(migrateRole)(
            Action.EnsureDatabaseRole(CnpgRendering.databaseRole(spec, namespace))
          ) :+
          Action.EnsureSchemaConfig(CnpgRendering.schemaConfigMap(namespace))
      case ProvisioningPlan.Failed(_) => Vector.empty

  /** Exposed so tests can assert on the object rather than on an action wrapper. */
  def deployment(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String,
      databasePlan: ProvisioningPlan = ProvisioningPlan.Supplied,
      sidecarImage: String = Settings.default.sidecarImage,
      namespacePrefix: String = Settings.default.namespacePrefix,
      proxyImage: String = Settings.default.proxyImage,
      baseDomain: Option[String] = None,
      httpsPort: Int = Settings.default.httpsPort
  ): Deployment =
    val identity    = selectorLabels(spec)
    val labels      = Labels.merged(spec.projectId, spec.serviceName, spec.labels)
    val annotations = spec.annotations + (Labels.GenerationKey -> spec.generation.toString)

    // Nothing beyond the descriptor's own env on the escape hatch (FR-016): the caller supplied
    // its own connection details, so there is no schema to establish and no credential to mount.
    // Nor for a service that has no database at all.
    val provisioned = databasePlan match
      case ProvisioningPlan.NotNeeded | ProvisioningPlan.Supplied => false
      case _                                                      => true

    val web = spec.hosting == WebHosting
    val containers = containersFor(
      spec,
      identity,
      withDatabaseEnv = provisioned,
      sidecarImage,
      namespacePrefix,
      proxyImage,
      baseDomain,
      httpsPort
    )
    // A web-hosted pod holds the service certificate alone: no cluster to join, no database.
    val held =
      if web then ZeroTrust.Held(cluster = false, service = true, database = false)
      else ZeroTrust.Held(cluster = true, service = true, database = provisioned)
    val tlsVolumes =
      ZeroTrust.volumes(held, spec, CnpgRendering.projectClusterName) ++ moduleVolumes(spec)
    val moduleInit = moduleInitContainers(spec)

    // The pull secret is *named*, never read. The Secret itself is the control plane's to write in
    // the project's namespace from the credential a member supplied, and the operator holds no
    // permission to read one — so the worst a resource naming the wrong Secret can do is fail to
    // pull, which the pod reports and `services get` surfaces.
    //
    // A service with no registry names nothing. It is an *empty list* rather than an absent field,
    // because that is what `PodSpecBuilder` produces for every list it was never given, and it is
    // what this renderer produced for this field before the field existed — so nothing about an
    // existing service's Deployment changes.
    def withPullSecret(builder: PodSpecBuilder): PodSpecBuilder =
      spec.imagePullSecret.fold(builder)(name =>
        builder.withImagePullSecrets(new LocalObjectReference(name))
      )

    val podSpec =
      if web then
        // The account is named, as for every service, and its token is not mounted: nothing in a
        // web-hosted pod has any business with the API server. No init container, no fsGroup.
        withPullSecret(
          new PodSpecBuilder()
            .withServiceAccountName(Names.serviceAccount(spec.serviceName))
            .withAutomountServiceAccountToken(false)
            .withContainers(containers*)
            .withVolumes(tlsVolumes*)
        ).build()
      else if provisioned then
        withPullSecret(
          new PodSpecBuilder()
            .withServiceAccountName(Names.serviceAccount(spec.serviceName))
            .withInitContainers((moduleInit :+ SchemaInit.container(spec.serviceName))*)
            .withContainers(containers*)
            .withVolumes((SchemaInit.volume() +: tlsVolumes)*)
            // The database key is mounted readable by this group and nobody else, which is what
            // libpq (in schema-init) insists on and what a non-root runtime can still read.
            .withSecurityContext(
              new io.fabric8.kubernetes.api.model.PodSecurityContextBuilder()
                .withFsGroup(ZeroTrust.Database.FsGroup)
                .build()
            )
        ).build()
      else
        withPullSecret(
          new PodSpecBuilder()
            .withServiceAccountName(Names.serviceAccount(spec.serviceName))
            .withInitContainers(moduleInit*)
            .withContainers(containers*)
            .withVolumes(tlsVolumes*)
        ).build()

    // The transport label on every pod: `Transition.needed` reads it, and without it the operator
    // would stop the Deployment on every pass. The formation label marks a pod as a cluster contact
    // point, which a web-hosted pod is not.
    val templateLabels =
      if web then labels + (Labels.TransportKey -> Labels.TransportTls)
      else
        labels + (Labels.FormationKey -> Labels.FormationBootstrap) +
          (Labels.TransportKey        -> Labels.TransportTls)

    val podTemplate = new PodTemplateSpecBuilder()
      .withMetadata(
        new ObjectMetaBuilder()
          .withLabels(templateLabels.asJava)
          // The restart count on the *pod template* is what makes a restart roll the pods: it
          // changes, the template changes, Kubernetes replaces them. NOT the generation, which is
          // on the Deployment's own metadata (where status reads it) — feature 001 put it here,
          // so every apply rolled every pod, and a pure scale replaced the instances that stayed
          // (feature 004, FR-016). Anything genuinely part of the template — image, env, port —
          // still rolls, because the template itself changed.
          .withAnnotations(
            (spec.annotations + (Labels.RestartsKey -> spec.restarts.toString)).asJava
          )
          .build()
      )
      .withSpec(podSpec)
      .build()

    val deploymentSpec = new DeploymentSpecBuilder()
      // Paused means zero replicas and nothing else: the configuration stays, so resuming is
      // one field rather than a re-render.
      .withReplicas(replicas(spec))
      .withProgressDeadlineSeconds(spec.progressDeadlineSeconds)
      // One at a time, literally: a surge pod joins the existing cluster and is ready before an
      // old pod leaves, so a service is never below its count and — measured — a single-instance
      // service deploys with no outage at all. Feature 003 rendered Recreate here, and was right
      // to: a node then joined *itself*, so the surge pod was a second cluster writing the same
      // journal. Feature 004 removed the cause, and with it the outage (research R6).
      .withStrategy(
        new DeploymentStrategyBuilder()
          .withType("RollingUpdate")
          .withRollingUpdate(
            new RollingUpdateDeploymentBuilder()
              .withMaxSurge(new IntOrString(1))
              .withMaxUnavailable(new IntOrString(0))
              .build()
          )
          .build()
      )
      // Immutable after creation. The identity labels only — a value that changed between
      // generations would make the second apply permanently rejected by the API server.
      .withSelector(new LabelSelectorBuilder().withMatchLabels(identity.asJava).build())
      .withTemplate(podTemplate)
      .build()

    new DeploymentBuilder()
      .withMetadata(
        new ObjectMetaBuilder()
          .withName(Names.deployment(spec.serviceName))
          .withNamespace(namespace)
          .withLabels(labels.asJava)
          .withAnnotations(annotations.asJava)
          .withOwnerReferences(Labels.ownerReference(resource))
          .build()
      )
      .withSpec(deploymentSpec)
      .build()

  /**
   * A wasm service's module, copied by its own image into the pod's module volume before the
   * runtime starts. The image's contract is exactly that: run with the volume mounted, write
   * `service.wasm` there, exit 0. No command is set, so the image's own does the copy; a failure is
   * the pod's init failing, which the service reports.
   */
  private def moduleInitContainers(spec: AnkkaServiceSpec): Vector[Container] =
    if spec.hosting != WasmHosting then Vector.empty
    else
      Vector(
        new ContainerBuilder()
          .withName(Names.container(spec.serviceName) + "-module")
          .withImage(spec.image)
          .withImagePullPolicy("IfNotPresent")
          .withVolumeMounts(
            new VolumeMountBuilder().withName(ModuleVolume).withMountPath(ModuleMount).build()
          )
          .withResources(
            new ResourceRequirementsBuilder()
              .withRequests(ModuleInitRequests)
              .withLimits(ModuleInitLimits)
              .build()
          )
          .build()
      )

  private def moduleVolumes(spec: AnkkaServiceSpec): Vector[Volume] =
    if spec.hosting != WasmHosting then Vector.empty
    else
      Vector(
        new VolumeBuilder()
          .withName(ModuleVolume)
          .withEmptyDir(
            new io.fabric8.kubernetes.api.model.EmptyDirVolumeSourceBuilder()
              .withSizeLimit(ModuleVolumeLimit)
              .build()
          )
          .build()
      )

  /**
   * `embedded`: one container, the image is the node. `process` (feature 009): the sidecar image is
   * the node — every port, probe, cluster variable and credential the single container carries
   * today — and the developer's image is a second container beside it, carrying its own variables
   * and how to find the sidecar, with no ports and no probe: its liveness is the sidecar's opinion.
   * `wasm` (feature 016): one container again, the runtime's image, which is the node exactly as
   * the sidecar is and loads the module from the volume the init container filled. The descriptor's
   * variables are all on it, unsplit — the runtime keeps the reserved ones from the module when it
   * reads them (the `config` import), since one container has one environment.
   */
  private def containersFor(
      spec: AnkkaServiceSpec,
      identity: Map[String, String],
      withDatabaseEnv: Boolean,
      sidecarImage: String,
      namespacePrefix: String,
      proxyImage: String,
      baseDomain: Option[String],
      httpsPort: Int
  ): Vector[Container] = spec.hosting match
    case WasmHosting =>
      val node = container(
        spec.copy(image = sidecarImage),
        identity,
        withDatabaseEnv,
        extraEnv = Vector(literal("ANKKA_WASM_MODULE", ModuleFile)),
        namespacePrefix = namespacePrefix
      )
      Vector(
        new ContainerBuilder(node)
          // A module the runtime refuses — another ABI version, a missing export — is a process
          // that logs every reason and exits 1. Its last log lines become the termination message,
          // which the service's reported reason carries, so the refusal names itself.
          .withTerminationMessagePolicy("FallbackToLogsOnError")
          .addToVolumeMounts(
            new VolumeMountBuilder()
              .withName(ModuleVolume)
              .withMountPath(ModuleMount)
              .withReadOnly(true)
              .build()
          )
          .build()
      )
    case EmbeddedHosting =>
      Vector(container(spec, identity, withDatabaseEnv, namespacePrefix = namespacePrefix))
    case WebHosting =>
      webContainers(spec, namespacePrefix, proxyImage, baseDomain, httpsPort)
    case ProcessHosting =>
      // A descriptor's variables are split: a model's key and configuration belong to the sidecar,
      // which runs the agent loop; everything else is the process's. By prefix, as
      // `ServiceSpec.SidecarEnvPrefixes` in controlplane-api says — duplicated here because the
      // operator must not depend on that module, and pinned by RenderingSuite.
      val (forSidecar, forProcess) =
        spec.env.partition(e => SidecarEnvPrefixes.exists(e.name.startsWith))
      val node = container(
        spec.copy(image = sidecarImage, env = forSidecar),
        identity,
        withDatabaseEnv,
        extraEnv = Vector(
          literal("ANKKA_PROCESS_ADDRESS", s"127.0.0.1:$ProcessPort"),
          literal("ANKKA_SIDECAR_PORT", SidecarPort.toString)
        ),
        namespacePrefix = namespacePrefix
      )
      // The sidecar is the node: it holds every identity. The process beside it speaks only to the
      // sidecar, over the pod's loopback, and needs none.
      val app = new ContainerBuilder()
        .withName(Names.container(spec.serviceName) + "-app")
        .withImage(spec.image)
        .withImagePullPolicy("IfNotPresent")
        .withEnv(
          (forProcess.map(environment) ++ Vector(
            literal("ANKKA_PROCESS_PORT", ProcessPort.toString),
            literal("ANKKA_SIDECAR_ADDRESS", s"127.0.0.1:$SidecarPort")
          ))*
        )
        .withResources(
          new ResourceRequirementsBuilder()
            .withRequests(AppQuantities)
            .withLimits(AppQuantities)
            .build()
        )
        .withLifecycle(
          new LifecycleBuilder()
            .withPreStop(
              new LifecycleHandlerBuilder()
                .withSleep(new SleepActionBuilder().withSeconds(PreStopSeconds).build())
                .build()
            )
            .build()
        )
        .build()
      Vector(node, app)
    case other =>
      // `render` refuses an unknown hosting before anything is rendered; this is for a caller that
      // reached the Deployment directly.
      throw IllegalArgumentException(s"unknown hosting \"$other\"")

  /**
   * A web-hosted pod (feature 021): the platform's proxy, named as the platform's container is in
   * every two-container pod, and the developer's process beside it. The proxy is told everything it
   * needs as its environment, holds the service certificate, serves the service's port and the
   * probe, and is sized by the platform; the process is told its port and the calling address, gets
   * the descriptor's whole environment, and is sized by the instance type.
   */
  private def webContainers(
      spec: AnkkaServiceSpec,
      namespacePrefix: String,
      proxyImage: String,
      baseDomain: Option[String],
      httpsPort: Int
  ): Vector[Container] =
    val port = spec.port.getOrElse(
      throw IllegalArgumentException("a web-hosted service's resource names no port")
    )
    val processPort = spec.processPort.getOrElse(DefaultProcessPort)
    // Derived here, never read from the resource, as the route's hostname is; rendered whether or
    // not the service is exposed, so exposing never changes the pod template and never rolls a pod.
    // The port is the gateway's HTTPS port, omitted when it is the scheme's own.
    val publicAuthority = baseDomain.map { base =>
      val host = Hostnames.of(spec.serviceName, spec.projectId, base)
      if httpsPort == 443 then host else s"$host:$httpsPort"
    }
    val proxyEnv = Vector(
      literal(ProxyEnv.Project, spec.projectId),
      literal(ProxyEnv.Service, spec.serviceName),
      literal(ProxyEnv.Port, port.toString),
      literal(ProxyEnv.ProcessPort, processPort.toString),
      // Empty when there are none: the variable is always set, so the proxy reads one shape.
      literal(ProxyEnv.Mounts, spec.mounts.map(m => s"${m.path}=${m.service}").mkString(",")),
      literal(ProxyEnv.Callers, spec.callers.mkString(","))
    ) ++ publicAuthority.map(literal(ProxyEnv.PublicAuthority, _)) :+
      literal("ANKKA_NAMESPACE_PREFIX", namespacePrefix)
    val proxy = new ContainerBuilder()
      .withName(Names.container(spec.serviceName))
      .withImage(proxyImage)
      .withImagePullPolicy("IfNotPresent")
      .withEnv(proxyEnv*)
      .withPorts(
        new ContainerPortBuilder()
          .withName(PortName)
          .withContainerPort(port)
          .withProtocol("TCP")
          .build(),
        new ContainerPortBuilder()
          .withName(ZeroTrust.ProbePortName)
          .withContainerPort(ZeroTrust.ProbePort)
          .withProtocol("TCP")
          .build()
      )
      .withVolumeMounts(
        ZeroTrust.mounts(ZeroTrust.Held(cluster = false, service = true, database = false))*
      )
      .withResources(
        new ResourceRequirementsBuilder()
          .withRequests(ProxyQuantities)
          .withLimits(ProxyQuantities)
          .build()
      )
      // The proxy answers ready while the process accepts a connection; the process has no probe
      // of its own, since no route of its is the platform's to call.
      .withReadinessProbe(
        new ProbeBuilder()
          .withHttpGet(
            new HTTPGetActionBuilder()
              .withPath("/ready")
              .withPort(new IntOrString(ZeroTrust.ProbePortName))
              .build()
          )
          .withPeriodSeconds(5)
          .build()
      )
      .withLifecycle(preStop)
      .build()
    val appQuantities = Map(
      "cpu"    -> new Quantity(s"${spec.cpuMillis}m"),
      "memory" -> new Quantity(s"${spec.memoryMiB}Mi")
    ).asJava
    val app = new ContainerBuilder()
      .withName(Names.container(spec.serviceName) + "-app")
      .withImage(spec.image)
      .withImagePullPolicy("IfNotPresent")
      .withEnv(
        (spec.env.map(environment) ++ Vector(
          literal(ProcessPortEnvVar, processPort.toString),
          literal(ServicesUrlEnvVar, s"http://127.0.0.1:$CallingPort")
        ))*
      )
      .withResources(
        new ResourceRequirementsBuilder()
          .withRequests(appQuantities)
          .withLimits(appQuantities)
          .build()
      )
      .withLifecycle(preStop)
      .build()
    Vector(proxy, app)

  /** Keep serving through a replacement; see the node container's own hook for why. */
  private def preStop: Lifecycle =
    new LifecycleBuilder()
      .withPreStop(
        new LifecycleHandlerBuilder()
          .withSleep(new SleepActionBuilder().withSeconds(PreStopSeconds).build())
          .build()
      )
      .build()

  private def container(
      spec: AnkkaServiceSpec,
      identity: Map[String, String],
      withDatabaseEnv: Boolean,
      extraEnv: Vector[EnvVar] = Vector.empty,
      namespacePrefix: String
  ): Container =
    // Requests equal limits. The descriptor models one size, and inventing a ratio between
    // request and limit would be a scheduling policy nobody asked for.
    val quantities = Map(
      "cpu"    -> new Quantity(s"${spec.cpuMillis}m"),
      "memory" -> new Quantity(s"${spec.memoryMiB}Mi")
    ).asJava

    // The whole credential secret with one envFrom, so the runtime sees ANKKA_DB_HOST/PORT/
    // NAME/USER/PASSWORD exactly as it would if a descriptor had supplied them by hand — the
    // runtime cannot tell a provisioned database from a supplied one, which is what makes the
    // escape hatch free.
    val envFrom =
      if withDatabaseEnv then
        Vector(
          new EnvFromSourceBuilder()
            .withSecretRef(
              new SecretEnvSourceBuilder()
                .withName(CnpgRendering.credentialSecretName(spec.serviceName))
                .build()
            )
            .build()
        )
      else Vector.empty

    // One Option, three consequences. The container port, the variable the runtime reads its port
    // from, and the probe all come from `spec.port`, so they have nothing to disagree with — and
    // the control plane refuses a descriptor that sets ANKKA_HTTP_PORT by hand, so there is no
    // second source for the middle one either.
    val portEnv = spec.port.map { port =>
      new EnvVarBuilder().withName(PortEnvVar).withValue(port.toString).build()
    }
    val containerPorts = spec.port.map { port =>
      new ContainerPortBuilder()
        .withName(PortName)
        .withContainerPort(port)
        .withProtocol("TCP")
        .build()
    }

    // How a node finds its peers, told to it by the platform — never by the descriptor, which
    // the control plane refuses if it tries. The selector is the very same identity the
    // Deployment selects on, so a node can never mistake another service's pods for its own.
    val clusterEnv = Vector(
      literal("ANKKA_CLUSTER_MODE", "kubernetes"),
      new EnvVarBuilder()
        .withName("POD_IP")
        .withValueFrom(
          new EnvVarSourceBuilder()
            .withFieldRef(new ObjectFieldSelectorBuilder().withFieldPath("status.podIP").build())
            .build()
        )
        .build(),
      literal("ANKKA_CLUSTER_SERVICE", spec.serviceName),
      // Identity plus the formation label: a pod from a template that predates cluster formation
      // must not be a contact point, or bootstrap waits on it forever (see Labels.FormationKey).
      literal("ANKKA_CLUSTER_POD_SELECTOR", contactPointSelector(identity)),
      literal("ANKKA_CLUSTER_CONTACT_POINTS", requiredContactPoints(spec).toString),
      // How a project id becomes a namespace, so a service can address another by name (feature
      // 014). The platform's, like the variables above; a descriptor that sets it is refused.
      literal("ANKKA_NAMESPACE_PREFIX", namespacePrefix)
    )
    // The management port's NAME is load-bearing: Kubernetes API discovery finds a pod's contact
    // point by looking for a container port called exactly this. Get it wrong and discovery finds
    // every pod and can reach none of them.
    val clusterPorts = Vector(
      new ContainerPortBuilder()
        .withName("management")
        .withContainerPort(ManagementPort)
        .withProtocol("TCP")
        .build(),
      new ContainerPortBuilder()
        .withName("remoting")
        .withContainerPort(RemotingPort)
        .withProtocol("TCP")
        .build(),
      new ContainerPortBuilder()
        .withName(ZeroTrust.ProbePortName)
        .withContainerPort(ZeroTrust.ProbePort)
        .withProtocol("TCP")
        .build()
    )

    val base = new ContainerBuilder()
      .withName(Names.container(spec.serviceName))
      .withImage(spec.image)
      // Not optional, and not about ports. Kubernetes defaults this to Always for a :latest tag,
      // so an image loaded straight onto the node (kind load, ctr import) is ignored and the pod
      // fails ErrImagePull with the image sitting right there — verified on a real node: same
      // image, same cluster, this one field apart. The platform's own manifests already say
      // IfNotPresent for themselves, for this reason. It never showed for workloads because every
      // test deployed registry.k8s.io/pause:3.9: pullable, and not :latest. Becomes a descriptor
      // field the day there is a registry and a re-pushed mutable tag has to be picked up.
      .withImagePullPolicy("IfNotPresent")
      .withEnv(
        (spec.env.map(environment) ++ portEnv ++ clusterEnv ++ extraEnv ++
          (if withDatabaseEnv then ZeroTrust.Database.Environment.map(literal) else Vector.empty))*
      )
      .withEnvFrom(envFrom*)
      .withPorts((containerPorts.toVector ++ clusterPorts)*)
      .withVolumeMounts(
        ZeroTrust.mounts(
          ZeroTrust.Held(cluster = true, service = true, database = withDatabaseEnv)
        )*
      )
      .withResources(
        new ResourceRequirementsBuilder().withRequests(quantities).withLimits(quantities).build()
      )

    // Readiness is cluster membership. Cluster Bootstrap registers that check on the management
    // endpoint itself; the http module adds "the HTTP server is bound" for a service that declares
    // a port. So one probe covers both, and a service that serves no HTTP — which feature 003's
    // tcpSocket could not probe at all — is ready on membership alone. It is also what keeps an
    // image whose runtime predates this from ever being Ready: no management endpoint, connection
    // refused, Failed by the Deployment's own deadline. Never liveness: a restart for a slow GC
    // now also costs a shard rebalance.
    base
      .withReadinessProbe(
        new ProbeBuilder()
          .withHttpGet(
            new HTTPGetActionBuilder()
              .withPath("/ready")
              // `probe`, not `management`: management requires the service's own certificate, and
              // the kubelet has none. The name is load-bearing exactly as `management` was.
              .withPort(new IntOrString(ZeroTrust.ProbePortName))
              .build()
          )
          .withPeriodSeconds(5)
          .build()
      )
      // Keep serving for a moment after SIGTERM is decided. A pod is removed from its Service's
      // endpoints when its deletion starts, but kube-proxy on each node learns that up to a
      // second later — and the runtime unbinds its HTTP port the instant it gets SIGTERM, so in
      // that second a request routed to the old pod is refused. The sleep runs *before* SIGTERM
      // and the pod serves through it. Kubernetes' own sleep action, not `sleep` in the image:
      // a workload image owes the platform no shell. Measured by the control plane's own
      // replacement-under-load case, which lost 2 of 33 commands without it.
      .withLifecycle(
        new LifecycleBuilder()
          .withPreStop(
            new LifecycleHandlerBuilder()
              .withSleep(new SleepActionBuilder().withSeconds(PreStopSeconds).build())
              .build()
          )
          .build()
      )
      .build()

  /**
   * The label selector Cluster Bootstrap discovers contact points with, `k=v,k=v`. The transport
   * label too, so a TLS node never probes a plain one (and the reverse cannot arise: an old pod's
   * selector predates the label).
   */
  def contactPointSelector(identity: Map[String, String]): String =
    (identity + (Labels.FormationKey -> Labels.FormationBootstrap) +
      (Labels.TransportKey           -> Labels.TransportTls)).toSeq.sorted
      .map((k, v) => s"$k=$v")
      .mkString(",")

  private def literal(name: String, value: String): EnvVar =
    new EnvVarBuilder().withName(name).withValue(value).build()

  /**
   * A secret becomes a reference, never a value.
   *
   * The operator never reads a secret a *descriptor* references — the kubelet resolves this
   * reference when it starts the pod, so the value never passes through here. (Since feature 002
   * the operator does hold `get`/`create` on secrets, for the database credentials it generates
   * itself; that is a different secret, and its value is never put into a status either.)
   */
  private def environment(entry: EnvEntry): EnvVar =
    val builder = new EnvVarBuilder().withName(entry.name)
    (entry.value, entry.secretName, entry.secretKey) match
      case (Some(literal), _, _) => builder.withValue(literal).build()
      case (None, Some(secret), Some(key)) =>
        val selector = new SecretKeySelectorBuilder().withName(secret).withKey(key).build()
        builder.withValueFrom(new EnvVarSourceBuilder().withSecretKeyRef(selector).build()).build()
      case _ =>
        // Validation upstream makes this unreachable; rendering an empty value rather than
        // throwing keeps the rest of the service deployable and lets the status say why.
        builder.withValue("").build()
