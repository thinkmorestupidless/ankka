# Feature Specification: Kubernetes Service Reconciliation

**Feature Branch**: `001-k8s-reconciliation`

**Created**: 2026-09-14

**Status**: Draft (revised — architecture changed to control plane + operator)

**Input**: User description: "let's define the work required for reconciliation of the deployed services in kubernetes"

## Context

The control plane records what an operator *wants*: `nakka services apply -f cart.json` durably
stores a descriptor, bumps a generation, and returns. It also knows how to *accept* a report about
what is actually running, and to drop a report describing a superseded generation.

Nothing turns a descriptor into a running workload, and nothing reports back. Every applied service
sits at `UpdateInProgress` with `0/0` instances forever — the intent is durable, the deployment is
not happening.

**Two components close that gap, with a custom resource between them.** The control plane keeps
tenancy, validation, the HTTP API and the audit journal; its only new job is projecting a service's
desired state into a `NakkaService` custom resource. A **nakka operator**, running inside the target
cluster, watches those resources and owns everything below: the namespace, the workload, the
service account, and the reported status.

That split is deliberate. Kubernetes already solves cascade deletion (owner references), change
notification (watches), and desired-vs-observed staleness (`metadata.generation` versus
`status.observedGeneration`). A reconciler that talked to Deployments directly from the control
plane would reimplement all three. It also means the control plane never holds cluster credentials
and never needs network reach into the cluster — the operator connects outward.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - An applied descriptor actually runs (Priority: P1)

An operator applies a service descriptor through the CLI. Without doing anything else, the workload
appears in the cluster, starts, and `nakka services list` shows it `Ready`. If the operator changes
the image tag and applies again, the running instance is replaced with the new image.

**Why this priority**: This is the entire point of the control plane. Until an apply produces a
running workload, every other feature is bookkeeping.

**Independent Test**: Apply a descriptor against a cluster running the operator, then poll
`nakka services get` until it reports `Ready`, and confirm the workload exists with the image,
environment and resource sizing from the descriptor.

**Acceptance Scenarios**:

1. **Given** a project with no service named `cart`, **When** an operator applies a valid `cart`
   descriptor, **Then** a custom resource for it exists in the cluster, the operator creates the
   workload, and the service reports `Ready` with `1/1`.
2. **Given** `cart` is `Ready` at generation 3, **When** the operator applies the same descriptor
   with a new image tag, **Then** the service reports `UpdateInProgress` at generation 4, the
   running instance is replaced, and the service returns to `Ready`.
3. **Given** a descriptor with an environment variable drawn from a secret, **When** it is applied,
   **Then** the running instance receives that value and the secret value never appears in any
   status, listing, custom resource or log the platform produces.
4. **Given** two projects each containing a service named `cart`, **When** both are applied,
   **Then** both run independently in separate namespaces and neither is mistaken for the other.

---

### User Story 2 - Reported status reflects reality (Priority: P2)

An operator reads `nakka services list` and trusts it. The lifecycle and instance counts describe
what the cluster is doing right now. When the instance crashes, the count drops. When the cluster
recovers it, the count returns.

**Why this priority**: Without this, story 1 is unverifiable from the CLI. Second only because a
deployment that runs but reports badly is still more useful than no deployment.

**Independent Test**: Drive cluster-side changes and assert the reported lifecycle, counts and
detail follow each change within the stated latency, with no operator command in between.

**Acceptance Scenarios**:

1. **Given** `cart` is `Ready` with `1/1`, **When** the instance terminates, **Then** the service
   reports a not-ready lifecycle, and returns to `Ready` once it is replaced.
2. **Given** `cart` is applied with an image tag that does not exist, **Then** the service reports
   a failed lifecycle with a detail naming the image and the reason, rather than staying
   `UpdateInProgress` indefinitely.
3. **Given** a service in steady state that nobody touches, **When** the operator and control plane
   run for an extended period, **Then** no new observation is recorded and the service's stored
   history does not grow.
