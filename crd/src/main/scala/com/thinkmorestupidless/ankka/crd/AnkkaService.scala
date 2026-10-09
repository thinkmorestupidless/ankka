package com.thinkmorestupidless.ankka.crd

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.fabric8.kubernetes.api.model.Namespaced
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.{Group, Kind, Plural, ShortNames, Version}

/**
 * A service's desired state, as the control plane projects it.
 *
 * Written by the control plane, never by the operator. Every field has a default so that an
 * operator reading a resource produced by an older control plane sees a missing field as its
 * default rather than failing to decode — a two-process system is always mid-upgrade somewhere.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class AnkkaServiceSpec(
    /** Redundant with the namespace, carried so the resource is self-describing under `kubectl`. */
    projectId: String = "",
    serviceName: String = "",
    /**
     * ankka's generation, bumped by every apply and restart.
     *
     * Not to be confused with `metadata.generation`, which the API server bumps on every spec
     * change and which is only meaningful against `status.observedGeneration`. This is the one the
     * staleness guard in `Service.onObserved` compares.
     */
    generation: Long = 0L,
    /** Desired state, not an observation: a paused service is paused even while pods wind down. */
    paused: Boolean = false,
    image: String = "",
    env: List[EnvEntry] = Nil,
    labels: Map[String, String] = Map.empty,
    annotations: Map[String, String] = Map.empty,
    /**
     * Informational only — what the operator ignores.
     *
     * The named sizes are the control plane's vocabulary, defined in `controlplane-api` where the
     * CLI can validate them. The operator cannot see that module and must not need to: it renders
     * the resolved numbers below. Carried anyway because `kubectl describe asvc` should say
     * "medium" rather than make an operator work it out from millicores.
     */
    instanceType: String = "small",
    /** Authoritative. Resolved from `instanceType` by the control plane. */
    cpuMillis: Int = 500,
    memoryMiB: Int = 512,
    /**
     * Carried and validated, but **not honoured** — the operator renders one replica and no
     * autoscaler.
     *
     * Every pod joins itself as a single-node cluster (`pekko.cluster.seed-nodes` is empty and
     * `ankka.join-self-if-no-seed-nodes` is on), so a second replica would be a second writer to
     * the same journal. Present in the schema so that enabling multi-replica support later is not a
     * breaking change.
     */
    autoscaling: AutoscalingSpec = AutoscalingSpec(),
    /**
     * The process container's size for a process-hosted service (feature 037), in millicores and
     * MiB, requests equal to limits; the platform's minimum unless the descriptor says.
     */
    processCpuMillis: Int = 100,
    processMemoryMiB: Int = 128,
    /**
     * Handed to the Deployment, so Kubernetes owns the not-progressing clock.
     *
     * The operator does not run a timer of its own, which is what stops two clocks disagreeing
     * about whether a rollout has given up.
     */
    progressDeadlineSeconds: Int = 600,
    /**
     * Whether the platform should provision this service's database.
     *
     * `true` (the default) means the control plane found no `ANKKA_DB_*` variable in the
     * descriptor's `env` and the operator should create a `Cluster`/`Database`/`DatabaseRole` for
     * it. `false` means the descriptor supplied its own connection details — the escape hatch — and
     * the operator renders no CNPG objects and no schema-init container at all. Set by the control
     * plane, from the descriptor, never inferred by the operator: the rule belongs where descriptor
     * validation already lives, so the CLI can apply the same one before the round trip, and so the
     * resource states which path a service took rather than leaving it to be worked out from what
     * is or is not present.
     */
    provisionDatabase: Boolean = true,
    /**
     * Whose database the service has (feature 037): `platform`, `supplied`, or `none` for a service
     * with no database at all, for which nothing is provisioned and whose runtime refuses a
     * component that needs one. Set by the control plane, as `provisionDatabase` is.
     */
    database: String = "platform",
    /**
     * The port the workload serves HTTP on — or absent, when it serves none.
     *
     * Already resolved by the control plane: the descriptor's `http`/`port` pair never reaches
     * here, so present-or-absent is the whole vocabulary and needs no magic value. From this one
     * field the operator renders the container port, `ANKKA_HTTP_PORT`, the readiness probe and the
     * Service's target, which is what makes them unable to disagree.
     *
     * `None` by default, deliberately: a resource written before this field existed serves no HTTP
     * as far as the operator is concerned, and keeps behaving exactly as it did.
     *
     * `contentAs` because Scala's `Option[Int]` erases to `Option[Object]`, and without the hint
     * Jackson picks the boxed type from the JSON rather than from the field.
     */
    @JsonDeserialize(contentAs = classOf[java.lang.Integer])
    port: Option[Int] = None,
    /**
     * How many times an operator has asked for the running instances to be replaced.
     *
     * This — not `generation` — is what goes on the pod template to make a restart roll the pods.
     * Feature 001 put the generation there, so *every* apply rolled every pod; from feature 004 a
     * pure scale must not touch the instances that stay (FR-016), and an unchanged re-apply need
     * not roll at all. A change to anything that is genuinely part of the template — image, env,
     * port — still rolls, because the template itself changed.
     */
    restarts: Int = 0,
    /**
     * Desired state: the service answers at its platform-derived hostname (feature 005). Only the
     * boolean crosses the boundary — the operator derives the hostname itself from the name, the
     * project and its own base domain, so no writer of this resource can point a route at a name
     * the service does not own.
     */
    exposed: Boolean = false,
    /**
     * `embedded` (one container, the image is the node), `process` (feature 009: the image is a
     * developer's process in another language and the operator runs the sidecar beside it), `wasm`
     * (feature 016: the image carries a WebAssembly module, which an init container copies into a
     * shared volume for the runtime to load) or `web` (feature 021: the image is any program that
     * serves HTTP, and the operator runs the platform's proxy beside it). The runtime's and the
     * proxy's images are the operator's, never the resource's.
     */
    hosting: String = "embedded",
    /**
     * The name of a `kubernetes.io/dockerconfigjson` Secret in this service's namespace, named on
     * the pod so the kubelet can pull from a private registry (feature 013).
     *
     * Set by the control plane from the *project's* registered credentials — the operator does not
     * know what a project is, and never reads the Secret: it renders a reference and the kubelet
     * resolves it, exactly as it does for a descriptor's own `secretKeyRef` variables. `None` on a
     * resource written before this field existed, which renders as it always did.
     */
    imagePullSecret: Option[String] = None,
    /**
     * The port the workload serves gRPC on — or absent, when it serves none.
     *
     * Resolved by the control plane from the descriptor's `grpc`/`grpcPort` pair, as `port` is from
     * `http`/`port`. Present, the operator renders a container port and `ANKKA_GRPC_PORT`, a second
     * port on the service's address, a headless address for per-call balancing, the policy that
     * admits workloads of the installation to it, and — when exposed — the route rule that sends
     * gRPC there. Absent renders none of it, which is what keeps a service that declares no gRPC
     * rendering exactly as it did before the field existed.
     */
    @JsonDeserialize(contentAs = classOf[java.lang.Integer])
    grpcPort: Option[Int] = None,
    /**
     * A web-hosted service's mounts (feature 021): each a path and the name of a service in the
     * same project, whose requests the proxy passes to that service. Names only, never addresses:
     * the proxy finds the service as any service is found.
     */
    mounts: List[MountEntry] = Nil,
    /**
     * The services a web-hosted service's proxy admits beside the internet and itself, as the
     * descriptor wrote them: `"<service>"`, `"<project>/<service>"` or `"*"`.
     */
    callers: List[String] = Nil,
    /**
     * The port a web-hosted service's program listens on, resolved by the control plane: the
     * descriptor's, or the platform's default. Absent for every other hosting.
     */
    @JsonDeserialize(contentAs = classOf[java.lang.Integer])
    processPort: Option[Int] = None,
    /**
     * Whether the platform should know this service on the installation's broker (feature 027).
     *
     * `false` when the descriptor names a broker of its own with an `ANKKA_KAFKA_*` variable, and
     * for a web-hosted service; set by the control plane from the descriptor, as
     * `provisionDatabase` is. `true` does not mean the installation has a broker: the operator
     * knows that.
     */
    provisionBroker: Boolean = true,
    /**
     * Whether the platform gives this service a bucket and a storage credential that reaches it
     * alone (feature 034). Set by the control plane from the descriptor. `false` says nothing about
     * whether the service has an object store of its own: that is an `ANKKA_S3_` variable in `env`,
     * which the operator reads, so the two cannot disagree.
     */
    provisionObjectStorage: Boolean = false,
    /**
     * Whether that bucket is reachable from the internet, for URLs the service signs. Only with
     * `provisionObjectStorage`; the control plane refuses it otherwise. The bucket's name and
     * address are never in the resource: the operator derives them (`Buckets`).
     */
    exposeObjectStorage: Boolean = false,
    /**
     * The generation of the storage credential a cloud provider issues for that bucket (feature
     * 044): raising it asks for a new credential in the same Secret. Absent means 1. An `Option` so
     * that the control plane, which does not set it, writes nothing here and never owns the field:
     * a value raised on the resource is not reverted by the next projection.
     */
    @JsonDeserialize(contentAs = classOf[java.lang.Long])
    storageCredentialGeneration: Option[Long] = None
)

