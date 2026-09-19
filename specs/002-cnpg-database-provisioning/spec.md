# Feature Specification: Platform-Provisioned Databases

**Feature Branch**: `002-cnpg-database-provisioning`

**Created**: 2026-09-16

**Status**: Draft

**Input**: User description: "handle the database side of things — deploy the CNPG operator, then trigger new databases to be created when we deploy services, as well as for the control plane"

## Context

Feature 001 made deployment real: applying a descriptor produces a running workload. It
deliberately left one thing out, and the omission is load-bearing rather than cosmetic.

**An ankka service cannot start without a database.** It is event-sourced by definition — it needs
Postgres carrying a journal, snapshots, durable state, projection offsets, view rows and timers.
Today a descriptor has to carry `ANKKA_DB_HOST`, `ANKKA_DB_NAME`, `ANKKA_DB_USER` and
`ANKKA_DB_PASSWORD` through its `env` block, and whoever writes that descriptor has to have
provisioned a database by hand first. The platform provisions nothing.

**And sharing one database between two services is destructive, not untidy.** `ankka_timers` has
no service column; the timer sweeper polls it unfiltered and *deletes* any row whose component id
it does not recognise, so two services on one database silently delete each other's timers. View
row tables are named from the component id alone and collide the same way, as do projection
offsets. "One database per service" is therefore a correctness rule that the platform currently
states in documentation and does nothing to enforce.

This feature closes both gaps: a database per service, provisioned by the platform at deploy time,
with credentials the operator never has to see or write down — and the control plane's own database
moved onto the same managed footing as everything else.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A deployed service gets its own database (Priority: P1)

An operator applies a descriptor that says nothing about databases. The service starts, connects to
a database that exists only for it, and reaches `Ready`. The operator never provisioned anything,
never generated a password, and never put a connection string in a file.

**Why this priority**: This is the feature. It is also the difference between a platform and a
deployment script — until the platform provisions state, every service deployment has a manual
prerequisite that is easy to get wrong in exactly the way that corrupts data.

**Independent Test**: Apply a descriptor with no database configuration whatsoever against a
cluster with the platform installed; assert the service reaches `Ready`, and that it is reading and
writing its own journal.

**Acceptance Scenarios**:

1. **Given** a project with no services, **When** an operator applies a `cart` descriptor carrying
   no database configuration, **Then** a database is provisioned for `cart`, credentials are
   delivered to it, and the service reports `Ready`.
2. **Given** `cart` is `Ready`, **When** it persists an event and is then restarted, **Then** it
   recovers that event — the database outlived the pod.
3. **Given** `cart` is `Ready`, **When** a second service `payments` is applied in the same
   project, **Then** it gets its **own** database, and neither service can see the other's tables.
4. **Given** a descriptor is applied twice, **Then** the second apply provisions nothing new and
   the service keeps the database and the data it already had.

---

### User Story 2 - One service cannot damage another's data (Priority: P1)

Two services in the same project run with credentials scoped to their own database. Neither can
read, write or drop anything belonging to the other, and in particular neither one's timer sweeper
can reach the other's timer rows.

**Why this priority**: Equal to US1, because US1 without this is worse than what exists today — it
would automate the creation of exactly the shared-database situation that silently destroys timers.
Provisioning and isolation are one feature, not two.

**Independent Test**: With two services deployed in one project, attempt to reach service B's
tables using service A's credentials and assert it is refused; then assert each service's timers
survive the other running its sweeper.

**Acceptance Scenarios**:

1. **Given** `cart` and `payments` are both `Ready` in one project, **When** `cart`'s credentials
   are used to query `payments`' database, **Then** access is refused.
2. **Given** both services have pending timers, **When** both timer sweepers run, **Then** each
   service's timers still exist and fire — neither deleted the other's.
3. **Given** both services declare a view with the same component id, **Then** each service's view
   rows are stored separately and neither overwrites the other.

---

### User Story 3 - The control plane runs on a managed database (Priority: P2)

The control plane's own Postgres is provisioned and operated the same way every service's is, rather
than as a hand-written single-container deployment with no backups, no failover story and data on
ephemeral storage.

**Why this priority**: The control plane holds the only durable record of what every service is
*supposed* to be. It is the least acceptable thing in the system to lose, and is currently the
least well protected. Second only because it does not block service provisioning.

**Independent Test**: Deploy the platform from scratch; assert the control plane's database is
managed by the same mechanism as service databases, on persistent storage, and that the control
plane recovers its recorded state across a database pod restart.

**Acceptance Scenarios**:

1. **Given** a freshly installed platform, **Then** the control plane's database is provisioned by
   the platform and the control plane reaches `Ready` against it.
2. **Given** services have been applied, **When** the control plane's database pod is restarted,
   **Then** the recorded desired state survives and every service is still listed.
