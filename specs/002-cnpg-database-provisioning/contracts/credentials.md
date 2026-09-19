# Contract: Credentials

**Satisfies**: FR-011 to FR-015, FR-020

## Generation

| Property | Value | Why |
|---|---|---|
| Source | `java.security.SecureRandom` | FR-011 |
| Length | ≥ 32 characters | |
| Alphabet | alphanumeric only | avoids quoting hazards in a URI, a `pgpass` line or a `psql -c` argument |
| When | **only when the secret does not exist** | rotating on every reconcile is an outage on a timer |
| Where | the service's own namespace | no cross-namespace copying (research R2) |

`Passwords.generate` is called from exactly one place, behind an existence check. This is the single
most important rule in this contract: every other action the operator takes is a server-side apply
and therefore idempotent by construction, and this one is not.

## Delivery

The service's container gets the whole secret with one `envFrom`, so the runtime sees
`ANKKA_DB_HOST`, `ANKKA_DB_PORT`, `ANKKA_DB_NAME`, `ANKKA_DB_USER` and `ANKKA_DB_PASSWORD` exactly
as it does today when a descriptor supplies them by hand. **The runtime is unchanged** — it cannot
tell the difference between a provisioned database and a supplied one, which is what makes the
escape hatch free.

## Non-disclosure (FR-012)

| Rule | Enforcement |
|---|---|
| Never in a service descriptor | the descriptor has no field for it |
| Never in a file under version control | generated at reconcile time, in-cluster only |
| Never in a status `detail` | `detail` is built only from CNPG `status.message` and rendering problems, neither of which carries secret data |
| Never in a log line | the operator logs object names, never secret contents |
| Not readable by another service | Kubernetes RBAC; a service pod mounts only its own secret |

## The control plane's credentials are different, and separately handled

The control plane's database is bootstrapped by CNPG (`bootstrap.initdb`), so **CNPG generates that
password itself** and none of the above applies. Its Deployment maps CNPG's auto-generated
`{cluster}-app` secret into `ANKKA_DB_*` key by key (verified names, research R8):

| CNPG key | ankka variable |
|---|---|
| `host` | `ANKKA_DB_HOST` |
| `port` | `ANKKA_DB_PORT` |
| `dbname` | `ANKKA_DB_NAME` |
| `username` | `ANKKA_DB_USER` |
| `password` | `ANKKA_DB_PASSWORD` |

Key-by-key rather than `envFrom`, because CNPG's key names are its own (`host`, not
`ANKKA_DB_HOST`).

## Separation (FR-020)

The control plane's database lives in `ankka-controlplane`; every service's lives in its project's
namespace. Neither side has RBAC to read the other's secret, and — after the init container's
`REVOKE CONNECT` — a service's role cannot connect to another service's database either. The
control plane's role is the owner of its own database only.
