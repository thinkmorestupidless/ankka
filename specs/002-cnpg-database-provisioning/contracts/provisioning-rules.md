# Contract: The Provisioning Decision

**Satisfies**: FR-005, FR-008, FR-013 to FR-019, FR-030 to FR-034

```
Provisioning.decide(spec, observed, config): Either[Vector[String], ProvisioningPlan]
```

Total, pure, clock-free. Every rule below is a unit test with no cluster.

## Rules, in order — first match wins

| # | Condition | Plan | Reported phase |
|---|---|---|---|
| 1 | `spec.provisionDatabase == false` | `Supplied` | `Supplied` |
| 2 | the project id or service name cannot be rendered into valid names | — (`Left`) | `Failed` |
| 3 | no `Cluster` in the project's namespace | `Provision` (cluster first) | `Waiting` |
| 4 | `Cluster` exists, `readyInstances < 1` | `Provision` (no-op on the cluster) | `Waiting` |
| 5 | credential secret absent | `Provision` (generate) | `Waiting` |
| 6 | role or database absent | `Provision` | `Waiting` |
| 7 | role or database reports a transient message (R4, R5) | `Provision` (re-apply) | `Waiting` |
| 8 | role or database reports a non-transient rejection | — (`Left`) | `Failed` |
| 9 | everything present and applied, database pre-existed this service | `NothingToDo` | `Recovered` |
| 10 | everything present and applied | `NothingToDo` | `Provisioned` |

**Rule 1 first** because the escape hatch means "the platform does nothing", and every later rule
would otherwise try to provision something the caller already owns.

**Rules 7 and 8 are the one split that matters.** Two CNPG messages resolve on their own and must
never reach `Failed`:

- `secrets "<name>" is forbidden: ... cannot get resource "secrets"` — CNPG's per-cluster RBAC
  allowlist has not caught up (R5)
- `role "<name>" does not exist` — the role and database are reconciling independently (R4)

Anything else — a quota rejection, an invalid value, a storage class that does not exist — is a
real failure and belongs in rule 8.

**Rule 9 versus 10** is FR-026: a service whose database already existed before this service was
created must say so, because retaining databases means a re-applied name silently inherits old
data. Detected by the `Database` object already existing when the service's own credential secret
had to be created fresh, or by CNPG's own `Database` being older than the `AnkkaService`.

## Idempotence (FR-008, SC-010)

`NothingToDo` must be reachable and must mean *zero writes* — no re-apply of the cluster, the role,
the database or the secret. Ten consecutive applies of an unchanged descriptor perform provisioning
work exactly once. This is asserted directly, by counting writes against a fake, because the
failure mode is invisible: everything works, and the API server takes continuous write load
forever.

## The escape hatch (FR-016 to FR-019)

The control plane sets `provisionDatabase = false` when the descriptor's `env` declares **any**
variable whose name begins `ANKKA_DB_`. The rule lives in `controlplane-api` so the CLI applies the
same one before the round trip.

On that path the platform renders no CNPG objects, no credential secret and **no init container** —
so it also does not apply ankka's schema or the `REVOKE CONNECT` hardening. The caller owns all of
it. FR-019 requires this be documented rather than implied: the platform is not enforcing
one-database-per-service here, and cannot.

## What the operator must not do

| Must not | Why |
|---|---|
| Regenerate an existing password | rotates credentials under a running service on every sweep |
| Delete a `Cluster`, `Database` or `DatabaseRole` | FR-024; it has no RBAC verb for it anyway (R12) |
| Report a transient CNPG message as `Failed` | R4, R5 — the most misleading failure this feature can produce |
| Provision for a project with no services | FR-005 |
| Put a password in a status `detail` or a log line | FR-012 |
