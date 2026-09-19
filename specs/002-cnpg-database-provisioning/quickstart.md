# Quickstart: Validating Platform-Provisioned Databases

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

Fastest signal first. These are the runs a reviewer performs; implementation detail belongs in
`tasks.md`.

---

## Prerequisites

| Need | For | Notes |
|---|---|---|
| JDK 21, sbt | everything | unchanged |
| Docker | integration and cluster suites | unchanged |
| A local cluster | the manual walkthrough | `kind create cluster --name ankka`; the suites start their own |
| Network access | installing CNPG | its manifest is fetched from GitHub by URL — the one component not vendored |

---

## Tier 1 — Pure logic (seconds, no Docker)

Provisioning decisions, rendering and phase classification are total functions over data.

```bash
sbt 'operator/testOnly com.thinkmorestupidless.ankka.operator.ProvisioningSuite'
sbt 'operator/testOnly com.thinkmorestupidless.ankka.operator.CnpgRenderingSuite'
sbt 'operator/testOnly com.thinkmorestupidless.ankka.operator.SchemaInitSuite'
sbt 'controlPlane/testOnly com.thinkmorestupidless.ankka.controlplane.ServiceProjectionSuite'
```

| Suite | Proves |
|---|---|
| `ProvisioningSuite` | Every row of [contracts/provisioning-rules.md](./contracts/provisioning-rules.md). Critically: the two transient CNPG messages (`forbidden`, `role does not exist`) classify as `Waiting`, **not** `Failed`; a real rejection classifies as `Failed`; and a steady state decides `NothingToDo`. |
| `CnpgRenderingSuite` | Cluster/Database/DatabaseRole render with the fields in [contracts/cnpg-resources.md](./contracts/cnpg-resources.md); `retain` reclaim policies are set; rendering is deterministic; hyphenated service names render unchanged (no normalisation). |
| `SchemaInitSuite` | The init container is rendered on the provisioned path and **not** on the escape-hatch path; it mounts the schema `ConfigMap`; its script contains the `REVOKE CONNECT` step; the pod template changes when the schema changes. |
| `ServiceProjectionSuite` | `provisionDatabase` is `false` exactly when the descriptor declares any `ANKKA_DB_*` env var, and `true` otherwise. |

---

## Tier 2 — The control plane, with Postgres and a fake cluster (minutes, Docker)

```bash
sbt 'controlPlane/testOnly com.thinkmorestupidless.ankka.controlplane.ProjectorSuite'
```

Mostly a regression check — the control plane's own behaviour barely changes — plus:

- The projected `AnkkaService` carries `provisionDatabase`, and the CLI-visible status reports
  which path a service took (FR-017).
- **Zero writes in steady state still holds** (SC-010), now with the database fields present.

---

## Tier 3 — The operator against a real cluster (slower, Docker)

```bash
sbt 'operator/testOnly com.thinkmorestupidless.ankka.operator.OperatorClusterSuite'
```

Installs CNPG into the throwaway k3s cluster, waits for its controller, then exercises what only a
real API server and a real Postgres can show:

- A project's `Cluster` is created lazily on first service, and **one** cluster results from two
  services applied together (FR-003).
- A service's `Database`, `DatabaseRole` and credential secret are created, and the service's pod
  reaches `Ready` having connected to its own database.
- **The transient window is survived, not reported as failure** — assert the service never enters
  `Failed` while CNPG's RBAC allowlist catches up (research R5). This is the single most valuable
  assertion in the suite, because the bug it guards is a permanent-looking error on every first
  deploy.
- **Isolation, measured rather than assumed** (research R9): with two services deployed, service
  A's credentials are refused `CONNECT` to service B's database, and refused `SELECT` on its
  tables.
- **Idempotence**: ten reconciles produce provisioning writes once (FR-008).
- **Nothing is destroyed**: delete the service, assert the `Database` and the data survive; re-apply
  the name and assert the data comes back and the status says `recovered` (FR-023, FR-026).
- **The `delete` verb is genuinely absent** — assert the operator's ServiceAccount cannot delete a
  `Database` even when asked directly (FR-024, structurally).

---

## Tier 4 — Both halves, end to end (slowest)

