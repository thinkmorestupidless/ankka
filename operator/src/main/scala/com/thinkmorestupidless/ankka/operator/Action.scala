package com.thinkmorestupidless.ankka.operator

import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.gatewayapi.v1.{BackendTLSPolicy, HTTPRoute}
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy
import io.fabric8.kubernetes.api.model.rbac.{Role, RoleBinding}
import io.fabric8.kubernetes.api.model.{
  ConfigMap,
  GenericKubernetesResource,
  Secret,
  Service,
  ServiceAccount
}
import com.thinkmorestupidless.ankka.crd.AnkkaServiceStatus
import com.thinkmorestupidless.ankka.operator.cnpg.{
  PostgresCluster,
  PostgresDatabase,
  PostgresDatabaseRole
}

/**
 * Something that should happen to the cluster, as a value.
 *
 * Producing one performs no I/O — `Rendering` is a total function from a resource to a list of
 * these, so every rule about what the operator does is a unit test with no cluster. `Executor` is
 * the only thing that acts on them. This is the same split ankka's components already use, where a
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

  /**
   * An exposed service's route (feature 005): one HTTPRoute in the service's namespace, attached to
   * the installation's Gateway. Server-side apply, idempotent. Rendered only for a service that is
   * exposed, declares a port, and on an operator that knows the base domain.
   */
  case EnsureHttpRoute(route: HTTPRoute)

  /**
   * Removes the route of a service that is no longer exposed — or no longer serves HTTP — while the
   * service itself stays. Owner-checked and read-first for the same reasons as `RemoveService`:
   * rendered on every pass, and never allowed to delete a route the resource does not own.
   */
  case RemoveHttpRoute(namespace: String, name: String, ownerUid: String)

  /**
   * A service's own identity and its one permission — reading the pods of its own project, so its
   * nodes can find each other. All owned by the resource; none needs a delete verb.
   */
  case EnsureServiceAccount(serviceAccount: ServiceAccount)
  case EnsureRole(role: Role)
  case EnsureRoleBinding(roleBinding: RoleBinding)

  /** Writes the status subresource, and nothing else. */
  case SetStatus(namespace: String, name: String, status: AnkkaServiceStatus)

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

  /**
   * Asks cert-manager for a certificate (feature 014). Owned by the resource, server-side applied;
   * the operator writes the request and never reads the Secret it produces.
   */
  case EnsureCertificate(certificate: GenericKubernetesResource)

  /** A project's database authority: its self-signed root and the Issuer over it. */
  case EnsureIssuer(issuer: GenericKubernetesResource)

  /** Who may connect to a workload's ports at all. */
  case EnsureNetworkPolicy(policy: NetworkPolicy)

  /** Owner-checked and read-first, like `RemoveService`: a service that stopped serving HTTP. */
  case RemoveNetworkPolicy(namespace: String, name: String, ownerUid: String)

  /** The gateway's instruction to reach an exposed service over TLS; rendered with its route. */
  case EnsureBackendTlsPolicy(policy: BackendTLSPolicy)

  /**
   * Removed with the route, owner-checked, and absent-safe on a cluster without the Gateway API.
   */
  case RemoveBackendTlsPolicy(namespace: String, name: String, ownerUid: String)

  def describe: String = this match
    case EnsureNamespace(name) => s"ensure namespace $name"
    case ApplyDeployment(d) =>
      s"apply deployment ${d.getMetadata.getNamespace}/${d.getMetadata.getName}"
    case DeleteDeployment(ns, name) => s"delete deployment $ns/$name"
    case EnsureService(svc) =>
      s"ensure service ${svc.getMetadata.getNamespace}/${svc.getMetadata.getName}"
    case RemoveService(ns, name, _) => s"remove service $ns/$name if owned"
    case EnsureHttpRoute(r) =>
      s"ensure httproute ${r.getMetadata.getNamespace}/${r.getMetadata.getName}"
    case RemoveHttpRoute(ns, name, _) => s"remove httproute $ns/$name if owned"
    case EnsureServiceAccount(sa) =>
      s"ensure serviceaccount ${sa.getMetadata.getNamespace}/${sa.getMetadata.getName}"
    case EnsureRole(r) => s"ensure role ${r.getMetadata.getNamespace}/${r.getMetadata.getName}"
    case EnsureRoleBinding(b) =>
      s"ensure rolebinding ${b.getMetadata.getNamespace}/${b.getMetadata.getName}"
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
    case EnsureCertificate(c) =>
      s"ensure certificate ${c.getMetadata.getNamespace}/${c.getMetadata.getName}"
    case EnsureIssuer(i) =>
      s"ensure ${i.getKind.toLowerCase} ${i.getMetadata.getNamespace}/${i.getMetadata.getName}"
    case EnsureNetworkPolicy(p) =>
      s"ensure networkpolicy ${p.getMetadata.getNamespace}/${p.getMetadata.getName}"
    case RemoveNetworkPolicy(ns, name, _) => s"remove networkpolicy $ns/$name if owned"
    case EnsureBackendTlsPolicy(p) =>
      s"ensure backendtlspolicy ${p.getMetadata.getNamespace}/${p.getMetadata.getName}"
    case RemoveBackendTlsPolicy(ns, name, _) => s"remove backendtlspolicy $ns/$name if owned"
