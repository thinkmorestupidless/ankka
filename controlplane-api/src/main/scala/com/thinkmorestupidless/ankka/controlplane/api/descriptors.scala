package com.thinkmorestupidless.ankka.controlplane.api

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import com.thinkmorestupidless.ankka.core.Codecs

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

  /** Why a name cannot be a service name, or nothing — the one rule, shared with `ankka init`. */
  def nameProblems(name: String): Vector[String] =
    ServiceDescriptor(name, ServiceSpec("x")).problems
      .filter(_.startsWith("service name"))

  /**
   * Kubernetes DNS label rules.
   *
   * The name becomes a Deployment and Service name, so a descriptor that cannot be expressed in
   * Kubernetes should be refused when it is applied rather than when the reconciler tries to render
   * it.
   */
  private val ValidName = "[a-z]([-a-z0-9]{0,61}[a-z0-9])?".r

/**
 * Validation for a project id.
 *
 * A project id becomes part of a Kubernetes namespace name (`{prefix}-{projectId}`), so it has to
 * be expressible as a DNS label. `ServiceKey`'s doc comment has always claimed project ids and
 * service names are both DNS labels, but only the service name was ever checked — this closes that.
 *
 * Lives here, beside `ServiceDescriptor.ValidName`, so the CLI rejects a bad id before the round
 * trip using the same code the server runs.
 */
object ProjectId:

  private val Valid = "[a-z]([-a-z0-9]{0,61}[a-z0-9])?".r

  /**
   * Conservative, because this side cannot see the server's namespace prefix.
   *
   * 63 is the DNS label ceiling; the default prefix `ankka` plus a separator takes six. A longer
   * prefix narrows it further, which the server checks when it projects — this bound catches the
   * obvious case early rather than being the only check.
   */
  val MaxLength: Int = 63 - "ankka".length - 1

  def problems(id: String): Vector[String] =
    if id.isEmpty then Vector("project id must not be empty")
    else if id.length > MaxLength then
      Vector(s"project id '$id' is ${id.length} characters, over the $MaxLength character limit")
    else if !Valid.matches(id) then
      Vector(
        s"project id '$id' is invalid: lowercase letters, digits and '-', starting with a letter"
      )
    else Vector.empty

  def isValid(id: String): Boolean = problems(id).isEmpty

