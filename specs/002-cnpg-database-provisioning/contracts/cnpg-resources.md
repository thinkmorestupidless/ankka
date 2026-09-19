# Contract: CNPG Resources ankka Renders

**Satisfies**: FR-001 to FR-008

Three partial fabric8 models under `operator/.../cnpg/`, all `postgresql.cnpg.io/v1` and namespaced.
ankka sets only the fields below; server-side apply means it owns only those and leaves CNPG's
defaults for everything else.

## `Cluster` — per-project capacity

```yaml
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata:
  name: ankka-db                    # one per project
  namespace: ankka-checkout         # the project's own namespace
  labels:
    app.kubernetes.io/managed-by: ankka
    ankka.thinkmorestupidless.com/project: checkout
spec:
  instances: 1
  storage:
    size: 1Gi
```

**No `bootstrap.initdb`.** A project's cluster hosts databases that arrive as `Database` objects;
bootstrapping one named database into it would create a database no service owns.

| Obligation | Why |
|---|---|
| Created lazily, on reconciling the first service in the project | FR-002, FR-005 — never for a project with no services |
| Creation is idempotent and concurrency-safe | FR-003 — two simultaneous first-service applies must converge on one cluster, not two |
| `storage.size` is a real PVC, never `emptyDir` | FR-004 — the hand-rolled Postgres in 001 used `emptyDir` and lost data on pod restart |
| Never deleted | FR-024, and structurally enforced by withholding the verb (R12) |

Read back: `status.readyInstances`. Anything less than 1 means `Waiting`, not `Failed`.

## `DatabaseRole` — the service's identity

```yaml
apiVersion: postgresql.cnpg.io/v1
kind: DatabaseRole
metadata:
  name: cart
  namespace: ankka-checkout
spec:
  cluster:
    name: ankka-db
  name: cart                        # hyphens are safe — CNPG quotes identifiers (R10)
  login: true
  passwordSecret:
    name: cart-db
  databaseRoleReclaimPolicy: retain
```

**`passwordSecret` must already exist**, and CNPG will not generate the password (R3). The secret
must be `kubernetes.io/basic-auth` with `username` and `password`.

> **`status.message` containing `forbidden` is transient.** CNPG adds a newly-referenced secret to
> its per-cluster RBAC allowlist only on its next `Cluster` reconcile — measured at 20–40 seconds.
> Treating it as terminal reports a permanent authorization failure for something that fixes
> itself. See R5.

## `Database` — the service's database

```yaml
apiVersion: postgresql.cnpg.io/v1
kind: Database
metadata:
  name: cart
  namespace: ankka-checkout
spec:
  cluster:
    name: ankka-db
  name: cart
  owner: cart                       # the DatabaseRole above
  databaseReclaimPolicy: retain
```

> **`status.message` of `role "cart" does not exist` is also transient** — the role and the
> database are applied together and reconcile independently, so the database can lose the race
> (R4).

## The credential `Secret`

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: cart-db
  namespace: ankka-checkout
type: kubernetes.io/basic-auth
stringData:
  username: cart
  password: <generated>
  # Connection details too, so the Deployment needs one envFrom and not a join
  ANKKA_DB_HOST: ankka-db-rw
  ANKKA_DB_PORT: "5432"
  ANKKA_DB_NAME: cart
  ANKKA_DB_USER: cart
  ANKKA_DB_PASSWORD: <generated>
```

`username`/`password` are there because `DatabaseRole` requires exactly those keys; the `ANKKA_DB_*`
keys are there so the service's container can consume the whole thing with one `envFrom` and the
init container can use the same source.

| Obligation | Why |
|---|---|
| **Created only if absent** — never server-side applied over | FR-008. Regenerating on each pass rotates the password under a running service on every sweep |
| Password from `SecureRandom`, ≥ 32 chars, alphanumeric | FR-011; alphanumeric avoids quoting problems in a URI or a `psql` invocation |
| Never logged, never placed in any status `detail` | FR-012 |

## Naming

| Thing | Name | Uniqueness |
|---|---|---|
| Project cluster | `ankka-db` | one per namespace, so one per project |
| `Database` / `DatabaseRole` / Postgres database / Postgres role | `{serviceName}` | unique within the project's namespace — two projects may both have `cart` (FR-006) |
| Credential secret | `{serviceName}-db` | same |

No project-qualified prefix: namespaces already scope these, and verified hyphen handling (R10)
means no normalisation — which matters, because normalising `-` to `_` would collide `my-cart`
with `my_cart`.
