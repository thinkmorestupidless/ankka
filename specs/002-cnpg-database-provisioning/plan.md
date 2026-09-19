# Implementation Plan: Platform-Provisioned Databases

**Branch**: `002-cnpg-database-provisioning` | **Date**: 2026-09-16 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/002-cnpg-database-provisioning/spec.md`

## Summary

Three pieces, in increasing order of novelty.

**CloudNativePG becomes a platform dependency**, installed as one more kustomize component beside
the CRD, the operator and the control plane.

**The control plane's own database becomes a CNPG `Cluster`**, replacing the hand-written
single-container Postgres `Deployment` from feature 001. This is the easy half: one well-known
database, created by `bootstrap.initdb`, which makes CNPG generate its credential secret for us.

**The operator provisions a database per service.** On reconciling any service it ensures its
project has a Postgres `Cluster` (created lazily, one per project), then renders that service a
`DatabaseRole`, a `Database` owned by it, and a generated-password `Secret` — and an init container
that waits for the database, applies ankka's schema, and revokes `PUBLIC` connect so the database
is genuinely private. All of it is rendered as inert `Action` values from the same pure function
that already renders the Deployment.

Everything asserted here about CNPG was **verified against a real cluster during planning**, not
read from documentation — the documentation proved wrong or silent on three of the points that
decide the design, and one of them (isolation) would have shipped a false guarantee.

## Technical Context

**Language/Version**: Scala 3.9.0, JDK 21 — unchanged

**Primary Dependencies**: **no new Scala dependencies.** CNPG's `Cluster`, `Database` and
`DatabaseRole` are modelled as partial fabric8 `CustomResource` types in the `operator` module,
exactly as `AnkkaService` already is. `CloudNativePG 1.30.0` becomes a *cluster* dependency,
installed by manifest.

**Storage**: No change to ankka's own schema. Per-project Postgres capacity on durable storage
(`storage.size`, a real PVC — unlike the `emptyDir` the hand-rolled Postgres used).

**Testing**: munit, four tiers as in 001 — pure offline suites for provisioning rules and
rendering; the existing Postgres-backed projector suite; a k3s suite; and the end-to-end suite.
Both cluster suites must now install CNPG into their throwaway k3s cluster and wait for a Postgres
`Cluster` to become ready. See Complexity Tracking.

**Target Platform**: Local kind cluster; CNPG 1.30.0

**Performance Goals**: A project's first service `Ready` within 3 minutes including creating
Postgres capacity from nothing (SC-002); subsequent services within 60 seconds reusing it (SC-003);
50 services across 5 projects on 5 instances, not 50 (SC-008)

**Constraints**: `sbt test` stays offline apart from containers it starts; compile stays
warning-free; the CLI gains nothing; **no platform operation may destroy a database** (FR-024,
FR-027) — enforced by withholding RBAC verbs rather than by care

**Scale/Scope**: Tens of projects, hundreds of services, one Postgres instance per project

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` remains an unmodified template — every principle is still a
`[PRINCIPLE_N_NAME]` placeholder. Gating therefore falls, as in feature 001, on the invariants
actually written down in `CLAUDE.md` and `README.md`.

| # | Invariant (source) | Pre-design | Post-design |
|---|---|---|---|
| 1 | Effects are inert data; handlers do no I/O; folds replay | PASS | PASS — provisioning is new `Action` cases from the same pure `Rendering`; `Fabric8Executor` stays the only interpreter. |
| 2 | Module direction; CLI carries no actor system, DB driver or Kubernetes client | PASS | PASS — CNPG models live in `operator` only. `crd`, `controlplane-api` and `cli` are untouched; the control plane never learns CNPG exists. |
| 3 | Anything needing the service first is a `RuntimeExtension` | PASS | PASS — no new extension; the projector is unchanged. |
| 4 | Registration is explicit; no classpath scanning | PASS | PASS — no new components. |
| 5 | Wire names declared separately from Scala method names | PASS | PASS — one new CR spec field, named explicitly. |
| 6 | Never touch `ActorContext` from a `Future` callback | PASS | PASS — no new actor code. |
| 7 | Cross-entity checks at the endpoint, never a handler | PASS | PASS — the provision/escape-hatch decision is made where descriptor validation already lives. |
| 8 | Both test levels real; default run deterministic and offline | **AT RISK** | **JUSTIFIED** — see Complexity Tracking; cluster suites get materially slower. |
| 9 | Test serialization must not be undone | PASS | PASS — one more serialized suite's worth of cost, not a scheduling change. |
| 10 | Compile warning-free under `-Wunused` | PASS | PASS — enforced at implementation. |
| 11 | DDL has a single copy | PASS | PASS — `modules/runtime/.../ankka/ddl` stays canonical; the operator reaches it by directory symlink, **verified working** (R6). |
| 12 | The operator has **no RBAC verb on `secrets`**, which makes "a status detail cannot contain a secret value" structural (`CLAUDE.md`, feature 001) | **VIOLATED** | **VIOLATED, MITIGATED** — unavoidable: the operator must create credential secrets. See Complexity Tracking. |

**Gate result**: pass, with three deviations recorded — one of them a genuine regression of a
security property this project explicitly documented.

## Project Structure

### Documentation (this feature)

