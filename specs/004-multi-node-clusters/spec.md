# Feature Specification: Multi-Node Service Clusters

**Feature Branch**: `004-multi-node-clusters`

**Created**: 2026-09-17

**Status**: Draft

**Input**: User description: "We can only have one instance of each service running — we can't form a multi-node cluster for a service. Spec it, using a base configuration plus an overlay for local running and a Kubernetes-specific overlay that each define the clustering mechanism for their respective means of execution."

## Why this exists

Every nakka service runs as exactly one pod. That is not a property of the programming model — the
runtime already uses cluster sharding, cluster singletons and a split-brain resolver, which is a
multi-node model through and through. It is a property of how a node finds its peers: it does not.
Each node binds its cluster transport to loopback, is given no peers, and joins *itself*.

So two replicas are not a two-node cluster. They are two one-node clusters, each hosting the same
entities over the same journal — two writers to one history, which is the failure event sourcing
exists to prevent. Everything downstream is that fact leaking out:

- one replica, always, and no autoscaler — a constraint, not a default;
- `autoscaling` in the descriptor validated, carried, and ignored;
- every deploy and restart a brief outage, because feature 003 had to stop Kubernetes running an old
  and a new pod side by side for the length of a rollout;
- `PartiallyReady` implemented and tested in feature 001, and unreachable ever since.

The fix is not in the operator. It is in teaching a node how to find its peers — **differently
depending on where it is running**, with the service's own code identical in both places.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A service runs as one cluster of several nodes (Priority: P1)

An operator asks for three instances of a service. Three pods start, find each other, and form
**one** cluster. Each entity lives on exactly one of them; a request arriving at any pod reaches it.

**Why this priority**: This is the feature. Everything else here is what makes it safe to rely on.

**Independent Test**: Apply the sample with three instances. Confirm one cluster of three members,
write to an entity through one pod and read it through another, and confirm that entity's history
has one unbroken sequence.

**Acceptance Scenarios**:

1. **Given** a descriptor asking for three instances, **When** it is applied, **Then** three pods run
   and report themselves members of the same single cluster.
2. **Given** that cluster, **When** an item is added to a cart through one pod and read back through
   another, **Then** the item is there — one entity, wherever the request landed.
3. **Given** many services deployed for the first time, each with several instances, **When** their
   pods start at the same moment, **Then** every one of them forms exactly one cluster — never two.
4. **Given** a descriptor that says nothing about instances, **When** it is applied, **Then** it runs
   as one instance exactly as it does today.
5. **Given** two services in one project, **When** both run several instances, **Then** each forms
   its own cluster and neither's nodes ever join the other's.

---

### User Story 2 - The same service runs locally, unchanged (Priority: P1)

A developer runs a service on their machine against the bundled database with no configuration at
all, exactly as today. The way its nodes find each other is simply different from how they do in a
cluster — chosen by where it is running, not by anything in the service's code.

**Why this priority**: Equal first. A platform that clusters in Kubernetes by breaking "it just runs"
locally has traded one property for another, and the local experience is where every user starts.
It is also what makes the rest testable: nearly every existing suite runs a service locally.

**Independent Test**: With nothing but the bundled database running, start a sample; it serves
requests. Start a second copy pointed at the first; the two form one cluster.

**Acceptance Scenarios**:

1. **Given** a developer's machine with no cluster configuration of any kind, **When** a service is
   started, **Then** it forms a cluster of one and serves requests, as it does today.
2. **Given** a running local node, **When** a second is started and told where the first is, **Then**
   the two form one cluster — so multi-node behaviour can be seen without Kubernetes.
3. **Given** a service's source code, **When** it is run locally and when it is deployed, **Then** it
   is the same code and the same build: how nodes find each other is supplied by where it runs.
4. **Given** a service with its own configuration, **When** it is deployed, **Then** its own settings
   still apply — the platform's choice of clustering mechanism does not replace them.
5. **Given** the existing test suites, **When** they run, **Then** they pass unchanged in their
   single-node form.

---

### User Story 3 - Deploys and scaling happen without an outage (Priority: P2)

An operator changes a running multi-instance service's image, or restarts it, or changes its
instance count. Instances are replaced a few at a time; the service keeps answering throughout.

**Why this priority**: This is what feature 003 had to give up, and the most visible thing multi-node
buys. Second only because it cannot exist before US1.

