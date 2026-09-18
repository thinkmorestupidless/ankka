package nakka.crd

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
final case class NakkaServiceSpec(
    /** Redundant with the namespace, carried so the resource is self-describing under `kubectl`. */
    projectId: String = "",
    serviceName: String = "",
    /**
     * nakka's generation, bumped by every apply and restart.
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
     * the resolved numbers below. Carried anyway because `kubectl describe nsvc` should say
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
     * `nakka.join-self-if-no-seed-nodes` is on), so a second replica would be a second writer to
     * the same journal. Present in the schema so that enabling multi-replica support later is not a
     * breaking change.
     */
    autoscaling: AutoscalingSpec = AutoscalingSpec(),
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
     * `true` (the default) means the control plane found no `NAKKA_DB_*` variable in the
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
     * The port the workload serves HTTP on — or absent, when it serves none.
     *
     * Already resolved by the control plane: the descriptor's `http`/`port` pair never reaches
     * here, so present-or-absent is the whole vocabulary and needs no magic value. From this one
     * field the operator renders the container port, `NAKKA_HTTP_PORT`, the readiness probe and the
     * Service's target, which is what makes them unable to disagree.
     *
     * `None` by default, deliberately: a resource written before this field existed serves no HTTP
     * as far as the operator is concerned, and keeps behaving exactly as it did.
     *
     * `contentAs` because Scala's `Option[Int]` erases to `Option[Object]`, and without the hint
     * Jackson picks the boxed type from the JSON rather than from the field.
     */
    @JsonDeserialize(contentAs = classOf[java.lang.Integer])
    port: Option[Int] = None
)

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
 * A separate type, not folded into `NakkaServiceStatus`'s own fields, because it is reported or
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
 * What the operator observed.
 *
 * Written to the status subresource, and only by the operator — its RBAC grants
 * `nakkaservices/status: update` and not `nakkaservices: update`, so a bug here cannot rewrite
 * desired state.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class NakkaServiceStatus(
    /** Echoes `spec.generation` — the nakka generation this report describes. */
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
    database: Option[DatabaseStatus] = None
):
  /** Equality for the purpose of "has anything actually changed", ignoring the clock. */
  def sameReport(other: NakkaServiceStatus): Boolean =
    copy(lastTransitionTime = "") == other.copy(lastTransitionTime = "")

/**
 * The custom resource itself.
 *
 * A plain class with the inherited no-arg constructor rather than a case class taking spec and
 * status: fabric8 instantiates it reflectively and populates it through the setters
 * `CustomResource` already provides, and giving it a constructor Jackson has to match is the usual
 * source of "works in a unit test, fails against the API server".
 */
@Group("nakka.thinkmorestupidless.com")
@Version("v1alpha1")
@Kind("NakkaService")
@Plural("nakkaservices")
@ShortNames(Array("nsvc"))
class NakkaService extends CustomResource[NakkaServiceSpec, NakkaServiceStatus] with Namespaced:
  override protected def initSpec(): NakkaServiceSpec = NakkaServiceSpec()

  /**
   * Null, deliberately.
   *
   * A resource nothing has reported on must be distinguishable from one reporting defaults — that
   * distinction is how an operator discovers the operator is not installed. An initialised status
   * would erase it.
   */
  override protected def initStatus(): NakkaServiceStatus = null

object NakkaService:
  /** Builds a resource for one service. */
  def apply(namespace: String, name: String, spec: NakkaServiceSpec): NakkaService =
    val resource = new NakkaService
    val meta = new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
      .withNamespace(namespace)
      .withName(name)
      .build()
    resource.setMetadata(meta)
    resource.setSpec(spec)
    resource
