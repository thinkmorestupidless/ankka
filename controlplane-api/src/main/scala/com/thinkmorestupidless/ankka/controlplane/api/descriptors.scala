package com.thinkmorestupidless.ankka.controlplane.api

import com.thinkmorestupidless.ankka.core.graph.GraphJson

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import com.thinkmorestupidless.ankka.core.{Codecs, Contract, PlatformVariables}

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
    // A service that serves gRPC has a second, headless address, `<name>-grpc-peers`, and an
    // address is a DNS label of at most 63 characters.
    val grpcNameProblems =
      Option
        .when(service.grpc && name.length > ServiceDescriptor.MaxGrpcName)(
          s"service name '$name' is ${name.length} characters; a service that serves gRPC has a " +
            s"name of at most ${ServiceDescriptor.MaxGrpcName}"
        )
        .toVector
    // The one rule that needs the name as well as the spec: a mount of the service itself would
    // pass a request to the proxy that is passing it, for ever.
    val selfMountProblems =
      service.mounts
        .filter(m => service.isWebHosted && m.service == name)
        .map(m => s"mount '${m.path}': a web-hosted service cannot mount itself")
    nameProblems ++ grpcNameProblems ++ service.problems ++ selfMountProblems

  def isValid: Boolean = problems.isEmpty

  /**
   * Lowercase hex SHA-256 of the descriptor's wire form: two descriptors share one exactly when
   * they state the same things (feature 033).
   *
   * The codec writes fields in declaration order and omits defaults, so the one thing left to
   * settle is the maps, which iterate in insertion order: labels or annotations reordered in a file
   * are the same service. Variables keep their order, because Kubernetes expands `$(VAR)` in order.
   */
  def digest: String =
    def sorted(map: Map[String, String]) = scala.collection.immutable.ListMap.from(map.toSeq.sorted)
    val canonical = copy(service =
      service.copy(labels = sorted(service.labels), annotations = sorted(service.annotations))
    )
    val bytes = com.github.plokhotnyuk.jsoniter_scala.core
      .writeToArray(canonical)(using Wire.descriptorCodec)
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(b => f"${b & 0xff}%02x")
      .mkString

object ServiceDescriptor:

  /** 63, less `-grpc-peers`: the longest name whose headless gRPC address is still a DNS label. */
  val MaxGrpcName: Int = 52

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

  /** Whether `text` could be a service's name, or a project's: a DNS label. */
  private[api] def isName(text: String): Boolean = ValidName.matches(text)

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

  /**
   * Ids no project may take, because the platform's own workloads use them.
   *
   * A workload's identity is `ankka://<project>/<service>`, read from its certificate, and it is
   * the whole of what another service's ACL or a platform listener trusts. The control plane is
   * `ankka://platform/controlplane` and the console `ankka://platform/console`, so a tenant project
   * called `platform` with a service called `controlplane` would be issued the control plane's
   * identity. The operator refuses the same ids (`Names.ReservedProjectIds`), since a resource can
   * be written by something other than the control plane, and `ReservedProjectIdsSuite` holds the
   * two lists to each other and to the identities the platform's manifests ask for.
   *
   * `local` is reserved for the other reason a project's id is part of a name: a service run on a
   * developer's machine that states its name reads a topic under the group
   * `ankka.local.<service>.…`, which is exactly what a deployed service in a project called `local`
   * would be given. Two different services must never share a group.
   *
   * `cloud-provider` is reserved because a project's namespace is `ankka-<project>`, and the
   * installation's cloud provider runs in `ankka-cloud-provider` (feature 044).
   */
  val Reserved: Set[String] = Set("platform", "local", "cloud-provider")

  /** Why `id` is reserved, in a sentence that is true of it. */
  def reservedBecause(id: String): String =
    if id == "local" then
      s"project id '$id' is reserved for services run locally, whose consumer groups it names"
    else if id == "cloud-provider" then
      s"project id '$id' is reserved for the namespace the installation's cloud provider runs in"
    else s"project id '$id' is reserved for the platform's own workloads"

  def problems(id: String): Vector[String] =
    if id.isEmpty then Vector("project id must not be empty")
    else if id.length > MaxLength then
      Vector(s"project id '$id' is ${id.length} characters, over the $MaxLength character limit")
    else if !Valid.matches(id) then
      Vector(
        s"project id '$id' is invalid: lowercase letters, digits and '-', starting with a letter"
      )
    else if Reserved.contains(id) then Vector(reservedBecause(id))
    else Vector.empty

  def isValid(id: String): Boolean = problems(id).isEmpty