**Independent Test**: Against a three-instance service under steady request load, roll a new image.
Confirm requests keep succeeding throughout, and that at no moment are there two clusters.

**Acceptance Scenarios**:

1. **Given** a three-instance service under load, **When** its image is changed, **Then** instances
   are replaced progressively and requests continue to be answered throughout.
2. **Given** an instance being replaced, **When** it shuts down, **Then** it leaves the cluster
   deliberately and its entities resume elsewhere, rather than being discovered missing.
3. **Given** a running service, **When** its instance count is raised, **Then** the new instances
   join the existing cluster; **when** it is lowered, **Then** the surplus leave it cleanly.
4. **Given** an instance that has started but not yet joined the cluster, **When** its readiness is
   reported, **Then** it is not ready and receives no requests.
5. **Given** a service with some instances ready and some not, **When** its status is reported,
   **Then** it says so — partially ready, with the counts — rather than rounding to either extreme.

---

### User Story 4 - The platform survives losing a node (Priority: P2)

A pod dies without warning. The rest of the cluster notices, removes it, and carries on. Its
entities come back on the surviving nodes with their history intact.

**Why this priority**: A multi-node cluster that cannot lose a node is strictly worse than the single
pod it replaces — more moving parts, same fragility. Not first because graceful operation has to work
before ungraceful operation can be reasoned about.

**Independent Test**: Kill one pod of three abruptly. Confirm the cluster settles at two members,
that an entity which lived on the dead pod answers again with its state, and that a replacement pod
rejoins to restore three.

**Acceptance Scenarios**:

1. **Given** a three-instance cluster, **When** one pod is killed without warning, **Then** the
   remaining members remove it and keep serving.
2. **Given** an entity that lived on the lost pod, **When** it is next requested, **Then** it answers
   from a surviving node with nothing lost.
3. **Given** the lost pod's replacement, **When** it starts, **Then** it joins the existing cluster
   rather than forming a new one.
4. **Given** a network partition between members, **When** it resolves, **Then** exactly one side has
   survived — never two halves that both believe they are the cluster.

---

### User Story 5 - The control plane runs as several nodes too (Priority: P3)

The control plane is a nakka application. It runs multiple instances on the same terms as any
service, keeps accepting commands while one of them is replaced, and does the same work once, not
once per node.

**Why this priority**: The strongest proof the feature is real is the platform running on it. Last
because it depends on everything above, and because the control plane working at one instance is not
a regression.

**Independent Test**: Run the control plane at three instances. Apply services while restarting one
of them. Confirm nothing is projected twice and nothing is recorded twice.

**Acceptance Scenarios**:

1. **Given** a multi-instance control plane, **When** a service is applied, **Then** it is projected
   to the cluster once, by one node, not once per node.
2. **Given** a status report from the operator, **When** several control plane nodes observe it,
   **Then** it is recorded once — observing the same fact several times does not grow the history.
3. **Given** a control plane node being replaced, **When** commands arrive during it, **Then** they
   are accepted and none is lost.
4. **Given** the node that was doing the projecting, **When** it goes away, **Then** another takes
   over without being told to.

---

### Edge Cases

- **Several pods of a brand-new service start at the same instant.** The moment a split is most
  likely: each could decide it is first and start a cluster. Exactly one cluster must result, and the
  pods that lose that race must join it rather than proceed alone.
- **A pod cannot reach its peers, or the means of discovering them, at startup.** It must wait and
  report not-ready — never give up and form a cluster of one beside the real one.
- **An image built on a runtime that predates this feature is deployed with several instances.** It
  knows nothing of discovery and would join itself. It must never be reported ready in that state,
  so that it fails visibly rather than splitting silently.
- **A service is scaled from one instance to several, or back.** The existing single node is a
  perfectly good cluster of one and the new nodes join it; no restart of the original is needed.
- **Every node is replaced at once** (a node pool upgrade, a full restart). The service is briefly
  down and comes back as one cluster with its state intact — no worse than today.
- **A service's own configuration sets clustering options of its own.** The platform's mechanism is
  a starting point a service can see and override, not something hidden from it.
- **A node is slow rather than dead** — a long pause, a starved CPU. It must not be removed so
  eagerly that an ordinary hiccup costs a member, nor so reluctantly that a dead one lingers.
- **Two services share a project namespace.** A node must only ever consider pods of *its own*
  service as peers, however similar their labels.

## Requirements *(mandatory)*

### Functional Requirements