```bash
sbt 'controlPlane/testOnly com.thinkmorestupidless.ankka.controlplane.EndToEndClusterSuite'
```

Postgres, k3s, CNPG, the real control plane, the real operator, driven through the CLI. Walks the
headline: a descriptor with **no database configuration at all** produces a running service on its
own database, and `ankka services list` shows it `Ready`.

Also: the control plane itself now runs on a CNPG `Cluster`, so this suite proves the bootstrap
path (research R8) as a side effect of starting up at all.

To skip everything needing a cluster:

```bash
sbt -Dankka.cluster.tests=off test
```

---

## Tier 5 — Manual walkthrough

```bash
kind create cluster --name ankka
./kustomization/deploy-local.sh          # now installs CNPG too, and waits for it
kubectl -n ankka-controlplane port-forward svc/ankka-controlplane 9000:9000 &

ankka config set url http://localhost:9000
ankka config set token dev-local-token
ankka organizations create acme --name "Acme Corp"
ankka projects create checkout --name Checkout -O acme
ankka config set project checkout
```

A descriptor with **nothing about databases in it**. (`"http": false` arrived with feature 003: a
descriptor now defaults to "serves HTTP on 9000" and is not `Ready` until it does, and `pause`
listens on nothing. To see a real service instead, deploy `sample-shopping-cart:latest` — see
[feature 003's quickstart](../003-deploy-real-service/quickstart.md).)

```json
{ "name": "cart", "service": { "image": "registry.k8s.io/pause:3.9", "http": false } }
```

```bash
ankka services apply -f cart.json
ankka services list
# NAME  STATUS  INSTANCES  GEN  IMAGE
# cart  Ready   1/1        1    registry.k8s.io/pause:3.9

kubectl -n ankka-checkout get cluster,database,databaserole,secret,deploy
kubectl -n ankka-checkout describe asvc cart | sed -n '/database/,+6p'
```

| Action | Expected |
|---|---|
| first `apply` in a project | a `Cluster` appears, then a `Database` and `DatabaseRole`; the service waits, then goes `Ready` — and **never** reports `Failed` on the way |
| apply a second service | reuses the same `Cluster`; `Ready` much faster |
| `psql` as service A into service B's database | refused: `permission denied for database` |
| `ankka services delete cart` | Deployment goes; **`Database` and its data remain** |
| re-apply `cart` | comes back with its old data, and the status says it recovered rather than provisioned |
| a descriptor carrying `ANKKA_DB_*` env vars | deploys, provisions nothing, no init container, status says `supplied` |

**Verifying the data actually persists** — the point of the whole feature:

```bash
kubectl -n ankka-checkout exec ankka-db-1 -c postgres -- psql -U postgres -c '\l' | grep cart
kubectl -n ankka-checkout exec ankka-db-1 -c postgres -- psql -U postgres -d cart -c '\dt'
# expect event_journal, snapshot, durable_state, projection_*, ankka_timers
```

---

## Reviewer's checklist

- [ ] Tier 1 runs with no Docker daemon at all.
- [ ] `sbt compile` warning-free; `sbt scalafmtCheckAll scalafmtSbtCheck` clean.
- [ ] `grep -rl "postgresql.cnpg.io" controlplane/src cli/src crd/src` returns **nothing** — CNPG is the operator's business alone.
- [ ] `cli`'s dependencies unchanged; `crd` still depends on no ankka module.
- [ ] `modules/runtime/src/main/resources/ankka/ddl/` is still the only real copy of the schema; the operator's path is a symlink to it.
- [ ] `docker compose up -d` + `sbt shoppingCart/run` still works — local development untouched.
- [ ] The operator's `ClusterRole` grants **no `delete`** on `clusters`, `databases` or `databaseroles`, and **no `list`** on `secrets`.
- [ ] `CLAUDE.md`'s claim that the operator has no verb on `secrets` is **corrected**, not left standing — it is no longer true, and an overstated security property is worse than an accurate weaker one.
- [ ] `README.md`'s "Databases are yours to provide" gap is rewritten: provisioning now exists, the escape hatch remains, and one-database-per-service is enforced on the provisioned path only.
- [ ] `README.md`'s note that the control plane's Postgres uses `emptyDir` is gone — it is a real PVC now.