4. **Given** an observation is produced for generation 4 but arrives after generation 5 has been
   applied, **Then** it is discarded and the reported status continues to describe generation 5.

---

### User Story 3 - Lifecycle commands take effect in the cluster (Priority: P2)

`pause`, `resume`, `restart` and `delete` are currently recorded and nothing more. An operator
expects `pause` to stop the instance, `resume` to bring it back, `restart` to replace it without
changing the descriptor, and `delete` to leave nothing behind.

**Why this priority**: The day-two operations. They share all of story 1's machinery, so they are
cheap once it exists, but an operator who pauses a service and finds it still running has been
actively misled.

**Independent Test**: Issue each command through the CLI and assert both the reported status and
the cluster-side outcome, including that a deleted service leaves no objects behind.

**Acceptance Scenarios**:

1. **Given** `cart` is `Ready`, **When** the operator pauses it, **Then** no instances run, the
   service reports `Paused` with `0/0`, and its configuration is retained.
2. **Given** `cart` is `Paused`, **When** the operator resumes it, **Then** the instance returns and
   the service reports `Ready`.
3. **Given** `cart` is `Ready`, **When** the operator restarts it, **Then** the instance is replaced
   with a new one, the descriptor is unchanged, and the service returns to `Ready`.
4. **Given** `cart` is running, **When** the operator deletes it, **Then** the custom resource is
   removed and every object created for it is removed with it, leaving nothing behind.
5. **Given** `cart` is paused, **When** a new descriptor is applied, **Then** it stays paused and
   the new descriptor takes effect on resume.

---

### User Story 4 - Drift and outages heal themselves (Priority: P3)

Someone deletes or edits the running workload directly. The cluster becomes briefly unreachable
from the control plane. The operator or the control plane restarts mid-deployment. In each case the
system returns to the recorded desired state on its own, with no operator intervention and no
duplicated or orphaned workloads.

**Why this priority**: This is what makes it a reconciler rather than a deploy script. Third
because the happy path has to exist before it can be restored.

**Independent Test**: Introduce each disruption and assert convergence back to desired state, with
no duplicate objects.

**Acceptance Scenarios**:

1. **Given** `cart` is `Ready`, **When** its workload is deleted directly in the cluster, **Then**
   the operator recreates it and the service returns to `Ready` without an operator command.
2. **Given** the cluster is unreachable from the control plane, **When** an operator applies a
   descriptor, **Then** the apply succeeds and the intent is durable, the reported status is marked
   as not currently confirmable rather than silently stale, and the descriptor is deployed once the
   cluster returns.
3. **Given** the operator is restarted while a rollout is in progress, **When** it comes back,
   **Then** it resumes from the custom resources it finds, produces no second copy of any object,
   and the service reaches `Ready`.
4. **Given** the control plane is restarted, **When** it comes back, **Then** it re-projects desired
   state and resumes consuming status without duplicating anything.
5. **Given** one service is permanently failing, **Then** every other service continues to be
   reconciled and reported on normally.

---

### Edge Cases

- **The cluster is unreachable from the control plane.** Reported status must be distinguishable
  from a confirmed-current status, so an operator does not read a stale `Ready` as a live one.
- **The operator is not installed, or is an incompatible version.** Applying must still succeed and
  record intent, and the service must report that nothing is acting on it — not sit silently at
  `UpdateInProgress`.
- **A custom resource is written but the operator rejects it** (quota, admission policy, a value
  that passed descriptor validation). The reason must reach the operator-visible detail and be
  retried with backoff.
- **A rollout never completes** — the image cannot be pulled, or the instance crashes in a loop.
  The service must reach a reported failed state within a bounded time.
- **A referenced secret does not exist.** Not detectable at apply time, so it must surface as a
  reported failure naming the secret.
