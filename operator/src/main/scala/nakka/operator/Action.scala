package nakka.operator

import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.{ConfigMap, Secret, Service}
import nakka.crd.NakkaServiceStatus
import nakka.operator.cnpg.{PostgresCluster, PostgresDatabase, PostgresDatabaseRole}

/**
 * Something that should happen to the cluster, as a value.
 *
 * Producing one performs no I/O — `Rendering` is a total function from a resource to a list of
 * these, so every rule about what the operator does is a unit test with no cluster. `Executor` is
 * the only thing that acts on them. This is the same split nakka's components already use, where a
 * handler returns a description of what should happen and the runtime decides how.
 *
 * `ApplyDeployment` carries a fabric8 `Deployment` rather than a parallel model of one. Building
 * that object is pure — it is a POJO, not a call — and inventing a second Deployment type would put
 * an untested conversion between the thing tests assert on and the thing the API server sees.
 */
enum Action:

  /** Idempotent. The per-project container has to exist before anything in it. */
  case EnsureNamespace(name: String)

  /** Server-side apply, taking ownership of the fields it sets. Idempotent. */
  case ApplyDeployment(deployment: Deployment)

  /** Idempotent; deleting an absent Deployment succeeds. */
  case DeleteDeployment(namespace: String, name: String)

  /**
   * The workload's in-cluster address. Server-side apply, idempotent. Rendered only for a service
   * that declares a port.
   */
  case EnsureService(service: Service)

  /**
   * Removes the address of a service that no longer serves HTTP.
   *
   * Carries the owning resource's uid because the executor must only ever delete a Service that
   * resource owns. Someone may legitimately hand-create a Service named after a workload that
   * serves no HTTP — for a protocol this platform does not model — and the operator must never
   * remove an object it did not create. Rendered on every pass for such a service, so it must also
   * cost nothing when there is nothing to remove: the executor reads first.
   */
  case RemoveService(namespace: String, name: String, ownerUid: String)

  /** Writes the status subresource, and nothing else. */
  case SetStatus(namespace: String, name: String, status: NakkaServiceStatus)

  /**
   * Ensures a project's shared Postgres capacity. Idempotent; concurrent first-service applies in
   * one project converge on the same object rather than racing to create two.
   */
  case EnsureCluster(cluster: PostgresCluster)

  /**
   * Ensures a service's credential secret exists. **Not idempotent by construction, unlike every
   * other action here** — the executor must create it only when absent, never overwrite it, since
   * regenerating a password rotates it under a running service on every reconcile pass.
   */
  case EnsureCredentials(secret: Secret)

  /**
   * Must be ensured before the [[EnsureDatabase]] it will own — CNPG rejects a database whose owner
   * role does not exist yet (research R4).
   */
  case EnsureDatabaseRole(role: PostgresDatabaseRole)

  /** A service's own logical database. Server-side apply; idempotent. */
  case EnsureDatabase(database: PostgresDatabase)

  /**
   * The schema `ConfigMap`, one per project namespace, mounted by every service's schema-init
   * container. Re-applied on every reconcile so a schema change reaches existing namespaces.
   */
  case EnsureSchemaConfig(configMap: ConfigMap)

  /**
   * Desired and observed agree.
   *
   * Distinct from an empty list so that "decided nothing needs doing" is a stated outcome rather
   * than an absence — a steady-state service producing no writes is a requirement, and a
   * requirement that shows up as `Nil` is one nobody can assert on.
   */
  case NoAction

  def describe: String = this match
    case EnsureNamespace(name) => s"ensure namespace $name"
    case ApplyDeployment(d) =>
      s"apply deployment ${d.getMetadata.getNamespace}/${d.getMetadata.getName}"
    case DeleteDeployment(ns, name) => s"delete deployment $ns/$name"
    case EnsureService(svc) =>
      s"ensure service ${svc.getMetadata.getNamespace}/${svc.getMetadata.getName}"
    case RemoveService(ns, name, _)  => s"remove service $ns/$name if owned"
    case SetStatus(ns, name, status) => s"set status $ns/$name to ${status.lifecycle}"
    case EnsureCluster(c) =>
      s"ensure cluster ${c.getMetadata.getNamespace}/${c.getMetadata.getName}"
    case EnsureCredentials(s) =>
      s"ensure credentials ${s.getMetadata.getNamespace}/${s.getMetadata.getName} (create-if-absent)"
    case EnsureDatabaseRole(r) =>
      s"ensure database role ${r.getMetadata.getNamespace}/${r.getMetadata.getName}"
    case EnsureDatabase(d) =>
      s"ensure database ${d.getMetadata.getNamespace}/${d.getMetadata.getName}"
    case EnsureSchemaConfig(cm) =>
      s"ensure schema config ${cm.getMetadata.getNamespace}/${cm.getMetadata.getName}"
    case NoAction => "nothing to do"
