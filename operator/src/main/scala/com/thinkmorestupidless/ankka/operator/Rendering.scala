package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.core.PlatformVariables
import io.fabric8.kubernetes.api.model.gatewayapi.v1.{
  HTTPBackendRefBuilder,
  HTTPHeaderMatchBuilder,
  HTTPPathMatchBuilder,
  HTTPRouteMatchBuilder,
  HTTPRouteTimeoutsBuilder,
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
  GenericKubernetesResource,
  GenericKubernetesResourceBuilder,
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
  VolumeMount,
  VolumeBuilder,
  VolumeMountBuilder
}
import com.thinkmorestupidless.ankka.crd.{
  AnkkaService,
  AnkkaServiceSpec,
  Buckets,
  EnvEntry,
  Hostnames
}

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
  val ProcessPortEnvVar: String = PlatformVariables.WebPort
  val ServicesUrlEnvVar: String = PlatformVariables.ServicesUrl
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
      databasePlan: ProvisioningPlan,
      knownToBroker: Boolean = false,
      objectStoragePlan: ObjectStoragePlan = ObjectStoragePlan.NotAsked,
      // The project's declared brokers (feature 037), read by the caller from `AnkkaProject` as
      // `databasePlan` is decided by it: `render` stays a pure function of its arguments.
      declaredBrokers: Vector[com.thinkmorestupidless.ankka.crd.ProjectBrokerEntry] = Vector.empty,
      // The requests to the installation's cloud provider this service needs (feature 044), already
      // rendered by the caller, which observes their answers to decide `objectStoragePlan`.
      cloudRequests: Vector[com.thinkmorestupidless.ankka.crd.CloudResource] = Vector.empty,
      // What the cloud provider says binds the service's ServiceAccount to its cloud identity
      // (feature 039): put on the ServiceAccount as they are. None for every other service.
      serviceAccountAnnotations: Map[String, String] = Map.empty
  ): Either[Vector[String], Vector[Action]] =
    val spec      = Option(resource.getSpec).getOrElse(AnkkaServiceSpec())
    val namespace = Names.namespace(settings.namespacePrefix, spec.projectId)
    // The installation's broker, when this service is known to it (`BrokerProvisioning.known`): its
    // user is rendered, its certificate names it, and its runtime is told where the broker is.
    // `false` renders exactly what was rendered before the broker existed.
    val broker = settings.broker.filter(_ => knownToBroker)
    val deployed = broker.fold(spec) { b =>
      spec.copy(env = spec.env ++ StrimziRendering.environment(spec, b))
    }
    val commonName = broker.map(_ => BrokerNames.user(spec.projectId, spec.serviceName))

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
          identityActions(resource, spec, namespace, serviceAccountAnnotations) ++
          secretKeyAction(spec, namespace) ++
          telemetryAction(resource, spec, namespace, settings) ++
          objectStorageActions(resource, spec, namespace, settings, objectStoragePlan) ++
          cloudRequests.map(Action.EnsureCloudResource(_)) ++
          zeroTrustActions(resource, spec, namespace, commonName) ++
          brokerActions(spec, broker) ++
          // Not while a cloud bucket waits on its provider: its endpoint and region are the
          // provider's to say, and an instance started without them would be started wrong.
          Option
            .when(
              ObjectStorage
                .withheld(objectStoragePlan, spec, settings, ObjectStorage.reported(resource))
                .isEmpty
            )(
              Action.ApplyDeployment(
                BrokerMounts.attach(
                  deployment(
                    resource,
                    deployed,
                    namespace,
                    databasePlan,
                    settings.sidecarImage,
                    settings.namespacePrefix,
                    settings.proxyImage,
                    settings.baseDomain,
                    settings.httpsPort,
                    settings.otlpEndpoint,
                    settings.otlpHeaders.isDefined,
                    storageEnv(
                      spec,
                      settings,
                      ObjectStorage.reported(resource),
                      objectStoragePlan,
                      ObjectStorage.credentialGeneration(
                        objectStoragePlan,
                        spec,
                        ObjectStorage.inPlace(resource)
                      )
                    )
                  ),
                  deployed,
                  declaredBrokers
                )
              )
            )
            .toVector :+
          addressAction(resource, spec, namespace) :+
          grpcPeersAction(resource, spec, namespace) :+
          routeAction(resource, spec, namespace, settings.baseDomain) :+
          backendTlsAction(resource, spec, namespace, settings.baseDomain)
      )

  /**
   * The service's secret key, made once, before the Deployment that names it, unless the descriptor
   * gives one of its own — as a descriptor that names a database gets none provisioned.
   */
  private def secretKeyAction(spec: AnkkaServiceSpec, namespace: String): Vector[Action] =
    Option
      .when(rendersSecretKey(spec))(
        Action.EnsureSecretKey(
          namespace,
          Names.secretKeySecret(spec.serviceName),
          Labels.identity(spec.projectId, spec.serviceName)
        )
      )
      .toVector

  /**
   * The service's telemetry Secret, before the Deployment that names it, when the installation
   * names a collector that wants a credential. A web-hosted service exports nothing, so has none.
   */
  private def telemetryAction(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String,
      settings: Settings
  ): Vector[Action] =
    Option
      .when(
        settings.otlpEndpoint.isDefined && settings.otlpHeaders.isDefined && spec.hosting != WebHosting
      )(
        Action.EnsureTelemetrySecret(
          namespace,
          Names.telemetrySecret(spec.serviceName),
          Labels.identity(spec.projectId, spec.serviceName),
          Labels.ownerReference(resource)
        )
      )
      .toVector

  /**
   * Where the platform's program of a workload sends its telemetry (feature 026): the collector's
   * address as a literal, and what to send with it by reference to the service's telemetry Secret.
   * On the container the runtime runs in — the one container of an embedded or a module-hosted
   * service, the sidecar of a process-hosted one — and never on a process or a web-hosted service's
   * containers. Nothing at all when the installation names no collector.
   */
  private def telemetryEnv(
      spec: AnkkaServiceSpec,
      otlpEndpoint: Option[String],
      otlpHeaders: Boolean
  ): Vector[EnvVar] =
    otlpEndpoint.toVector.flatMap { endpoint =>
      val headers = Option.when(otlpHeaders) {
        val selector = new SecretKeySelectorBuilder()
          .withName(Names.telemetrySecret(spec.serviceName))
          .withKey(Names.TelemetryHeadersEntry)
          .build()
        new EnvVarBuilder()
          .withName(PlatformVariables.OtlpHeaders)
          .withValueFrom(new EnvVarSourceBuilder().withSecretKeyRef(selector).build())
          .build()
      }
      literal(PlatformVariables.OtlpEndpoint, endpoint) +: headers.toVector
    }

  /**
   * A service's bucket and its storage credential (feature 034), before the Deployment that names
   * the credential's Secret. Rendered only while the store is there to answer: with no store, or
   * one that cannot be reached, there is nothing to ask, and the plan's status says why.
   */
  private def objectStorageActions(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String,
      settings: Settings,
      plan: ObjectStoragePlan
  ): Vector[Action] =
    val bucket = Buckets.name(spec.projectId, spec.serviceName)
    val secret = Buckets.secret(spec.serviceName)
    val provision = plan match
      case _ if ObjectStorage.takesCloudPath(spec, settings, ObjectStorage.reported(resource)) =>
        Vector.empty
      case ObjectStoragePlan.Waiting(None) | ObjectStoragePlan.Ready(_, _) =>
        val inPlace = ObjectStorage.inPlace(resource)
        val asked   = spec.storageCredentialGeneration
        // Feature 039: a generation asked above the one in place is issued, and its old keys end
        // after the grace; the sweep runs only once a credential has been issued again, so a
        // service that never asked makes the calls it made before.
        val credential =
          if asked > inPlace then
            Vector(Action.ReissueStorageCredential(namespace, secret, bucket, asked))
          else
            Vector(
              Action.EnsureStorageCredential(
                namespace,
                secret,
                Labels.identity(spec.projectId, spec.serviceName),
                bucket,
                inPlace
              )
            )
        val sweep = Option.when(math.max(asked, inPlace) > 0)(Action.DeleteExpiredKeys(bucket))
        // The bucket's CORS rule is the platform's (feature 039): the descriptor's origins while it
        // is reachable from the internet, else none. Rendered only for a descriptor that says
        // either, so a bucket nobody exposed is asked nothing more than before.
        val cors = Option.when(spec.exposeObjectStorage || spec.objectStorageOrigins.nonEmpty)(
          Action.SetBucketCors(
            bucket,
            if spec.exposeObjectStorage then spec.objectStorageOrigins else Nil
          )
        )
        (Action.EnsureBucket(bucket) +: credential) ++ sweep ++ cors
      case _ => Vector.empty
    provision ++ bucketExposure(resource, spec, namespace, settings, plan)

  /**
   * A bucket reachable from the internet (feature 034): one route in the service's own namespace,
   * owned by its resource so deleting the service removes it, at the store's one hostname and the
   * bucket's path, naming the store's Service through a grant in the store's namespace. Every other
   * service, whether it ever asked or not, has the route's removal rendered — owner-checked, and a
   * read that finds nothing for one that never had one — so dropping the request, or the bucket,
   * leaves no route behind, and a URL signed before stops working.
   */
  private def bucketExposure(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String,
      settings: Settings,
      plan: ObjectStoragePlan
  ): Vector[Action] =
    val asked = plan match
      case ObjectStoragePlan.Waiting(_) | ObjectStoragePlan.Ready(_, _) =>
        spec.provisionObjectStorage && spec.exposeObjectStorage
      case _ => false
    (asked, settings.baseDomain, settings.objectStore) match
      case (true, Some(base), Some(store)) =>
        Vector(
          Action.EnsureReferenceGrant(referenceGrant(namespace, store)),
          Action.EnsureHttpRoute(bucketRoute(resource, spec, namespace, base, store))
        )
      case _ =>
        Vector(
          Action.RemoveHttpRoute(
            namespace,
            Names.bucketRoute(spec.serviceName),
            Option(resource.getMetadata).flatMap(m => Option(m.getUid)).getOrElse("")
          )
        )

  /**
   * The mover's Job for one phase of a move (feature 039, contracts/operator.md "The Job").
   *
   * It runs as the service, under the service's identity labels, so the network policies that let
   * the service reach Garage and Google Cloud Storage let it, and it holds no Kubernetes
   * permission. Its only credentials are the service's two, by reference from their Secrets: the
   * operator names them and never reads either. Never retried, so a failure is the phase's, read
   * from the mover's termination message; removed a day after it finishes.
   *
   * @param move
   *   the move's generation, so a move asked for again has Jobs of its own
   * @param deadlineSeconds
   *   for the verify: what remains of the write pause bound, after which Kubernetes stops it
   */
  def moveJob(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String,
      settings: Settings,
      move: Int,
      phase: MovePhase,
      target: CloudBucket,
      deadlineSeconds: Option[Long]
  ): io.fabric8.kubernetes.api.model.batch.v1.Job =
    val source = settings.objectStore
    def literal(name: String, value: String) =
      new io.fabric8.kubernetes.api.model.EnvVarBuilder().withName(name).withValue(value).build()
    def fromSecret(name: String, secret: String, key: String) =
      new io.fabric8.kubernetes.api.model.EnvVarBuilder()
        .withName(name)
        .withValueFrom(
          new io.fabric8.kubernetes.api.model.EnvVarSourceBuilder()
            .withSecretKeyRef(
              new io.fabric8.kubernetes.api.model.SecretKeySelectorBuilder()
                .withName(secret)
                .withKey(key)
                .build()
            )
            .build()
        )
        .build()
    val env = Vector(
      literal("MOVER_SOURCE_ENDPOINT", source.map(_.endpoint).getOrElse("")),
      literal("MOVER_SOURCE_REGION", source.map(_.region).getOrElse("")),
      literal("MOVER_SOURCE_BUCKET", Buckets.name(spec.projectId, spec.serviceName)),
      fromSecret(
        "MOVER_SOURCE_ACCESS_KEY",
        Buckets.secret(spec.serviceName),
        StorageCredential.AccessKeyEntry
      ),
      fromSecret(
        "MOVER_SOURCE_SECRET_KEY",
        Buckets.secret(spec.serviceName),
        StorageCredential.SecretKeyEntry
      ),
      literal("MOVER_TARGET_ENDPOINT", target.endpoint),
      literal("MOVER_TARGET_REGION", target.region),
      literal("MOVER_TARGET_BUCKET", target.bucket),
      fromSecret(
        "MOVER_TARGET_ACCESS_KEY",
        Buckets.cloudSecret(spec.serviceName),
        StorageCredential.AccessKeyEntry
      ),
      fromSecret(
        "MOVER_TARGET_SECRET_KEY",
        Buckets.cloudSecret(spec.serviceName),
        StorageCredential.SecretKeyEntry
      )
    )
    val labels   =
      Labels.identity(spec.projectId, spec.serviceName) + (Labels.RoleKey -> "storage-mover")
    val quantity = (q: String) => new io.fabric8.kubernetes.api.model.Quantity(q)
    val container = new ContainerBuilder()
      .withName("mover")
      .withImage(settings.storageMoverImage)
      .withImagePullPolicy("IfNotPresent")
      .withTerminationMessagePolicy("FallbackToLogsOnError")
      .withArgs(phase.mode)
      .withEnv(env*)
      .withResources(
        new io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder()
          .withRequests(Map("cpu" -> quantity("250m"), "memory" -> quantity("256Mi")).asJava)
          .withLimits(Map("memory" -> quantity("512Mi")).asJava)
          .build()
      )
      .build()
    val template = new PodTemplateSpecBuilder()
      .withMetadata(new ObjectMetaBuilder().withLabels(labels.asJava).build())
      .withSpec(
        new PodSpecBuilder()
          .withRestartPolicy("Never")
          .withServiceAccountName(Names.serviceAccount(spec.serviceName))
          .withContainers(container)
          .build()
      )
      .build()
    val jobSpec = new io.fabric8.kubernetes.api.model.batch.v1.JobSpecBuilder()
      .withBackoffLimit(0)
      .withTtlSecondsAfterFinished(86400)
      .withTemplate(template)
      .build()
    deadlineSeconds.foreach(d => jobSpec.setActiveDeadlineSeconds(java.lang.Long.valueOf(d)))
    new io.fabric8.kubernetes.api.model.batch.v1.JobBuilder()
      .withMetadata(
        new ObjectMetaBuilder()
          .withNamespace(namespace)
          .withName(Names.moveJob(spec.serviceName, move, phase))
          .withLabels(labels.asJava)
          .withOwnerReferences(Labels.ownerReference(resource))
          .build()
      )
      .withSpec(jobSpec)
      .build()

  /** Lets the routes of one project's namespace name the store's Service, and nothing else. */
  def referenceGrant(namespace: String, store: ObjectStoreSettings): GenericKubernetesResource =
    new GenericKubernetesResourceBuilder()
      .withApiVersion("gateway.networking.k8s.io/v1beta1")
      .withKind("ReferenceGrant")
      .withMetadata(
        new ObjectMetaBuilder()
          .withName(namespace)
          .withNamespace(store.service.namespace)
          .withLabels(Map(Labels.ManagedByKey -> Labels.ManagedByAnkka).asJava)
          .build()
      )
      .withAdditionalProperties(
        Map[String, AnyRef](
          "spec" -> Map[String, AnyRef](
            "from" -> java.util.List.of(
              Map(
                "group"     -> "gateway.networking.k8s.io",
                "kind"      -> "HTTPRoute",
                "namespace" -> namespace
              ).asJava
            ),
            "to" -> java.util.List.of(
              Map("group" -> "", "kind" -> "Service", "name" -> store.service.name).asJava
            )
          ).asJava
        ).asJava
      )
      .build()

  /**
   * The bucket's route: its path at the store's hostname, with no route timeout, since Envoy's
   * default of fifteen seconds ends an upload or a download of any size worth signing a URL for. No
   * `BackendTLSPolicy`: the store speaks no TLS, and the gateway ends the browser's.
   */
  def bucketRoute(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String,
      baseDomain: String,
      store: ObjectStoreSettings
  ): HTTPRoute =
    new HTTPRouteBuilder()
      .withMetadata(identityMeta(resource, spec, namespace, Names.bucketRoute(spec.serviceName)))
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
          .withHostnames(Hostnames.storage(baseDomain))
          .withRules(
            new HTTPRouteRuleBuilder()
              .withMatches(
                new HTTPRouteMatchBuilder()
                  .withPath(
                    new HTTPPathMatchBuilder()
                      .withType("PathPrefix")
                      .withValue("/" + Buckets.name(spec.projectId, spec.serviceName))
                      .build()
                  )
                  .build()
              )
              .withTimeouts(new HTTPRouteTimeoutsBuilder().withRequest("0s").build())
              .withBackendRefs(
                new HTTPBackendRefBuilder()
                  .withName(store.service.name)
                  .withNamespace(store.service.namespace)
                  .withPort(store.service.port)
                  .build()
              )
              .build()
          )
          .build()
      )
      .build()

  /**
   * What the developer's container is told about its bucket, for a service that asks: the
   * credential's Secret, whatever the plan — so with no store there is no Secret and no instance
   * starts with an empty credential — and where the bucket is, when the installation has a store.
   */
  def storageEnv(
      spec: AnkkaServiceSpec,
      settings: Settings,
      reported: Option[com.thinkmorestupidless.ankka.crd.ObjectStorageStatus],
      plan: ObjectStoragePlan = ObjectStoragePlan.NotAsked,
      credentialGeneration: Int = 0
  ): Option[StorageEnv] =
    if ObjectStorage.takesCloudPath(spec, settings, reported) then cloudStorageEnv(spec, plan)
    else garageStorageEnv(spec, settings, credentialGeneration)

  /**
   * A bucket in the installation's cloud account (feature 044): where it is, as the provider
   * answered, and the credential's Secret, which the provider wrote. Nothing at all until all three
   * requests are answered, and nothing for a bucket the provider refused: no instance starts told
   * of a bucket that is not there.
   */
  private def cloudStorageEnv(spec: AnkkaServiceSpec, plan: ObjectStoragePlan): Option[StorageEnv] =
    plan match
      case ObjectStoragePlan.Ready(_, Some(cloud)) =>
        Some(
          StorageEnv(
            secret =
              Option.when(spec.objectStorageCredential)(Buckets.cloudSecret(spec.serviceName)),
            literals = Vector(
              StorageEnv.Endpoint -> cloud.endpoint,
              StorageEnv.Region   -> cloud.region,
              StorageEnv.Bucket   -> cloud.bucket
            ) ++
              // A bucket reachable from the internet is reached there at the cloud's own address,
              // which is the one the provider answered (feature 039): no route of the installation's.
              Option.when(spec.exposeObjectStorage)(StorageEnv.PublicEndpoint -> cloud.endpoint),
            credentialGeneration = cloud.credentialGeneration.toInt
          )
        )
      case _ => None

  private def garageStorageEnv(
      spec: AnkkaServiceSpec,
      settings: Settings,
      credentialGeneration: Int
  ): Option[StorageEnv] =
    Option.when(spec.provisionObjectStorage) {
      val bucket = Buckets.name(spec.projectId, spec.serviceName)
      val where = settings.objectStore.toVector.flatMap(store =>
        Vector(StorageEnv.Endpoint -> store.endpoint, StorageEnv.Region -> store.region)
      )
      // Only for a bucket reachable from the internet: the address a URL for a browser is signed
      // for, since a signature covers the host it was made for.
      val public = (for
        _    <- Option.when(spec.exposeObjectStorage)(())
        base <- settings.baseDomain
      yield StorageEnv.PublicEndpoint -> Buckets.publicEndpoint(base, settings.httpsPort)).toVector
      StorageEnv(
        secret = Some(Buckets.secret(spec.serviceName)),
        literals = (where :+ (StorageEnv.Bucket -> bucket)) ++ public,
        credentialGeneration = credentialGeneration
      )
    }

  private def rendersSecretKey(spec: AnkkaServiceSpec): Boolean =
    !spec.env.exists(_.name == PlatformVariables.SecretKey)

  /**
   * The certificates a service's pods mount and the policies that decide who may connect to them
   * (feature 014) — before the Deployment, so the Secrets exist by the time a pod asks the kubelet
   * for them. A pod scheduled first waits on its volume and starts once cert-manager has issued.
   */
  /**
   * The service's user on the installation's broker (feature 027): before the Deployment, so the
   * user is being made by the time the runtime first connects. Its project's topics are rendered
   * from the project's resource, not here.
   */
  private def brokerActions(
      spec: AnkkaServiceSpec,
      broker: Option[BrokerSettings]
  ): Vector[Action] =
    broker.toVector.map(settings => Action.EnsureKafkaUser(StrimziRendering.user(spec, settings)))

  private def zeroTrustActions(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String,
      commonName: Option[String]
  ): Vector[Action] =
    val ownerUid = Option(resource.getMetadata).flatMap(m => Option(m.getUid)).getOrElse("")
    // The HTTP policy, then the gRPC one: the order every hosting renders them in.
    val http = (spec.port match
      case Some(port) =>
        Vector(Action.EnsureNetworkPolicy(ZeroTrust.httpPolicy(resource, spec, namespace, port)))
      case None =>
        Vector(
          Action
            .RemoveNetworkPolicy(namespace, ZeroTrust.httpPolicyName(spec.serviceName), ownerUid)
        )
    ) ++ (spec.grpcPort match
      case Some(port) =>
        Vector(Action.EnsureNetworkPolicy(ZeroTrust.grpcPolicy(resource, spec, namespace, port)))
      case None =>
        Vector(
          Action
            .RemoveNetworkPolicy(namespace, ZeroTrust.grpcPolicyName(spec.serviceName), ownerUid)
        ))
    if spec.hosting == WebHosting then
      // No cluster certificate and no cluster policy: a web-hosted service forms no cluster. The
      // probe port, which the cluster policy opens for every other service, gets a policy of its
      // own (feature 021).
      // The mount certificate only while there are mounts. One whose last mount is removed is left,
      // owned by the resource, as every certificate a service stops needing is (R7).
      (Action.EnsureCertificate(ZeroTrust.serviceCertificate(resource, spec, namespace)) +:
        Option
          .when(spec.mounts.nonEmpty)(
            Action.EnsureCertificate(ZeroTrust.mountCertificate(resource, spec, namespace))
          )
          .toVector) ++
        http :+
        Action.EnsureNetworkPolicy(ZeroTrust.probePolicy(resource, spec, namespace))
    else
      // The service certificate always, not only with a port: it is the identity the service calls
      // others with, and the runtime's HTTP server starts in every ankka service, exposed or not.
      Vector(
        Action.EnsureCertificate(ZeroTrust.clusterCertificate(resource, spec, namespace)),
        Action.EnsureCertificate(
          ZeroTrust.serviceCertificate(resource, spec, namespace, commonName)
        ),
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
    (spec.exposed, spec.port.orElse(spec.grpcPort), baseDomain) match
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
      namespace: String,
      annotations: Map[String, String]
  ): Vector[Action] =
    // A web-hosted pod names the account and mounts no token for it; it has no peers to find, so
    // it is granted nothing (feature 021).
    if spec.hosting == WebHosting then
      Vector(Action.EnsureServiceAccount(serviceAccount(resource, spec, namespace, annotations)))
    else
      Vector(
        Action.EnsureServiceAccount(serviceAccount(resource, spec, namespace, annotations)),
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
      namespace: String,
      annotations: Map[String, String] = Map.empty
  ): ServiceAccount =
    val meta = identityMeta(resource, spec, namespace, Names.serviceAccount(spec.serviceName))
    // Only when a cloud provider named some, so every other ServiceAccount is what it was.
    if annotations.nonEmpty then meta.setAnnotations(annotations.asJava)
    new ServiceAccountBuilder().withMetadata(meta).build()

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
   * Exactly one of these per pass: the address exists when there is a port, HTTP or gRPC, and does
   * not when there is neither. After the Deployment, since a Service in front of nothing is only
   * noise.
   */
  private def addressAction(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String
  ): Action =
    address(resource, spec, namespace) match
      case Some(service) => Action.EnsureService(service)
      case None =>
        Action.RemoveService(
          namespace,
          Names.service(spec.serviceName),
          Option(resource.getMetadata).flatMap(m => Option(m.getUid)).getOrElse("")
        )

  /**
   * The route, after the address it points at. Rendered only when the service is exposed, has a
   * port — HTTP or gRPC — and the operator knows the base domain; in every other case the route is
   * removed if this resource owns one — so unexposing, or dropping both ports, takes the route away
   * without touching the service.
   */
  private def routeAction(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String,
      baseDomain: Option[String]
  ): Action =
    (spec.exposed, spec.port.orElse(spec.grpcPort), baseDomain) match
      case (true, Some(_), Some(base)) =>
        Action.EnsureHttpRoute(httpRoute(resource, spec, namespace, base))
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
   *
   * One rule per protocol the service serves, gRPC's first. A gRPC call is told from an HTTP
   * request by its content type, so the platform needs to know nothing of a service's methods; the
   * match is a full match, so it admits `application/grpc` and `application/grpc+proto` and not
   * `application/grpc-web`. The gateway applies a fifteen-second timeout to a route that names
   * none, which would cut every stream, so the gRPC rule says it has none. The HTTP rule is
   * rendered exactly as it was before gRPC endpoints existed.
   */
  def httpRoute(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String,
      baseDomain: String
  ): HTTPRoute =
    val grpcRule = spec.grpcPort.map { port =>
      new HTTPRouteRuleBuilder()
        .withMatches(
          new HTTPRouteMatchBuilder()
            .withHeaders(
              new HTTPHeaderMatchBuilder()
                .withName("content-type")
                .withType("RegularExpression")
                .withValue(GrpcContentType)
                .build()
            )
            .build()
        )
        .withTimeouts(new HTTPRouteTimeoutsBuilder().withRequest("0s").build())
        .withBackendRefs(
          new HTTPBackendRefBuilder()
            .withName(Names.service(spec.serviceName))
            .withPort(port)
            .build()
        )
        .build()
    }
    val httpRule = spec.port.map { port =>
      new HTTPRouteRuleBuilder()
        .withBackendRefs(
          new HTTPBackendRefBuilder()
            .withName(Names.service(spec.serviceName))
            .withPort(port)
            .build()
        )
        .build()
    }
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
          .withRules((grpcRule.toVector ++ httpRule.toVector)*)
          .build()
      )
      .build()

  /** What a gRPC call's content type is, as Envoy matches a header: the whole value. */
  val GrpcContentType: String = """application/grpc(\+.+)?"""

  /** The headless gRPC address when the service serves gRPC; its removal, if owned, when not. */
  private def grpcPeersAction(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String
  ): Action =
    val ownerUid = Option(resource.getMetadata).flatMap(m => Option(m.getUid)).getOrElse("")
    spec.grpcPort match
      case Some(port) =>
        Action.EnsureGrpcPeers(grpcPeers(resource, spec, namespace, port), ownerUid)
      case None => Action.RemoveService(namespace, Names.grpcPeers(spec.serviceName), ownerUid)

  /**
   * The headless address a service that serves gRPC also has: no cluster IP, so its DNS name
   * resolves to one address per ready instance, and the platform's own gRPC client balances its
   * calls across them. A cluster IP balances connections, and a gRPC channel holds one connection
   * for minutes, so without this a caller would send everything to one instance and a new one would
   * see nothing. Only ready instances are published, so an instance is in a caller's rotation
   * exactly while it can answer. Exposed so tests can assert on the object.
   */
  def grpcPeers(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String,
      port: Int
  ): Service =
    new ServiceBuilder()
      .withMetadata(
        new ObjectMetaBuilder()
          .withName(Names.grpcPeers(spec.serviceName))
          .withNamespace(namespace)
          .withLabels(Labels.merged(spec.projectId, spec.serviceName, spec.labels).asJava)
          .withOwnerReferences(Labels.ownerReference(resource))
          .build()
      )
      .withSpec(
        new ServiceSpecBuilder()
          .withClusterIP("None")
          .withSelector(selectorLabels(spec).asJava)
          .withPorts(
            new ServicePortBuilder()
              .withName(GrpcPortName)
              .withProtocol("TCP")
              .withPort(port)
              .withTargetPort(new IntOrString(port))
              .build()
          )
          .build()
      )
      .build()

  /** The service's address with `port` as its HTTP port, whatever the spec says. */
  def service(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String,
      port: Int
  ): Service =
    address(resource, spec.copy(port = Some(port)), namespace).getOrElse(
      throw IllegalStateException("a service with a port has an address")
    )

  /**
   * The service's address: one port per protocol it serves, `http` then `grpc`, or none at all.
   * Exposed so tests can assert on the object rather than on an action wrapper.
   *
   * The `grpc` port says `kubernetes.io/h2c`, which is what makes the gateway speak HTTP/2 to it;
   * TLS comes separately, from the backend TLS policy, so what reaches the service is HTTP/2 over
   * TLS despite the name. The `http` port says nothing, and so renders as it always has.
   */
  def address(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String
  ): Option[Service] =
    val ports = spec.port.map { port =>
      new ServicePortBuilder()
        .withName(PortName)
        .withProtocol("TCP")
        // The same value twice. A Service is not a place to translate between an outer
        // and an inner port; nothing here has an outer one.
        .withPort(port)
        .withTargetPort(new IntOrString(port))
        .build()
    }.toVector ++ spec.grpcPort.map { port =>
      new ServicePortBuilder()
        .withName(GrpcPortName)
        .withProtocol("TCP")
        .withPort(port)
        .withTargetPort(new IntOrString(port))
        .withAppProtocol(GrpcAppProtocol)
        .build()
    }.toVector
    Option.when(ports.nonEmpty)(addressWith(resource, spec, namespace, ports))

  private def addressWith(
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      namespace: String,
      ports: Vector[io.fabric8.kubernetes.api.model.ServicePort]
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
          .withPorts(ports*)
          .build()
      )
      .build()

  /** What both the Deployment and the Service select on. Immutable for the life of a service. */
  private def selectorLabels(spec: AnkkaServiceSpec): Map[String, String] =
    Labels.identity(spec.projectId, spec.serviceName)

  private val PortName = "http"

  /**
   * The gRPC port's name. Load-bearing, as `http` is: the Service targets it, the backend TLS
   * policy names it as a section, and its SRV record — which is how another service finds the port
   * — is `_grpc._tcp`.
   */
  val GrpcPortName: String = "grpc"

  /** What the runtime reads its gRPC port from — `modules/grpc`'s `reference.conf`. */
  private val GrpcPortEnvVar = "ANKKA_GRPC_PORT"

  /** HTTP/2 to the gRPC port, from the gateway and any proxy that reads it. */
  val GrpcAppProtocol: String = "kubernetes.io/h2c"

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
    // On every pass that provisions, because the operator never reads a Secret to learn whether it
    // is there: a `create` answered with a conflict is how it finds out, once per process.
    def credentials: Action =
      Action.EnsureCredentials(
        CnpgRendering.credentialSecret(spec, namespace, CnpgRendering.projectClusterName)
      )
    plan match
      case ProvisioningPlan.NotNeeded | ProvisioningPlan.Supplied => Vector.empty
      case ProvisioningPlan.Waiting(_, needsRole, needsDatabase, _) =>
        tls ++ Vector(
          Some(credentials),
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
        (tls :+ credentials) ++
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
      httpsPort: Int = Settings.default.httpsPort,
      otlpEndpoint: Option[String] = None,
      otlpHeaders: Boolean = false,
      storage: Option[StorageEnv] = None
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
    val containers = withStorage(
      spec,
      containersFor(
        spec,
        identity,
        withDatabaseEnv = provisioned,
        sidecarImage,
        namespacePrefix,
        proxyImage,
        baseDomain,
        httpsPort,
        telemetryEnv(spec, otlpEndpoint, otlpHeaders)
      ),
      storage
    )
    // A web-hosted pod holds the service certificate alone: no cluster to join, no database.
    val held =
      if web then
        ZeroTrust.Held(
          cluster = false,
          service = true,
          database = false,
          mount = spec.mounts.nonEmpty
        )
      else ZeroTrust.Held(cluster = true, service = true, database = provisioned)
    // The project's declarations (feature 037) ride beside the TLS volumes on every pod: optional,
    // so a project without them starts as before.
    val tlsVolumes =
      ZeroTrust.volumes(held, spec, CnpgRendering.projectClusterName) ++ moduleVolumes(spec) ++
        projectVolumes(spec)
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
            (spec.annotations + (Labels.RestartsKey -> spec.restarts.toString) ++
              // Feature 039: a storage credential issued again rolls the pods onto it. Absent
              // until one is, so upgrading the operator changes no pod template.
              storage
                .filter(_.credentialGeneration > 0)
                .map(s => Labels.StorageCredentialKey -> s.credentialGeneration.toString)).asJava
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
   * The bucket's variables go to the developer's program and to no program of the platform's: the
   * one container of an embedded or a wasm service (whose module asks its `config` for them), and
   * `<service>-app` beside a sidecar or a proxy, neither of which opens a bucket. Unlike a
   * database's credential, which the sidecar holds because the sidecar opens the database.
   */
  private def withStorage(
      spec: AnkkaServiceSpec,
      containers: Vector[Container],
      storage: Option[StorageEnv]
  ): Vector[Container] =
    storage.fold(containers) { env =>
      val target =
        if spec.hosting == ProcessHosting || spec.hosting == WebHosting then
          containers.indexWhere(_.getName == Names.container(spec.serviceName) + "-app")
        else 0
      containers.updated(
        target,
        new ContainerBuilder(containers(target))
          .addToEnv(env.literals.map((name, value) => literal(name, value))*)
          .addToEnvFrom(
            env.secret.toVector.map(secret =>
              new EnvFromSourceBuilder()
                .withSecretRef(new SecretEnvSourceBuilder().withName(secret).build())
                .build()
            )*
          )
          .build()
      )
    }

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
      httpsPort: Int,
      telemetry: Vector[EnvVar]
  ): Vector[Container] = spec.hosting match
    case WasmHosting =>
      val node = container(
        spec.copy(image = sidecarImage),
        identity,
        withDatabaseEnv,
        extraEnv = Vector(literal("ANKKA_WASM_MODULE", ModuleFile)) ++ telemetry,
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
      Vector(
        container(
          spec,
          identity,
          withDatabaseEnv,
          extraEnv = telemetry,
          namespacePrefix = namespacePrefix
        )
      )
    case WebHosting =>
      webContainers(spec, namespacePrefix, proxyImage, baseDomain, httpsPort)
    case ProcessHosting =>
      // A descriptor's variables are split by `PlatformVariables`, the one declaration the control
      // plane and the module host read too: what is the platform's program's alone (a model's key,
      // a supplied database, the secret key, the issuers it accepts) goes to the sidecar, which runs
      // the agent loop, has the journal, holds the secret store and verifies tokens; everything else
      // is the process's; and the broker's are both's — the sidecar connects to it, and the process
      // may want to know there is one.
      val (forSidecar, forProcess) = spec.env.partition(e => PlatformVariables.runtimeOnly(e.name))
      val shared                   = forProcess.filter(e => PlatformVariables.shared(e.name))
      val node = container(
        spec.copy(image = sidecarImage, env = forSidecar ++ shared),
        identity,
        withDatabaseEnv,
        extraEnv = Vector(
          literal("ANKKA_PROCESS_ADDRESS", s"127.0.0.1:$ProcessPort"),
          literal("ANKKA_SIDECAR_PORT", SidecarPort.toString)
        ) ++ telemetry,
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
            .withRequests(appQuantities(spec))
            .withLimits(appQuantities(spec))
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
        ZeroTrust.mounts(
          ZeroTrust
            .Held(cluster = false, service = true, database = false, mount = spec.mounts.nonEmpty)
        )*
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

  // The project's declarations (feature 037) reach every runtime: a web-hosted service has none, so
  // it mounts nothing and renders as before.
  /** The process container's size (feature 037): the descriptor's, else the platform's minimum. */
  private def appQuantities(spec: AnkkaServiceSpec): java.util.Map[String, Quantity] =
    Map(
      "cpu"    -> new Quantity(s"${spec.processCpuMillis}m"),
      "memory" -> new Quantity(s"${spec.processMemoryMiB}Mi")
    ).asJava

  private def projectVolumes(spec: AnkkaServiceSpec): Vector[Volume] =
    if spec.hosting == WebHosting then Vector.empty else Vector(ProjectConfig.volume())

  private def projectMounts(spec: AnkkaServiceSpec): Vector[VolumeMount] =
    if spec.hosting == WebHosting then Vector.empty else Vector(ProjectConfig.mount())

  private def projectEnvironment(spec: AnkkaServiceSpec): Vector[EnvVar] =
    if spec.hosting == WebHosting then Vector.empty
    else
      // Feature 037: a service with no database is told so, and refuses a component that needs one.
      val none = Option.when(spec.database == "none")(literal("ANKKA_DATABASE", "none"))
      Vector(ProjectConfig.environment()) ++ none

  private def container(
      spec: AnkkaServiceSpec,
      identity: Map[String, String],
      withDatabaseEnv: Boolean,
      extraEnv: Vector[EnvVar],
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
    // The secret key, by reference to the Secret `secretKeyAction` makes: on this container only,
    // which is always the platform's own (a process-hosted service's app container is built apart
    // and never gets it). A descriptor that gives the key has it in `spec.env` instead.
    val secretKeyEnv = Option.when(rendersSecretKey(spec)) {
      val selector = new SecretKeySelectorBuilder()
        .withName(Names.secretKeySecret(spec.serviceName))
        .withKey(Names.SecretKeyEntry)
        .build()
      new EnvVarBuilder()
        .withName(PlatformVariables.SecretKey)
        .withValueFrom(new EnvVarSourceBuilder().withSecretKeyRef(selector).build())
        .build()
    }

    val portEnv = spec.port.map { port =>
      new EnvVarBuilder().withName(PlatformVariables.HttpPort).withValue(port.toString).build()
    }
    val containerPorts = spec.port.map { port =>
      new ContainerPortBuilder()
        .withName(PortName)
        .withContainerPort(port)
        .withProtocol("TCP")
        .build()
    }
    // gRPC's pair, from the one field, rendered only when the service declared it — so a service
    // that did not renders exactly what it did before gRPC endpoints existed.
    val grpcEnv = spec.grpcPort.map { port =>
      new EnvVarBuilder().withName(GrpcPortEnvVar).withValue(port.toString).build()
    }
    val grpcPorts = spec.grpcPort.map { port =>
      new ContainerPortBuilder()
        .withName(GrpcPortName)
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
        .build(),
      // The control plane's read of the topology (feature 019). Not in any selector.
      new ContainerPortBuilder()
        .withName(ZeroTrust.ObservePortName)
        .withContainerPort(ZeroTrust.ObservePort)
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
        (spec.env.map(
          environment
        ) ++ portEnv ++ grpcEnv ++ clusterEnv ++ extraEnv ++ secretKeyEnv ++
          (if withDatabaseEnv then ZeroTrust.Database.Environment.map(literal) else Vector.empty)
          ++ projectEnvironment(spec))*
      )
      .withEnvFrom(envFrom*)
      .withPorts((containerPorts.toVector ++ grpcPorts.toVector ++ clusterPorts)*)
      .withVolumeMounts(
        (ZeroTrust.mounts(
          ZeroTrust.Held(cluster = true, service = true, database = withDatabaseEnv)
        ) ++ projectMounts(spec))*
      )
      // A service that refuses to start writes why to the termination log (StartRefusal); one that
      // exits before it can, or without one, leaves its last log lines instead. Either way the
      // operator reports the message as the service's detail, so `ankka services get` shows it.
      .withTerminationMessagePolicy("FallbackToLogsOnError")
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

/**
 * What a developer's container is told about its bucket (feature 034): the credential's Secret by
 * `envFrom`, and the rest as literals, which change with the pod template and so reach every
 * instance when they change. Only what never changes is in the Secret, which is written once.
 */
/**
 * @param credentialGeneration
 *   the storage credential's generation in place (feature 039), put on the pod template when it is
 *   above 0 so the service rolls onto a credential issued again, and never before it is there
 */
final case class StorageEnv(
    /** The Secret its key comes from; none for a service that declined a credential. */
    secret: Option[String],
    literals: Vector[(String, String)],
    credentialGeneration: Int = 0
)

object StorageEnv:
  val Endpoint: String       = PlatformVariables.ObjectStoragePrefix + "ENDPOINT"
  val Region: String         = PlatformVariables.ObjectStoragePrefix + "REGION"
  val Bucket: String         = PlatformVariables.ObjectStoragePrefix + "BUCKET"
  val PublicEndpoint: String = PlatformVariables.ObjectStoragePrefix + "PUBLIC_ENDPOINT"
