package com.thinkmorestupidless.ankka.core

/**
 * Which variables are the platform's, said once.
 *
 * Three programs read this: the control plane refuses a descriptor that gives a variable only the
 * platform sets (`ServiceSpec.problems`); the operator splits a process-hosted service's
 * environment between the platform's container and the developer's (`Rendering.containersFor`); and
 * the module host answers a module's `config` import (`HostImports.lookup`). Before this object
 * each kept a list of its own, and they had drifted.
 *
 * The operator depends on `crd` alone, so it cannot see `core`. It compiles **this same file**
 * instead (`operator`'s `unmanagedSources` in `build.sbt`). That is why the file imports nothing
 * outside the standard library: an import of anything else in `core` breaks the operator's build,
 * on purpose.
 */
private[ankka] object PlatformVariables:

  /** The service's secret key, which the platform's program alone holds. */
  val SecretKey: String = "ANKKA_SECRET_KEY"

  /**
   * How long a call to another service waits for its answer. The platform's program makes every
   * such call, for a process as for itself, so the setting is its.
   */
  val ServiceClientTimeout: String = "ANKKA_SERVICE_CLIENT_TIMEOUT"

  /** The port the service serves HTTP on. A descriptor declares the port instead. */
  val HttpPort: String = "ANKKA_HTTP_PORT"

  /** The port the service serves gRPC on. A descriptor declares `grpcPort` instead. */
  val GrpcPort: String = "ANKKA_GRPC_PORT"

  /** The collector an instance exports its telemetry to: the installation's to say, once. */
  val OtlpEndpoint: String = "ANKKA_OTLP_ENDPOINT"

  /** What is sent with the telemetry so the collector accepts it: a credential, from a Secret. */
  val OtlpHeaders: String = "ANKKA_OTLP_HEADERS"

  /**
   * A service's name when it runs on a developer's machine, which names its topic sources' consumer
   * groups. A deployed service's name is its certificate's, so a descriptor that gives this is
   * refused, with a message of its own rather than as one of `PlatformOnly`: the platform does not
   * set it, it reads it nowhere.
   */
  val ServiceName: String = "ANKKA_SERVICE_NAME"

  /**
   * Set by the platform alone, by exact name. A descriptor that gives one is refused: the operator
   * sets each of them, and two values for one would leave the pod with whichever came last.
   */
  val PlatformOnly: Set[String] = Set(
    HttpPort,
    GrpcPort,
    // How a node finds its peers in a cluster.
    "ANKKA_CLUSTER_MODE",
    "POD_IP",
    "ANKKA_CLUSTER_SERVICE",
    "ANKKA_CLUSTER_POD_SELECTOR",
    "ANKKA_CLUSTER_CONTACT_POINTS",
    "ANKKA_NAMESPACE_PREFIX",
    // How the sidecar and a process find each other.
    "ANKKA_PROCESS_PORT",
    "ANKKA_PROCESS_ADDRESS",
    "ANKKA_SIDECAR_PORT",
    "ANKKA_SIDECAR_ADDRESS",
    "ANKKA_SIDECAR_BIND",
    // How the runtime finds and sizes a module.
    "ANKKA_WASM_MODULE",
    "ANKKA_WASM_INSTANCES",
    "ANKKA_WASM_MAX_MEMORY_PAGES",
    // Where telemetry goes: the installation's, given only to the platform's program.
    OtlpEndpoint,
    OtlpHeaders
  )

  /**
   * For the platform's program and never the developer's. A descriptor may give them — a model's
   * key, a database it supplies, a secret key of its own, the issuers it accepts tokens from, how
   * long a call to another service waits — and they go to the platform's container only: the
   * sidecar runs the agent loop, connects to the database, holds the secret store, verifies tokens
   * and calls other services, handing the process only the principal and the answers.
   */
  val RuntimeOnlyPrefixes: Vector[String] =
    Vector("ANTHROPIC_", "ANKKA_MODEL_", "ANKKA_DB_", "ANKKA_AUTH_")
  val RuntimeOnlyNames: Set[String] = Set(SecretKey, ServiceClientTimeout)

  /** Where a web-hosted service's program listens. */
  val WebPort: String = "PORT"

  /** Where a web-hosted service's program calls the project's services, through its proxy. */
  val ServicesUrl: String = "ANKKA_SERVICES_URL"

  /**
   * What the platform tells a web-hosted service's program, by exact name. A web-hosted descriptor
   * may not give them; to a service of any other hosting they are ordinary names.
   */
  val WebOnly: Set[String] = Set(WebPort, ServicesUrl)

  /** Given to both programs of a process-hosted service: the broker's. */
  val SharedPrefixes: Vector[String] = Vector("ANKKA_KAFKA_")

  /** Read by the platform's program from its own environment, whoever set them. */
  val RuntimeReadPrefixes: Vector[String] =
    Vector("ANKKA_CLUSTER_", "ANKKA_WASM_", "ANKKA_SIDECAR_", "ANKKA_PROCESS_")
  val RuntimeReadNames: Set[String] = Set("ANKKA_BASE_DOMAIN", "ANKKA_HTTPS_PORT")

  def platformOnly(name: String): Boolean = PlatformOnly.contains(name)

  def runtimeOnly(name: String): Boolean =
    RuntimeOnlyNames.contains(name) || RuntimeOnlyPrefixes.exists(name.startsWith)

  def shared(name: String): Boolean = SharedPrefixes.exists(name.startsWith)

  /**
   * What a module's `config` import answers as absent. A module runs in the platform's own
   * container, so its environment holds every variable the platform's program reads; this is the
   * read-time version of the split the operator makes for a process.
   */
  def withheldFromModule(name: String): Boolean =
    platformOnly(name) || runtimeOnly(name) ||
      RuntimeReadNames.contains(name) || RuntimeReadPrefixes.exists(name.startsWith)
