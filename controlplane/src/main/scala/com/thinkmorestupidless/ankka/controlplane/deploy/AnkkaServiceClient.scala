package com.thinkmorestupidless.ankka.controlplane.deploy

import com.thinkmorestupidless.ankka.crd.{AnkkaServiceSpec, AnkkaServiceStatus}

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

  /** Writes desired state. Idempotent: an unchanged spec performs no write at all. */
  def put(namespace: String, name: String, spec: AnkkaServiceSpec): Unit

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
