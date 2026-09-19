# Phase 1 Data Model: Platform-Provisioned Databases

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Research**: [research.md](./research.md)

Three layers: two fields added to the existing contract, partial models of three CNPG kinds, and
the pure values the operator computes.

---

## 1. The contract gains two fields

### `AnkkaServiceSpec` — `crd/src/main/scala/ankka/crd/AnkkaService.scala`

| Field | Type | Notes |
|---|---|---|
| `provisionDatabase` | `Boolean = true` | **NEW.** `false` means the descriptor supplied its own connection details and the platform provisions nothing (R11). Set by the control plane, never inferred by the operator. |

Defaulted `true` so a resource written by an older control plane is provisioned rather than
silently skipped — the safe direction, since skipping would leave a service with no database at all.

### `AnkkaServiceStatus` — same file

| Field | Type | Notes |
|---|---|---|
| `database` | `Option[DatabaseStatus]` | **NEW.** What the platform did about this service's database. Absent on the escape-hatch path. |

```scala
final case class DatabaseStatus(
    /** "Provisioned", "Waiting", "Recovered", "Supplied", "Failed" — see §4. */
    phase: String = "",
    /** The database's name, so an operator can find it without reading a secret. */
    name: String = "",
    /** The `Cluster` serving it, so shared capacity is visible. */
    cluster: String = "",
    /**
     * True when this service was given a database that already existed.
     *
     * FR-026: retaining databases means a re-applied service name inherits old data. That is the
     * accepted cost of never destroying anything, and it has to be visible rather than surprising.
     */
    recovered: Boolean = false,
    detail: Option[String] = None
)
```

**Why this is on the status and not inferred**: FR-017 requires an operator to be able to *tell*
which path a service took, and FR-026 requires recovery to be visible. Both are reporting
requirements, so both belong in the one place the platform reports.

### `ServiceStatus` — `controlplane-api/.../descriptors.scala`

Gains `database: Option[String]` — a short human phrase (`"provisioned"`, `"supplied"`,
`"recovered existing data"`) for `ankka services get`. Not the full structure: the CLI's job is to
say which path was taken, not to mirror a Kubernetes status.

---

## 2. Partial CNPG models (`operator/.../cnpg/`)

Only the fields ankka sets. Server-side apply gives per-field ownership, so modelling six fields of
`Cluster.spec` and ignoring the rest is correct, not lossy (R1). All three are
`postgresql.cnpg.io/v1`, namespaced, and carry `@JsonInclude(NON_ABSENT)` so an unset field is
omitted rather than sent as null.

### `PostgresCluster` — `kind: Cluster`

| Field | Set to |
|---|---|
| `instances` | 1 (configurable) |
| `storage.size` | configurable, default `1Gi` |
| `bootstrap.initdb.database` / `.owner` | only for the control plane's own cluster (R8); omitted for a project's cluster, whose databases arrive as `Database` objects |
| `enableSuperuserAccess` | omitted — left at CNPG's default |

Read back: `status.readyInstances`, to decide whether capacity is usable yet.

### `PostgresDatabase` — `kind: Database`

| Field | Set to |
|---|---|
| `name` | the service name, hyphens included (R10) |
| `owner` | the same name — the role from `PostgresDatabaseRole` |
| `cluster.name` | the project's cluster |
| `databaseReclaimPolicy` | `retain` — R12 |

Read back: `status.applied`, `status.message`.

### `PostgresDatabaseRole` — `kind: DatabaseRole`

| Field | Set to |
|---|---|
| `name` | the service name |
| `cluster.name` | the project's cluster |
| `login` | `true` |
| `passwordSecret.name` | the generated credential secret |
| `databaseRoleReclaimPolicy` | `retain` — R12 |

Read back: `status.applied`, `status.message` — and **a `forbidden` message here is transient**
(R5), which the classification rules must know.

