# Phase 0 Research: Platform-Provisioned Databases

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Date**: 2026-09-16

Every CNPG behaviour below was **checked against a running CNPG 1.30.0 on a local kind cluster**
during planning. That mattering is not a formality: the published documentation was inconsistent or
silent on R3, R5 and R9, and R9 in particular would have shipped a security guarantee that is false
by default.

---

## R0 — Why CloudNativePG at all

**Decision**: CNPG 1.30.0, installed by manifest as a platform dependency.

**Rationale**: Named explicitly in the request. It is also the only option that makes
"a database per service" a *declarative* operation rather than SQL the platform has to execute
itself: `Database` and `DatabaseRole` are Kubernetes objects with their own reconcilers, so the
operator asks for a database the same way it asks for a Deployment, and the same
`Action`/`Executor` split already in place applies unchanged.

**Alternatives considered**: the operator connecting to Postgres directly and running
`CREATE DATABASE` / `CREATE ROLE` itself (works, and is how many platforms do it — rejected because
it puts SQL execution, credential handling and retry logic in the operator for something a
reconciler already does, and because it needs superuser credentials the operator would then hold);
one Postgres per service via plain `StatefulSet`s (no declarative database/role management at all,
and disproportionate resource cost).

---

## R1 — Where CNPG's types live in the build

**Decision**: partial fabric8 `CustomResource` models under `operator/.../cnpg/`. No new module, no
new Scala dependency, and nothing added to `crd`.

**Rationale**: Only the operator talks to CNPG. `crd` holds the `NakkaService` contract between the
control plane and the operator — CNPG is not part of that contract, and putting it there would
imply the control plane needs it. Partial models are safe with server-side apply: a manager owns
only the fields it sends, so modelling six fields of `Cluster.spec` and ignoring the other few
hundred is correct rather than lossy.

**Alternatives considered**: fabric8's `GenericKubernetesResource` (no model code, but rendering
becomes string-keyed map-building — untestable by comparison and with no compile-time safety, for a
feature whose whole testing story rests on pure rendering functions); a full generated CNPG model
(none is published for Scala, and generating one is disproportionate for three kinds).

---

## R2 — Topology: per-project Postgres capacity

**Decision**: one CNPG `Cluster` per project, in that project's existing namespace, created lazily
when the first service in the project is reconciled.

**Rationale**: **Forced, not preferred.** Verified: a `Database` in namespace B referencing a
`Cluster` in namespace A gets **no status and no events at all** — the reconciler never touches it
— while the same object in the `Cluster`'s own namespace reports `{"applied":true}`. Since projects
already own namespaces, per-project capacity is the only arrangement in which a service's database,
its credentials and its workload all land in one namespace with no cross-namespace copying.

**Alternatives considered**: one `Cluster` per service (a whole Postgres instance and PVC per
service — disproportionate, and SC-008 explicitly wants 5 instances for 50 services); one global
`Cluster` in a shared namespace (would force every service's `Database`, `DatabaseRole` and
credential `Secret` into a namespace no project owns, then copied back out — more moving parts and
a worse blast radius).

---

## R3 — Credentials: the platform must generate them

**Decision**: the operator generates a password with `SecureRandom`, writes it to a
`kubernetes.io/basic-auth` `Secret`, and references that from the `DatabaseRole`. **It generates
only when the secret is absent** — never on a later pass.

**Rationale**: Verified: `DatabaseRole.spec.passwordSecret` requires a caller-supplied secret
containing `username` and `password`; given none, CNPG creates a role with no password. CNPG does
not generate passwords, and the documentation does not say so clearly. The create-only-if-absent
rule is the important half: regenerating on every reconcile would rotate the password under a
running service on every sweep, which is a self-inflicted outage on a timer.

**Alternatives considered**: `disablePassword` with TLS client certificates, which CNPG *does*
generate and rotate itself (genuinely attractive, and the direction CNPG is pushing — rejected for
now because nakka's runtime reads `NAKKA_DB_PASSWORD` and would need certificate-based auth support
first; noted as the better long-term answer).

---

## R4 — Ordering: a database needs its role to exist first

**Decision**: render `DatabaseRole` before `Database`, and treat the resulting error as transient.

**Rationale**: Verified: a `Database` whose `owner` role does not yet exist fails with
`{"applied":false,"message":"while creating database \"x\": ERROR: role \"x\" does not exist"}`.
Both objects are applied in one pass and CNPG reconciles them independently, so the `Database` can
lose the race. It self-heals once the role lands — so this must be reported as *in progress*, never
as `Failed`.

---

## R5 — The transient `forbidden` window nobody documents

**Decision**: a `forbidden` error on a `DatabaseRole`'s password secret is **transient and must be
retried**, never treated as terminal.