```text
specs/002-cnpg-database-provisioning/
├── plan.md
├── spec.md
├── research.md
├── data-model.md
├── quickstart.md
├── checklists/requirements.md
├── contracts/
│   ├── cnpg-resources.md        # Partial CNPG models and what we set (FR-001..FR-008)
│   ├── provisioning-rules.md    # The pure decision: provision, skip, or fail (FR-013..FR-019)
│   ├── schema-init.md           # The init container's contract (FR-009..FR-012)
│   ├── credentials.md           # Generation, delivery, never-rotate (FR-013..FR-015)
│   └── rbac-and-install.md      # RBAC, the withheld delete verb, install (FR-029..FR-038)
└── tasks.md                     # Phase 2 (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

No new sbt modules. The module graph is unchanged.

```text
crd/                                          UNCHANGED — AnkkaService is the contract; CNPG is not
operator/src/main/scala/ankka/operator/
├── cnpg/
│   ├── PostgresCluster.scala                 NEW: partial fabric8 model, postgresql.cnpg.io/v1
│   ├── PostgresDatabase.scala                NEW: partial model
│   ├── PostgresDatabaseRole.scala            NEW: partial model
│   └── CnpgDefinitions.scala                 NEW: group/version/kind, shared serialization
├── Provisioning.scala                        NEW: PURE — spec → provisioning decision
├── Passwords.scala                           NEW: generation; create-only-if-absent
├── SchemaInit.scala                           NEW: PURE — renders the init container
├── Rendering.scala                           MODIFIED: emits provisioning actions + init container
├── Action.scala                              MODIFIED: EnsureCluster, EnsureDatabase,
│                                             EnsureDatabaseRole, EnsureCredentials, EnsureSchemaConfig
├── Executor.scala                            MODIFIED: interprets them; reads back readiness
├── LifecycleRules.scala                      MODIFIED: "waiting for database" is a real state
└── resources/ankka/ddl -> ../../../../../modules/runtime/...   NEW SYMLINK (verified, R6)

controlplane/src/main/scala/ankka/controlplane/deploy/
└── ServiceProjection.scala                   MODIFIED: sets provisionDatabase from the descriptor

crd/src/main/scala/ankka/crd/AnkkaService.scala   MODIFIED: + provisionDatabase, + database status

kustomization/
├── components/cnpg/                          NEW: the CNPG operator install
├── components/postgres/                      REPLACED: Deployment+Secret+Service → CNPG Cluster
└── deploy-local.sh                           MODIFIED: install CNPG; wait for the Cluster
```

**Structure Decision**: CNPG's types live in `operator` and nowhere else, because only the operator
talks to CNPG. The control plane's database is a *static* resource applied by kustomize — it must
exist before any service is ever applied, so nothing can create it in response to one — which is
what keeps the control plane free of any knowledge of CNPG despite having a CNPG-managed database.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| **The operator gains `secrets: create, get, patch`, breaking the "no verb on secrets" property feature 001 documented as structural** | Unavoidable given the chosen design: CNPG does not generate passwords (verified — `DatabaseRole` requires a caller-supplied basic-auth secret), so *something* ankka owns must generate one and write it. The operator is the only component that renders per-service infrastructure. | Having the **control plane** generate credentials was considered and rejected: it would give the component that deliberately cannot create workloads the power to mint their credentials, and would put secret material through the `AnkkaService` spec — a resource readable by anyone with `get` on it. **Mitigation, and it is partial, not equivalent**: the operator still never reads a *service's* secret data on the status path, `detail` construction is unchanged from 001, and the operator gets no `list` on secrets, so it cannot enumerate. The honest position is that "cannot read a secret" is no longer structurally true and `CLAUDE.md` must be corrected rather than left overstating the guarantee. |
| **Both k3s suites must install CNPG and wait for a Postgres `Cluster`** | FR-038 requires real-cluster verification, and provisioning cannot be verified against a fake — the isolation finding in R9 is exactly the class of thing a fake would model wrongly and then agree with itself about. | Measured during planning: CNPG's own operator took ~25s to become ready and a 1-instance `Cluster` ~20-60s more. That is a real addition to suites already taking 40-60s. Alternatives rejected: a fake CNPG (would have "proved" isolation that does not exist by default); testing provisioning only offline (leaves FR-038 unmet). Mitigation: install CNPG once per suite, share one project's `Cluster` across the services a suite deploys, and keep both suites behind the existing `-Dankka.cluster.tests=off` switch. |
| **Two provisioning paths** — platform-provisioned and bring-your-own | Operator's explicit choice on FR-016. | One path was offered and declined. The cost is real and recorded in the spec (FR-019): on the escape-hatch path the platform cannot enforce one-database-per-service, so the rule this feature exists to enforce is enforceable on only one of the two paths. Both paths get tests (SC-013). |
| **Databases are never reclaimed** | Operator's explicit choice on FR-021, taken to avoid irreversibly destroying event-sourced history on a temporary removal. | Turned into an advantage: because nothing should ever delete a database, the operator is granted **no `delete` verb** on `clusters`, `databases` or `databaseroles`. FR-024 and FR-027 stop being promises and become things the API server refuses — the same trick used for `ankkaservices: update` in 001. Cost: accumulation, and a re-apply silently inheriting old state, which FR-025 and FR-026 exist to make visible. |