> **Ordering.** The role must exist before the database can be owned by it (R4). Both are applied
> in one pass and reconcile independently, so the database may transiently report
> `role "x" does not exist`. Neither that nor R5's `forbidden` is a failure.

---

## 3. Operator pure values

### `Provisioning.decide`

```
decide(spec: AnkkaServiceSpec, observed: DatabaseObservation, config: Settings)
  : Either[Vector[String], ProvisioningPlan]
```

Total and pure. Full rules in [contracts/provisioning-rules.md](./contracts/provisioning-rules.md).

### `ProvisioningPlan`

| Case | Meaning |
|---|---|
| `Supplied` | the descriptor brought its own database; render no CNPG objects and no init container |
| `Provision(cluster, role, database, credentials)` | the four objects this service needs |
| `NothingToDo` | already provisioned and unchanged — **no writes** (FR-008, SC-010) |

### `DatabaseObservation`

What one read of the cluster found: whether the project's `Cluster` exists and how many instances
are ready; whether the `Database`, `DatabaseRole` and credential `Secret` exist; and the `applied`
/ `message` of each. The only input to phase classification, which is what keeps that classification
a pure unit test.

### `Credentials`

`username`, `password`, `host`, `port`, `dbname`. Generated once (R3) and **never regenerated** —
`Passwords.generate` is called only when the secret is absent, because rotating under a running
service on every sweep is a self-inflicted outage on a timer.

### New `Action` cases

Inert, as in 001; `Fabric8Executor` stays the only interpreter.

| Case | Notes |
|---|---|
| `EnsureCluster(cluster)` | idempotent; concurrent first-service applies converge (FR-003) |
| `EnsureCredentials(secret)` | **create-only-if-absent**, not apply — the one action that must not overwrite |
| `EnsureDatabaseRole(role)` | after credentials |
| `EnsureDatabase(database)` | after the role (R4) |
| `EnsureSchemaConfig(configMap)` | the schema, published per project namespace (R6) |

---

## 4. Database phases

Derived every pass from spec plus observation, never stored — the same discipline as the service
lifecycle in 001.

```
(spec.provisionDatabase = false) ──────────────────────▶ Supplied      (terminal; nothing rendered)

(no Cluster yet) ──▶ Waiting ──Cluster ready──▶ Waiting ──role+db applied──▶ Provisioned
                       │                          │
                       │                          ├── database existed already ──▶ Recovered
                       │                          └── forbidden / role-not-yet ──▶ Waiting  (R4, R5)
                       │
                       └── rendering invalid, or a real rejection ──▶ Failed
```

`Waiting` is load-bearing and new: the two transient CNPG conditions (R4, R5) and a `Cluster` still
starting all resolve on their own, and reporting any of them as `Failed` would be the single most
misleading thing this feature could do (FR-026, FR-034).

`Recovered` exists only to satisfy FR-026 — it is `Provisioned` plus the fact that the data was
already there.

---

## 5. Relationships

```
Organization 1─* Project 1─* Service                      (control plane, its own CNPG Cluster)
                    │            │
                    │            └─ spec.provisionDatabase ──┐
                    │                                        ▼
                    ▼                              ┌──────────────────────┐
         Namespace ankka-{projectId}               │ provision, or supply │
                    │                              └──────────────────────┘
                    ├─ Cluster  "ankka-db"          (one per project, lazily created)
                    │     ├─ Database      {service}   owner = {service}
                    │     └─ DatabaseRole  {service}   passwordSecret = {service}-db
                    ├─ Secret   {service}-db           (generated once, never rotated)
                    ├─ ConfigMap "ankka-schema"        (the DDL, one per namespace)
                    └─ Deployment {service}
                          └─ initContainer: wait → apply schema → REVOKE CONNECT FROM PUBLIC
                          └─ container: env ← Secret {service}-db
```

Everything for one service sits in one namespace — which is what R2's namespace constraint forces,
and incidentally what removes any need to copy credentials between namespaces.