final case class ServiceSpec(
    image: String,
    env: Vector[EnvVar] = Vector.empty,
    labels: Map[String, String] = Map.empty,
    annotations: Map[String, String] = Map.empty,
    resources: ServiceResources = ServiceResources(),
    /**
     * Feature 037: `"none"` for a service with no database at all, such as one made of consumers
     * alone; nothing is provisioned, and the runtime refuses an entity, a view, a workflow or a
     * timed action in it. Absent means the platform's, or a supplied one through `ANKKA_DB_*`.
     */
    database: Option[String] = None,
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
     * `wasm`: the image carries a WebAssembly module, and the platform runs its own runtime with
     * the module loaded into it — one container, the module delivered by running the image once.
     */
    hosting: String = ServiceSpec.Embedded,
    /**
     * The sidecar protocol version the image's SDK speaks — `"1.0"`. Required with `process` or
     * `wasm` hosting, meaningless with `embedded`. Checked against the platform's own when the
     * service is projected (`Compatibility.supportsProtocol`): same major, minor not above.
     */
    protocol: Option[String] = None,
    /**
     * Whether this service serves gRPC. Saying nothing means it serves none, and a service that
     * says nothing is deployed exactly as it was before gRPC endpoints existed.
     *
     * A boolean beside a plain port, for the reason `http` is one: a JSON `null` on an `Option`
     * reads as absent, so "serves none" has to be said positively. Only an embedded service can
     * serve gRPC; the SDKs in other languages declare no gRPC endpoint.
     */
    grpc: Boolean = false,
    /**
     * The port the workload serves gRPC on. Ignored when `grpc` is false; must differ from `port`
     * when both are served. The default is `ankka.grpc.port`'s own.
     */
    grpcPort: Int = ServiceSpec.DefaultGrpcPort,
    /**
     * Paths of a web-hosted service answered by another service of its project (feature 021). The
     * service's proxy passes a request under one to that service, which is told the request came
     * from the internet. Meaningful only for web hosting.
     */
    mounts: Vector[Mount] = Vector.empty,
    /**
     * The services a web-hosted service's proxy admits beside the internet and itself:
     * `"<service>"` in its own project, `"<project>/<service>"`, or `"*"` for every service of its
     * project (feature 021). Meaningful only for web hosting.
     */
    callers: Vector[String] = Vector.empty,
    /**
     * The port a web-hosted service's own program listens on, told to it as `PORT` (feature 021).
     * Absent means the platform's default, `DefaultProcessPort`. An `Option` defaulting to `None`,
     * the one shape the codec's reading of `null` as absent cannot turn into something else.
     */
    processPort: Option[Int] = None,
    /**
     * Whether the platform gives this service a bucket in the installation's object store, and a
     * storage credential that reaches it and nothing else (feature 034). The developer's program is
     * told where it is as `ANKKA_S3_*` variables; a descriptor that gives one of those itself has
     * an object store of its own. A positive boolean, for the reason `http` is one.
     */
    provisionObjectStorage: Boolean = false,
    /**
     * Whether that bucket is reachable from the internet, at the store's hostname, so the service
     * can give a browser URLs it signs (feature 034). Only with `provisionObjectStorage`.
     */
    exposeObjectStorage: Boolean = false
):

  /** The declared runtime, parsed; `None` when undeclared; the problem text when malformed. */
  def declaredRuntime: Option[Either[String, Version]] = runtime.map(Version.parse)

  def isProcessHosted: Boolean = hosting == ServiceSpec.Process

  /** A WebAssembly module the platform's runtime loads, rather than an image that is the node. */
  def isModuleHosted: Boolean = hosting == ServiceSpec.Wasm

  /** Written in another language: a process beside the runtime, or a module inside it. */
  def isPolyglot: Boolean = isProcessHosted || isModuleHosted

  /** Any program that serves HTTP, beside the platform's proxy (feature 021). */
  def isWebHosted: Boolean = hosting == ServiceSpec.Web

  /**
   * Whether the descriptor names a broker of its own (feature 027): any variable whose name starts
   * `ANKKA_KAFKA_`. By name, never value, as a supplied database is, so a variable taken from a
   * secret counts.
   */
  def suppliesBroker: Boolean = env.exists(_.name.startsWith(ServiceSpec.BrokerVariablePrefix))

  /** Feature 037: the service declares it has no database at all. */
  def hasNoDatabase: Boolean = database.contains(ServiceSpec.NoDatabase)

  private def databaseProblems: Vector[String] =
    database.toVector.flatMap { value =>
      val unknown = Option.when(value != ServiceSpec.NoDatabase)(
        s"database '$value' is not a choice; leave it out, or say \"none\" for a service with no database"
      )
      val supplied = Option.when(
        value == ServiceSpec.NoDatabase && env.exists(_.name.startsWith("ANKKA_DB_"))
      )("database \"none\" and an ANKKA_DB_ variable: a service with no database supplies none")
      unknown.toVector ++ supplied.toVector
    }

  /** The port a web-hosted service's program is told to listen on; `None` for any other hosting. */
  def resolvedProcessPort: Option[Int] =
    Option.when(isWebHosted)(processPort.getOrElse(ServiceSpec.DefaultProcessPort))

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

  /** `resolvedPort`'s twin: the one value the resource, and so the operator, sees. */
  def resolvedGrpcPort: Option[Int] = Option.when(grpc)(grpcPort)

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
        .when(env.exists(_.name == PlatformVariables.HttpPort))(
          s"env var '${PlatformVariables.HttpPort}' conflicts with the service port; " +
            "declare the port instead"
        )
        .toVector
    // The same rule for the variables that tell a node where it is running and how to find its
    // peers: the platform sets them, and a descriptor that sets them too is two sources of truth
    // for one fact — refused, not resolved in favour of one of them. The port has its own message
    // above, because it has a field to point the user at.
    val platformEnvProblems =
      env
        .filter(e =>
          PlatformVariables.platformOnly(e.name) &&
            e.name != PlatformVariables.HttpPort && e.name != PlatformVariables.GrpcPort
        )
        .map(e => s"env var '${e.name}' is set by the platform and cannot be declared")
    // Any hosting: a Secret the platform issued holds a certificate's key or an authority's, and a
    // variable taken from one would hand a workload an identity that is not its own.
    val secretProblems =
      env.flatMap(e =>
        e.secretKeyRef
          .filter(ref => ServiceSpec.isPlatformSecret(ref.name))
          .map(ref =>
            s"env var '${e.name}': secret '${ref.name}' is issued by the platform and cannot " +
              "be read by a service"
          )
      )
    // A deployed service's name is read from the certificate the platform issued it, never from its
    // environment, so that no service can name its consumer groups as another's. The variable is how
    // a service run on a developer's machine states its name; in a descriptor it would be read by
    // nothing, and a variable that looks as if it renames a service and does not is worse than one
    // that cannot be set.
    val serviceNameEnvProblems =
      env
        .filter(_.name == PlatformVariables.ServiceName)
        .map(e =>
          s"env var '${e.name}' names a service run locally; a deployed service's name comes " +
            "from the platform, so it cannot be declared"
        )
    val hostingProblems =
      if !ServiceSpec.Hostings.contains(hosting) then
        Vector(
          s"hosting must be \"${ServiceSpec.Embedded}\", \"${ServiceSpec.Process}\", " +
            s"\"${ServiceSpec.Wasm}\" or \"${ServiceSpec.Web}\", not \"$hosting\""
        )
      else if isPolyglot && protocol.isEmpty then
        Vector(s"protocol must be declared for $hosting hosting")
      else if !isPolyglot && protocol.nonEmpty then
        Vector("protocol is meaningful only for process or wasm hosting")
      else Vector.empty
    // The runtime a module is loaded into is the platform's, and it serves the module's routes.
    val moduleProblems =
      Option
        .when(isModuleHosted && !http)(
          "a wasm service's runtime serves HTTP; remove \"http\": false"
        )
        .toVector
    val protocolProblems = declaredProtocol.flatMap(_.left.toOption).map("protocol " + _).toVector
    // The same shape as the HTTP port's rules: the range is checked whether or not gRPC is served,
    // the variable is refused by name, and the field is the only way to say it.
    val grpcProblems =
      Option
        .when(grpcPort < 1 || grpcPort > 65535)(
          s"service grpcPort $grpcPort is outside the range 1-65535"
        )
        .toVector ++
        Option
          .when(grpc && http && grpcPort == port)(
            s"grpcPort $grpcPort is also the service port; gRPC and HTTP are served on different ports"
          )
          .toVector ++
        Option
          .when(env.exists(_.name == PlatformVariables.GrpcPort))(
            s"env var '${PlatformVariables.GrpcPort}' conflicts with the service grpcPort; " +
              "declare the grpcPort instead"
          )
          .toVector ++
        // A process or a module has no gRPC endpoint to declare: the SDKs in other languages serve
        // HTTP routes only, and a port the platform opened for them would be one nothing answers.
        // A runtime too old to know the gRPC port would never be ready, and never say why.
        (for
          case Right(runtime) <- declaredRuntime.toVector if grpc
          case Right(platform) <- Vector(
            Version.parse(com.thinkmorestupidless.ankka.core.BuildInfo.version)
          )
          if !Compatibility.servesGrpc(platform, runtime)
        yield Compatibility.grpcRefusal(runtime)) ++
        Option
          .when(grpc && (isPolyglot || isWebHosted))(
            "only an embedded service serves gRPC; remove \"grpc\" or use embedded hosting"
          )
          .toVector
    runtimeProblems ++ imageProblems ++ envProblems ++ portProblems ++ portEnvProblems ++ platformEnvProblems ++
      serviceNameEnvProblems ++ secretProblems ++ hostingProblems ++ moduleProblems ++ webProblems ++
      protocolProblems ++
      grpcProblems ++ objectStorageProblems ++ resources.problems ++ databaseProblems

  /**
   * Asking the platform for a bucket, and having a store of one's own, are two different services
   * (feature 034). A bucket's name needs the project as well, so its limit is checked where the
   * project is known: the control plane's apply and its projection.
   */
  private def objectStorageProblems: Vector[String] =
    val own = env.map(_.name).filter(PlatformVariables.objectStorage)
    val both =
      if provisionObjectStorage then
        own.map(name =>
          s"provisionObjectStorage cannot be combined with env var '$name', which supplies an " +
            "object store of the service's own"
        )
      else Vector.empty
    val exposed =
      Option
        .when(exposeObjectStorage && !provisionObjectStorage)(
          "exposeObjectStorage needs provisionObjectStorage: only a bucket the platform made can " +
            "be reached from outside the cluster"
        )
        .toVector
    both ++ exposed

  /**
   * What only a web-hosted service may say, and what it may not (feature 021). Empty for a service
   * that is not web-hosted and says none of it.
   */
  private def webProblems: Vector[String] =
    if !isWebHosted then
      Vector(
        Option.when(mounts.nonEmpty)("mounts"),
        Option.when(callers.nonEmpty)("callers"),
        Option.when(processPort.nonEmpty)("processPort")
      ).flatten.map(field => s"$field is meaningful only for web hosting")
    else
      val httpProblems =
        Option
          .when(!http)("a web-hosted service's proxy serves HTTP; remove \"http\": false")
          .toVector
      val runtimeProblems =
        Option
          .when(runtime.nonEmpty)(
            "runtime is meaningful only for a service built on ankka; a web-hosted service " +
              "declares none"
          )
          .toVector
      val reservedProblems =
        env
          .filter(e => PlatformVariables.WebOnly.contains(e.name))
          .map(e => s"env var '${e.name}' is set by the platform and cannot be declared")
      val databaseProblems =
        env
          .filter(_.name.startsWith("ANKKA_DB_"))
          .map(e => s"env var '${e.name}' supplies a database, and a web-hosted service has none")
      val servicePortProblems =
        Option
          .when(ServiceSpec.ProxyPorts.contains(port))(
            s"service port $port is used by the platform's proxy"
          )
          .toVector
      val processPortProblems =
        val p = processPort.getOrElse(ServiceSpec.DefaultProcessPort)
        if p < 1 || p > 65535 then Vector(s"processPort $p is outside the range 1-65535")
        else if p == port then
          Vector(
            s"processPort $p is the service's own port; the process and the proxy cannot both " +
              "listen on it"
          )
        else if ServiceSpec.PlatformPorts.contains(p) then
          Vector(s"processPort $p is used by the platform")
        else Vector.empty
      httpProblems ++ runtimeProblems ++ reservedProblems ++ databaseProblems ++
        servicePortProblems ++ processPortProblems ++ Mount.problems(mounts) ++
        AdmittedCaller.problems(callers)

