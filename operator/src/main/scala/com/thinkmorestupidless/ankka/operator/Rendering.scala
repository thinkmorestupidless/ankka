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
  ObjectMetaBuilder,
  PodSpecBuilder,
  PodTemplateSpecBuilder,
  Quantity,
  ResourceRequirementsBuilder
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

  /** Where the two containers of a process-hosted pod find each other, on the pod's loopback. */
  val ProcessPort: Int = 9010
  val SidecarPort: Int = 9011

  /** Mirrors `ServiceSpec.SidecarEnvPrefixes` in controlplane-api; see `containersFor`. */
  val SidecarEnvPrefixes: Vector[String] = Vector("ANTHROPIC_", "ANKKA_MODEL_")

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
   * @param newPassword
   *   by-name, so `Passwords.generate()` is only ever evaluated when `databasePlan` actually needs
   *   fresh credentials. `render` still performs no I/O of its own; the randomness lives in the
   *   caller's argument expression, not in this function's body.
   */
  def render(
      resource: AnkkaService,
      settings: Settings,
      databasePlan: ProvisioningPlan,
      newPassword: => String
  ): Either[Vector[String], Vector[Action]] =
    val spec      = Option(resource.getSpec).getOrElse(AnkkaServiceSpec())
    val namespace = Names.namespace(settings.namespacePrefix, spec.projectId)

    val problems =
      Names.namespaceProblems(settings.namespacePrefix, spec.projectId) ++
        Names.serviceNameProblems(spec.serviceName) ++
        (if spec.image.isEmpty then Vector("image must not be empty") else Vector.empty) ++
        (if spec.hosting == ProcessHosting && settings.sidecarImage.isEmpty then
           Vector("operator has no sidecar image")
         else Vector.empty)

    if problems.nonEmpty then Left(problems)
    else
      Right(
        (Action.EnsureNamespace(namespace) +:
          databaseActions(spec, namespace, settings, databasePlan, newPassword)) ++
          identityActions(resource, spec, namespace) :+
          Action.ApplyDeployment(
            deployment(resource, spec, namespace, databasePlan, settings.sidecarImage)
          ) :+
          addressAction(resource, spec, namespace) :+
          routeAction(resource, spec, namespace, settings.baseDomain)
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

  /** The CNPG objects this pass needs to ensure, ahead of the Deployment that depends on them. */
  private def databaseActions(
      spec: AnkkaServiceSpec,
      namespace: String,
      settings: Settings,
      plan: ProvisioningPlan,
      newPassword: => String
  ): Vector[Action] = plan match
    case ProvisioningPlan.Supplied => Vector.empty
    case ProvisioningPlan.Waiting(needsCluster, needsCredentials, needsRole, needsDatabase, _) =>
      Vector(
        Option.when(needsCluster)(
          Action.EnsureCluster(CnpgRendering.projectCluster(spec.projectId, settings))
        ),
        Option.when(needsCredentials)(
          Action.EnsureCredentials(
            CnpgRendering.credentialSecret(
              spec,
              namespace,
              CnpgRendering.projectClusterName,
              newPassword
            )
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
    case ProvisioningPlan.Ready(_) =>
      // A deliberate, narrow exception to "steady state writes nothing" (contracts/schema-init.md):
      // the schema ConfigMap is kept current on every pass so a schema change reaches an
      // existing project namespace automatically, rather than sitting unapplied until some other
      // event happens to trigger a reconcile. This does cost one small, idempotent write per
      // project's active reconciles — never a credential, a role or a database, which is what
      // the idempotence tests (SC-010) actually assert zero writes on.
      Vector(Action.EnsureSchemaConfig(CnpgRendering.schemaConfigMap(namespace)))
    case ProvisioningPlan.Failed(_) => Vector.empty

  /** Exposed so tests can assert on the object rather than on an action wrapper. */
  def deployment(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String,
      databasePlan: ProvisioningPlan = ProvisioningPlan.Supplied,
      sidecarImage: String = Settings.default.sidecarImage
  ): Deployment =
    val identity    = selectorLabels(spec)
    val labels      = Labels.merged(spec.projectId, spec.serviceName, spec.labels)
    val annotations = spec.annotations + (Labels.GenerationKey -> spec.generation.toString)

    // Nothing beyond the descriptor's own env on the escape hatch (FR-016): the caller supplied
    // its own connection details, so there is no schema to establish and no credential to mount.
    val provisioned = databasePlan != ProvisioningPlan.Supplied

    val containers = containersFor(spec, identity, withDatabaseEnv = provisioned, sidecarImage)

    val podSpec =
      if provisioned then
        new PodSpecBuilder()
          .withServiceAccountName(Names.serviceAccount(spec.serviceName))
          .withInitContainers(SchemaInit.container(spec.serviceName))
          .withContainers(containers*)
          .withVolumes(SchemaInit.volume())
          .build()
      else
        new PodSpecBuilder()
          .withServiceAccountName(Names.serviceAccount(spec.serviceName))
          .withContainers(containers*)
          .build()

    val podTemplate = new PodTemplateSpecBuilder()
      .withMetadata(
        new ObjectMetaBuilder()
          .withLabels((labels + (Labels.FormationKey -> Labels.FormationBootstrap)).asJava)
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
   * `embedded`: one container, the image is the node. `process` (feature 009): the sidecar image is
   * the node — every port, probe, cluster variable and credential the single container carries
   * today — and the developer's image is a second container beside it, carrying its own variables
   * and how to find the sidecar, with no ports and no probe: its liveness is the sidecar's opinion.
   */
  private def containersFor(
      spec: AnkkaServiceSpec,
      identity: Map[String, String],
      withDatabaseEnv: Boolean,
      sidecarImage: String
  ): Vector[Container] =
    if spec.hosting != ProcessHosting then Vector(container(spec, identity, withDatabaseEnv))
    else
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
        )
      )
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

  private def container(
      spec: AnkkaServiceSpec,
      identity: Map[String, String],
      withDatabaseEnv: Boolean,
      extraEnv: Vector[EnvVar] = Vector.empty
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
      literal("ANKKA_CLUSTER_CONTACT_POINTS", requiredContactPoints(spec).toString)
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
      .withEnv((spec.env.map(environment) ++ portEnv ++ clusterEnv ++ extraEnv)*)
      .withEnvFrom(envFrom*)
      .withPorts((containerPorts.toVector ++ clusterPorts)*)
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
              .withPort(new IntOrString("management"))
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

  /** The label selector Cluster Bootstrap discovers contact points with, `k=v,k=v`. */
  def contactPointSelector(identity: Map[String, String]): String =
    (identity + (Labels.FormationKey -> Labels.FormationBootstrap)).toSeq.sorted
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