3. **Given** the control plane's database is unreachable, **When** an operator runs a read command,
   **Then** they get a clear error rather than a silent empty result.

---

### User Story 4 - Credentials are generated, never authored (Priority: P2)

No database password is ever written into a descriptor, a manifest, a config file or the repository.
Each service's credentials are generated at provisioning time and delivered to that service alone.

**Why this priority**: The current mechanism actively invites a plaintext password in a descriptor,
and a descriptor is a file people commit. Fixing provisioning without fixing this would leave the
worse habit in place.

**Independent Test**: Deploy a service, then grep every descriptor, manifest and repository file
for its database password; assert it appears in none of them, and that the service still connects.

**Acceptance Scenarios**:

1. **Given** a service is provisioned, **Then** its password appears in no descriptor and no file
   under version control.
2. **Given** a service is provisioned, **Then** its credentials are readable only by that service,
   not by other services in the same project.
3. **Given** a descriptor carries no database configuration, **Then** the connection details it
   receives are entirely platform-supplied.

---

### Edge Cases

- **A project's first service ever.** The project has no database capacity yet; something has to
  create it before the service can start, and the service must not fail permanently while it waits.
- **Two services in one project applied simultaneously.** Both may observe "no database capacity
  yet" at once. Exactly one shared instance must result, not two.
- **A service is applied before database capacity is ready.** Postgres takes tens of seconds to
  become available; the service must wait rather than crash-loop or report a permanent failure.
- **A fresh database with none of ankka's tables in it.** Something must apply the journal,
  snapshot, durable-state, projection and timer schema before the service can function.
- **A service is deleted and then re-applied under the same name.** Whether it finds its old data
  or a clean database is a decision with irreversible consequences either way.
- **A project is deleted while its services still hold data.**
- **Credentials are rotated, or the credential secret is deleted by hand.** The service must either
  recover or report clearly that it cannot.
- **The database provisioning mechanism is not installed.** Applying a service must fail with a
  reason naming the missing dependency, not hang at "no operator has reported on this service".
- **Two projects both contain a service called `cart`.** Their databases, roles and credentials must
  not collide.
- **Storage fills up, or a storage class is unavailable.** Provisioning must report why rather than
  leaving a service in progress indefinitely.
- **The platform is deployed to a cluster where something else already manages Postgres.**

## Requirements *(mandatory)*

### Functional Requirements

#### Database capacity

- **FR-001**: The platform MUST provide database capacity for a project without an operator
  provisioning anything by hand.
- **FR-002**: Database capacity MUST be created on first need and MUST NOT require an operator to
  predict which projects will exist.
- **FR-003**: Concurrent first-service applies within one project MUST converge on exactly one
  shared database instance for that project.
- **FR-004**: Database capacity MUST use durable storage — a restart of the database MUST NOT lose
  committed data.
- **FR-005**: The platform MUST NOT create database capacity for a project that has never had a
  service applied to it.

#### Per-service databases

- **FR-006**: Each service MUST receive its own logical database, distinct from every other
  service's, including services in the same project and services of the same name in other projects.
- **FR-007**: Each service MUST receive its own credentials, which MUST NOT grant access to any
  other service's database.
- **FR-008**: Provisioning MUST be idempotent — re-applying an unchanged descriptor MUST NOT create
  a second database, reset credentials, or destroy data.
- **FR-009**: A service's database MUST have ankka's required schema applied by an init step that
  runs alongside the service's own workload, before the service's container starts.
- **FR-010**: The service's container MUST NOT start until the schema has been applied
  successfully. A failure to apply it MUST prevent the service starting rather than let it start
  against an incomplete database.
- **FR-011**: The schema application MUST be safe to run repeatedly against a database that already
  has it, and MUST therefore run on every start rather than only the first.
- **FR-012**: ankka's required schema MUST be available to the init step in every project's
  namespace, and MUST remain a single copy in the repository — the deploying mechanism MUST NOT
  require the schema to be duplicated per project or per deployment path.

#### Credentials

- **FR-013**: Database passwords MUST be generated by the platform, never authored by an operator.
- **FR-014**: A generated password MUST NOT appear in a service descriptor, in any file under
  version control, or in any log or status the platform produces.
- **FR-015**: Credentials MUST be delivered to the service that owns them and MUST be readable by
  that service without it knowing where they came from.
- **FR-016**: The platform MUST NOT require the descriptor to carry database connection details.

#### Descriptor and compatibility

- **FR-017**: A descriptor that carries no database configuration MUST result in a provisioned
  database.
- **FR-018**: A descriptor MAY supply its own database connection details, in which case the
  platform MUST skip provisioning entirely and use what it was given. This is the documented
  escape hatch for an external or pre-existing database.
- **FR-019**: The rule that decides between provisioning and the escape hatch MUST be explicit and
  observable — an operator MUST be able to tell from a service's reported state which path it took,
  and MUST NOT have to infer it.
