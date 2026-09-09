package nakka.controlplane.api

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import nakka.core.Codecs

/**
 * A service's desired state.
 *
 * Modelled on Akka's service descriptor, which is applied declaratively with
 * `akka service apply -f`. Everything the control plane does is a consequence of comparing one of
 * these against what is actually running.
 */
final case class ServiceDescriptor(name: String, service: ServiceSpec):

  /** Problems that make this descriptor unusable, reported all at once. */
  def problems: Vector[String] =
    val nameProblems =
      if name.isEmpty then Vector("service name must not be empty")
      else if !ServiceDescriptor.ValidName.matches(name) then
        Vector(
          s"service name '$name' is invalid: lowercase letters, digits and '-', " +
            "starting with a letter"
        )
      else Vector.empty
    nameProblems ++ service.problems

  def isValid: Boolean = problems.isEmpty

object ServiceDescriptor:
  /**
   * Kubernetes DNS label rules.
   *
   * The name becomes a Deployment and Service name, so a descriptor that cannot be expressed in
   * Kubernetes should be refused when it is applied rather than when the reconciler tries to render
   * it.
   */
  private val ValidName = "[a-z]([-a-z0-9]{0,61}[a-z0-9])?".r

final case class ServiceSpec(
    image: String,
    env: Vector[EnvVar] = Vector.empty,
    labels: Map[String, String] = Map.empty,
    annotations: Map[String, String] = Map.empty,
    resources: ServiceResources = ServiceResources()
):
  def problems: Vector[String] =
    val imageProblems =
      if image.isEmpty then Vector("service image must not be empty") else Vector.empty
    val envProblems = env.flatMap(_.problems)
    imageProblems ++ envProblems ++ resources.problems

/**
 * A container environment variable, either literal or drawn from a secret.
 *
 * Exactly one of `value` and `secretKeyRef` must be set — a variable with both is ambiguous, and
 * one with neither is almost certainly a mistake in the descriptor.
 */
final case class EnvVar(
    name: String,
    value: Option[String] = None,
    secretKeyRef: Option[SecretKeyRef] = None
):
  def problems: Vector[String] =
    if name.isEmpty then Vector("env var name must not be empty")
    else
      (value, secretKeyRef) match
        case (Some(_), None) => Vector.empty
        case (None, Some(_)) => Vector.empty
        case (Some(_), Some(_)) =>
          Vector(s"env var '$name' sets both value and secretKeyRef")
        case (None, None) =>
          Vector(s"env var '$name' sets neither value nor secretKeyRef")

final case class SecretKeyRef(name: String, key: String)

final case class ServiceResources(
    instanceType: String = "small",
    autoscaling: Autoscaling = Autoscaling()
):
  def problems: Vector[String] =
    if InstanceType.byName(instanceType).isEmpty then
      Vector(
        s"unknown instanceType '$instanceType'; one of ${InstanceType.names.mkString(", ")}"
      )
    else autoscaling.problems

final case class Autoscaling(
    /**
     * Defaults to one replica, not Akka's three.
     *
     * Akka's default targets a managed production cluster. nakka's primary target is a development
     * cluster, where three replicas of every service is a surprise rather than a safeguard. Set it
     * explicitly for production.
     */
    minInstances: Int = 1,
    maxInstances: Int = 10,
    targetCpuPercent: Int = 80
):
  def problems: Vector[String] =
    Vector(
      Option.when(minInstances < 1)("minInstances must be at least 1"),
      Option.when(maxInstances < minInstances)(
        s"maxInstances ($maxInstances) is below minInstances ($minInstances)"
      ),
      Option.when(targetCpuPercent < 1 || targetCpuPercent > 100)(
        s"targetCpuPercent must be between 1 and 100, was $targetCpuPercent"
      )
    ).flatten

/** Named compute sizes, so a descriptor does not carry raw CPU and memory numbers. */
enum InstanceType(val name: String, val cpuMillis: Int, val memoryMiB: Int):
  case Small  extends InstanceType("small", 500, 512)
  case Medium extends InstanceType("medium", 1000, 1024)
  case Large  extends InstanceType("large", 2000, 2048)

object InstanceType:
  def byName(name: String): Option[InstanceType] = values.find(_.name == name)
  def names: Vector[String]                      = values.toVector.map(_.name)

/**
 * What the control plane observes.
 *
 * `Ready`, `UpdateInProgress`, `PartiallyReady` and `Unavailable` mirror Akka's states.
 * `NotDeployed`, `Paused` and `Failed` are additions: nakka needs to distinguish a service that has
 * never been applied, one deliberately stopped, and one whose deployment gave up — Akka's four
 * states cannot express those.
 */
enum ServiceLifecycle:
  case NotDeployed
  case UpdateInProgress
  case Ready
  case PartiallyReady
  case Unavailable
  case Paused
  case Failed

