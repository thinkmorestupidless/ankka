# Feature Specification: Deploy a Real nakka Service

**Feature Branch**: `003-deploy-real-service`

**Created**: 2026-09-17

**Status**: Draft

**Input**: User description: "Deploy a real nakka application to the cluster, end to end. Today every deployment path has only ever been proven with `registry.k8s.io/pause` — a placeholder that starts, stays up, and needs nothing. No actual nakka service has ever run in-cluster, and two concrete gaps block it: no sample builds a container image, and the operator renders a Deployment with no containerPort and no Kubernetes Service, so even once an image exists nothing can reach the workload's HTTP endpoints."

## Why this exists

Features 001 and 002 built a platform that deploys services and provisions their databases, and proved
both against real clusters. But every one of those proofs used `registry.k8s.io/pause` — an image
chosen precisely because it starts, stays up, and needs nothing. It opens no port, reads no
environment variable, and connects to no database.

So the platform's central claim — *apply a descriptor, get a running service* — has never been tested
against a service. Three things are currently believed rather than known:

- that a nakka runtime can reach its platform-provisioned database at all;
- that the schema the platform applies is the schema the runtime expects;
- that a deployed service can receive a request.

The third is not merely untested, it is impossible: nothing renders a port or a Service, so a
deployed workload has no address. `lifecycle: Ready` currently means "the container process started",
which for `pause` is the whole truth and for a real application is nearly meaningless.

This feature closes that gap and makes `Ready` mean something.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A deployed service can be reached (Priority: P1)

An operator applies a descriptor for a service that serves HTTP. The platform gives that service an
address inside the cluster, and reports it as `Ready` only once it is actually listening.

**Why this priority**: This is the missing half of "apply a descriptor, get a running service". Without
it a deployment is a process nobody can talk to. It is also the prerequisite for every other story —
US2 has nothing to prove without an address to send a request to.

**Independent Test**: Apply any descriptor that declares a port, using any image that listens on it,
and confirm a Service exists that routes to the workload, and that the service is not reported `Ready`
before the port accepts connections.

**Acceptance Scenarios**:

1. **Given** a descriptor that declares no port, **When** it is applied, **Then** the service is
   deployed with the platform's default port, reachable at a stable in-cluster address.
2. **Given** a descriptor declaring a specific port, **When** it is applied, **Then** the workload is
   configured to listen on that port and the address routes to that same port — the two cannot differ.
3. **Given** a workload whose port is not yet accepting connections, **When** its status is reported,
   **Then** it is not `Ready`, and it becomes `Ready` once the port opens.
4. **Given** a descriptor that both declares a port and sets the runtime's own port environment
   variable by hand, **When** it is applied, **Then** it is refused with a message naming the conflict,
   rather than deploying something whose address is wrong.
5. **Given** a service that serves no HTTP, **When** its descriptor says so explicitly, **Then** no
   address is created for it, no port is exposed, and it still reaches `Ready` when it is running.

---

### User Story 2 - A real nakka application runs on the platform (Priority: P1)

A developer packages the shopping cart sample, applies it through the CLI with no database
configuration of its own, and uses it: adds items to a cart over HTTP, reads the cart back, and finds
the data still there after the pod is replaced.

**Why this priority**: This is the point of the feature. It is the first time the runtime's database
connection, the platform-applied schema, single-node cluster formation, HTTP serving and status
reporting are exercised together rather than individually assumed. Equal priority to US1 because US1
is only demonstrably correct once something real depends on it.

**Independent Test**: Deploy the sample through the real CLI against a platform-provisioned database,
POST an item to a cart, GET the cart back and see the item, delete the pod, and GET the cart again.

**Acceptance Scenarios**:

1. **Given** the sample packaged as an image available to the cluster, **When** its descriptor is
   applied through the CLI with nothing about databases in it, **Then** the platform provisions a
   database, applies the schema, starts the service and reports it `Ready`.
2. **Given** a `Ready` sample service, **When** an item is added to a cart over HTTP, **Then** the
   request succeeds and reading that cart back returns the item.