final case class ServiceSpec(
    image: String,
    env: Vector[EnvVar] = Vector.empty,
    labels: Map[String, String] = Map.empty,
    annotations: Map[String, String] = Map.empty,
    resources: ServiceResources = ServiceResources(),
    /**
     * Whether this service serves HTTP at all.
     *
     * A boolean rather than an optional `port`, and not for taste: under ankka's shared codec
     * config jsoniter reads a JSON `null` as *absent* and applies the field's default, so
     * `{"port": null}` on an `Option[Int]` defaulting to `Some(9000)` decodes as 9000. Absent and
     * `null` cannot be told apart, which makes "serves none" something that has to be said
     * positively. `DescriptorSuite` pins that behaviour.
     */
    http: Boolean = true,
    /**
     * The port the workload listens on. Ignored when `http` is false.
     *
     * The default is `com.thinkmorestupidless.ankka.http.port`'s own, so a descriptor that says
     * nothing gets the behaviour the runtime already had. `0` is not "none": `HttpServer.at`
     * already gives it a meaning — pick a free port — and a second, opposite one here would be a
     * trap.
     */
    port: Int = ServiceSpec.DefaultPort,
    /**
     * The ankka version the image was built against — `"0.2.0"`, the same value as the build's
     * `ankkaVersion` (the template writes both from one parameter). Absent means unchecked: every
     * descriptor written before this field existed stays valid and silent. Present, it is compared
     * against the platform's own version when the service is projected (`Compatibility`), and an
     * unsupported one is reported — naming both — rather than run against a schema it may not
     * match. A declaration, not a measurement: the runtime also logs and serves its version, but
     * the platform must be able to refuse before anything starts (feature 006, research R3).
     */
    runtime: Option[String] = None,
    /**
     * Where the developer's code runs (feature 009). `embedded`: the image is an ankka service and
     * the JVM in it is the node. `process`: the image is a process in another language, and the
     * platform runs the sidecar — ankka's own runtime — beside it; the descriptor cannot name the
     * sidecar's image or set its variables, by the same rule that refuses the cluster's variables.
     */
    hosting: String = ServiceSpec.Embedded,
    /**
     * The sidecar protocol version the image's SDK speaks — `"1.0"`. Required with `process`
     * hosting, meaningless with `embedded`. Checked against the platform's own when the service is
     * projected (`Compatibility.supportsProtocol`): same major, minor not above.
     */
    protocol: Option[String] = None
):

  /** The declared runtime, parsed; `None` when undeclared; the problem text when malformed. */
  def declaredRuntime: Option[Either[String, Version]] = runtime.map(Version.parse)

  def isProcessHosted: Boolean = hosting == ServiceSpec.Process

  /** The declared protocol, parsed; `None` when undeclared; the problem text when malformed. */
  def declaredProtocol: Option[Either[String, ProtocolVersion]] =
    protocol.map(ProtocolVersion.parse)

  /**
   * The one value everything downstream sees.
   *
   * The custom resource and the operator never learn that two fields exist. The container port, the
   * injected `ANKKA_HTTP_PORT`, the readiness probe and the Service's target all come from this, so
   * they have nothing to disagree with.
   */
  def resolvedPort: Option[Int] = Option.when(http)(port)

  def problems: Vector[String] =
    val imageProblems =
      if image.isEmpty then Vector("service image must not be empty") else Vector.empty
    val runtimeProblems = declaredRuntime.flatMap(_.left.toOption).map("runtime " + _).toVector
    val envProblems     = env.flatMap(_.problems)
    // Both unconditional — checked when `http` is false too. A nonsense port is nonsense whether
    // or not it is used, and one rule with no exceptions is one nobody has to remember.
    val portProblems =
      Option
        .when(port < 1 || port > 65535)(s"service port $port is outside the range 1-65535")
        .toVector
    // By name, never value: the value may come from a secret, so only the name is inspectable —
    // the same shape as the ANKKA_DB_* escape hatch. The `port` field is the only way to set the
    // runtime's port; a descriptor with two ways to say one thing is refused rather than quietly
    // resolved in favour of one of them.
    val portEnvProblems =
      Option
        .when(env.exists(_.name == ServiceSpec.PortEnvVar))(
          s"env var '${ServiceSpec.PortEnvVar}' conflicts with the service port; " +
            "declare the port instead"
        )
        .toVector
    // The same rule for the variables that tell a node where it is running and how to find its
    // peers: the platform sets them, and a descriptor that sets them too is two sources of truth
    // for one fact — refused, not resolved in favour of one of them.
    val platformEnvProblems =
      env
        .filter(e =>
          ServiceSpec.PlatformEnvVars.contains(e.name) || ServiceSpec.SidecarEnvVars
            .contains(e.name)
        )
        .map(e => s"env var '${e.name}' is set by the platform and cannot be declared")
    val hostingProblems =
      if hosting != ServiceSpec.Embedded && hosting != ServiceSpec.Process then
        Vector(
          s"hosting must be \"${ServiceSpec.Embedded}\" or \"${ServiceSpec.Process}\", not \"$hosting\""
        )
      else if isProcessHosted && protocol.isEmpty then
        Vector("protocol must be declared for process hosting")
      else if !isProcessHosted && protocol.nonEmpty then
        Vector("protocol is meaningful only for process hosting")
      else Vector.empty
    val protocolProblems = declaredProtocol.flatMap(_.left.toOption).map("protocol " + _).toVector
    runtimeProblems ++ imageProblems ++ envProblems ++ portProblems ++ portEnvProblems ++ platformEnvProblems ++
      hostingProblems ++ protocolProblems ++ resources.problems