object ServiceSpec:
  /**
   * `com.thinkmorestupidless.ankka.http.port`'s default in `modules/http`'s `reference.conf`.
   * Adopted, not chosen.
   */
  val DefaultPort: Int = 9000

  /** `ankka.grpc.port`'s default in `modules/grpc`'s `reference.conf`. Adopted, not chosen. */
  val DefaultGrpcPort: Int = 9090

  // Which variables are the platform's is `core`'s `PlatformVariables`, read here, by the operator
  // and by the module host alike; this object holds no list of its own.

  val Embedded: String = "embedded"
  val Process: String  = "process"
  val Wasm: String     = "wasm"
  val Web: String      = "web"

  val Hostings: Vector[String] = Vector(Embedded, Process, Wasm, Web)

  /** The port a web-hosted service's program is told when its descriptor states none. */
  val DefaultProcessPort: Int = 8080

  /**
   * What every broker variable's name starts with (feature 027). A descriptor that gives one names
   * a broker of its own, and the platform makes nothing for it on the installation's.
   */
  val BrokerVariablePrefix: String = "ANKKA_KAFKA_"

  /** Feature 037: the one value `database` takes. */
  val NoDatabase: String = "none"

  /**
   * The ports the platform uses inside a pod, which a web-hosted service's program may not take:
   * management, readiness, observation, the proxy's calling address, and remoting.
   */
  val PlatformPorts: Set[Int] = Set(7626, 7627, 7628, 7630, 17355)

  /** The ports a web-hosted service's proxy listens on besides the service's own. */
  val ProxyPorts: Set[Int] = Set(7627, 7630)

  /**
   * The Secrets the platform issues into a project's namespace: a workload's certificates, and its
   * project database's own authorities and certificates. No descriptor of any hosting may read one
   * (feature 021): a certificate's key is an identity, and the process of a web-hosted service in
   * particular must never hold the one its proxy passes requests under a mount with.
   */
  val PlatformSecretSuffixes: Vector[String] =
    // `-telemetry`: the collector's credential, which the operator writes for each service.
    // `-storage`: a service's storage credential (feature 034), a secret key a sibling's descriptor
    // could otherwise hand to another service.
    Vector("-service-tls", "-mount-tls", "-cluster-tls", "-database-tls", "-telemetry", "-storage")

  /** The project database's cluster name, and the prefix of every Secret it is issued. */
  val PlatformSecretPrefix: String = "ankka-db"

  def isPlatformSecret(name: String): Boolean =
    PlatformSecretSuffixes.exists(name.endsWith) || name == PlatformSecretPrefix ||
      name.startsWith(PlatformSecretPrefix + "-")

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

/**
 * A path of a web-hosted service and the service of its project that answers requests under it
 * (feature 021). The proxy passes a request whose path is `path`, or lies under it, to `service`,
 * with `path` removed from its front.
 */
final case class Mount(path: String, service: String)