/** One mount of a web-hosted service: a path, and the service of its project that answers it. */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class MountEntry(path: String = "", service: String = "")

/**
 * One environment variable, literal or drawn from a secret.
 *
 * The operator never reads *this* secret's value — it renders a reference and the kubelet resolves
 * it. The operator does hold `get`/`create`/`patch` on secrets as of the database provisioning
 * feature, needed to create and check for a service's generated database credentials, but never
 * `list` — it cannot enumerate secrets it was not told about, and no code path reads a secret's
 * contents into a status `detail`.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class EnvEntry(
    name: String = "",
    value: Option[String] = None,
    secretName: Option[String] = None,
    secretKey: Option[String] = None
)

@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class AutoscalingSpec(
    minInstances: Int = 1,
    maxInstances: Int = 10,
    targetCpuPercent: Int = 80
)

/**
 * What the platform did about one service's database.
 *
 * A separate type, not folded into `AnkkaServiceStatus`'s own fields, because it is reported or
 * absent as a unit — there is no meaningful "half a database status".
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class DatabaseStatus(
    /**
     * "Waiting", "Provisioned", "Recovered", "Supplied" or "Failed" — see the provisioning
     * contract.
     */
    phase: String = "",
    /** The database's name, so an operator can find it without reading a secret. */
    name: String = "",
    /** The `Cluster` serving it, so shared per-project capacity is visible. */
    cluster: String = "",
    /**
     * True when this service was handed a database that already existed.
     *
     * Databases are never destroyed (a deliberate platform-wide guarantee), so a re-applied service
     * name recovers whatever was there before rather than starting clean. That has to be visible
     * rather than surprising, which is what this field is for.
     */
    recovered: Boolean = false,
    detail: Option[String] = None
)

