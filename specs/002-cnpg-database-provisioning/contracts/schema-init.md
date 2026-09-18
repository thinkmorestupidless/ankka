# Contract: The Schema Init Container

**Satisfies**: FR-009 to FR-012, and the isolation half of FR-006/FR-007

One init container, rendered onto every provisioned service's Deployment, ahead of the service's
own container. It does three things in order and then exits.

```yaml
initContainers:
  - name: nakka-schema
    image: postgres:17-alpine          # already used by the control plane's wait-for-postgres
    envFrom:
      - secretRef:
          name: cart-db                # the generated credentials
    volumeMounts:
      - name: nakka-schema
        mountPath: /schema
        readOnly: true
    command: ["sh", "-c", "<see below>"]
volumes:
  - name: nakka-schema
    configMap:
      name: nakka-schema               # published per project namespace by the operator
```

## What it runs

```sh
set -e

# 1. Wait. The project's Cluster may still be starting, and the role may not exist yet
#    because CNPG's per-cluster RBAC allowlist has not caught up (see research R5).
until pg_isready -h "$NAKKA_DB_HOST" -p "$NAKKA_DB_PORT" -U "$NAKKA_DB_USER"; do sleep 1; done
until psql -c 'select 1' >/dev/null 2>&1; do sleep 2; done

# 2. Apply nakka's schema. Every statement is IF NOT EXISTS, so this runs on every start
#    rather than only the first, which makes it self-healing after a restore.
for f in /schema/*.sql; do psql -v ON_ERROR_STOP=1 -f "$f"; done

# 3. Close the database. Postgres grants CONNECT to PUBLIC by default, so without this any
#    other service's role can connect to this database and enumerate every database name.
#    The owner can revoke it without locking itself out, and REVOKE is idempotent.
psql -v ON_ERROR_STOP=1 -c "REVOKE CONNECT ON DATABASE \"$NAKKA_DB_NAME\" FROM PUBLIC;"
```

`psql` reads `PGHOST`/`PGPORT`/`PGUSER`/`PGPASSWORD`/`PGDATABASE`; the operator sets those from the
same credential secret alongside the `NAKKA_DB_*` names, so no connection string is assembled.

## Obligations

| # | Obligation | Why |
|---|---|---|
| S1 | The service's container MUST NOT start unless this succeeds | FR-010 — a service must never run against a half-built database |
| S2 | Safe to run on every start, not just the first | FR-011; all ten statements are `IF NOT EXISTS` |
| S3 | Waits rather than failing while the database is not yet reachable | R4, R5 — both transient conditions land here |
| S4 | `ON_ERROR_STOP=1` on the schema and hardening steps | a partially applied schema that reported success is worse than a failure |
| S5 | Revokes `PUBLIC` connect on its own database | FR-007; **verified necessary** — see below |
| S6 | Never echoes `PGPASSWORD` or any secret value | FR-012 |
| S7 | Not rendered at all on the escape-hatch path | FR-016 — the caller owns their database, including its schema |

## Why step 3 exists

Measured on a real cluster with two roles on one `Cluster` (research R9):

| Attempt by service A against service B's database | Default | After `REVOKE` |
|---|---|---|
| `SELECT` from B's table | denied | denied |
| `CREATE TABLE` in B's database | denied | denied |
| `CONNECT` to B's database | **allowed** | **denied** |
| Enumerate all database names | **allowed** | allowed (catalog is global) |

Table data is private by default, which is what makes the `nakka_timers` collision structurally
impossible (SC-005). But `CONNECT` is not, and FR-007 says credentials must not grant access to
another service's database. Without step 3 this feature would ship a guarantee it does not have.

## The schema `ConfigMap`

Published by the operator into each project namespace as `nakka-schema`, from the schema on its own
classpath at `/nakka/ddl/*.sql` — which is a **directory symlink** to
`modules/runtime/src/main/resources/nakka/ddl`, the canonical copy (research R6, verified).

| Obligation | Why |
|---|---|
| One copy in the repository | `CLAUDE.md`'s single-copy rule; `docker-compose` and `NakkaTestKit` still read the same files |
| Re-applied on every reconcile | a schema change must reach existing namespaces |
| Content-addressed or checksummed in the pod template | otherwise a changed schema does not restart pods, and nothing applies it |

The last row is the easy one to miss: mounting a `ConfigMap` that later changes does not restart
anything, so a schema update would sit unapplied until something else happened to roll the pod.