object ServiceSpec:
  /**
   * `com.thinkmorestupidless.ankka.http.port`'s default in `modules/http`'s `reference.conf`.
   * Adopted, not chosen.
   */
  val DefaultPort: Int = 9000

  /** What the runtime reads its port from, and what the operator therefore injects. */
  val PortEnvVar: String = "ANKKA_HTTP_PORT"

  /**
   * What the platform tells a deployed node about where it is running (feature 004). Set by the
   * operator on every workload; a descriptor may not set them. `ANKKA_HTTP_PORT` has its own rule
   * above, with its own message, because it has a field to point the user at.
   */
  val PlatformEnvVars: Set[String] = Set(
    "ANKKA_CLUSTER_MODE",
    "POD_IP",
    "ANKKA_CLUSTER_SERVICE",
    "ANKKA_CLUSTER_POD_SELECTOR",
    "ANKKA_CLUSTER_CONTACT_POINTS"
  )

  val Embedded: String = "embedded"
  val Process: String  = "process"

  /**
   * How the sidecar and the process find each other (feature 009). The operator sets them on the
   * two containers; a descriptor may not.
   */
  val SidecarEnvVars: Set[String] = Set(
    "ANKKA_PROCESS_PORT",
    "ANKKA_PROCESS_ADDRESS",
    "ANKKA_SIDECAR_PORT",
    "ANKKA_SIDECAR_ADDRESS",
    "ANKKA_SIDECAR_BIND"
  )

  /**
   * A descriptor's variables that belong on the sidecar rather than the process: a model's key and
   * configuration, because the sidecar runs the agent loop and the process never calls a model.
   * Prefixes, matched by the operator when it splits the environment.
   */
  val SidecarEnvPrefixes: Vector[String] = Vector("ANTHROPIC_", "ANKKA_MODEL_", "ANKKA_DB_")

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
     * Akka's default targets a managed production cluster. ankka's primary target is a development
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
 * `NotDeployed`, `Paused` and `Failed` are additions: ankka needs to distinguish a service that has
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

  /**
   * Stopped by its organization being disabled (feature 008) — not by its members, unlike `Paused`.
   */
  case Suspended

object ServiceLifecycle:

  def byName(name: String): Option[ServiceLifecycle] = values.find(_.toString == name)

  /**
   * Encodes as a plain string, not as `{"type":"Ready"}`.
   *
   * ankka's shared codec config sets a discriminator field, which is right for events — an
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
    detail: Option[String] = None,
    /**
     * Whether this describes a confirmed observation of the cluster.
     *
     * `false` means the control plane could not confirm it and is restating what it last knew, or
     * that nothing has reported on the service at all. A field rather than detail text because
     * `services list` prints a table that drops `detail`, and an operator scanning that table is
     * exactly who needs to know a `Ready` is not current.
     */
    confirmed: Boolean = true,
    /**
     * A short, human phrase for what the platform did about this service's database —
     * `"provisioned"`, `"supplied"`, `"recovered existing data"`, `"waiting for database"` — or
     * `None` before anything has reported. Deliberately a phrase, not the operator's full
     * `DatabaseStatus`: the CLI's job is to say which path was taken, not to mirror a Kubernetes
     * status the CLI has no other reason to know the shape of.
     */
    database: Option[String] = None,
    /**
     * Where the service answers from outside the cluster, as a full URL — `https://` and the
     * platform-derived hostname — or `None` while it is not exposed. A URL rather than a bare
     * hostname so a client can use it verbatim; the scheme is always `https`.
     */
    hostname: Option[String] = None,
    /**
     * Whether the operator asked for the service to be reachable from outside. Distinct from
     * `hostname` being present: an exposed service on a control plane with no base domain
     * configured is exposed and has no hostname, and the CLI should say so rather than show `-`.
     */
    exposed: Boolean = false,
    /**
     * Whether the service's organization is disabled and it has been stopped for it (feature 008).
     */
    suspended: Boolean = false,
    /**
     * Whether its members paused it. Beside `lifecycle` because the listing view needs to tell a
     * member's pause from an operator *reporting* `Paused`: a stale report landing just after a
     * resume otherwise leaves the listing saying Paused while the entity says Ready.
     */
    paused: Boolean = false,
    /** `embedded` or `process` (feature 009): where the developer's code runs. Display only. */
    hosting: String = "embedded",
    /** The sidecar protocol the service declared, for a process-hosted one. Display only. */
    protocol: Option[String] = None
)