object Mount:

  /** A segment of a path: unreserved URL characters only. */
  private val Segment = "[A-Za-z0-9._~-]+".r

  private def segments(path: String): Vector[String] = path.split('/').toVector.drop(1)

  /** One mount's problems, then every duplicate and every mount inside another. */
  def problems(mounts: Vector[Mount]): Vector[String] =
    val shapes = mounts.flatMap { m =>
      val path =
        if !m.path.startsWith("/") then Vector(s"mount '${m.path}': a path starts with \"/\"")
        else if m.path == "/" then
          Vector(
            "mount '/': a mount cannot be every path; the process serves what no mount does"
          )
        else if m.path.endsWith("/") || !segments(m.path).forall(Segment.matches) then
          Vector(
            s"mount '${m.path}': a path is whole segments of letters, digits, \"-\", \".\", " +
              "\"_\" and \"~\", with no trailing \"/\""
          )
        else Vector.empty
      val service =
        Option
          .when(!ServiceDescriptor.isName(m.service))(
            s"mount '${m.path}': '${m.service}' is not a service name"
          )
          .toVector
      path ++ service
    }
    val paths = mounts.map(_.path).distinct
    val duplicates =
      paths
        .filter(p => mounts.count(_.path == p) > 1)
        .map(p => s"mount '$p' is declared more than once")
    val nested =
      for
        inner <- paths
        outer <- paths
        if inner != outer && segments(inner).startsWith(segments(outer))
      yield s"mount '$inner' is inside mount '$outer'"
    shapes ++ duplicates ++ nested

/** Who a web-hosted service's proxy admits beside the internet and itself (feature 021). */
enum AdmittedCaller:
  /** A service of the web-hosted service's own project: `"orders"`. */
  case Service(name: String)

  /** A service of another project: `"billing/invoices"`. */
  case ServiceIn(project: String, name: String)

  /** Every service of the web-hosted service's own project: `"*"`. */
  case AnyInProject

object AdmittedCaller:

  def parse(entry: String): Either[String, AdmittedCaller] =
    val refusal =
      s"caller '$entry' is not \"<service>\", \"<project>/<service>\" or \"*\""
    entry.split("/", -1) match
      case Array("*")                                    => Right(AnyInProject)
      case Array(name) if ServiceDescriptor.isName(name) => Right(Service(name))
      case Array(project, name)
          if ServiceDescriptor.isName(project) && ServiceDescriptor.isName(name) =>
        Right(ServiceIn(project, name))
      case _ => Left(refusal)

  /** Each entry's shape, then each entry given twice. */
  def problems(entries: Vector[String]): Vector[String] =
    entries.flatMap(parse(_).left.toOption) ++
      entries.distinct
        .filter(e => entries.count(_ == e) > 1)
        .map(e => s"caller '$e' is declared more than once")

final case class ServiceResources(
    instanceType: String = "small",
    autoscaling: Autoscaling = Autoscaling(),
    /**
     * Feature 037: what the developer's process container gets, for a process-hosted service.
     * Absent, the platform's minimum, as before.
     */
    process: Option[ProcessResources] = None
):
  def problems: Vector[String] =
    (if InstanceType.byName(instanceType).isEmpty then
       Vector(
         s"unknown instanceType '$instanceType'; one of ${InstanceType.names.mkString(", ")}"
       )
     else autoscaling.problems) ++ process.toVector.flatMap(_.problems)

/**
 * The process container's size (feature 037), as Kubernetes quantities: `cpu` such as `500m` or
 * `1`, at most 8; `memory` such as `512Mi` or `1Gi`, at most 16Gi. Requests equal limits.
 */
final case class ProcessResources(cpu: String, memory: String):
  def problems: Vector[String] =
    ProcessResources
      .cpuMillis(cpu)
      .fold(
        why => Vector(s"process cpu '$cpu': $why"),
        millis =>
          Option
            .when(millis > ProcessResources.MaxCpuMillis)(s"process cpu '$cpu' is more than 8")
            .toVector
      ) ++ ProcessResources
      .memoryMiB(memory)
      .fold(
        why => Vector(s"process memory '$memory': $why"),
        mib =>
          Option
            .when(mib > ProcessResources.MaxMemoryMiB)(
              s"process memory '$memory' is more than 16Gi"
            )
            .toVector
      )

object ProcessResources:
  val MaxCpuMillis: Int = 8000
  val MaxMemoryMiB: Int = 16 * 1024
  val DefaultCpuMillis  = 100
  val DefaultMemoryMiB  = 128

  /** `500m` or a whole or decimal number of CPUs. */
  def cpuMillis(cpu: String): Either[String, Int] =
    val text = cpu.trim
    if text.isEmpty then Left("empty")
    else if text.endsWith("m") then
      text.dropRight(1).toIntOption.filter(_ > 0).toRight("not a positive number of millicores")
    else
      text.toDoubleOption
        .filter(_ > 0)
        .map(d => math.round(d * 1000).toInt)
        .toRight("not a positive number of CPUs")

  /** `512Mi`, `1Gi`, or bytes with a binary suffix. */
  def memoryMiB(memory: String): Either[String, Int] =
    val text  = memory.trim
    val units = Map("Ki" -> 1.0 / 1024, "Mi" -> 1.0, "Gi" -> 1024.0, "Ti" -> 1024.0 * 1024)
    units.keys.find(text.endsWith) match
      case Some(unit) =>
        text
          .dropRight(unit.length)
          .toDoubleOption
          .filter(_ > 0)
          .map(n => math.ceil(n * units(unit)).toInt)
          .toRight(s"not a positive number of $unit")
      case None => Left("needs a binary unit: Ki, Mi, Gi or Ti")

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

/**
 * One of a web-hosted service's mounts, with what is behind it (feature 021). `state` is `ok`,
 * `no service`, `serves no HTTP` or `paused` when one service is read, since the endpoint can ask
 * the mounted service's entity; it is empty in a listing, whose rows cannot.
 */
