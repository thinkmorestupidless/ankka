# Contract: `NakkaServiceClient` — the Control Plane's Seam

**Satisfies**: FR-005 to FR-011, FR-037

The only interface between the control plane and the cluster. `io.fabric8` types **must not appear**
in this signature, and only `nakka.controlplane.deploy.Fabric8NakkaServiceClient` may import
fabric8 — checkable with one `grep`.

```scala
package nakka.controlplane.deploy

/**
 * Access to NakkaService resources, and nothing else in the cluster.
 *
 * Blocking by design: callers run on `NakkaExecutors.virtual`. Implementations throw on failure;
 * the projector classifies and backs off.
 */
trait NakkaServiceClient:

  /** Writes desired state. Idempotent — an unchanged spec performs no write. (FR-005) */
  def put(namespace: String, name: String, spec: NakkaServiceSpec): Unit

  /** Removes the resource. Children cascade. Succeeds if already absent. (FR-009, FR-029) */
  def delete(namespace: String, name: String): Unit

  /** Every NakkaService the control plane owns, for the periodic sweep. */
  def list(): Vector[NakkaServiceResource]

  /** Status changes, delivered as they happen. (FR-008) */
  def watch(onStatus: NakkaServiceResource => Unit): AutoCloseable
```

`NakkaServiceResource` is `(namespace, name, spec, status: Option[NakkaServiceStatus], uid)`.

## Obligations

| # | Obligation | Why |
|---|---|---|
| C1 | `put` is idempotent, and an unchanged spec performs **no** write | FR-005, SC-003 |
| C2 | `put` enforces the spec, reverting an out-of-band `kubectl edit` of it | FR-007 — the control plane's record is authoritative |
| C3 | `put` never writes `status` | FR-003 — status is the operator's, enforced by RBAC too |
| C4 | `delete` on an absent resource succeeds | FR-009; deletion is retried |
| C5 | No method touches any object other than a `NakkaService` | FR-011, SC-008 |
| C6 | Failures throw rather than returning a sentinel | one classification point; a swallowed failure becomes a falsely `confirmed` observation |
| C7 | No method retries internally | FR-032 owns backoff; nested retries make the outer budget meaningless |
| C8 | `watch` reconnects on disconnect, and the caller is told when it is not connected | FR-030 — a silently dead watch reports stale state as confirmed |

## `FakeNakkaServiceClient` (test)

In-memory, satisfying C1–C8, plus controls a test drives directly:

- `failNext(times, error)` — FR-032 backoff and FR-030 `confirmed = false`
- `setStatus(key, status)` — simulate the operator without running one
- `clearStatus(key)` — FR-031, a resource nothing has reported on
- `driftEdit(key, f)` / `driftDelete(key)` — FR-007, out-of-band edits to the resource
- `disconnect()` / `reconnect()` — C8

This is what lets the projector, the trigger consumer, retry behaviour and status ingest all run in
the offline suite (SC-010).

## Why the seam is this narrow

The control plane can reach exactly one resource kind. That is the whole of FR-011 and most of
FR-036: its RBAC grants `nakkaservices` and nothing else, so even a compromised control plane
cannot create a workload, read a secret, or touch another tenant's objects. The narrowness is the
security property, and the interface makes it obvious at review time rather than only in a
ClusterRole nobody reads.