/** Who did what to a service, and when: `GET /services/{project}/{name}/history` (feature 008). */
final case class HistoryActor(
    subject: String,
    display: Option[String] = None,
    administrative: Boolean = false
)

final case class HistoryEntry(
    kind: String,
    generation: Long,
    actor: Option[HistoryActor] = None,
    at: Option[java.time.Instant] = None
)

// ── Identity (feature 008) ─────────────────────────────────────────────────

/**
 * What `ankka login` needs to start, and nothing else: served without a credential at `GET /auth`.
 * The issuer is public by nature (every token names it) and the client id is a public client's.
 */
final case class AuthDiscovery(issuer: String, clientId: String, audience: String)

/** A caller's role in one organization. */
enum Role:
  case Owner
  case Member

object Role:
  def byName(name: String): Option[Role] = name.trim.toLowerCase match
    case "owner"  => Some(Owner)
    case "member" => Some(Member)
    case _        => None

  def name(role: Role): String = role match
    case Owner  => "owner"
    case Member => "member"

  /**
   * Encodes as `"owner"` / `"member"`, not `{"type":"Owner"}` — the same reasoning, and the same
   * placement in the companion, as `ServiceLifecycle`'s codec.
   */
  given codec: JsonValueCodec[Role] = new JsonValueCodec[Role]:
    def decodeValue(in: JsonReader, default: Role): Role =
      val text = in.readString(null)
      byName(text).getOrElse(in.decodeError(s"unknown role '$text'; one of owner, member"))
    def encodeValue(x: Role, out: JsonWriter): Unit = out.writeVal(name(x))
    def nullValue: Role                             = null

/** One of the caller's organizations, with their role in it. */
final case class OrganizationMembership(id: String, name: String, role: Role)

/** The caller, as the control plane sees them: `GET /auth/whoami`, and `ankka whoami`. */
final case class Whoami(
    subject: String,
    name: Option[String] = None,
    email: Option[String] = None,
    emailVerified: Boolean = false,
    platformAdmin: Boolean = false,
    organizations: Vector[OrganizationMembership] = Vector.empty
)

/**
 * The body of a create request.
 *
 * Separate from the corresponding summary type so that a create cannot carry counts: an operator
 * does not get to declare how many projects an organization has.
 */
final case class CreateOrganization(name: String, owner: Option[Owner] = None)

/**
 * The first owner of an organization created *for* someone: a platform administrator provisioning a
 * tenant names the subject that will own it, so the organization is never, even briefly, the
 * administrator's. `email` and `display` are what the members listing shows until the owner logs
 * in; only `subject` is a key. Given by anyone without the administrator role, the create is
 * refused.
 */
final case class Owner(
    subject: String,
    email: Option[String] = None,
    display: Option[String] = None
)

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
final case class OrganizationDetail(id: String, name: String, disabled: Boolean = false)

final case class ProjectDetail(id: String, name: String, organizationId: String)

/**
 * A detail plus the counts a listing needs, composed where both are available — and, since feature
 * 008, whether the organization is disabled and the *caller's* role in it (`None` for a platform
 * administrator looking at an organization they are not a member of).
 */