- **FR-020**: Existing descriptors that carry database environment variables MUST keep working
  unchanged, taking the escape-hatch path; they MUST NOT silently connect to a different database
  than the one they name.
- **FR-021**: On the escape-hatch path the platform cannot enforce one-database-per-service, since
  it did not create the database. The platform MUST document that the caller owns that guarantee on
  this path, and MUST NOT imply it is enforcing it.

#### The control plane's own database

- **FR-022**: The control plane's database MUST be provisioned and operated by the same mechanism
  as service databases.
- **FR-023**: The control plane's database MUST use durable storage and MUST survive a restart of
  the database with its recorded desired state intact.
- **FR-024**: The control plane MUST NOT be able to reach any service's database, and no service
  MUST be able to reach the control plane's.

#### Lifecycle

- **FR-025**: Deleting a service MUST retain its database and all of its data. Re-applying a
  service of the same name in the same project MUST recover that data rather than start clean.
- **FR-026**: Deleting a project MUST retain its database capacity and every database in it. No
  operation exposed by the platform destroys a database.
- **FR-027**: Because nothing is reclaimed automatically, the platform MUST make unused databases
  discoverable, so that an operator performing deliberate cleanup can tell which databases belong
  to services that no longer exist.
- **FR-028**: Re-applying a service name that previously existed MUST make clear in its reported
  state that it has recovered an existing database rather than been given a new one — inheriting
  old state silently is the known cost of retaining, and MUST be visible rather than surprising.
- **FR-029**: No lifecycle operation MUST be capable of destroying data as a side effect of an
  action whose stated purpose is something else.
- **FR-030**: Pausing a service MUST NOT affect its database or its data.

#### Failure handling and observability

- **FR-031**: A service whose database cannot be provisioned MUST report why, in terms an operator
  can act on, and MUST NOT sit indefinitely in a non-terminal state.
- **FR-032**: A service waiting for database capacity that is still starting MUST report that it is
  waiting, and MUST proceed on its own once capacity is ready.
- **FR-033**: If the provisioning mechanism is absent from the cluster, applying a service MUST
  report that specific cause.
- **FR-034**: Provisioning actions MUST be recorded in operational output identifying the service,
  the action and the outcome.
- **FR-035**: A provisioning failure for one service MUST NOT prevent any other service from being
  provisioned or reconciled.

#### Installation and delivery

- **FR-036**: The provisioning mechanism MUST be installable as part of deploying the platform, and
  installation MUST be idempotent.
- **FR-037**: Provisioning behaviour MUST be exercisable without a real cluster, so its rules are
  testable deterministically and offline in line with the rest of the project's test suite.
- **FR-038**: This feature MUST be verified automatically against a real cluster, including that a
  provisioned service genuinely reads and writes its own database.

### Key Entities

- **Database capacity**: the shared Postgres instance serving one project's services. Created on
  first need, sized independently of how many services use it.
- **Service database**: one logical database belonging to exactly one service. The unit of
  isolation, and the thing "one database per service" refers to.
- **Service database role**: the identity a service authenticates as, owning its own database and
  nothing else.
- **Service credentials**: generated username, password and connection details, delivered to one
  service.
- **Required schema**: the journal, snapshot, durable-state, projection-offset and timer tables a
  ankka service needs before it can function, plus the view row tables it creates for itself.
- **Control plane database**: the same shape as a service database, for the component that holds
  every service's desired state.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An operator can deploy a working service from a descriptor containing zero database
  configuration, with no manual provisioning step.
- **SC-002**: A project's first service reaches `Ready` within 3 minutes of being applied on a
  healthy cluster, including the time to create database capacity from nothing.
- **SC-003**: A second and subsequent service in an existing project reaches `Ready` within 60
  seconds, reusing existing capacity.
- **SC-004**: 100% of deployed services have a database no other service can access, verified by
  attempting cross-service access and being refused.
- **SC-005**: Two services in one project, each with pending timers and each running its sweeper,
  both retain 100% of their own timers — the collision that motivated this feature cannot occur.
- **SC-006**: Zero generated passwords appear in any descriptor, repository file, log line or
  reported status, verified by search.
- **SC-007**: Committed data survives a database restart in 100% of attempts, for both a service
  and the control plane.
- **SC-008**: 50 services across 5 projects all reach `Ready`, using 5 database instances rather
  than 50.
- **SC-009**: 100% of provisioning failures produce an operator-readable cause; no service remains
  in a non-terminal state beyond the configured timeout.
- **SC-010**: Re-applying an unchanged descriptor performs no provisioning work and loses no data,
  across at least 10 consecutive applies.
- **SC-011**: The provisioning rules are fully exercisable in the standard offline test run, with no
  cluster present.
