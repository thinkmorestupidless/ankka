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

  /** The port the service serves HTTP on. A descriptor declares the port instead. */
  val HttpPort: String = "ANKKA_HTTP_PORT"

  /** The port the service serves gRPC on. A descriptor declares `grpcPort` instead. */
  val GrpcPort: String = "ANKKA_GRPC_PORT"

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
    "ANKKA_WASM_MAX_MEMORY_PAGES"
  )

  /**
   * For the platform's program and never the developer's. A descriptor may give them — a model's
   * key, a database it supplies, a secret key of its own, the issuers it accepts tokens from — and
   * they go to the platform's container only: the sidecar runs the agent loop, connects to the
   * database, holds the secret store and verifies tokens, handing the process only the principal.
   */
  val RuntimeOnlyPrefixes: Vector[String] =
    Vector("ANTHROPIC_", "ANKKA_MODEL_", "ANKKA_DB_", "ANKKA_AUTH_")
  val RuntimeOnlyNames: Set[String] = Set(SecretKey)

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
