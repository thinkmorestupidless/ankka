package nakka.controlplane.deploy

import nakka.crd.{NakkaServiceSpec, NakkaServiceStatus}

/** One `NakkaService` as the control plane sees it. */
final case class NakkaServiceResource(
    namespace: String,
    name: String,
    spec: NakkaServiceSpec,
    status: Option[NakkaServiceStatus],
    uid: String = ""
)

/**
 * Access to `NakkaService` resources, and to nothing else in the cluster.
 *
 * The narrowness is the security property. The control plane can ask for a service to exist; it
 * cannot create a workload, read a secret, or touch another tenant's objects — and that shows at
 * review time here, rather than only in a ClusterRole nobody reads.
 *
 * No `io.fabric8` type appears in this signature, which is what lets the whole projector, its
 * retries and its staleness handling run in the offline suite against a fake.
 *
 * Blocking by design: callers run on virtual threads. Implementations throw on failure — the
 * projector is the single place failure is classified, and a swallowed error would be reported as a
 * confirmed observation, which is the one lie this design must not tell.
 */
trait NakkaServiceClient extends AutoCloseable:

  /**
   * Ensures a project's namespace exists. Idempotent.
   *
   * The control plane's job, not the operator's, and the reason is owner references: they only work
   * within a namespace, so the resource has to live beside the workload it owns. That makes the
   * namespace a precondition of writing the resource at all — and the operator cannot create it
   * first, because it only learns a project exists by seeing the resource.
   */
  def ensureNamespace(namespace: String): Unit

  /** Writes desired state. Idempotent: an unchanged spec performs no write at all. */
  def put(namespace: String, name: String, spec: NakkaServiceSpec): Unit

  /** Removes the resource; its children cascade. Succeeds if already absent. */
  def delete(namespace: String, name: String): Unit

  /** Every `NakkaService` in the namespaces this control plane owns. */
  def list(): Vector[NakkaServiceResource]

  /** Delivers a resource whenever its status changes. */
  def watch(onChange: NakkaServiceResource => Unit): AutoCloseable

  /**
   * Whether the cluster is currently readable.
   *
   * A watch that died silently is indistinguishable from a quiet cluster, and reporting stale state
   * as confirmed is exactly what FR-030 exists to prevent — so the caller has to be able to ask.
   */
  def connected: Boolean

  def close(): Unit = ()