#### Forming one cluster

- **FR-001**: A service deployed with more than one instance MUST form a single cluster containing
  all of its running instances.
- **FR-002**: Under no circumstances — simultaneous first start, discovery being unreachable, slow
  peers — may the instances of one service form more than one cluster. An instance that cannot join
  MUST wait rather than proceed alone.
- **FR-003**: An instance MUST only ever treat instances of the *same service* as peers. Instances of
  other services, including others in the same project, MUST NOT be joined.
- **FR-004**: Each entity MUST be active on at most one instance at a time, and a request for it
  arriving at any instance MUST reach it.
- **FR-005**: Work the platform does once per service — firing timers, sweeping — MUST be done once
  per cluster, however many instances it has.

#### Choosing how nodes find each other

- **FR-006**: How a node finds its peers MUST be determined by where it is running, not by the
  service's code. The same build MUST run locally and deployed.
- **FR-007**: The platform's clustering settings MUST be organised as one shared base plus one overlay
  per means of execution — local, and Kubernetes — where each overlay defines only the clustering
  mechanism for its environment and everything common lives in the base once.
- **FR-008**: Run locally with no configuration, a service MUST form a cluster of one and work against
  the bundled database, exactly as today.
- **FR-009**: Run locally, it MUST be possible to start further nodes that join the first, so that
  multi-node behaviour can be observed without Kubernetes.
- **FR-010**: Deployed by the platform, a service MUST be told it is running in Kubernetes by the
  platform — the person writing the descriptor MUST NOT have to say so.
- **FR-011**: Selecting an overlay MUST NOT replace or hide the service's own configuration; a
  service's settings MUST still apply, and MUST be able to override the platform's.
- **FR-012**: Adding a further means of execution later MUST mean adding an overlay, not changing the
  base or any service.

#### Instances

- **FR-013**: The instance count MUST come from the descriptor's existing minimum-instances setting,
  which MUST now be honoured rather than ignored.
- **FR-014**: A descriptor that does not state it MUST run one instance, so that nothing changes for
  anyone who does not ask for more.
- **FR-015**: No autoscaler is rendered. The maximum and the CPU target remain validated and carried,
  and remain unhonoured, and the documentation MUST say so.
- **FR-016**: Changing the instance count of a running service MUST add instances to, or remove them
  from, the existing cluster, without replacing the instances that stay.

#### Deploying without an outage

- **FR-017**: A service with more than one instance MUST be updated progressively, and MUST keep
  serving requests throughout.
- **FR-018**: An instance being shut down MUST leave the cluster deliberately before it stops, handing
  its entities on, rather than being detected as lost.
- **FR-019**: At no point during an update may the old and new instances of a service constitute two
  separate clusters. This is the safety property feature 003 secured by replacing instances
  all-at-once; it MUST now hold *with* progressive replacement.
- **FR-020**: A single-instance service MUST remain correct through an update. Whether it is also
  uninterrupted is a consequence to be measured, not a requirement.

#### Honest readiness

- **FR-021**: An instance MUST NOT be ready, and MUST NOT receive requests, until it has joined the
  cluster.
- **FR-022**: An instance that is running but can never join — because its runtime predates this
  feature, or discovery is misconfigured — MUST never be reported ready, and the service MUST
  eventually be reported failed with a reason, using the platform's existing deadline.
- **FR-023**: A service with some instances ready and others not MUST be reported as partially ready,
  with both counts.
- **FR-024**: A service that serves no HTTP MUST still be able to become ready; readiness is about
  cluster membership, which every service has, not about a port, which not every service has.

#### Surviving failure

- **FR-025**: When an instance is lost without warning, the remaining instances MUST remove it and
  continue serving, and its entities MUST become available again on a survivor with their state.
- **FR-026**: After a network partition, exactly one side MUST survive as the cluster.
- **FR-027**: A replacement for a lost instance MUST join the existing cluster.

#### Least privilege

- **FR-028**: Whatever a service's instances are permitted to see in order to find their peers MUST be
  limited to their own project, and to reading.
- **FR-029**: Each service MUST run under an identity of its own, so that permissions are granted to
  a service and not to a project's workloads at large.
- **FR-030**: Those identities and permissions MUST be created by the platform and MUST be removed
  with the service.
- **FR-031**: Any new permission the platform's own components need in order to grant those MUST be
  the narrowest that works, and MUST be proven under the components' real identities — not assumed
  from tests that run with administrative credentials.