final case class MountStatus(path: String, service: String, state: String = "")

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
    /**
     * `embedded`, `process` (feature 009), `wasm` (feature 016) or `web` (feature 021): where the
     * developer's code runs. Display only.
     */
    hosting: String = "embedded",
    /** The protocol the service declared, for a process- or module-hosted one. Display only. */
    protocol: Option[String] = None,
    /** A web-hosted service's mounts, each with what is behind it (feature 021). */
    mounts: Vector[MountStatus] = Vector.empty,
    /**
     * The services a web-hosted service admits beside the internet, as the descriptor wrote them.
     */
    callers: Vector[String] = Vector.empty,
    /** The port a web-hosted service's process listens on, stated or defaulted. */
    processPort: Option[Int] = None,
    /**
     * A short phrase for what the platform did about this service's credential on the
     * installation's broker (feature 027) — `"provisioned"`, `"supplied"`, `"waiting for broker"` —
     * or `None` when there is nothing to report: a web-hosted service, or an installation with no
     * broker. A phrase, as `database` is.
     */
    broker: Option[String] = None,
    /**
     * The topics this service's components read or publish to that its project does not declare, by
     * the names the components gave them. Read from the service's running instances: `None` when
     * none answered, and on a listing row; empty when they answered and every topic is declared.
     */
    undeclaredTopics: Option[Vector[String]] = None,
    /**
     * Each side this service's components take on a declared topic with a contract (feature 037):
     * whether what the component states is the declared contract. From the running instances, as
     * `undeclaredTopics` is: `None` when none answered, and on a listing row.
     */
    topicChecks: Option[Vector[TopicCheck]] = None,
    /**
     * Each topic source of the service with how far behind it is (feature 037), from the running
     * instances: each instance reads its own partitions, so lags are summed over them, and the
     * first reason any instance is failing on is named. `None` when none answered, and on a listing
     * row.
     */
    topicSources: Option[Vector[TopicSourceReport]] = None,
    /**
     * What the platform did about the service's bucket (feature 034), as a phrase — `provisioned`,
     * `recovered existing bucket`, `supplied`, `waiting for object storage`, `object storage
     * provisioning failed` — like `database`, and for the same reason. Absent when the service has
     * no object storage, or nothing has reported yet.
     */
    objectStorage: Option[String] = None,
    /** The bucket the platform gives the service, when its descriptor asks for one. */
    bucket: Option[String] = None,
    /** Where that bucket is on the internet, when its descriptor asks that it be reachable. */
    bucketAddress: Option[String] = None
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
    at: Option[java.time.Instant] = None,
    /**
     * The image of the descriptor this entry recorded: an apply's or a rollback's (feature 033).
     */
    image: Option[String] = None,
    /** `ServiceDescriptor.digest` of the descriptor this entry recorded, all 64 characters. */
    digest: Option[String] = None,
    /** On a `rolled-back` entry: the generation whose descriptor was applied again. */
    rolledBackTo: Option[Long] = None
)

/**
 * The body of `POST /services/{project}/{name}/rollback` (feature 033). No generation asks for the
 * most recent one whose descriptor differs from the service's.
 */
final case class RollbackRequest(generation: Option[Long] = None)

/** A rollback's reply: the generation it rolled back to, which the caller may not have named. */
final case class RolledBack(rolledBackTo: Long, status: ServiceStatus)

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
/**
 * What the installation is (feature 044, `GET /installation`): its version, and its cloud when it
 * names a provider.
 */
final case class Installation(platformVersion: String, cloud: Option[CloudInstallation] = None)

/**
 * The installation's cloud: its provider, the one account its cloud resources are made in, the
 * default location, and the wrapping key, which only an owner of an organization is shown.
 */
final case class CloudInstallation(
    provider: String,
    account: String,
    location: String,
    kmsKey: Option[String] = None
)

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
 * something. `usage` is different: it is the organization's own record of what it holds, kept as
 * things are created and deleted, and it is what a `quota` is checked against.
 */
final case class OrganizationDetail(
    id: String,
    name: String,
    disabled: Boolean = false,
    quota: Option[Quota] = None,
    usage: Usage = Usage.zero
)

/**
 * The most an organization may hold: projects, services across its projects, and instances — the
 * sum of every service's `minInstances`, which is what an installation runs for it. Each limit is
 * optional and one left out (or `null`) is unlimited; `0` is a limit that allows none. There is no
 * quota by default, and an organization with none behaves exactly as if this type did not exist.
 *
 * Set, replaced whole and cleared by a platform administrator; enforced by the control plane at the
 * moment a project is created or a service applied, never against what already runs.
 */
final case class Quota(
    projects: Option[Int] = None,
    services: Option[Int] = None,
    instances: Option[Int] = None
)

object Quota:
  /** Empty when the quota can be set: every named limit is non-negative, and one is named. */
  def problems(quota: Quota): Vector[String] =
    val negative = Vector(
      "projects"  -> quota.projects,
      "services"  -> quota.services,
      "instances" -> quota.instances
    ).collect { case (name, Some(limit)) if limit < 0 => s"a quota's $name cannot be negative" }
    val empty = Option.when(
      quota.projects.isEmpty && quota.services.isEmpty && quota.instances.isEmpty
    )("a quota names at least one limit; to lift every limit, clear the quota instead")
    negative ++ empty

/**
 * What an organization records as existing: its projects, its services, and their instances. Kept
 * by the organization itself as things are created and deleted, so it is exact rather than a
 * listing's count — the number a quota is checked against.
 *
 * No field has a default, so a usage that is written is written whole; the summaries default the
 * whole value to [[Usage.zero]], which the codec then leaves out — a summary with no `usage` means
 * nothing is held, and decodes as such from a control plane that predates quotas.
 */
final case class Usage(projects: Int, services: Int, instances: Int)

object Usage:
  val zero: Usage = Usage(0, 0, 0)

final case class ProjectDetail(
    id: String,
    name: String,
    organizationId: String,
    registry: Option[RegistrySummary] = None
)

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
    role: Option[Role] = None,
    quota: Option[Quota] = None,
    usage: Usage = Usage.zero
)

final case class ProjectSummary(
    id: String,
    name: String,
    organizationId: String,
    services: Int,
    registry: Option[RegistrySummary] = None
)

object OrganizationSummary:
  def of(
      detail: OrganizationDetail,
      projects: Int,
      role: Option[Role] = None
  ): OrganizationSummary =
    OrganizationSummary(
      detail.id,
      detail.name,
      projects,
      detail.disabled,
      role,
      detail.quota,
      detail.usage
    )

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
    ProjectSummary(detail.id, detail.name, detail.organizationId, services, detail.registry)

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

// ── Topology (feature 019) ──────────────────────────────────────────────────

/** One handler of a node: a command, a query, a step, a route, a tool. */
final case class TopologyHandler(name: String, `type`: String, streaming: Option[Boolean] = None)

/** One component, endpoint, topic or outside party of a service's topology. */
final case class TopologyNode(
    id: String,
    kind: String,
    layer: Int,
    platform: Boolean,
    handlers: Vector[TopologyHandler]
)

/** A connection the service declares: a subscription or a publication. */
final case class DeclaredEdge(
    from: String,
    to: String,
    kind: String,
    /** Feature 037: what the component states for the topic, on a topic edge. */
    contract: Option[Contract] = None,
    broker: Option[String] = None
)

/** The calls a handler ran for, by how each ended. */
final case class HandledCounts(ok: Long, refused: Long, failed: Long)

/** The calls nothing answered, by why. Never added to the handled ones. */
final case class UnansweredCounts(timedOut: Long, undelivered: Long)

/** Percentiles read from a bucketed histogram, so each is a bucket's upper edge and says so. */
final case class DurationMillis(p50: Double, p99: Double, max: Double, bucketed: Boolean = true)

/**
 * One caller handler and one callee handler, and what the window saw between them. An instance
 * sends its `histogram` so that a merge can recompute the percentiles; the merged response drops
 * it.
 */
final case class CallPair(
    caller: String,
    callee: String,
    handled: HandledCounts,
    unanswered: UnansweredCounts,
    durationMillis: DurationMillis,
    streaming: Boolean = false,
    histogram: Vector[Long] = Vector.empty
)

/** The observed calls between two nodes, a pair per two handlers. */
final case class CallEdge(from: String, to: String, pairs: Vector[CallPair])