3. **Given** a cart with items, **When** the workload's pod is deleted and replaced, **Then** reading
   the cart back returns the same items — the data was in the database, not in memory.
4. **Given** a `Ready` sample service, **When** its database is inspected directly, **Then** it
   contains the events the cart operations produced, in the service's own database and nowhere else.

---

### User Story 3 - The proof survives future changes (Priority: P2)

A maintainer changes how workloads are rendered and finds out immediately if a real service stops
working, rather than the next time someone deploys one by hand.

**Why this priority**: Without this, US1 and US2 are a demonstration rather than a guarantee, and the
chain silently rots. Lower than P1 only because the capability must exist before it can be guarded.

**Independent Test**: Break the rendering deliberately — remove the port, or point the address at the
wrong one — and confirm the automated suite fails.

**Acceptance Scenarios**:

1. **Given** the automated suite, **When** it runs, **Then** it deploys the real sample against a real
   cluster and a real database and exercises its endpoints over HTTP.
2. **Given** rendering that produces an address pointing at a port nothing listens on, **When** the
   suite runs, **Then** it fails.
3. **Given** a developer with no local cluster, **When** they run the test suite with cluster tests
   disabled, **Then** the suite is skipped along with the existing cluster suites and everything else
   still runs.

---

### Edge Cases

- **A descriptor declares a port outside the valid range** (zero, negative, or above 65535) — refused
  at apply time with a message naming the field, not deployed and left to fail in the cluster.
- **A descriptor declares a port and also sets the runtime's port environment variable** — refused, as
  in US1 scenario 4. Two sources of truth for one fact is the failure this design exists to prevent.
- **A service declares a port but never opens it** (misconfigured, or crashed during startup) — never
  reaches `Ready`, and the reported detail says the rollout did not progress, exactly as an unpullable
  image does today.
- **A service that served HTTP is changed to serve none, or the reverse** — the address is created or
  removed to match, without the operator needing to delete and recreate the workload.
- **The image is not present on the node** — reported as a pull failure with a reason, the existing
  behaviour for a bad image reference; this feature does not change it.
- **Two services in one project each serve HTTP** — each gets its own address, and they do not
  collide, even though both use the same default port number.

## Requirements *(mandatory)*

### Functional Requirements

#### Declaring a port

- **FR-001**: A service descriptor MUST be able to declare the port its workload listens on.
- **FR-002**: A descriptor that does not mention a port MUST default to the platform's standard
  service port, so that the common case needs no configuration.
- **FR-003**: A descriptor MUST be able to declare that its service serves no HTTP at all, and such a
  service MUST be deployed with no exposed port and no address.
- **FR-004**: A declared port MUST be validated at apply time — within the valid port range — and a
  bad value MUST be refused with a message naming the problem, alongside every other descriptor
  problem in the same response.
- **FR-005**: The declared port MUST be the single source of truth: the port the workload is
  configured to listen on, the port exposed on the workload, and the port its address routes to MUST
  all derive from it and MUST NOT be able to disagree.
- **FR-006**: A descriptor that declares a port and *also* sets the runtime's own port environment
  variable directly MUST be refused, naming the conflict.

#### Reaching the service

- **FR-007**: A service that declares a port MUST be given a stable in-cluster address that routes to
  its workload.
- **FR-008**: That address MUST be named after the service, so it is predictable without looking it
  up, and MUST be unique within its project.
- **FR-009**: The address MUST be removed when the service is deleted, with no orphan left behind.
- **FR-010**: A service that declares it serves no HTTP MUST NOT be given an address, and one that
  previously had an address MUST lose it.

#### Honest readiness

- **FR-011**: A service that declares a port MUST NOT be reported `Ready` until that port accepts
  connections.
- **FR-012**: A service that declares it serves no HTTP MUST still be able to reach `Ready` — having
  no port MUST NOT make it permanently un-`Ready`.
- **FR-013**: A service whose port never opens MUST eventually be reported as not progressing, with a
  reason an operator can act on, using the platform's existing deadline rather than a new clock.