- **A service is deleted while its rollout is in progress.** Deletion wins; nothing survives it.
- **Two applies land in quick succession.** Only the latest generation may end up running.
- **A pre-existing object has the same name** as one the operator would create but is not owned by
  it. The operator must refuse to adopt or overwrite it and must report the collision.
- **Someone edits the custom resource directly** with `kubectl`, bypassing the control plane. The
  control plane's record is authoritative and must be restored.
- **More than one control plane node, or more than one operator replica, is running.** Neither may
  race itself.
- **Many services change at once.** No service may be starved.

## Requirements *(mandatory)*

### Functional Requirements

#### The custom resource contract

- **FR-001**: The platform MUST define a `NakkaService` custom resource whose spec expresses a
  service's desired state and whose status expresses its observed state.
- **FR-002**: The resource MUST carry the control plane's generation, so that a status can be tied
  to the desired state it describes and a stale one recognised.
- **FR-003**: The resource MUST be the *only* contract between the control plane and the operator.
  Neither may depend on the other's internals, and either MUST be restartable and upgradable
  independently of the other.
- **FR-004**: The resource definition MUST be versioned, and an operator encountering a version it
  does not understand MUST report that rather than acting on a partial reading.

#### Control plane responsibilities

- **FR-005**: The control plane MUST project each service's desired state into its custom resource,
  and MUST do so idempotently — repeated projection of unchanged desired state performs no write.
- **FR-006**: Projection MUST be triggered both by a change in desired state and periodically, so a
  missed trigger is recovered without operator action.
- **FR-007**: The control plane MUST restore its own record if a custom resource is edited or
  deleted out of band. The control plane's record is authoritative.
- **FR-008**: The control plane MUST consume status from the custom resource and record it as an
  observation against the generation it describes.
- **FR-009**: The control plane MUST remove a service's custom resource when the service is deleted,
  and that removal MUST survive the cluster being unreachable at the time.
- **FR-010**: An apply MUST succeed and durably record intent even when the cluster is unreachable.
- **FR-011**: The control plane MUST NOT create, modify or read any cluster object other than
  `NakkaService` resources and its own namespace-scoped access to them.

#### Operator responsibilities

- **FR-012**: The operator MUST render a `NakkaService` into the complete set of objects needed to
  run it, deriving: the container image; literal and secret-referenced environment variables;
  labels and annotations; and CPU and memory limits from the named instance type.
- **FR-013**: Rendering MUST be deterministic — the same resource at the same generation always
  produces the same objects.
- **FR-014**: The operator MUST create the per-project namespace before the first service in it is
  deployed.
- **FR-015**: Every object the operator creates MUST be owned by the custom resource, so that
  deleting the resource removes them without the operator having to track them.
- **FR-016**: The operator MUST NOT modify, adopt or delete any object it does not own. A name
  collision with a foreign object MUST be reported as a failure, not resolved by overwriting.
- **FR-017**: When the cluster diverges from the rendered desired state through a change the
  operator did not make, the operator MUST restore the rendered state. The custom resource is the
  only source of truth; an out-of-band edit is reverted, not merged.
- **FR-018**: The operator MUST react to a change in a custom resource without waiting for a
  polling interval, and MUST additionally re-examine everything periodically so that drift nobody
  announced is still corrected.
- **FR-019**: The operator MUST write observed lifecycle, ready count, desired count and a detail
  message to the resource's status, stating the generation it describes.
- **FR-020**: Observed lifecycle MUST be derived by explicit, testable rules covering at minimum:
  nothing deployed; a rollout in progress; all requested instances ready; some but not all ready;
  none ready while some are wanted; deliberately stopped; and failed.
- **FR-021**: A service whose rollout has not progressed for a configurable period MUST be reported
  failed with the reason, rather than remaining in progress indefinitely.
- **FR-022**: The operator MUST NOT write a status identical to the one already recorded.
- **FR-023**: Detail messages MUST be phrased for an operator to act on, and MUST never contain
  secret values.