/** How far back the observed calls reach, and how many there were. */
final case class TopologyWindow(seconds: Long, since: String, calls: Long, unanswered: Long = 0L)

/** The instance that wrote a topology document. */
final case class TopologyService(name: String, runtime: String, instance: String, startedAt: String)

/**
 * What one instance serves at `/observability/topology`: the document as the local console reads
 * it.
 */
final case class InstanceTopologyDocument(
    service: TopologyService,
    window: TopologyWindow,
    nodes: Vector[TopologyNode],
    declared: Vector[DeclaredEdge],
    calls: Vector[CallEdge],
    /** Feature 037: each topic source of the instance, with how far behind it is. */
    topicSources: Vector[TopicSourceReport] = Vector.empty
)

/**
 * A topic source as an instance reports it (feature 037): what reads which topic under which group,
 * from where, at which version; the declared broker and the contract it states; `lag`, the messages
 * the topic holds past the last one handled, as of the instance's last poll; `failing`, the reason
 * of the change being delivered again, until one succeeds.
 */
final case class TopicSourceReport(
    kind: String,
    component: String,
    topic: String,
    group: String,
    start: String,
    version: Int,
    recordedVersion: Option[Int] = None,
    behind: Boolean = false,
    broker: Option[String] = None,
    contract: Option[String] = None,
    lag: Option[Long] = None,
    failing: Option[String] = None
)

/** Whether an instance's topology was read, and if not, why not. */
enum InstanceStatus:
  /** The instance answered. */
  case Ok

  /** It did not answer in time. */
  case Unreachable

  /** The connection was refused: its runtime predates the topology. */
  case Unsupported

  /** It answered with an error, or with something that is not a topology. */
  case Failed

object InstanceStatus:
  def byName(name: String): Option[InstanceStatus] = values.find(_.wire == name)

  extension (status: InstanceStatus) def wire: String = status.toString.toLowerCase

  /** A plain word on the wire, as `ServiceLifecycle`; in the companion so no site can miss it. */
  given codec: JsonValueCodec[InstanceStatus] = new JsonValueCodec[InstanceStatus]:
    def decodeValue(in: JsonReader, default: InstanceStatus): InstanceStatus =
      val name = in.readString(null)
      byName(name).getOrElse(in.decodeError(s"unknown instance status '$name'"))
    def encodeValue(x: InstanceStatus, out: JsonWriter): Unit = out.writeVal(x.wire)
    def nullValue: InstanceStatus                             = null

/** One instance of a deployed service, as the control plane found it when asked. */
final case class InstanceTopology(
    pod: String,
    status: InstanceStatus,
    problem: Option[String] = None,
    runtime: Option[String] = None,
    readAt: Option[String] = None
)

/** A node that not every answering instance has, and which have it. */
final case class TopologyDifference(node: String, presentOn: Vector[String])

/**
 * `GET /services/{projectId}/{name}/topology`: every instance's topology, merged.
 *
 * `partial` is true whenever an instance is not `ok`, and such an instance contributes nothing; the
 * counts are the sum of the instances that answered. Handled and unanswered stay apart.
 */
final case class ServiceTopology(
    service: String,
    running: Int,
    contributing: Int,
    partial: Boolean,
    instances: Vector[InstanceTopology],
    window: TopologyWindow,
    nodes: Vector[TopologyNode],
    declared: Vector[DeclaredEdge],
    calls: Vector[CallEdge],
    differences: Vector[TopologyDifference]
)

// ── Deploy tokens (feature 013) ────────────────────────────────────────────

/**
 * `POST /organizations/{id}/tokens`.
 *
 * `expiresIn` is seconds: absent means the default lifetime, `0` means a token that never expires.
 * A number rather than an `Option[Instant]` because the *server's* clock decides when "ninety days
 * from now" is; a client that sent an instant would be asserting its own.
 */
final case class CreateDeployToken(label: String, expiresIn: Option[Long] = None)

/** The one response that ever carries a secret. There is no route that returns it again. */
final case class DeployTokenCreated(
    id: String,
    label: String,
    /** `ankka_<id>_<secret>`. Shown here and nowhere else, ever. */
    secret: String,
    subject: String,
    expiresAt: Option[java.time.Instant] = None
)

/** A token in a listing: everything but the secret and the digest derived from it. */
final case class DeployTokenSummary(
    id: String,
    label: String,
    subject: String,
    createdBy: Option[String] = None,
    createdAt: Option[java.time.Instant] = None,
    /** `None` means it never expires, which the listing says in so many words. */
    expiresAt: Option[java.time.Instant] = None,
    /** The date, not the instant — see the feature's clarification on last use. */
    lastUsed: Option[java.time.LocalDate] = None
)

// ── Registry credentials (feature 013) ───────────────────────────────────────

/**
 * `PUT /projects/{id}/registry`: a credential the cluster will pull a private image with.
 *
 * The password crosses the wire once, under TLS, and appears in no reply and no journal — it is
 * written to a Kubernetes Secret and the control plane keeps only the fact that it did so.
 */
final case class SetRegistry(server: String, username: String, password: String)

/** What a reader is told about a project's registry: never the password. */
final case class RegistrySummary(
    server: String,
    username: String,
    setAt: Option[java.time.Instant] = None,
    /** A display label, as everywhere else in this API — never a subject key. */
    setBy: Option[String] = None
)

/** What is wrong with a registry credential, checked identically by the CLI and the server. */
object Registries:

  /** The name of the Secret the control plane writes into a project's namespace. */
  val SecretName: String = "ankka-registry"

  def problems(server: String, username: String, password: String): Vector[String] =
    val serverProblems =
      if server.trim.isEmpty then Vector("registry server must not be empty")
      else if server.contains("://") || server.contains('/') then
        Vector("registry server must be a host, such as ghcr.io, not a URL")
      else Vector.empty
    val usernameProblems =
      if username.trim.isEmpty then Vector("registry username must not be empty") else Vector.empty
    val passwordProblems =
      if password.isEmpty then Vector("registry password must not be empty") else Vector.empty
    serverProblems ++ usernameProblems ++ passwordProblems

// ── Project topics (feature 027) ─────────────────────────────────────────────

/**
 * `PUT /projects/{id}/topics/{name}`: the partitions a declared topic has, whether the broker keeps
 * only the last message under each key, and the contract it carries (feature 037): a name and the
 * schema document, which the control plane fingerprints and holds for the project.
 */
final case class TopicDeclarationRequest(
    partitions: Int,
    compacted: Boolean = false,
    contract: Option[ContractDeclaration] = None
)

/** A contract as declared: its name and its schema document, a JSON Schema. */
final case class ContractDeclaration(name: String, schema: GraphJson)

/**
 * One side of a declared topic as the platform last saw it (feature 037): a component of a service
 * that reads or publishes the topic, what it states, and whether that is the declared contract.
 * `state` is `checked`, `mismatch`, or `unchecked` for an instance started before the declaration.
 */