/**
 * What the platform did about one service's bucket (feature 034), in the shape of `DatabaseStatus`
 * and with its phases.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class ObjectStorageStatus(
    /** "Waiting", "Provisioned", "Recovered", "Supplied" or "Failed". */
    phase: String = "",
    /** The bucket's name; empty when the service has an object store of its own. */
    bucket: String = "",
    /** Where the bucket is on the internet, when its descriptor asked that it be reachable. */
    publicAddress: Option[String] = None,
    /**
     * True when the bucket is older than this incarnation of the resource: a service of the same
     * name had it before and was deleted. Buckets are never deleted by the platform.
     */
    recovered: Boolean = false,
    /** Why it is waiting or failed. Never contains a secret. */
    detail: Option[String] = None
)

/**
 * What the platform did about one service's credential on the installation's broker (feature 027):
 * reported or absent as a unit, as `DatabaseStatus` is. A project's topics are reported on its
 * `AnkkaProject`.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class BrokerStatus(
    /** "Waiting", "Provisioned", "Recovered", "Supplied" or "Failed", as the database's phase. */
    phase: String = "",
    /** True when the service's user was there before it was applied. */
    recovered: Boolean = false,
    detail: Option[String] = None
)

/**
 * What the operator observed.
 *
 * Written to the status subresource, and only by the operator — its RBAC grants
 * `ankkaservices/status: update` and not `ankkaservices: update`, so a bug here cannot rewrite
 * desired state.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class AnkkaServiceStatus(
    /** Echoes `spec.generation` — the ankka generation this report describes. */
    generation: Long = 0L,
    /** Echoes the `metadata.generation` the operator acted on. */
    observedGeneration: Long = 0L,
    /** One of the seven `ServiceLifecycle` names, as a plain string. */
    lifecycle: String = "NotDeployed",
    readyInstances: Int = 0,
    desiredInstances: Int = 0,
    /** Operator-readable. Never contains a secret value. */
    detail: Option[String] = None,
    lastTransitionTime: String = "",
    /**
     * What the platform did about this service's database. Absent on the escape-hatch path
     * (`provisionDatabase = false`) — there is nothing to report because nothing was provisioned.
     */
    database: Option[DatabaseStatus] = None,
    /**
     * The route's state as the gateway reports it, for an exposed service: `accepted`, `pending`,
     * or `rejected: <reason>` — a route can be accepted by the listener and still not resolve its
     * backend, so both of the gateway's conditions fold into this one word. Absent when the service
     * is not exposed.
     */
    route: Option[String] = None,
    /**
     * What the platform did about this service's credential on the installation's broker. Absent
     * for a web-hosted service, and in an installation with no broker.
     */
    broker: Option[BrokerStatus] = None,
    /**
     * What the platform did about this service's bucket (feature 034). Absent only when the service
     * neither asks for one nor gives an object store of its own.
     */
    objectStorage: Option[ObjectStorageStatus] = None
):
  /** Equality for the purpose of "has anything actually changed", ignoring the clock. */
  def sameReport(other: AnkkaServiceStatus): Boolean =
    copy(lastTransitionTime = "") == other.copy(lastTransitionTime = "")