- **FR-024**: A failing service MUST NOT prevent the operator from reconciling any other service.
- **FR-025**: The operator MUST recover from its own restart without creating a second copy of any
  object, rebuilding its view from the custom resources it finds.

#### Lifecycle operations

- **FR-026**: Pausing MUST stop all instances while retaining configuration; resuming MUST restore
  them.
- **FR-027**: Restarting MUST replace every running instance without changing the descriptor.
- **FR-028**: Applying a changed descriptor MUST roll the instance over rather than stopping first.
- **FR-029**: Deleting MUST remove every object created for the service.

#### Reporting and staleness

- **FR-030**: The control plane MUST distinguish "observed and current" from "could not be
  observed", so an operator can tell a live `Ready` from one unconfirmed since the cluster became
  unreachable, **in the listing they scan most often** and not only in a detail field.
- **FR-031**: A service whose custom resource exists but which no operator has ever reported on
  MUST be distinguishable from one actively being rolled out.

#### Failure handling and operability

- **FR-032**: Failures to reach or write to the cluster MUST be retried with a bounded, increasing
  backoff, and MUST NOT discard recorded desired state.
- **FR-033**: A rejected write MUST surface the cluster's stated reason and be retried rather than
  abandoned.
- **FR-034**: Every reconciliation attempt MUST be recorded in operational output identifying the
  service, the generation, the action taken and the outcome.
- **FR-035**: The reconcile interval, retry backoff bounds and the not-progressing timeout MUST be
  configurable, with defaults suitable for a development cluster.
- **FR-036**: The operator MUST obtain cluster access from its own in-cluster identity, holding no
  credential the control plane supplies. The control plane's cluster credentials MUST grant access
  to `NakkaService` resources and nothing else, and MUST NOT be reachable by the CLI.

#### Delivery and testability

- **FR-037**: Rendering and lifecycle classification MUST be exercisable without a cluster, so
  their behaviour is testable deterministically and offline in line with the rest of the project's
  test suite.
- **FR-038**: This feature MUST deliver a working operator, verified automatically against a real
  single-node cluster started by the test run, rather than by hand.
- **FR-039**: The platform MUST ship the means to install the resource definition and the operator
  into a cluster, and installing MUST be idempotent.

### Key Entities

- **Desired state**: the service descriptor and its generation, already recorded today.
- **Generation**: ties an observation to the desired state it describes, and lets a late report
  from a superseded deployment be discarded.
- **`NakkaService` custom resource**: the contract. Spec is the control plane's projection of
  desired state; status is the operator's report. The only thing both sides know about.
- **Rendered object set**: what one custom resource at one generation corresponds to. Owned by the
  resource, so deletion cascades.
- **Observation**: lifecycle, ready count, desired count and detail for one generation. Carried in
  the resource's status, then folded into the journal by existing machinery.
- **Deployment target**: the cluster, its namespace prefix, and the control plane's narrow
  credentials for reaching its custom resources.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An operator applying a valid descriptor to a healthy cluster with the operator
  installed sees the service reported ready within 60 seconds, with no command issued after the
  apply.
- **SC-002**: A change originating in the cluster is reflected in what the operator sees within 15
  seconds.
- **SC-003**: A service left untouched in steady state records zero new observations and zero
  cluster writes over an hour of running.
- **SC-004**: A workload deleted out of band is restored within 10 seconds, in 100% of attempts.
- **SC-005**: 100% of failures produce an operator-readable reason; no service remains in a
  non-terminal in-progress state beyond the configured not-progressing timeout.
- **SC-006**: 50 services applied simultaneously all reach their reported ready state, with none
  waiting more than three reconcile cycles to be attempted.
- **SC-007**: Restarting either component during an in-flight rollout results in convergence with
  zero duplicated and zero orphaned objects, in 100% of attempts.
- **SC-008**: Zero objects not created by the operator are modified or deleted by it, across all
  scenarios including name collisions.