final case class TopicCheck(
    topic: String,
    service: String,
    component: String,
    direction: String,
    stated: Option[String] = None,
    state: String
)

/**
 * A topic a project declares, and how far the platform has got with it: `phase` is a phrase, as a
 * database's is — `"waiting for broker"`, `"provisioned"`, `"recovered"`, `"failed"` — absent
 * before the operator has reported on it. `contract` is the declared name and fingerprint; `checks`
 * the sides the platform has seen.
 */
final case class ProjectTopic(
    name: String,
    partitions: Int,
    phase: Option[String] = None,
    detail: Option[String] = None,
    compacted: Boolean = false,
    contract: Option[Contract] = None,
    checks: Vector[TopicCheck] = Vector.empty
)

/**
 * `PUT /projects/{id}/brokers/{name}` (feature 037): a broker the project declares beside the
 * installation's, which a component may name for one topic: its address, the shape of its
 * credential and the project secret holding it.
 */
final case class BrokerDeclarationRequest(bootstrap: String, shape: String, secret: String)

/** A broker a project declares, as listed. */
final case class ProjectBroker(
    name: String,
    bootstrap: String,
    shape: String,
    secret: String,
    declaredAt: Option[String] = None
)

/** The rules of a broker's declaration, the same in the CLI and the control plane. */
object ProjectBrokers:

  val Shapes: Vector[String] = Vector("certificate", "sasl")

  /** The entries a project secret must hold for each shape. */
  def needs(shape: String): Vector[String] = shape match
    case "certificate" => Vector("ca.crt", "tls.crt", "tls.key")
    case "sasl"        => Vector("ca.crt", "username", "password")
    case _             => Vector.empty

  /**
   * Everything wrong with the declaration itself; the secret's entries are the entity's to check.
   */
  def problems(name: String, request: BrokerDeclarationRequest): Vector[String] =
    Option
      .when(!ProjectTopics.validName(name))(s"broker '$name': ${ProjectTopics.NameRule}")
      .toVector ++
      Option
        .when(
          request.bootstrap.trim.isEmpty || !request.bootstrap.trim
            .matches("[A-Za-z0-9._-]+:[0-9]+(,[A-Za-z0-9._-]+:[0-9]+)*")
        )(
          s"broker '$name': bootstrap '${request.bootstrap}' is not host:port[,host:port]"
        )
        .toVector ++
      Option
        .when(!Shapes.contains(request.shape))(
          s"broker '$name': shape '${request.shape}' is not one of ${Shapes.mkString(", ")}"
        )
        .toVector ++
      ProjectSecrets.nameProblems(request.secret).map(p => s"broker '$name': secret $p")

  /** The refusal of a secret that lacks what the shape needs, worded once. */
  def lacking(secret: String, shape: String, missing: Vector[String]): String =
    s"project secret '$secret' lacks ${missing.map(m => s"'$m'").mkString(", ")}, which shape '$shape' needs"

/** What is wrong with a topic's declaration, checked identically by the CLI and the server. */
object ProjectTopics:

  /** The most partitions a declared topic may ask for. */
  val MaxPartitions: Int = 1000

  /**
   * A name the broker can hold under a project's prefix, and one the topic's resource can be named
   * for: a Kubernetes name, which is what keeps `<project>.<name>` within Kafka's limit too.
   */
  val NameRule: String =
    "a name is lower-case letters, digits, \"-\" and \".\", starting and ending with a letter " +
      "or digit, at most 100 characters"

  private val Name = "[a-z0-9]([a-z0-9.-]{0,98}[a-z0-9])?".r

  def validName(name: String): Boolean = Name.matches(name)

  /** The largest schema document a contract may be declared with. */
  val MaxSchemaBytes: Int = 65536

  /** Everything wrong with declaring `name` with `partitions`, all at once. */
  def problems(name: String, partitions: Int): Vector[String] =
    Option.when(!validName(name))(s"topic '$name': $NameRule").toVector ++
      Option
        .when(partitions < 1 || partitions > MaxPartitions)(
          s"topic '$name': partitions $partitions is outside the range 1-$MaxPartitions"
        )
        .toVector

  /**
   * The same, with the contract's own rules: its name, and a schema that is JSON and not too big.
   */
  def problems(name: String, request: TopicDeclarationRequest): Vector[String] =
    problems(name, request.partitions) ++ request.contract.toVector.flatMap { c =>
      val nameProblem = Option.when(!Contract.validName(c.name))(
        s"topic '$name': contract name '${c.name}' is not ${Contract.NameRule}"
      )
      val size = com.github.plokhotnyuk.jsoniter_scala.core.writeToArray(c.schema).length
      val sizeProblem = Option.when(size > MaxSchemaBytes)(
        s"topic '$name': the schema of '${c.name}' is $size bytes, more than $MaxSchemaBytes"
      )
      nameProblem.toVector ++ sizeProblem.toVector
    }

  /** The refusal of fewer partitions than the project declares: the entity's rule, worded here. */
  def fewer(name: String, has: Int, asked: Int): String =
    s"topic '$name' has $has partitions and cannot have fewer; $asked was asked"

// ── Project secrets (feature 023) ────────────────────────────────────────────

/**
 * `PUT /projects/{id}/secrets/{name}`: entries of a project secret, merged into what it holds. The
 * values cross the wire once, under TLS, and appear in no reply and no journal: they are written to
 * a Kubernetes Secret in the project's namespace, and the control plane records only their names.
 */
final case class SetProjectSecret(entries: Map[String, String])

/** What a reader is told about a project secret: its entries' names, never a value. */
final case class ProjectSecretSummary(
    name: String,
    entries: Vector[String],
    setAt: Option[java.time.Instant] = None,
    /** A display label, as everywhere else in this API — never a subject key. */
    setBy: Option[String] = None
)

