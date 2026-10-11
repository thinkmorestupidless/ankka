package com.thinkmorestupidless.ankka.controlplane.deploy

import com.thinkmorestupidless.ankka.crd.{
  AnkkaProjectSpec,
  AnkkaProjectStatus,
  AnkkaServiceSpec,
  AnkkaServiceStatus
}

/** One `AnkkaService` as the control plane sees it. */
final case class AnkkaServiceResource(
    namespace: String,
    name: String,
    spec: AnkkaServiceSpec,
    status: Option[AnkkaServiceStatus],
    uid: String = ""
)

/**
 * Access to `AnkkaService` resources, a project's namespace, and a registry credential it can write
 * but never read. Nothing else in the cluster.
 *
 * The narrowness is the security property. The control plane can ask for a service to exist; it
 * cannot create a workload, cannot read *any* secret — including the registry credentials it wrote
 * itself — and cannot touch another tenant's objects. That shows at review time here, rather than
 * only in a ClusterRole nobody reads.
 *
 * No `io.fabric8` type appears in this signature, which is what lets the whole projector, its
 * retries and its staleness handling run in the offline suite against a fake.
 *
 * Blocking by design: callers run on virtual threads. Implementations throw on failure — the
 * projector is the single place failure is classified, and a swallowed error would be reported as a
 * confirmed observation, which is the one lie this design must not tell.
 */
trait AnkkaServiceClient extends AutoCloseable:

  /**
   * Ensures a project's namespace exists. Idempotent.
   *
   * The control plane's job, not the operator's, and the reason is owner references: they only work
   * within a namespace, so the resource has to live beside the workload it owns. That makes the
   * namespace a precondition of writing the resource at all — and the operator cannot create it
   * first, because it only learns a project exists by seeing the resource.
   */
  def ensureNamespace(namespace: String): Unit

  /**
   * A project's rehearsal namespace, and the operator's grant there (feature 041). The default
   * makes only the namespace, for a client with no cluster's RBAC to write.
   */
  def ensureRehearsalNamespace(namespace: String, projectId: String): Unit =
    val _ = projectId
    ensureNamespace(namespace)

  /**
   * Puts a registry credential where the kubelet will read it, in a project's namespace.
   *
   * Write-only, and that is the point: the control plane can create and replace this Secret and can
   * never read one back, so a compromised control plane leaks no credential it was given earlier.
   * It cannot delete one either — the same rule that keeps it away from database credentials — so
   * clearing a registry stops naming the Secret rather than removing it.
   *
   * Creates the namespace first, for the same reason `put` needs it: a project's namespace may not
   * exist yet when its first credential arrives.
   *
   * Throws on failure. The caller reports that to the operator rather than recording a credential
   * the cluster does not hold.
   */
  def ensurePullSecret(namespace: String, server: String, username: String, password: String): Unit

  /**
   * Sets entries of a project secret, merged into what the Secret holds: the entries named are
   * added or replaced and every other entry is kept. Creates the Secret, and the namespace, when
   * they do not exist. Write-only as `ensurePullSecret` is: never a `get`, and whatever the API
   * server answers a write with is discarded.
   *
   * Throws on failure; a refusal the cluster makes of the request itself (too large, invalid) is a
   * `CommandError(BadRequest)`, anything else is the caller's to report as unavailable.
   */
  def setSecretEntries(namespace: String, name: String, entries: Map[String, String]): Unit

  /** Removes one entry of a project secret. The Secret stays, empty if that was its last entry. */
  def removeSecretEntry(namespace: String, name: String, entry: String): Unit

  /** Writes desired state. Idempotent: an unchanged spec performs no write at all. */
  def put(namespace: String, name: String, spec: AnkkaServiceSpec): Unit

  /**
   * Writes a project's declarations — its topics (feature 027) — as its `AnkkaProject`, creating
   * the project's namespace first, as a project may declare a topic before it has a service.
   * Idempotent: unchanged declarations perform no write.
   */
  def putProject(namespace: String, name: String, spec: AnkkaProjectSpec): Unit

  /**
   * A contract's schema document into the project's schema `ConfigMap`, under its fingerprint
   * (feature 037).
   */
  def putSchema(namespace: String, fingerprint: String, document: String): Unit

  /** The document declared under a fingerprint, if the project's schema `ConfigMap` holds it. */
  def schema(namespace: String, fingerprint: String): Option[String]

  /** What the operator last reported of a project's topics, or nothing yet. */
  def projectStatus(namespace: String, name: String): Option[AnkkaProjectStatus]

  /**
   * The control plane's own database's backups (feature 041); nothing from a client that cannot see
   * them, as a fake cannot.
   */
  def controlPlaneBackups(): Option[com.thinkmorestupidless.ankka.crd.LineStatus] = None

  /** Removes the resource; its children cascade. Succeeds if already absent. */
  def delete(namespace: String, name: String): Unit

  /** Every `AnkkaService` in the namespaces this control plane owns. */
  def list(): Vector[AnkkaServiceResource]

  /** Delivers a resource whenever its status changes. */
  def watch(onChange: AnkkaServiceResource => Unit): AutoCloseable

  /**
   * Whether the cluster is currently readable.
   *
   * A watch that died silently is indistinguishable from a quiet cluster, and reporting stale state
   * as confirmed is exactly what FR-030 exists to prevent — so the caller has to be able to ask.
   */
  def connected: Boolean

  def close(): Unit = ()

  /** A project's resource as written (feature 041), for a held control plane to compare. */
  def projectSpec(namespace: String, name: String): Option[AnkkaProjectSpec] =
    val _ = (namespace, name)
    None

  /** The projects whose namespaces the platform labelled, by id (feature 041). */
  def platformProjects(): Vector[String] = Vector.empty

  /** `garage-copy-status`'s entries (feature 041); none where there is no copy. */
  def copyStatus(): Map[String, String] = Map.empty

