package com.thinkmorestupidless.ankka.operator

/**
 * Every name the operator renders, in one place.
 *
 * Naming is where a Kubernetes controller goes wrong quietly: a name that is one character too
 * long, or that is computed slightly differently in two places, produces an object the operator
 * then cannot find again.
 */
object Names:

  /** The Secret holding a service's secret key, under the entry `key`. */
  /** A service's bucket's route, for a bucket reachable from the internet (feature 034). */
  def bucketRoute(serviceName: String): String = s"$serviceName-storage"

  def secretKeySecret(serviceName: String): String = s"$serviceName-secret-key"

  /**
   * The Secret holding what a service sends with its telemetry, written by the operator from the
   * installation's own (feature 026). Its suffix is one `ServiceSpec.PlatformSecretSuffixes` names,
   * so no descriptor can read it.
   */
  def telemetrySecret(serviceName: String): String = s"$serviceName-telemetry"

  /** The entry of that Secret the headers are under. */
  val TelemetryHeadersEntry: String = "headers"

  /** The entry of that Secret the key is under. */
  val SecretKeyEntry: String = "key"

  /**
   * A cloud request's name (feature 044), from what it is for and whom it serves, so a second
   * render finds the first. A service's is `<service>-<suffix>`; a project's is
   * `<project>.<suffix>`: a dot cannot appear in a service's name, a DNS label, so a project's
   * request can never share a name with any service's, whatever the service is called.
   */
  object CloudRequest:
    val IdentitySuffix: String          = "identity"
    val SecretAccessSuffix: String      = "secret-access"
    val BucketSuffix: String            = "bucket"
    val StorageCredentialSuffix: String = "storage-credential"
    val SecretSyncSuffix: String        = "secret-sync"
    val BackupBucketSuffix: String      = "backup-bucket"
    val BackupCredentialSuffix: String  = "backup-credential"
    val WrappingKeySuffix: String       = "wrapping-key"

    def ofService(serviceName: String, suffix: String): String = s"$serviceName-$suffix"
    def ofProject(projectId: String, suffix: String): String   = s"$projectId.$suffix"

  /** Kubernetes DNS label ceiling. Namespaces, Deployments and containers all obey it. */
  val MaxLabelLength: Int = 63

  private val ValidLabel = "[a-z]([-a-z0-9]{0,61}[a-z0-9])?".r

  def isLabel(value: String): Boolean = ValidLabel.matches(value)

  /**
   * One namespace per project.
   *
   * Per project rather than per service because a project is the tenancy boundary, and because
   * owner references only work within a namespace — the resource has to live beside the objects it
   * owns for cascade deletion to be structural.
   */
  def namespace(prefix: String, projectId: String): String = s"$prefix-$projectId"

  /**
   * Project ids nothing is rendered for, because the platform's own workloads use them.
   *
   * A workload's certificate carries `ankka://<project>/<service>`, and that URI is the identity
   * every ACL and every platform listener reads. The control plane's is
   * `ankka://platform/controlplane`, issued by the same authority, so a resource in a project
   * called `platform` could ask for a certificate naming the control plane. The control plane
   * refuses to create such a project; the operator refuses again here, because the resource is the
   * only thing it knows about its writer, and anything with the right to write one could write this
   * one.
   *
   * `local` is reserved because a project's id is also part of the consumer group a deployed
   * service reads a topic under, and a service run on a developer's machine that states its name
   * reads under `ankka.local.<service>.…`. The control plane's list is the same
   * (`ReservedProjectIdsSuite`).
   *
   * `cloud-provider` is reserved because a project's namespace is `<prefix>-<project>`, and the
   * installation's cloud provider runs in `ankka-cloud-provider` with the power over the cloud
   * account (feature 044): a project of that name would render into the provider's namespace.
   */
  val ReservedProjectIds: Set[String] = Set("platform", "local", "cloud-provider")

  /** Problems with a rendered namespace name, reported all at once. */
  def namespaceProblems(prefix: String, projectId: String): Vector[String] =
    val rendered = namespace(prefix, projectId)
    Vector(
      Option.when(projectId.isEmpty)("project id must not be empty"),
      Option.when(projectId.nonEmpty && !isLabel(projectId))(
        s"project id '$projectId' is not a DNS label"
      ),
      Option.when(ReservedProjectIds.contains(projectId))(
        if projectId == "local" then
          s"project id '$projectId' is reserved for services run locally, whose consumer groups " +
            "it names; no certificate is issued in its name"
        else
          s"project id '$projectId' is reserved for the platform's own workloads; " +
            "no certificate is issued in its name"
      ),
      Option.when(rendered.length > MaxLabelLength)(
        s"namespace '$rendered' is ${rendered.length} characters, over the $MaxLabelLength limit"
      )
    ).flatten

  /** The Deployment, the container, the resource and the Service all share the service's name. */
  def deployment(serviceName: String): String = serviceName

  def container(serviceName: String): String = serviceName

  /**
   * The Service, which is also the service's in-cluster address: `<name>` within the project's
   * namespace, `<name>.<namespace>.svc.cluster.local` from outside it. Sharing the name is what
   * makes that address predictable without looking anything up.
   */
  def service(serviceName: String): String = serviceName

  /** What the service's pods run as. One per service, never the namespace default. */
  def serviceAccount(serviceName: String): String = serviceName

  /** The Role and RoleBinding that let those pods read their own project's pods. */
  def peersRole(serviceName: String): String = s"$serviceName-peers"

  /**
   * The headless address a service that serves gRPC also has, which resolves to one address per
   * ready instance so a caller balances per call rather than per connection. Every service's own
   * address is named exactly after it, so this name could be another service's; the action that
   * ensures it never takes over an object this resource does not own.
   */
  def grpcPeers(serviceName: String): String = s"$serviceName-grpc-peers"

  /** The service's route, named after it like its Service: one per service, in its namespace. */
  def httpRoute(serviceName: String): String = serviceName

  def serviceNameProblems(serviceName: String): Vector[String] =
    if serviceName.isEmpty then Vector("service name must not be empty")
    else if !isLabel(serviceName) then Vector(s"service name '$serviceName' is not a DNS label")
    else Vector.empty
