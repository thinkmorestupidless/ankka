# Feature Specification: Managed Broker — Topics Provisioned, Secured and Injected the Way Databases Are

**Feature Branch**: `027-managed-broker`

**Created**: 2026-10-03

**Status**: Draft

> **Correction, 2026-10-03.** The defect this spec's first story fixes was real at the commit it was
> written against and was fixed by feature 019 (merged in #59) before any work on this spec began:
> the operator now has `SharedEnvPrefixes`, naming `ANKKA_KAFKA_`, and gives the variable to both
> containers of a process-hosted service. When this spec is picked up, User Story 1 reduces to its
> scenarios 2 and 3 (one shared prefix declaration read by the operator and the wasm `config`
> import) and scenario 5 is dropped; FR-001 to FR-003 and the Context's defect paragraph should be
> rewritten to match before `/speckit-clarify` runs.

**Input**: User description: "Topics are how ankka services exchange facts, and the platform provides
no broker: a service names a Kafka it cannot reach on the platform at all when it is process-hosted,
because the operator routes only `ANTHROPIC_`, `ANKKA_MODEL_` and `ANKKA_DB_` variables to the sidecar
and the sidecar is the only thing that reads `ANKKA_KAFKA_BOOTSTRAP_SERVERS`. First fix that defect,
with the prefix lists that are duplicated in `controlplane-api`, the operator and the sidecar's wasm
`config` import collapsed into one declaration. Then give the platform a broker the way it has a
database: one Kafka per installation as a kustomization component, topics declared in the descriptor
and rendered by the operator under the project's name, a credential per service, ACLs that make a
project the boundary, connection variables injected into the workload that a descriptor may not set,
a status phase, and nothing ever deleted. Out of scope: topics shared across projects by grant,
schema registries, a broker per project, and any change to how a view or consumer reads a topic,
which is 024-replayable-topics."

## Context

A view or a consumer can read a Kafka topic and a consumer can publish to one. The runtime's
`modules/runtime/.../runtime/Kafka.scala` holds `KafkaPublisher` and `KafkaSubscriber` over Pekko
Connectors; `ProjectionRuntime.fromEnv()` reads `ANKKA_KAFKA_BOOTSTRAP_SERVERS` and connects when it
is set, and a topic-sourced component in a service with no broker is refused at startup, naming the
variable. That is the whole of the platform's broker support: one bootstrap address, no TLS, no
SASL, no credential, no topic creation beyond whatever the broker's auto-create setting does, and no
isolation between projects. The operator, the CRD and the control plane never mention Kafka. The
docs say "The platform provides no broker of its own", and the domain plan's whole inter-service
design rests on seven topics.

Before any of that, there is a defect. For process hosting, `Rendering.scala` in the operator splits
a descriptor's `env` by `SidecarEnvPrefixes`, which is `ANTHROPIC_`, `ANKKA_MODEL_` and `ANKKA_DB_`:
those go to the sidecar container and everything else to the app container. The same list is
declared again as `ServiceSpec.SidecarEnvPrefixes` in `controlplane-api/.../descriptors.scala`, and
a third, longer list of reserved prefixes lives in the sidecar's `wasm/HostImports.scala` for the
`config` import. `ANKKA_KAFKA_` is in none of them. So a Python or TypeScript service that declares a
topic source and sets the variable in its descriptor has the variable rendered onto its app
container, where nothing reads it, while the sidecar, which calls `ProjectionRuntime.fromEnv()` in
its `Main.scala`, sees nothing and refuses to start, naming the component that needs a broker. The
docs for topics and the configuration reference both say the platform routes the variable to the
sidecar. Embedded and wasm services are unaffected because they are one container. No test caught
it because no k3s suite deploys a process-hosted service with a topic.

The database side shows exactly the shape a broker should take, and the reasons are the same.
`ServiceProjection.scala` in the control plane decides provisioning from the descriptor: setting any
`ANKKA_DB_*` variable turns it off, and the resource records which path the service took. The
operator's `Provisioning.decide` is a pure function from an observation to a plan, with `Supplied`,
`Waiting`, `Ready` and `Failed` and a reported phase, and treats CNPG's transient messages as
in-progress. Capacity is shared per project: one CNPG `Cluster` per project namespace, with a
project-level issuer and a network policy. Per service, `CnpgRendering` renders a `DatabaseRole` and
a `Database` with reclaim `retain`, so nothing is ever destroyed and a re-applied name recovers its
data. The credential is a client certificate issued per service; the `<svc>-db` Secret holds only
connection coordinates and is created only if absent. Injection is `envFrom` that Secret plus literal
TLS variables, routed to the sidecar for process hosting, and the runtime cannot tell a provisioned
database from a supplied one. The result is reported in the resource's status as a `DatabaseStatus`.

Five decisions follow.

- **The defect is fixed first and separately.** It is a one-line cause with a real failure behind
  it, and it needs no broker to exist. The fix is the prefix joining one shared declaration that
  the control plane's validation, the operator's rendering and the sidecar's `config` import all
  read, so the three lists cannot drift again, and a k3s case that deploys the Python sample with
  a topic so the failure stays visible.
- **One broker per installation, not per project.** A CNPG cluster per project is cheap; a Kafka
  per project is not, and a topic's whole value is that services in different projects can share
  it. The project boundary is the ACL, enforced by the broker, not a separate broker.
- **Topics are declared in the descriptor and owned by the project.** A descriptor lists
  `topics: [{name, partitions}]`; the operator renders each as `<project>.<name>`. The runtime
  maps a component's declared topic name onto the project-qualified one, so service code names
  `transactions` and the broker sees `money.transactions`. A service reads and writes only its
  project's topics.
- **The credential is the certificate the service already holds.** Strimzi's `KafkaUser` with TLS
  authentication, trusting the installation's service authority, means no password is generated
  and nothing is stored that could leak. Where that is not possible, a SCRAM credential written
  under the database rule (create if absent, never read back) is the fallback.
- **Supplying your own is the escape hatch, exactly as for a database.** A descriptor that sets
  any `ANKKA_KAFKA_*` variable gets no provisioning and no topics, and `ServiceSpec.problems`
  refuses `topics` beside it, so a descriptor says one thing.

This feature is not a change to how topics are read: start positions, qualified consumer group ids
and rebuild are 024-replayable-topics, which this feature assumes, since the broker's ACL scheme
needs group ids that carry the project. It is not a schema registry and not a grant mechanism for
cross-project reads, which is an open question below.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A process-hosted service with a topic starts on the platform (Priority: P1)

A developer deploys the Python shopping cart, which has a producing consumer, with
`ANKKA_KAFKA_BOOTSTRAP_SERVERS` in its descriptor pointing at an installation's Kafka. The pod
starts, the sidecar connects to the broker, and the consumer publishes. Before this story the sidecar
refused to start and the pod never became ready, while the variable sat unread on the app container.

**Why this priority**: It is a defect in shipped behaviour that the docs describe as working, and
it blocks every process-hosted service with a topic whether or not the platform ever provisions a
broker. It is fixed before the rest of this feature and can be released alone.

**Independent Test**: A unit test over the operator's rendering asserts the variable lands on the
sidecar container; a unit test over `ServiceSpec` asserts the shared declaration is the one the
operator uses; a k3s case deploys the Python sample with a topic against a Kafka in the cluster and
asserts the pod is ready and a message is published.

**Acceptance Scenarios**:

1. **Given** a process-hosted descriptor with `ANKKA_KAFKA_BOOTSTRAP_SERVERS` in its `env`,
   **When** the operator renders its Deployment, **Then** the variable is on the sidecar container
   and not on the app container.
2. **Given** the three places that hold a prefix list, **When** they are read, **Then** they are one
   declaration in `controlplane-api`, and the operator and the sidecar's wasm `config` import read
   it; a test that adds a prefix to the declaration sees it in all three behaviours.
3. **Given** a wasm module that asks its `config` import for `ANKKA_KAFKA_BOOTSTRAP_SERVERS`,
   **When** it does, **Then** the answer is absent, since the prefix is now the platform's.
4. **Given** the Python sample with a topic-sourced view deployed to a k3s cluster with a Kafka,
   **When** the pod starts, **Then** the sidecar connects and the pod becomes ready, and a message
   published to the topic appears as a row in the view.
5. **Given** the same deployment before the fix, **When** the pod starts, **Then** the sidecar
   refuses to start naming the component, which is the failure this case keeps visible.

---

### User Story 2 - A service declares a topic and the platform provides it (Priority: P1)

A developer adds `topics: [{"name": "transactions", "partitions": 12}]` to the wallet's descriptor
and sets no broker variable. On apply the operator creates the topic as `money.transactions` on the
installation's broker, a credential for the wallet, and ACLs; the pod receives the bootstrap
address and its TLS settings; the wallet's transaction notifier publishes; and `ankka services get`
reports the broker phase as ready. A second service in the same project with a view over
`transactions` reads it with no topic declaration of its own.

**Why this priority**: This is the feature. Without it every deployment hand-configures a broker
outside the platform and nothing isolates projects.

**Independent Test**: In the k3s suite with the broker component installed, apply two services in
one project, one declaring and publishing to a topic, one reading it; assert the topic exists under
the project's name, both pods are ready, a published message reaches the reader's view, and the
status reports the broker phase.

**Acceptance Scenarios**:

1. **Given** a descriptor declaring a topic and setting no `ANKKA_KAFKA_*` variable, **When** it is
   applied, **Then** the resource carries the topic, and the operator renders a topic named
   `<project>.<name>` with the declared partitions, a credential for the service, and ACLs granting
   the service read and write on its project's topics.
2. **Given** the rendered workload, **When** its environment is inspected, **Then** it carries
   `ANKKA_KAFKA_BOOTSTRAP_SERVERS` and the `ANKKA_KAFKA_TLS_*` variables, routed to the sidecar for
   process hosting, and the service's code names the topic by its declared name.
3. **Given** a consumer in that service producing to the topic, **When** a change is delivered,
   **Then** the message is on `<project>.<name>` on the installation's broker.
4. **Given** a second service in the same project with a view over the same topic name and no
   `topics` declaration, **When** it starts, **Then** it reads the project's topic and the view
   fills.
5. **Given** the service's status, **When** `ankka services get` is run, **Then** it reports a
   broker phase in the shape of the database phase: waiting while the broker's operator works,
   ready when the topic and credential exist, failed only for a problem that will not clear.
6. **Given** a declared topic whose partition count the broker has not yet created, **When** the
   status is read, **Then** it is waiting, not failed, as a CNPG `Database` racing its role is.

---

### User Story 3 - A project is the boundary, enforced by the broker (Priority: P1)

A service in project `casino` declares a view over a topic named `transactions`. Its project has no
such topic; `money` does. The service's credential is refused by the broker for `money.transactions`
and the view stays empty, and the refusal is in the service's log naming the topic. Nothing in ankka
had to check anything: the broker's ACL did.

**Why this priority**: Isolation that rests on a naming convention is not isolation. The database
side closes other projects' databases at the network; the broker must close other projects' topics
at the ACL, or a misnamed topic reads another tenant's facts.

**Independent Test**: In the k3s suite, deploy a service in a second project with a view over the
first project's topic name; assert the broker's authorization failure in the sidecar's log and that
the view has no rows after a message is published in the first project.

**Acceptance Scenarios**:

1. **Given** a service in project B with a view over a topic that exists only as
   `A.<name>`, **When** it starts, **Then** the broker refuses its credential for that topic, the
   refusal is logged naming the topic, and the view receives nothing.
2. **Given** a service in project A, **When** it attempts, through a supplied client in a test, to
   produce to `B.<name>`, **Then** the broker refuses the write.
3. **Given** the broker's ACLs, **When** they are listed, **Then** each service's credential has
   read and write on exactly the topics prefixed with its project and nothing else, and no
   credential has cluster-wide rights.
4. **Given** a descriptor that both declares `topics` and sets an `ANKKA_KAFKA_*` variable,
   **When** it is applied, **Then** it is refused with an error naming both, before any resource
   is written.
5. **Given** a descriptor that sets `ANKKA_KAFKA_*` and declares no topics, **When** it is applied,
   **Then** the service is marked supplied, nothing is provisioned, and the variables reach the
   sidecar as the defect fix guarantees.

---

### User Story 4 - Nothing is destroyed, and a re-applied service recovers its topic (Priority: P2)

An operator deletes a service that declared a topic, then applies it again under the same name. The
topic and every message on it are still there, and the status reports the topic as recovered. A
platform administrator who wants a topic gone removes it by hand, as for a database.

**Why this priority**: The database rule exists because losing data on a delete is worse than any
leftover. A broker that deleted a topic with its service would be the first thing on the platform
that destroys data.

**Independent Test**: Declare, publish, delete the service, re-apply it, read from the topic's
earliest offset and assert the message is there; read the status and assert recovered.

**Acceptance Scenarios**:

1. **Given** a service with a declared topic holding messages, **When** the service is deleted,
   **Then** the topic and its messages remain on the broker and the credential is retained.
2. **Given** the same name applied again, **When** the status is read, **Then** the broker phase
   reports the topic recovered, and the service's consumers resume from their committed offsets.
3. **Given** a project deleted after its last service, **When** the broker is inspected, **Then**
   the project's topics remain and the documentation says who removes them.

---

### User Story 5 - The local installation has a broker (Priority: P2)

A developer runs `deploy-local.sh` on kind. A single-node Kafka comes up as a platform component
with a zero-trust policy admitting the workload namespaces, and the shopping cart sample's checkout
notifier publishes to it with no configuration in the sample's descriptor.

**Why this priority**: The k3s suites and the local platform need a broker to prove the rest; an
installation that must bring its own Kafka before the first topic works is one where the feature is
never tried.

**Independent Test**: `kubectl apply -k` of the local overlay brings up the broker; the sample
deploys with a declared topic; the smoke test publishes and reads.

**Acceptance Scenarios**:

1. **Given** the local overlay, **When** it is applied, **Then** a broker runs in its own namespace
   with a policy admitting every workload namespace and nothing else, and its own operator if the
   chosen shape has one.
2. **Given** the cloud overlay, **When** `RemoteOverlaySuite` renders it, **Then** the broker's
   sizing and storage are placeholders marked `SET` and the component is the same one.
3. **Given** `ReservedProjectIdsSuite` and the manifests, **When** the broker component asks for an
   `ankka://` identity, **Then** it is in the `platform` project.

---

### Edge Cases

- **A topic declared by two services in one project.** Both declarations must agree on partitions;
  the second apply that disagrees is refused at the control plane naming the first service. A
  topic is the project's, declared by whichever service publishes to it first.
- **A partition count lowered.** Kafka cannot shrink a topic; the apply is refused at the control
  plane with the reason. Raising it is applied.
- **A topic name that is not a valid Kafka name once prefixed.** Refused by `ServiceSpec.problems`
  with the rule, before any resource is written.
- **The broker's operator is slow to create a credential.** The status is waiting; the pod starts
  and the sidecar's connection backs off until the credential exists, within the existing restart
  source's backoff.
- **A service's certificate rotates.** The broker trusts the authority, not the leaf, so a
  rotated certificate authenticates without any change to the `KafkaUser`.
- **A message published with a `ce-subject` naming another project's entity.** Ordering is by
  subject within the topic; the project is in the topic name, not the subject, and nothing
  changes.
- **An installation that already runs a Kafka it wants to keep.** The cloud overlay points the
  component's rendering at an external bootstrap address and the operator still renders topics,
  users and ACLs through the broker's own operator, if it is one that can manage an external
  cluster; if not, every service supplies its own and the feature is off. [NEEDS CLARIFICATION:
  whether the chosen broker operator supports managing topics on a cluster it did not create.]
- **Consumer group ids.** The qualified form from 024-replayable-topics is what the ACL grants
  group access to; the old unqualified form is refused by the broker, which is deliberate.

## Requirements *(mandatory)*

### Functional Requirements

**Defect fix (released first)**

- **FR-001**: `ANKKA_KAFKA_` MUST be a sidecar-routed prefix, so a process-hosted service's broker
  variables reach the sidecar container.
- **FR-002**: The sidecar-routed and platform-reserved prefix lists MUST be one declaration in
  `controlplane-api`, read by the operator's rendering and the sidecar's wasm `config` import.
- **FR-003**: A k3s case MUST deploy a process-hosted service with a topic and assert it becomes
  ready and publishes.

**Declaration and provisioning**

- **FR-004**: A descriptor MUST be able to declare topics by name and partition count, validated
  by `ServiceSpec.problems`, projected onto the resource by the control plane and declared in the
  CRD schema with `CrdSchemaSuite` holding the two together.
- **FR-005**: The operator MUST render, per declared topic, a topic named `<project>.<name>` with
  the declared partitions, and per service a credential and ACLs granting read and write on the
  project's topics and the service's qualified consumer groups, and nothing wider.
- **FR-006**: The operator MUST inject the bootstrap address and TLS settings as `ANKKA_KAFKA_*`
  variables routed to the sidecar, and the runtime MUST map a component's declared topic name to
  the project-qualified name.
- **FR-007**: A descriptor that sets any `ANKKA_KAFKA_*` variable MUST be marked supplied and get
  no provisioning, and one that also declares topics MUST be refused.
- **FR-008**: The resource's status MUST report a broker phase in the shape of the database phase,
  treating the broker operator's transient states as waiting.
- **FR-009**: The platform MUST never delete a topic, a credential or an ACL; a re-applied service
  MUST report its topic recovered.

**Platform**

- **FR-010**: A kustomization component MUST provide one broker per installation with a zero-trust
  policy admitting every workload namespace, enabled in the local overlay and placeholdered in the
  cloud overlay.
- **FR-011**: The broker's credential for a service MUST be the service's certificate where the
  broker supports it; a generated credential, where used, MUST follow the database rule of create
  if absent and never read back.

**Documentation**

- **FR-012**: The topics guide and the configuration reference MUST describe declaration,
  qualification, the escape hatch and the retention rule, and the limitations page MUST drop "the
  platform provides no broker".

### Key Entities

- **Topic declaration**: name and partitions on a descriptor; rendered as a project-qualified
  topic.
- **Broker credential**: the service's identity on the broker, certificate-backed.
- **Broker status**: phase, topic names, recovered flag and detail on the resource's status.
- **Prefix declaration**: the one list of sidecar-routed and platform-reserved variable prefixes.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A process-hosted service with a topic becomes ready on a real cluster; before the fix
  the same deployment never does.
- **SC-002**: A service in another project is refused by the broker for a topic it did not
  declare, measured by the broker's own authorization failure, not by a platform check.
- **SC-003**: A service declaring a topic needs no broker configuration in its descriptor and
  publishes on first deploy.
- **SC-004**: Deleting and re-applying a service loses no message on its topic.
- **SC-005**: The three prefix lists are one declaration, verified by a test that changes it once
  and observes all three behaviours.

## Assumptions

- Strimzi is the broker shape the plan evaluates first, since `KafkaTopic` and `KafkaUser` as
  resources are what make the operator's rendering a pure function; a plain StatefulSet would need
  the operator to speak the admin protocol itself.
- The installation's service authority can be trusted by the broker for TLS client
  authentication, as the per-project database authority is trusted by Postgres.
- 024-replayable-topics lands first or together, since the ACLs grant the qualified group ids.
- Kafka remains the only broker; the two-method broker interface is unchanged.

## Dependencies

- Gates every domain stage going to production and no stage's build: a Kafka the installation
  supplies is enough to develop against.
- The defect fix (FR-001 to FR-003) gates domain stage 2's process-hosted services and is released
  on its own.
- 024-replayable-topics: qualified consumer group ids are what the ACLs grant.
- 023-secret-store and 026-telemetry-export share the prefix declaration of FR-002.
- 025-polyglot-service-client: none.

## Open Questions

- Strimzi versus a plain broker: Strimzi gives topics, users and ACLs as resources at the cost of
  another operator in every installation. [NEEDS CLARIFICATION: decided in the plan after measuring
  the k3s suite's startup cost with Strimzi installed.]
- Whether a cross-project read should be grantable per topic in the declaring descriptor, which
  would let `money.transactions` be read by `casino` services without a relay service in between.
  The domain plan assumes it is; without it, every cross-project fact needs a consumer in the
  owning project that republishes to a topic the reader's project owns.
- Whether the installation's broker is also where the platform's own future needs go, and so
  whether the `platform` project should hold topics.