/**
 * A client a held control plane uses (feature 041, research R19): every read is the cluster's, and
 * every write is compared with what the cluster holds, recorded on the hold when it differs, and
 * not made. Released, it is the client it wraps.
 */
final class HeldClient(underlying: AnkkaServiceClient, hold: RestoreHold)
    extends AnkkaServiceClient:

  private def refuse(what: String): Nothing =
    throw com.thinkmorestupidless.ankka.core.CommandError(
      s"the control plane's database was restored and its projection is held; $what is made " +
        "once a platform administrator releases it",
      com.thinkmorestupidless.ankka.core.ErrorCode.Unavailable
    )

  def ensureNamespace(namespace: String): Unit =
    if !hold.held then underlying.ensureNamespace(namespace)

  override def ensureRehearsalNamespace(namespace: String, projectId: String): Unit =
    if hold.held then refuse("a rehearsal namespace")
    else underlying.ensureRehearsalNamespace(namespace, projectId)

  def ensurePullSecret(
      namespace: String,
      server: String,
      username: String,
      password: String
  ): Unit =
    if hold.held then refuse("a registry credential")
    else underlying.ensurePullSecret(namespace, server, username, password)

  def setSecretEntries(namespace: String, name: String, entries: Map[String, String]): Unit =
    if hold.held then refuse("a project secret")
    else underlying.setSecretEntries(namespace, name, entries)

  def removeSecretEntry(namespace: String, name: String, entry: String): Unit =
    if hold.held then refuse("a project secret")
    else underlying.removeSecretEntry(namespace, name, entry)

  def put(namespace: String, name: String, spec: AnkkaServiceSpec): Unit =
    if !hold.held then underlying.put(namespace, name, spec)
    else
      val running = underlying.list().find(r => r.namespace == namespace && r.name == name)
      hold.service(
        spec.projectId,
        spec.serviceName,
        Option.when(
          !running.exists(r => r.spec.generation == spec.generation && r.spec.image == spec.image)
        )(
          com.thinkmorestupidless.ankka.controlplane.api.ServiceDifference(
            spec.projectId,
            spec.serviceName,
            Some(spec.generation),
            running.map(_.spec.generation),
            Some(spec.image),
            running.map(_.spec.image)
          )
        )
      )

  def putProject(namespace: String, name: String, spec: AnkkaProjectSpec): Unit =
    if !hold.held then underlying.putProject(namespace, name, spec)
    else
      val written = underlying.projectSpec(namespace, name).toList.flatMap(_.topics)
      def topics(t: List[com.thinkmorestupidless.ankka.crd.ProjectTopicEntry]) =
        t.map(e => (e.name, e.partitions, e.compacted)).toSet
      hold.topics(spec.projectId, topics(spec.topics) != topics(written))

  def putSchema(namespace: String, fingerprint: String, document: String): Unit =
    if hold.held then refuse("a contract's schema")
    else underlying.putSchema(namespace, fingerprint, document)

  def schema(namespace: String, fingerprint: String): Option[String] =
    underlying.schema(namespace, fingerprint)

  def projectStatus(namespace: String, name: String): Option[AnkkaProjectStatus] =
    underlying.projectStatus(namespace, name)

  override def controlPlaneBackups(): Option[com.thinkmorestupidless.ankka.crd.LineStatus] =
    underlying.controlPlaneBackups()

  def delete(namespace: String, name: String): Unit =
    if !hold.held then underlying.delete(namespace, name)
    else
      underlying
        .list()
        .find(r => r.namespace == namespace && r.name == name)
        .foreach(r =>
          hold.service(
            r.spec.projectId,
            r.spec.serviceName,
            Some(
              com.thinkmorestupidless.ankka.controlplane.api.ServiceDifference(
                r.spec.projectId,
                r.spec.serviceName,
                None,
                Some(r.spec.generation),
                None,
                Some(r.spec.image)
              )
            )
          )
        )

  def list(): Vector[AnkkaServiceResource]                         = underlying.list()
  def watch(onChange: AnkkaServiceResource => Unit): AutoCloseable = underlying.watch(onChange)
  def connected: Boolean                                           = underlying.connected
  override def close(): Unit                                       = underlying.close()
  override def projectSpec(namespace: String, name: String) =
    underlying.projectSpec(namespace, name)
  override def platformProjects(): Vector[String] = underlying.platformProjects()
  override def copyStatus(): Map[String, String]  = underlying.copyStatus()