/** What is wrong with a project secret, checked identically by the CLI and the server. */
object ProjectSecrets:

  val MaxNameLength: Int = 253
  val MaxValueBytes: Int = 65536
  val ReservedPrefix     = "ankka-"
  val ReservedSuffixes =
    Vector(
      "-db",
      "-cluster-tls",
      "-service-tls",
      "-database-tls",
      "-secret-key",
      "-telemetry",
      "-mount-tls",
      "-storage"
    )
  private val ValidName  = """[a-z0-9]([a-z0-9.-]*[a-z0-9])?""".r
  private val ValidEntry = """[A-Za-z0-9._-]+""".r

  /** What is wrong with a project secret's name, if anything. */
  def nameProblems(name: String): Vector[String] =
    if name.isEmpty then Vector("a project secret needs a name")
    else if name.length > MaxNameLength || !ValidName.matches(name) then
      Vector(
        s"project secret name '$name' must be lowercase letters, digits, '-' and '.', begin and " +
          s"end with a letter or digit, and be at most $MaxNameLength characters"
      )
    else if name.startsWith(ReservedPrefix) || ReservedSuffixes.exists(name.endsWith) then
      Vector(
        s"project secret name '$name' is one the platform uses for its own Secrets in a project " +
          s"(names beginning '$ReservedPrefix' or ending ${ReservedSuffixes.mkString("'", "', '", "'")})"
      )
    else Vector.empty

  /** What is wrong with an entry's name, if anything. */
  def entryProblems(entry: String): Vector[String] =
    if entry.isEmpty || entry.length > MaxNameLength || !ValidEntry.matches(entry) then
      Vector(
        s"entry name '$entry' must be 1 to $MaxNameLength letters, digits, '.', '_' or '-'"
      )
    else Vector.empty

  /** Everything wrong with setting `entries` on `name`, all at once. Never quotes a value. */
  def problems(name: String, entries: Map[String, String]): Vector[String] =
    val noEntries =
      if entries.isEmpty then Vector("a project secret is set with at least one entry")
      else Vector.empty
    val entryNames = entries.keys.toVector.sorted.flatMap(entryProblems)
    val values = entries.toVector.sortBy(_._1).flatMap { (entry, value) =>
      val bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
      if value.isEmpty then Vector(s"entry '$entry' must not be empty")
      else if bytes > MaxValueBytes then
        Vector(s"entry '$entry' is at most $MaxValueBytes bytes as UTF-8; it is $bytes")
      else Vector.empty
    }
    nameProblems(name) ++ noEntries ++ entryNames ++ values

/**
 * What is wrong with a create request, all at once, checked identically by the CLI and the server.
 */
object DeployTokenRules:

  val MaxLabelLength: Int   = 100
  val DefaultLifetime: Long = 90L * 24 * 60 * 60
  val MaximumLifetime: Long = 365L * 24 * 60 * 60

  def problems(label: String, expiresIn: Option[Long]): Vector[String] =
    val labelProblems =
      if label.trim.isEmpty then Vector("a deploy token needs a label")
      else if label.length > MaxLabelLength then
        Vector(s"a deploy token's label is at most $MaxLabelLength characters")
      else if label.exists(c => c == '\n' || c == '\r') then
        Vector("a deploy token's label is one line")
      else Vector.empty
    val lifetimeProblems = expiresIn match
      case Some(seconds) if seconds < 0 => Vector("a lifetime cannot be negative")
      case Some(seconds) if seconds > MaximumLifetime =>
        Vector(s"a deploy token may live at most ${MaximumLifetime / 86400} days")
      case _ => Vector.empty
    labelProblems ++ lifetimeProblems

object Wire:
  given descriptorCodec: JsonValueCodec[ServiceDescriptor] = Codecs.make[ServiceDescriptor]
  given specCodec: JsonValueCodec[ServiceSpec]             = Codecs.make[ServiceSpec]
  given statusCodec: JsonValueCodec[ServiceStatus]         = Codecs.make[ServiceStatus]
  given mountStatusCodec: JsonValueCodec[MountStatus]      = Codecs.make[MountStatus]
  given projectCodec: JsonValueCodec[ProjectSummary]       = Codecs.make[ProjectSummary]
  given renameCodec: JsonValueCodec[Rename]                = Codecs.make[Rename]
  given orgDetailCodec: JsonValueCodec[OrganizationDetail] = Codecs.make[OrganizationDetail]
  given quotaCodec: JsonValueCodec[Quota]                  = Codecs.make[Quota]
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

  // The three documents that cross the wire whole; the types inside them are written as parts.
  given instanceTopologyDocumentCodec: JsonValueCodec[InstanceTopologyDocument] =
    Codecs.make[InstanceTopologyDocument]
  given instanceTopologyCodec: JsonValueCodec[InstanceTopology] = Codecs.make[InstanceTopology]
  given serviceTopologyCodec: JsonValueCodec[ServiceTopology]   = Codecs.make[ServiceTopology]

  given authDiscoveryCodec: JsonValueCodec[AuthDiscovery]     = Codecs.make[AuthDiscovery]
  given whoamiCodec: JsonValueCodec[Whoami]                   = Codecs.make[Whoami]
  given installationCodec: JsonValueCodec[Installation]       = Codecs.make[Installation]
  given inviteCodec: JsonValueCodec[Invite]                   = Codecs.make[Invite]
  given roleChangeCodec: JsonValueCodec[RoleChange]           = Codecs.make[RoleChange]
  given repairCodec: JsonValueCodec[Repair]                   = Codecs.make[Repair]
  given membersCodec: JsonValueCodec[MembersResponse]         = Codecs.make[MembersResponse]
  given historyCodec: JsonValueCodec[Vector[HistoryEntry]]    = Codecs.make[Vector[HistoryEntry]]
  given rollbackRequestCodec: JsonValueCodec[RollbackRequest] = Codecs.make[RollbackRequest]
  given rolledBackCodec: JsonValueCodec[RolledBack]           = Codecs.make[RolledBack]

  given createTokenCodec: JsonValueCodec[CreateDeployToken]   = Codecs.make[CreateDeployToken]
  given tokenCreatedCodec: JsonValueCodec[DeployTokenCreated] = Codecs.make[DeployTokenCreated]
  given tokenSummaryCodec: JsonValueCodec[DeployTokenSummary] = Codecs.make[DeployTokenSummary]
  given tokensCodec: JsonValueCodec[Vector[DeployTokenSummary]] =
    Codecs.make[Vector[DeployTokenSummary]]
  given setRegistryCodec: JsonValueCodec[SetRegistry] = Codecs.make[SetRegistry]
  given contractCodec: JsonValueCodec[Contract]       = Codecs.make[Contract]
  given brokerDeclarationCodec: JsonValueCodec[BrokerDeclarationRequest] =
    Codecs.make[BrokerDeclarationRequest]
  given projectBrokerCodec: JsonValueCodec[ProjectBroker] = Codecs.make[ProjectBroker]
  given projectBrokersCodec: JsonValueCodec[Vector[ProjectBroker]] =
    Codecs.make[Vector[ProjectBroker]]
  given topicDeclarationCodec: JsonValueCodec[TopicDeclarationRequest] =
    Codecs.make[TopicDeclarationRequest]
  given projectTopicCodec: JsonValueCodec[ProjectTopic]          = Codecs.make[ProjectTopic]
  given projectTopicsCodec: JsonValueCodec[Vector[ProjectTopic]] = Codecs.make[Vector[ProjectTopic]]
  given setProjectSecretCodec: JsonValueCodec[SetProjectSecret]  = Codecs.make[SetProjectSecret]
  given projectSecretCodec: JsonValueCodec[ProjectSecretSummary] = Codecs.make[ProjectSecretSummary]
  given projectSecretsCodec: JsonValueCodec[Vector[ProjectSecretSummary]] =
    Codecs.make[Vector[ProjectSecretSummary]]