#### The control plane

- **FR-032**: The control plane MUST run as several instances on the same terms as any service.
- **FR-033**: Projecting desired state into the cluster MUST be done by one instance at a time, and
  MUST move to another without intervention if that instance goes away.
- **FR-034**: A status report observed by several control plane instances MUST NOT be recorded more
  than once.
- **FR-035**: The control plane MUST keep accepting commands while one of its instances is replaced.

#### Proving it

- **FR-036**: An automated test MUST deploy a real service with several instances into a real cluster
  and show: one cluster formed; an entity written through one instance and read through another; a
  progressive update under load without failed requests; and an instance killed and survived.
- **FR-037**: An automated test MUST start many instances simultaneously, repeatedly, and show that one
  cluster results every time. Once is not evidence for a race.
- **FR-038**: These tests MUST be skipped by the existing switch that disables cluster tests.
- **FR-039**: The existing suites MUST continue to pass, running locally in single-node form.

### Key Entities

- **Cluster**: the set of a service's running instances acting as one. There is exactly one per
  service, however many instances it has, including one.
- **Means of execution**: where a service is running — locally, or in Kubernetes. Determines how its
  nodes find each other, and nothing else about it.
- **Base and overlays**: the platform's clustering settings. The base holds what is common; each
  overlay holds only the peer-finding mechanism for one means of execution.
- **Instance count**: how many instances a service runs. Already in the descriptor as the minimum of
  its autoscaling range; honoured for the first time.
- **Service identity**: what a service's instances run as, and the read-only, project-scoped
  permission that lets them discover each other. Created and removed with the service.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A service asked for three instances is one cluster of three, ready to serve, within
  three minutes of being applied to a project that already has database capacity.
- **SC-002**: Across at least twenty simultaneous cold starts of a multi-instance service, the number
  of times more than one cluster forms is zero.
- **SC-003**: During a progressive update of a three-instance service under continuous request load,
  at least 99% of requests succeed, and none returns wrong or stale data.
- **SC-004**: After one of three instances is killed without warning, the service answers requests
  for entities that lived on it within sixty seconds, with their state intact.
- **SC-005**: A developer with only the bundled database running can start a sample with no
  configuration and have it serve a request, as they can today.
- **SC-006**: The source and the build of a service are identical between a local run and a deployed
  one; only the environment differs.
- **SC-007**: A service's instances can list nothing outside their own project and can modify nothing
  at all — verified by attempting both under a service's real identity.
- **SC-008**: An image that cannot cluster, deployed with several instances, is never reported ready
  and is reported failed within the platform's existing deadline.
- **SC-009**: With the control plane at three instances, applying a service produces exactly one
  projection, and one status report produces exactly one record, however many instances saw it.
- **SC-010**: Every suite that passes before this feature passes after it.

## Assumptions

- **Peer discovery in Kubernetes is through the cluster's own API**, with pods of a service
  identified by the labels the platform already puts on them. Chosen over name-resolution-based
  discovery for its faster and more deterministic convergence; the cost — every service holding a
  credential that can list pods in its project — is accepted and bounded by FR-028 to FR-030.
- **The instance count is fixed, not elastic.** Scaling a stateful, sharded cluster on a load signal
  means every scale-in is a member leaving under pressure; that needs draining proven first and is a
  feature of its own.
- **The selection mechanism is the platform's, not the application's.** The reference this design
  follows selects its overlay by replacing the application's configuration file wholesale, which is
  right for an application that owns its configuration and wrong for a platform running images whose
  configuration belongs to someone else (FR-011). The layering is adopted; the selector is not.
- **Locally, peers are named rather than discovered**: a node is told where an existing node is, and
  defaults to itself. That is the local overlay's entire mechanism.
- **Partition handling keeps the majority.** Two-instance services therefore cannot survive a
  partition with both sides intact, and an odd instance count is the documented recommendation.
- **One cluster per service, never per project.** Services do not share a cluster, in keeping with
  their not sharing a database.
- **No network isolation is added.** Cluster traffic between a service's pods is as reachable from
  other namespaces as its HTTP port already is (feature 003's stated non-goal).
- **Single-instance updates may still cost a brief interruption**; the requirement is that they stay
  correct (FR-020).
- **Governance comes from `CLAUDE.md`**; `.specify/memory/constitution.md` is still an unfilled
  template, as for features 001 to 003.