/**
 * The one thing an endpoint is allowed to do to the cluster: put a project's registry credential in
 * it.
 *
 * A separate, one-method interface rather than handing `ProjectEndpoint` the whole
 * `AnkkaServiceClient`. It is smaller in two ways that matter. An endpoint holding the client could
 * write desired state directly, going around the projector that owns generations and staleness —
 * the kind of shortcut that is obvious now and invisible in a year. And the client is only built
 * when the projector starts, so an endpoint constructed before that would have to hold an
 * `Option[AnkkaServiceClient]` and re-derive a project's namespace from the deployment
 * configuration; here the projector already knows both.
 */
trait RegistryWriter:

  /**
   * Writes the credential for a project, creating its namespace if need be. Throws if the cluster
   * refused or is unreachable, which the caller reports as unavailable — nothing is recorded.
   */
  def writePullSecret(projectId: String, server: String, username: String, password: String): Unit

/**
 * What an endpoint may know of a project's topics in the cluster: how far the operator has got with
 * each. Read-only, and one method, for the reasons `RegistryWriter` is one.
 */
/**
 * The control plane's own database's backups (feature 041), read from its archiver's ObjectStore
 * and its Cluster in the control plane's namespace, through the `backups` component's Role there.
 */
trait PlatformBackupsReader:

  /** The control plane's line of history, or nothing when it cannot be read. */
  def controlPlaneBackups(): Option[com.thinkmorestupidless.ankka.crd.LineStatus]

  /** The copy to the secondary store's status entries (feature 041); none without a copy. */
  def copyStatus(): Map[String, String] = Map.empty

/**
 * Makes a project's rehearsal namespace (feature 041): the platform's labels, the project it
 * rehearses, and a RoleBinding granting the operator the one delete it has, there and nowhere else.
 */
trait RehearsalNamespaces:
  /** Throws when the cluster refused either; nothing is recorded then. */
  def ensureRehearsalNamespace(projectId: String): Unit

trait ProjectTopicsReader:

  /** The operator's last report, or nothing yet; throws if the cluster cannot be read. */
  def topicStatus(projectId: String): Option[AnkkaProjectStatus]

/**
 * The other thing an endpoint may do to the cluster: a project secret's entries. Two methods, for
 * the reasons `RegistryWriter` is one: no access to desired state, and the projector already knows
 * a project's namespace.
 */
trait ProjectSecretWriter:

  /** Throws if the cluster refused or is unreachable; the caller records nothing. */
  def setEntries(projectId: String, name: String, entries: Map[String, String]): Unit

  /** Throws if the cluster refused or is unreachable; the caller records nothing. */
  def removeEntry(projectId: String, name: String, entry: String): Unit

/**
 * Where a declared contract's schema document goes and comes from (feature 037): the project's
 * `ankka-project-schemas` ConfigMap, one key per fingerprint. Written before the declaration is
 * recorded, as a secret is, so the journal never holds a document.
 */
trait ProjectSchemaStore:

  /** Throws if the cluster refused or is unreachable; the caller records nothing. */
  def putSchema(projectId: String, fingerprint: String, document: String): Unit

  def schema(projectId: String, fingerprint: String): Option[String]