/**
 * The custom resource itself.
 *
 * A plain class with the inherited no-arg constructor rather than a case class taking spec and
 * status: fabric8 instantiates it reflectively and populates it through the setters
 * `CustomResource` already provides, and giving it a constructor Jackson has to match is the usual
 * source of "works in a unit test, fails against the API server".
 */
@Group("ankka.thinkmorestupidless.com")
@Version("v1alpha1")
@Kind("AnkkaService")
@Plural("ankkaservices")
@ShortNames(Array("asvc"))
class AnkkaService extends CustomResource[AnkkaServiceSpec, AnkkaServiceStatus] with Namespaced:
  override protected def initSpec(): AnkkaServiceSpec = AnkkaServiceSpec()

  /**
   * Null, deliberately.
   *
   * A resource nothing has reported on must be distinguishable from one reporting defaults — that
   * distinction is how an operator discovers the operator is not installed. An initialised status
   * would erase it.
   */
  override protected def initStatus(): AnkkaServiceStatus = null

object AnkkaService:
  /** Builds a resource for one service. */
  def apply(namespace: String, name: String, spec: AnkkaServiceSpec): AnkkaService =
    val resource = new AnkkaService
    val meta = new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
      .withNamespace(namespace)
      .withName(name)
      .build()
    resource.setMetadata(meta)
    resource.setSpec(spec)
    resource