- **SC-012**: A provisioned service is verified against a real cluster to read and write its own
  database, by the standard test run and without manual steps.
- **SC-013**: A descriptor supplying its own database connection details deploys successfully and
  provisions nothing, and its reported state makes clear it took that path — verified for both
  paths in the same test run.
- **SC-014**: Deleting a service and re-applying it under the same name recovers the data it had
  before, in 100% of attempts, and the recovery is visible in its reported state rather than
  silent.
- **SC-015**: No sequence of platform operations — deleting a service, deleting a project, pausing,
  restarting, re-applying — destroys a database or its contents, verified by exercising all of them
  against a service with committed data.

## Assumptions

- **CloudNativePG is the provisioning mechanism**, named explicitly in the request. Installed into
  the cluster as a platform dependency alongside ankka's own operator and custom resource
  definition, the same way those are installed today.
- **Database capacity is per project, not per service and not one global instance.** This is forced
  rather than chosen: CloudNativePG's `Database` and `DatabaseRole` resources can only reference a
  Postgres cluster in their **own namespace** — verified directly, a same-namespace reference
  reconciles and reports status while a cross-namespace one produces no status and no events at
  all. Since the platform already gives each project its own namespace, per-project capacity is the
  only topology where a service's database, its credentials and its workload all land in one
  namespace with no cross-namespace copying. Per-service capacity would mean one Postgres instance
  per service, which is disproportionate; one global instance would put every project's databases
  in a namespace no project owns.
- **Provisioning is the operator's job, not the control plane's.** A database is per-service
  infrastructure rendered from desired state, exactly like the workload — and the control plane
  deliberately holds no permission to create workloads. This also matches the precedent already
  recorded for this codebase, where a comparable platform provisions per-application messaging
  topics from its operator rather than its API.
- **Passwords are generated by the platform, because the provisioning mechanism does not generate
  them.** Verified: `DatabaseRole` requires a caller-supplied basic-auth secret containing
  `username` and `password`, and creates a role with no password if none is given. The owner role
  must also exist before a database can be owned by it — a role, then a database owned by it, then
  authentication with the supplied password, was verified working end to end.
- **The control plane's database is a static, known-ahead-of-time resource, not a dynamically
  provisioned one.** It has to exist before any service is ever applied, so it cannot be created by
  the operator in response to a service. It is installed with the platform.
- **The required schema is idempotent.** All ten of its statements are `IF NOT EXISTS`, which is
  what makes applying it on every start — not just the first — safe.
- **The schema is applied by an init step beside each service's workload**, chosen over having the
  runtime do it. This keeps `modules/runtime` untouched and local development behaviour identical,
  at the cost of a mechanism that exists only in-cluster. The runtime still creates its own view
  row tables at startup, as it does today, so a service's schema arrives from two places rather
  than one.
- **That choice has a delivery consequence worth stating up front.** The schema has to be readable
  inside *every* project's namespace, and project namespaces are created at runtime, so whichever
  component renders a service's workload must be able to supply the schema itself. The component
  that renders workloads deliberately does not depend on the runtime module where the schema lives
  today — keeping it free of an actor system and a database driver was an explicit design decision
  — so this feature has to get the schema to that component without creating a second maintained
  copy of it and without undoing that separation.
- **Bring-your-own database remains supported**, so there are two provisioning paths to build and
  test, not one. On the supplied-database path the platform cannot enforce one-database-per-service,
  and says so rather than implying otherwise.
- **Nothing is ever reclaimed automatically.** Databases outlive the services that created them and
  the projects that contained them. This trades accumulation and silent state inheritance on
  re-apply for the guarantee that no platform operation can destroy an event journal.
- **Services remain capped at one replica**, per feature 001. Connection pooling and multi-replica
  access patterns are therefore not yet a concern.
- **Local development is unchanged.** Running a service under `sbt run` against the bundled
  Postgres continues to work as it does today; this feature concerns what happens in a cluster.
- **Backups, point-in-time recovery and high availability are configuration of the provisioning
  mechanism**, not something this feature implements. Sensible development defaults now; production
  posture is a later decision.

## Out of Scope

- **Backups, scheduled backups and point-in-time recovery.** The mechanism supports them; choosing
  and operating a retention and object-store policy is separate work.
- **High availability and failover.** Single-instance capacity is the target here, consistent with
  the single-replica service cap from feature 001.
- **Connection pooling.** Not useful while every service is capped at one replica.
- **Multi-replica services and Pekko cluster formation.** Still deferred from feature 001.
- **Migrating existing hand-provisioned databases** into platform-managed ones.
- **Per-service database sizing, storage classes or resource tuning** exposed through the
  descriptor. One sensible default for now.
- **Database observability** — metrics, slow query logs, dashboards.
- **Anything about a registry**, which remains deferred from feature 001.