**Rationale**: This one is worth the detail, because it looks exactly like a permanent RBAC bug.
CNPG maintains a per-`Cluster` `Role` whose `secrets` rule carries an explicit `resourceNames`
allowlist. Verified, immediately after creating a role and its secret together:

```
{"applied":false, "message":"secrets \"hyphen-test-pw\" is forbidden: User
 \"system:serviceaccount:verify-cnpg:nakka-db\" cannot get resource \"secrets\" ..."}
```

Roughly 20–40 seconds later, with no intervention, the same object reported `{"applied":true}` and
the secret name had appeared in the allowlist:

```
["secrets"] -> ["get","watch"] names=["hyphen-test-pw","nakka-db-app","nakka-db-ca", ...]
```

CNPG adds a newly-referenced `passwordSecret` to its per-cluster allowlist only when it next
reconciles the `Cluster`. An operator that surfaced this as `Failed` would report a permanent
authorization failure for something that fixes itself — the single most misleading failure mode
this feature can produce.

**Alternatives considered**: pre-creating the secret and waiting for the allowlist before applying
the `DatabaseRole` (adds a poll on CNPG's internal RBAC — coupling to an implementation detail that
is not part of CNPG's API); ignoring it (the service would report `Failed` on every first deploy).

---

## R6 — Getting nakka's schema to the operator

**Decision**: a **directory symlink**, `operator/src/main/resources/nakka/ddl` →
`modules/runtime/src/main/resources/nakka/ddl`. The operator then publishes the schema as a
`ConfigMap` in each project namespace, and the init container mounts it.

**Rationale**: This was the consequence flagged at specification time, and it resolves cleanly.
Verified: sbt follows a directory symlink and all three `.sql` files land at `/nakka/ddl/` on the
operator's classpath, with the canonical copy untouched in `modules/runtime` — so `docker-compose`'s
bind mount and `NakkaTestKit` keep working exactly as they do, and there is still one copy of the
schema in the repository. Same technique as feature 001 used for the CRD, in the same direction
(the build tool follows symlinks; kustomize does not).

**Alternatives considered**: a third `nakka-schema` Docker image bundling the schema and a psql
client (clean single-copy story and no `ConfigMap` at all — rejected as a third deployable to build,
load and version for three SQL files, and awkward to build with a JVM-oriented packager); a new
`schema` sbt module depended on by both `runtime` and `operator` (honest and symlink-free, but a
whole module for three files); `unmanagedResourceDirectories` pointing at the runtime's resources
(would either drag in `runtime`'s `reference.conf` or land the files at the classpath root under a
different path than the runtime uses).

---

## R7 — Which component applies the schema

**Decision**: an init container beside the service's own workload, per the operator's answer to
FR-009. It waits for the database, applies the schema, applies the hardening from R9, then exits;
the service's container does not start until it succeeds.

**Rationale**: Chosen by the operator over having the runtime do it. It leaves `modules/runtime`
and local development untouched, and keeps schema establishment visible in the rendered workload.
It reuses the `postgres:17-alpine` image and the `pg_isready` wait loop feature 001 already added to
the control plane, so the waiting behaviour is not new code. All ten statements are
`IF NOT EXISTS`, so running on every start is safe and makes the step self-healing after a restore.

**Noted honestly**: a service's schema now arrives from two places — this init container for the
journal, snapshot, durable-state, projection and timer tables, and the runtime itself for view row
tables, which `ProjectionRuntime` already creates at startup. Runtime-applied schema would have
unified those; that was the rejected alternative.

---

## R8 — The control plane's own database

**Decision**: replace the hand-written Postgres `Deployment` with a CNPG `Cluster`, applied
statically by kustomize, using `bootstrap.initdb` to create the `nakka` database and owner.

**Rationale**: It must exist before any service is ever applied, so nothing can create it in
response to a service — it stays a static resource, which is exactly what keeps the control plane
ignorant of CNPG. `bootstrap.initdb` makes CNPG generate the credential secret itself, so this half
needs no `DatabaseRole`, no generated password and none of R3. It also fixes a real weakness from
001: that Postgres used `emptyDir`, so the control plane's journal did not survive its own pod.

Verified key names in the auto-generated `{cluster}-app` secret, which the control plane's
Deployment maps into its `NAKKA_DB_*` variables one-for-one:

| CNPG secret key | nakka variable |
|---|---|
| `host` (= `{cluster}-rw`) | `NAKKA_DB_HOST` |
| `port` (= `5432`) | `NAKKA_DB_PORT` |
| `dbname` | `NAKKA_DB_NAME` |
| `username` | `NAKKA_DB_USER` |
| `password` | `NAKKA_DB_PASSWORD` |

(Also present: `user`, `uri`, `jdbc-uri`, `fqdn-uri`, `fqdn-jdbc-uri`, `pgpass`.) Services created
per `Cluster`: `{name}-rw`, `{name}-ro`, `{name}-r`, all on 5432.

---

## R9 — Isolation is **not** free, and the documentation does not say so

**Decision**: the init container must run `REVOKE CONNECT ON DATABASE "<db>" FROM PUBLIC` as the
database's own owner, in addition to applying the schema.

**Rationale**: This is the finding that justifies having verified everything. A `Database` plus a
`DatabaseRole` does **not** produce an isolated database. Measured, with two roles on one
`Cluster`:

| Attempt by service A against service B's database | Result |
|---|---|
| `SELECT` from B's table | **denied** — `permission denied for table` |
| `CREATE TABLE` in B's database | **denied** |
| `CONNECT` to B's database | **allowed** |
| `SELECT datname FROM pg_database` | **allowed** — every database name enumerable |

So the guarantee that actually matters holds by default: table data is private, because each
service's tables live in its own database owned by its own role — which is what makes the
`nakka_timers` collision (SC-005) structurally impossible. But `CONNECT` is granted to `PUBLIC` by
default, so any service can open a connection to any other's database and enumerate all database
names. FR-007 says credentials must not grant access to another service's database; connecting *is*
access.

Verified fix, and it is cheap: the owning role can close this itself —

```sql
REVOKE CONNECT ON DATABASE "my-cart" FROM PUBLIC;
```

After which another role's connection attempt is refused with
`permission denied for database "my-cart" / User does not have CONNECT privilege`, while the owner
still connects normally. It is idempotent, so it belongs in the init container's SQL beside the
schema.

**Alternatives considered**: `Database.spec.allowConnections` (all-or-nothing for the database
including its owner — useless here); doing it via CNPG declaratively (no field exists for
per-role connect grants).

---

## R10 — Naming, and whether hyphens are safe

**Decision**: name the database and role after the service directly, hyphens included.
`Database`/`DatabaseRole`/`Secret` objects are named after the service; the Postgres database and
role take the same name.

**Rationale**: Service names are DNS labels, so they may contain hyphens, which are not legal in a
bare Postgres identifier. Verified that CNPG quotes identifiers correctly: a `DatabaseRole` and
`Database` both named `my-cart` reconciled to `{"applied":true}`, the database appeared in `\l` as
`my-cart` owned by `my-cart`, and authentication as `my-cart` to database `my-cart` succeeded. No
normalisation to underscores is needed — which is fortunate, since normalising would make
`my-cart` and `my_cart` collide.

Uniqueness: object names only need to be unique within a namespace, and a namespace is a project,
so two projects may both have a `cart` without collision (FR-006). No project-qualified prefix.

---

## R11 — Deciding between provisioning and the escape hatch

**Decision**: the **control plane** decides and states the answer in the `NakkaService` spec as
`provisionDatabase: Boolean`. The rule: if the descriptor's `env` declares any variable named
`NAKKA_DB_*`, the descriptor is supplying its own database and the platform provisions nothing.

**Rationale**: The decision is a judgement about a descriptor, and descriptor validation already
lives in the control plane and `controlplane-api` where the CLI can apply the same rule before the
round trip. Putting it in the spec rather than leaving the operator to infer it makes the resource
self-describing and satisfies FR-017 — `kubectl get nsvc -o yaml` shows which path a service took,
and the field can be surfaced in `nakka services get`.

**Alternatives considered**: the operator inspecting `spec.env` itself (same outcome, but the rule
would live in the component that cannot validate descriptors, and it would be invisible in the
resource); an explicit descriptor field like `service.database.provision` (clearer, but it is a
user-facing schema change to `ServiceDescriptor` for something inferable, and it would break
existing descriptors' meaning).

---

## R12 — Making "nothing destroys a database" structural

**Decision**: grant the operator **no `delete` verb** on `clusters`, `databases` or
`databaseroles`, and set `databaseReclaimPolicy: retain` / `databaseRoleReclaimPolicy: retain`.

**Rationale**: FR-024 and FR-027 say no platform operation may destroy a database. Withholding the
verb turns that from a promise into something the API server refuses, exactly as feature 001 made
"the operator cannot rewrite desired state" structural by withholding `nakkaservices: update`. The
reclaim policies are belt-and-braces for the case where a CR is deleted by hand: `retain` leaves
the actual Postgres database in place.

The cost is stated in the spec and not hidden: nothing is ever reclaimed, so unused databases
accumulate and re-applying a deleted service silently inherits its old data. FR-025 and FR-026 make
both visible — the second via the service's reported state, so an operator re-applying a name sees
that it recovered rather than started clean.