final case class OrganizationSummary(
    id: String,
    name: String,
    projects: Int,
    disabled: Boolean = false,
    role: Option[Role] = None
)

final case class ProjectSummary(id: String, name: String, organizationId: String, services: Int)

object OrganizationSummary:
  def of(
      detail: OrganizationDetail,
      projects: Int,
      role: Option[Role] = None
  ): OrganizationSummary =
    OrganizationSummary(detail.id, detail.name, projects, detail.disabled, role)

// ── Membership (feature 008) ───────────────────────────────────────────────

/**
 * `POST /organizations/{id}/members`: invite an email address, claimed on its first verified login.
 */
final case class Invite(email: String, role: Role = Role.Member)

/** `PUT /organizations/{id}/members/{subject}/role`. */
final case class RoleChange(role: Role)

/**
 * `POST /organizations/{id}/members/{subject}/repair` — a platform administrator adding a member
 * directly.
 */
final case class Repair(role: Role = Role.Owner)

final case class MemberSummary(
    subject: String,
    role: Role,
    email: Option[String] = None,
    display: Option[String] = None,
    since: Option[java.time.Instant] = None,
    /** Who invited or added them — an actor's display, never a key. */
    addedBy: Option[String] = None
)

final case class InvitationSummary(
    email: String,
    role: Role,
    invitedAt: Option[java.time.Instant] = None,
    invitedBy: Option[String] = None
)

final case class MembersResponse(
    members: Vector[MemberSummary] = Vector.empty,
    invitations: Vector[InvitationSummary] = Vector.empty
)

object ProjectSummary:
  def of(detail: ProjectDetail, services: Int): ProjectSummary =
    ProjectSummary(detail.id, detail.name, detail.organizationId, services)

/**
 * Codecs live beside the types, so the CLI and the control plane cannot disagree about the wire
 * format.
 *
 * `com.thinkmorestupidless.ankka.core.Codecs.make` is reused rather than reimplemented: it inlines
 * the shared discriminator configuration at the call site, which is what jsoniter requires, and it
 * keeps one JSON convention across the whole project. `core` is Pekko-free, so depending on it
 * costs the CLI nothing.
 *
 * Named explicitly because anonymous givens for `Vector[X]` all synthesise the same name and
 * collide.
 */
/**
 * One instance's output, as the platform received it.
 *
 * `error` rather than an exception because a service with several instances may have one that
 * cannot be read — restarting, or just gone — and losing the other instances' output to report that
 * would be the wrong trade. Each instance says for itself whether it could be read.
 */
final case class InstanceLogs(instance: String, output: String, error: Option[String])

/**
 * A service's output, per instance.
 *
 * Always a list, even for the single-instance case, because which instance produced a line is
 * exactly what a reader needs when a service runs several and only one is misbehaving.
 */
final case class LogsResponse(instances: Vector[InstanceLogs])

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

  given instanceLogsCodec: JsonValueCodec[InstanceLogs] = Codecs.make[InstanceLogs]
  given logsCodec: JsonValueCodec[LogsResponse]         = Codecs.make[LogsResponse]

  given authDiscoveryCodec: JsonValueCodec[AuthDiscovery]  = Codecs.make[AuthDiscovery]
  given whoamiCodec: JsonValueCodec[Whoami]                = Codecs.make[Whoami]
  given inviteCodec: JsonValueCodec[Invite]                = Codecs.make[Invite]
  given roleChangeCodec: JsonValueCodec[RoleChange]        = Codecs.make[RoleChange]
  given repairCodec: JsonValueCodec[Repair]                = Codecs.make[Repair]
  given membersCodec: JsonValueCodec[MembersResponse]      = Codecs.make[MembersResponse]
  given historyCodec: JsonValueCodec[Vector[HistoryEntry]] = Codecs.make[Vector[HistoryEntry]]