object ServiceLifecycle:

  def byName(name: String): Option[ServiceLifecycle] = values.find(_.toString == name)

  /**
   * Encodes as a plain string, not as `{"type":"Ready"}`.
   *
   * nakka's shared codec config sets a discriminator field, which is right for events — an
   * `ItemAdded` needs to say what it is. Applied to a case-object-only enum it produces a wrapper
   * object for what is conceptually one word, and this particular word is the field an operator
   * reads most often. Defined in the companion so it is in implicit scope wherever the enum
   * appears, rather than at each derivation site where one could be missed and two wire formats
   * result.
   */
  given codec: JsonValueCodec[ServiceLifecycle] = new JsonValueCodec[ServiceLifecycle]:
    def decodeValue(in: JsonReader, default: ServiceLifecycle): ServiceLifecycle =
      val name = in.readString(null)
      byName(name).getOrElse(in.decodeError(s"unknown service lifecycle '$name'"))

    def encodeValue(x: ServiceLifecycle, out: JsonWriter): Unit = out.writeVal(x.toString)

    def nullValue: ServiceLifecycle = null

/** A service as the CLI sees it. */
final case class ServiceStatus(
    name: String,
    projectId: String,
    lifecycle: ServiceLifecycle,
    /** Increments on every apply, so an observation can be tied to a desired state. */
    generation: Long,
    image: String,
    readyInstances: Int,
    desiredInstances: Int,
    detail: Option[String] = None
)

/**
 * The body of a create request.
 *
 * Separate from the corresponding summary type so that a create cannot carry counts: an operator
 * does not get to declare how many projects an organization has.
 */
final case class CreateOrganization(name: String)

final case class CreateProject(name: String, organizationId: String)

/** The body of a rename. Structurally identical to a create, but not the same request. */
final case class Rename(name: String)

/**
 * An organization exactly as its own entity knows it.
 *
 * Split from `OrganizationSummary` because the count in a summary is not the entity's to state — it
 * comes from counting projects, which only a view can do. A type with a field its producer has to
 * fill with a placeholder zero is a type that will eventually be read as if the zero meant
 * something.
 */
final case class OrganizationDetail(id: String, name: String)

final case class ProjectDetail(id: String, name: String, organizationId: String)

/** A detail plus the counts a listing needs, composed where both are available. */
final case class OrganizationSummary(id: String, name: String, projects: Int)

final case class ProjectSummary(id: String, name: String, organizationId: String, services: Int)

object OrganizationSummary:
  def of(detail: OrganizationDetail, projects: Int): OrganizationSummary =
    OrganizationSummary(detail.id, detail.name, projects)

object ProjectSummary:
  def of(detail: ProjectDetail, services: Int): ProjectSummary =
    ProjectSummary(detail.id, detail.name, detail.organizationId, services)

/**
 * Codecs live beside the types, so the CLI and the control plane cannot disagree about the wire
 * format.
 *
 * `nakka.core.Codecs.make` is reused rather than reimplemented: it inlines the shared discriminator
 * configuration at the call site, which is what jsoniter requires, and it keeps one JSON convention
 * across the whole project. `core` is Pekko-free, so depending on it costs the CLI nothing.
 *
 * Named explicitly because anonymous givens for `Vector[X]` all synthesise the same name and
 * collide.
 */
object Wire:
  given descriptorCodec: JsonValueCodec[ServiceDescriptor] = Codecs.make[ServiceDescriptor]
  given specCodec: JsonValueCodec[ServiceSpec]             = Codecs.make[ServiceSpec]
  given statusCodec: JsonValueCodec[ServiceStatus]         = Codecs.make[ServiceStatus]
  given projectCodec: JsonValueCodec[ProjectSummary]       = Codecs.make[ProjectSummary]
  given renameCodec: JsonValueCodec[Rename]                = Codecs.make[Rename]
  given orgDetailCodec: JsonValueCodec[OrganizationDetail] = Codecs.make[OrganizationDetail]
  given projectDetailCodec: JsonValueCodec[ProjectDetail]  = Codecs.make[ProjectDetail]

  given createOrgCodec: JsonValueCodec[CreateOrganization] = Codecs.make[CreateOrganization]
  given createProjectCodec: JsonValueCodec[CreateProject]  = Codecs.make[CreateProject]

  given organizationCodec: JsonValueCodec[OrganizationSummary] =
    Codecs.make[OrganizationSummary]

  given statusListCodec: JsonValueCodec[Vector[ServiceStatus]] =
    Codecs.make[Vector[ServiceStatus]]

  given projectListCodec: JsonValueCodec[Vector[ProjectSummary]] =
    Codecs.make[Vector[ProjectSummary]]

  given organizationListCodec: JsonValueCodec[Vector[OrganizationSummary]] =
    Codecs.make[Vector[OrganizationSummary]]