- **SC-009**: Deleting a service leaves zero of its objects in the cluster, verified by listing.
- **SC-010**: Rendering and lifecycle classification are fully exercisable in the standard offline
  test run, with no cluster present.
- **SC-011**: The operator's behaviour against a real cluster is verified by the standard test run
  without manual steps.
- **SC-012**: A cluster with no operator installed still accepts applies, and every affected
  service reports that nothing is acting on it within one reconcile cycle.

## Assumptions

- **Project maps to a namespace**, `{prefix}-{projectId}`; service name maps to the workload name
  within it. The descriptor already validates service names as DNS labels because they become
  object names. **Project ids are not validated today** — `ProjectEndpoint` accepts any path
  segment despite `ServiceKey`'s comment claiming otherwise — so this feature adds that validation.
- **A single deployment target.** One control plane, one cluster, one operator. The custom resource
  makes multi-cluster a later configuration change rather than a redesign, since a second operator
  in a second cluster needs no control plane change beyond addressing.
- **Services run at exactly one replica, and no autoscaler is rendered.** This is a correctness
  constraint, not a preference: `pekko.cluster.seed-nodes` is empty and
  `nakka.join-self-if-no-seed-nodes` is on, so each pod joins *itself*; the build carries no
  cluster-bootstrap or Kubernetes discovery dependency. Two replicas would be two independent
  single-node clusters sharing one journal, each hosting the same entity ids — concurrent writers
  to the same `persistence_id`. The descriptor's `autoscaling` block is validated and recorded but
  not honoured, and the operator renders no HorizontalPodAutoscaler. See Out of Scope.
- **A service's database is supplied by the operator of the service, not provisioned by the
  platform.** A nakka service is event-sourced and therefore never stateless; it needs Postgres
  carrying the journal, snapshot, durable-state, projection and timer tables. The descriptor's
  existing `env` and `secretKeyRef` already carry `NAKKA_DB_HOST`, `NAKKA_DB_PORT`, `NAKKA_DB_NAME`,
  `NAKKA_DB_USER` and `NAKKA_DB_PASSWORD`, so this needs no new descriptor field.
- **One database per service is mandatory, and the platform must say so.** Sharing a database
  between two nakka services is not merely untidy — it is destructive. `nakka_timers` has no
  service column, and `TimerSweeper` polls it unfiltered and **deletes** any row whose component id
  is not in its own registry, so two services on one database silently delete each other's timers.
  View row tables are named from the component id alone and collide the same way, as do projection
  offsets.
- **Secrets are provisioned out of band.** The platform references them by name and never reads a
  secret value.
- **Networking, ingress and TLS are out of scope.** Reaching a deployed service from outside the
  cluster is not part of this feature.
- **Storage is out of scope**; no persistent volumes are rendered. Service *state* lives in the
  external Postgres above, not on disk.
- **The existing generation and observation guards are reused, not redesigned.**
- **Authentication to the control plane remains the existing shared bearer token.**

## Out of Scope

- **Multi-replica services and Pekko cluster formation.** Requires cluster-bootstrap and Kubernetes
  API discovery in `modules/runtime`, a headless Service, pod-list RBAC, management health routes
  wired to probes, and the remoting port in the container spec. It is a feature in its own right,
  and until it lands the replica cap above holds. The operator is the right home for its rendering.
- **Platform-provisioned databases.** Creating a database per service, applying the canonical DDL,
  generating credentials into a Secret and injecting them. The operator is the natural home —
  directly analogous to how a streaming operator provisions topics per application — but it is
  separate work with its own credential-rotation and data-retention questions.
- **Autoscaling.** Follows multi-replica; meaningless before it.
- Multi-cluster and multi-region deployment, replication filters, origin routing.
- Ingress, service mesh, TLS termination, external addressing.
- Operator-configurable rollout strategies, canary and blue/green deployment.
- Secret creation and management.
- Log and metric collection from deployed workloads back into the control plane.
- A web console; the CLI remains the only client.
- Per-identity authorization for control plane operations.
