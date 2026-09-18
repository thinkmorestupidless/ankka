package nakka.operator

import io.fabric8.kubernetes.api.model.apps.{
  Deployment,
  DeploymentBuilder,
  DeploymentSpecBuilder,
  DeploymentStrategyBuilder
}
import io.fabric8.kubernetes.api.model.{
  Container,
  ContainerBuilder,
  ContainerPortBuilder,
  IntOrString,
  ProbeBuilder,
  Service,
  ServiceBuilder,
  ServicePortBuilder,
  ServiceSpecBuilder,
  TCPSocketActionBuilder,
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
import nakka.crd.{EnvEntry, NakkaService, NakkaServiceSpec}

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
   * One replica, always.
   *
   * A correctness constraint, not a simplification. `pekko.cluster.seed-nodes` is empty and
   * `nakka.join-self-if-no-seed-nodes` is on, so each pod joins *itself* — two replicas would be
   * two independent single-node clusters sharing one journal, each hosting the same entity ids. Two
   * writers to one `persistence_id` is the failure event sourcing exists to prevent, and an
   * autoscaler would reach it automatically. Hence also: no HorizontalPodAutoscaler is rendered.
   */
  val Replicas: Int = 1

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
      resource: NakkaService,
      settings: Settings,
      databasePlan: ProvisioningPlan,
      newPassword: => String
  ): Either[Vector[String], Vector[Action]] =
    val spec      = Option(resource.getSpec).getOrElse(NakkaServiceSpec())
    val namespace = Names.namespace(settings.namespacePrefix, spec.projectId)

    val problems =
      Names.namespaceProblems(settings.namespacePrefix, spec.projectId) ++
        Names.serviceNameProblems(spec.serviceName) ++
        (if spec.image.isEmpty then Vector("image must not be empty") else Vector.empty)

    if problems.nonEmpty then Left(problems)
    else
      Right(
        Action.EnsureNamespace(namespace) +:
          databaseActions(spec, namespace, settings, databasePlan, newPassword) :+
          Action.ApplyDeployment(deployment(resource, spec, namespace, databasePlan)) :+
          addressAction(resource, spec, namespace)
      )

  /**
   * Exactly one of these per pass: the address exists when there is a port, and does not when there
   * is not. After the Deployment, since a Service in front of nothing is only noise.
   */
  private def addressAction(
      resource: NakkaService,
      spec: NakkaServiceSpec,
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

  /** Exposed so tests can assert on the object rather than on an action wrapper. */
  def service(
      resource: NakkaService,
      spec: NakkaServiceSpec,
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
  private def selectorLabels(spec: NakkaServiceSpec): Map[String, String] =
    Labels.identity(spec.projectId, spec.serviceName)

  private val PortName = "http"

  /** What the runtime reads its port from — `modules/http`'s `reference.conf`. */
  private val PortEnvVar = "NAKKA_HTTP_PORT"

  /** The CNPG objects this pass needs to ensure, ahead of the Deployment that depends on them. */
  private def databaseActions(
      spec: NakkaServiceSpec,
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
      resource: NakkaService,
      spec: NakkaServiceSpec,
      namespace: String,
      databasePlan: ProvisioningPlan = ProvisioningPlan.Supplied
  ): Deployment =
    val identity    = selectorLabels(spec)
    val labels      = Labels.merged(spec.projectId, spec.serviceName, spec.labels)
    val annotations = spec.annotations + (Labels.GenerationKey -> spec.generation.toString)

    // Nothing beyond the descriptor's own env on the escape hatch (FR-016): the caller supplied
    // its own connection details, so there is no schema to establish and no credential to mount.
    val provisioned = databasePlan != ProvisioningPlan.Supplied

    val podSpec =
      if provisioned then
        new PodSpecBuilder()
          .withInitContainers(SchemaInit.container(spec.serviceName))
          .withContainers(container(spec, withDatabaseEnv = true))
          .withVolumes(SchemaInit.volume())
          .build()
      else new PodSpecBuilder().withContainers(container(spec, withDatabaseEnv = false)).build()

    val podTemplate = new PodTemplateSpecBuilder()
      .withMetadata(
        new ObjectMetaBuilder()
          .withLabels(labels.asJava)
          // The generation on the *pod template* is what makes a rolling replacement happen.
          // It is why restart needs no separate mechanism: a restart bumps the generation,
          // the annotation changes, and the pods roll.
          .withAnnotations(annotations.asJava)
          .build()
      )
      .withSpec(podSpec)
      .build()

    val deploymentSpec = new DeploymentSpecBuilder()
      // Paused means zero replicas and nothing else: the configuration stays, so resuming is
      // one field rather than a re-render.
      .withReplicas(if spec.paused then 0 else Replicas)
      .withProgressDeadlineSeconds(spec.progressDeadlineSeconds)
      // Never two pods at once, not even for the length of a rollout. The default, RollingUpdate
      // with maxSurge 25%, rounds up to a whole extra pod at one replica — so old and new run
      // side by side on every deploy and restart, each a single-node cluster of its own, both
      // writing one journal. That is the failure `Replicas = 1` exists to prevent, reached without
      // ever changing the replica count. The cost is honest and unavoidable at one replica: a
      // deploy is a brief outage. The platform's own Deployments already say Recreate.
      .withStrategy(new DeploymentStrategyBuilder().withType("Recreate").build())
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

  private def container(spec: NakkaServiceSpec, withDatabaseEnv: Boolean): Container =
    // Requests equal limits. The descriptor models one size, and inventing a ratio between
    // request and limit would be a scheduling policy nobody asked for.
    val quantities = Map(
      "cpu"    -> new Quantity(s"${spec.cpuMillis}m"),
      "memory" -> new Quantity(s"${spec.memoryMiB}Mi")
    ).asJava

    // The whole credential secret with one envFrom, so the runtime sees NAKKA_DB_HOST/PORT/
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
    // the control plane refuses a descriptor that sets NAKKA_HTTP_PORT by hand, so there is no
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
      .withEnv((spec.env.map(environment) ++ portEnv)*)
      .withEnvFrom(envFrom*)
      .withPorts(containerPorts.toList*)
      .withResources(
        new ResourceRequirementsBuilder().withRequests(quantities).withLimits(quantities).build()
      )

    // Readiness, never liveness. This is what makes `Ready` mean "the port is open" rather than
    // "the JVM launched" — and it does so without LifecycleRules changing at all, because
    // readyReplicas already counts only pods that pass their probe. A liveness probe would restart
    // a healthy pod during a long GC, and on a single replica whose entities rehydrate from the
    // journal that turns a hiccup into an outage. tcpSocket rather than httpGet because the
    // operator knows neither the workload's routes nor its ACL, and a probe is no place to hold a
    // bearer token — the control plane's own Deployment settled this the same way.
    spec.port
      .fold(base) { port =>
        base.withReadinessProbe(
          new ProbeBuilder()
            .withTcpSocket(new TCPSocketActionBuilder().withPort(new IntOrString(port)).build())
            .withInitialDelaySeconds(10)
            .withPeriodSeconds(5)
            .build()
        )
      }
      .build()

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