#### Packaging a sample

- **FR-014**: The shopping cart sample MUST be packaged as a container image by the project's existing
  build, using the same mechanism and conventions as the operator's and control plane's images.
- **FR-015**: Building every image the project produces MUST remain one command — adding this image
  MUST NOT require knowing its name.
- **FR-016**: The local deployment script MUST make the sample's image available to the local cluster
  the same registry-free way it already does for the other two.

#### Proving it

- **FR-017**: An automated test MUST deploy the real sample against a real cluster and a real
  platform-provisioned database, through the same control plane path an operator would use.
- **FR-018**: That test MUST exercise the sample over HTTP — write, then read back — rather than only
  asserting that objects exist.
- **FR-019**: That test MUST prove durability by replacing the workload's pod and reading the same
  data back.
- **FR-020**: That test MUST be skipped by the existing switch that disables cluster tests, adding no
  new switch.
- **FR-021**: The feature MUST document a manual walkthrough that deploys the sample to a local
  cluster and exercises it, so the chain can be verified by hand without running the suite.

### Key Entities

- **Service port**: the port a service's workload listens on. Declared in the descriptor, defaulted
  when absent, and absent entirely for a service that serves no HTTP. The single fact from which the
  exposed port, the runtime's configuration and the address's target are all derived.
- **Service address**: the stable in-cluster name that routes traffic to a service's workload. Exists
  only for a service that declares a port; named after the service; removed with it.
- **Sample image**: a container image built from the shopping cart sample by the project's own build,
  carrying a real nakka runtime — the first workload to exercise the platform as an application rather
  than as a placeholder.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A developer can go from a checked-out repository to a working shopping cart running on a
  local cluster using only documented commands, with no hand-written Kubernetes manifests and nothing
  about databases or ports in the descriptor.
- **SC-002**: A cart item added over HTTP is readable over HTTP immediately afterwards, and is still
  readable after the workload has been replaced from scratch — proving it was persisted rather than
  held in memory.
- **SC-003**: A service is never reported `Ready` while its port is closed — verified by observing the
  reported status throughout a deployment, not only at the end.
- **SC-004**: A service that serves no HTTP deploys and reaches `Ready` with no address created for
  it, confirming the port is genuinely optional rather than nominally so.
- **SC-005**: The port the workload listens on and the port its address routes to are identical for
  every deployed service, including one that declares a non-default port.
- **SC-006**: Deliberately breaking the port or address rendering causes the automated suite to fail.
- **SC-007**: Building every image the project produces remains a single command after this feature,
  as it was before.
- **SC-008**: Two services deployed in the same project are independently reachable, each at its own
  address, with no port collision between them.

## Assumptions

- **The shopping cart sample is the only sample packaged.** The multi-agent planner needs a model API
  key to do anything, which makes it unusable as an automated in-cluster proof; packaging it is a
  later decision, not an oversight.
- **The platform's standard service port is the runtime's existing default**, so that a descriptor
  that says nothing about ports produces the behaviour the runtime already has. This feature does not
  choose a new number.
- **HTTP only.** Exposing other protocols, multiple ports per service, or anything reachable from
  outside the cluster (ingress, load balancers, TLS) is out of scope. A service is reachable from
  within the cluster; getting traffic in from outside remains the operator's own concern, as it is
  today for the control plane.
- **One replica still.** Nothing here changes the single-replica constraint; an address routing to one
  pod is all that is required, and the constraint's reasons are unchanged.
- **Readiness means the port is open**, not that the application considers itself healthy. A deeper
  health endpoint is a larger design question — what it should assert, and what a service must
  implement to satisfy it — deliberately left for later.
- **The existing database provisioning is reused unchanged.** The sample gets its database the same
  way any service does; this feature adds no database behaviour and changes none.
- **Governance comes from `CLAUDE.md`**, not `.specify/memory/constitution.md`, which is still an
  unfilled template — the same position features 001 and 002 were written under.
