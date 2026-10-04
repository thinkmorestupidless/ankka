# Feature Specification: Managed Broker — Topics Provisioned, Secured and Injected the Way Databases Are

**Feature Branch**: `027-managed-broker`

**Created**: 2026-10-03

**Status**: Draft

> **Revised 2026-10-04.** As first written, this spec's first story fixed a defect: a process-hosted
> service's broker variable was rendered onto its app container, where nothing reads it, and the
> sidecar refused to start. Features 019 and 023 fixed that before any work began here: the three
> lists of platform variables are one declaration, `PlatformVariables` in `core`, which names
> `ANKKA_KAFKA_` as a prefix given to both programs of a process-hosted service, and the control
> plane, the operator and the module host all read it. What is left of that story is its proof on a
> cluster. The description below is the one this spec was written from, defect included.

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

A supplied broker reaches a service of every hosting. `PlatformVariables` in `core` says once which
variables are the platform's, and the control plane's validation, the operator's rendering and the
module host's `config` import all read it; it names `ANKKA_KAFKA_` as a prefix given to both
programs of a process-hosted service, because the sidecar is what connects to the broker and the
process may register a component that needs one only where there is one. A rendering test holds
that split. What nothing holds is the whole: no k3s suite deploys a process-hosted service with a
topic, which is how the variable sat unread on the app container, with the sidecar refusing to
start, until the lists were made one.

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

- **What is already built is proved first.** A k3s case deploys the Python sample with a topic
  against a Kafka in the cluster, so the failure that went unseen stays visible. It needs no
  provisioning, and the Kafka it stands up in k3s is the one every later story's suite uses.
- **One broker per installation, not per project.** A CNPG cluster per project is cheap; a Kafka
  per project is not. The project boundary is the ACL, enforced by the broker, not a separate
  broker, and it is a hard one: no service reads or writes a topic of another project. One broker
  is what leaves a later feature able to grant a single topic across that boundary.
- **Topics are declared in the descriptor and owned by the project.** A descriptor lists
  `topics: [{name, partitions}]`; the operator renders each as `<project>.<name>`. The runtime
  maps a component's declared topic name onto the project-qualified one, so service code names
  `transactions` and the broker sees `money.transactions`. A service reads and writes only its
  project's topics.
- **The credential is the certificate the service already holds.** The broker trusts the
  installation's service authority and knows a service by its certificate's common name, so no
  password is generated and nothing is stored that could leak. Planning found that the service
  certificate has no common name today and that Kafka knows a TLS client by nothing else, so the
  certificate gains one, naming the project and the service, in an installation that has a broker.
- **Supplying your own is the escape hatch, exactly as for a database.** A descriptor that sets
  any `ANKKA_KAFKA_*` variable gets no provisioning and no topics, and `ServiceSpec.problems`
  refuses `topics` beside it, so a descriptor says one thing.

This feature is not a change to how topics are read: start positions, qualified consumer group ids
and rebuild are 024-replayable-topics, which this feature assumes, since the broker's ACL scheme
needs group ids that carry the project. It is not a schema registry and not a grant mechanism for
cross-project reads: a fact one project's services need from another's is republished, by a
consumer in the owning project, to a topic the reader's project owns.

## Clarifications

### Session 2026-10-04

- Q: Can a service read another project's topic? → A: No. The project is a hard boundary in this
  feature; a cross-project fact needs a consumer in the owning project that republishes to a topic
  the reader's project owns. Grants are a later feature.
- Q: Which services are known to the installation's broker? → A: Every service with a runtime (not
  web-hosted), in an installation that has a broker, is given a credential and the broker's
  address, unless its descriptor names a broker of its own. Declaring a topic is not what earns
  them.
- Q: What happens when a component names a topic no descriptor of its project declares? → A:
  Nothing is created. The component waits and tries again, the service's log names the missing
  topic, and the service is still ready; it flows once a service of the project declares the topic.
- Q: What is the broker made of? → A: Strimzi. Its operator runs the broker, and topics, users and
  permissions are resources ankka's operator renders. The plan measures what it costs the k3s
  suites to start and returns to this only if that is unacceptable.
- Q: Are the four new glossary terms right? → A: Yes: broker (refusing Kafka, message bus, queue),
  partition (refusing shard), declared topic (refusing managed topic, provisioned topic) and broker
  variable (refusing Kafka variable). What is published to a topic gets no term of its own, since
  "message" is a gRPC status's, and the features say what the broker allows or refuses a credential
  rather than "ACL", which is an endpoint's.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A process-hosted service with a topic is proved on a cluster (Priority: P1)

A developer deploys the Python shopping cart, whose graph consumer publishes to a topic, with
`ANKKA_KAFKA_BOOTSTRAP_SERVERS` in its descriptor naming a Kafka in the cluster. The pod starts, the
sidecar connects to the broker, and the consumer publishes. The platform gives the variable to both
containers today; nothing has ever deployed such a service to a cluster to show that it works.

**Why this priority**: The failure this guards against shipped once, in behaviour the docs described
as working, and went unseen because no suite deploys a process-hosted service with a topic. It is
also the first use of a Kafka inside the k3s suites, which every later story needs.

**Independent Test**: A k3s case deploys the Python sample with a topic against a Kafka in the
cluster and asserts the pod is ready and a message the consumer publishes is read from the topic.

**Acceptance Scenarios**:

- added `features/broker/supplied.feature`: a service hosted as a process is ready with the broker its descriptor names
- added `features/broker/supplied.feature`: a consumer of a service hosted as a process publishes to the broker its descriptor names
- added `features/broker/supplied.feature`: a broker variable is given to both programs of a service hosted as a process

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

- added `features/broker/topics.feature`: a declared topic is made on the installation's broker for its project
- added `features/broker/topics.feature`: a service with a declared topic is told where the installation's broker is
- added `features/broker/topics.feature`: a service proves which service it is to the broker with its certificate
- added `features/broker/topics.feature`: both programs of a service hosted as a process are told where the installation's broker is
- added `features/broker/topics.feature`: a consumer publishes to its project's topic by the name the descriptor declared
- added `features/broker/topics.feature`: another service of the project reads a declared topic without declaring it
- added `features/broker/topics.feature`: a service that declares no topic is told where the installation's broker is
- added `features/broker/topics.feature`: a consumer that publishes to a topic no descriptor declares waits for it
- added `features/broker/topics.feature`: what waited for a topic is published once the topic is declared
- added `features/broker/topics.feature`: a web-hosted service is given nothing of the installation's broker
- added `features/broker/installation.feature`: a service of an installation with no broker is deployed as it was before
- added `features/broker/topics.feature`: the status says how far the platform has got with a service's topics
- added `features/broker/descriptor.feature`: two services of a project that declare one topic agree on its partitions
- added `features/broker/descriptor.feature`: a topic's partitions can be made more and never fewer

---

### User Story 3 - A project is the boundary, enforced by the broker (Priority: P1)

A service in project `casino` has a view over a topic named `transactions`. Its project has
declared no such topic; `money` has. The view reads `casino`'s topic of that name, which does not
exist, so it stays empty and the service's log names the topic it waits for. Nothing of
`money.transactions` reaches it. And a client holding the `casino` service's credential that asks
the broker for `money.transactions` by its full name is refused, to read or to write. Nothing in
ankka had to check anything: the broker's ACL did.

**Why this priority**: Isolation that rests on a naming convention is not isolation. The database
side closes other projects' databases at the network; the broker must close other projects' topics
at the ACL, or a misnamed topic reads another tenant's facts.

**Independent Test**: In the k3s suite, deploy a service in a second project with a view over the
first project's topic name; assert the broker's authorization failure in the sidecar's log and that
the view has no rows after a message is published in the first project.

**Acceptance Scenarios**:

- added `features/broker/isolation.feature`: a service reads nothing of another project's topic of the same name
- added `features/broker/isolation.feature`: a service's credential is refused a topic of another project
- added `features/broker/isolation.feature`: a service's credential reaches the topics of its own project and nothing else
- added `features/broker/descriptor.feature`: a descriptor's topics are refused when they cannot be made
- added `features/broker/supplied.feature`: a service whose descriptor names a broker is given nothing on the installation's

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

- added `features/broker/kept.feature`: a deleted service's topic keeps what was published to it
- added `features/broker/kept.feature`: a service deployed again finds its topic
- added `features/broker/kept.feature`: a view of a service deployed again reads on from where it had read to
- added `features/broker/kept.feature`: a deleted project's topics are kept

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

- added `features/broker/installation.feature`: a local platform has a broker from the start
- added `features/broker/installation.feature`: only the services of the installation reach its broker
- added `features/broker/installation.feature`: an installation in a cluster has the broker a local platform has
- added `features/broker/installation.feature`: the installation's broker belongs to the platform and to no project of a member

---

### Edge Cases

- **A topic declared by two services in one project.** Both declarations must agree on partitions;
  the second apply that disagrees is refused at the control plane naming the first service. A
  topic is the project's, declared by whichever service publishes to it first.
- **A web-hosted service that declares topics.** It has no runtime to read or publish with, so
  `ServiceSpec.problems` refuses `topics` for web hosting, as it refuses a database variable there.
- **A service that declares no topic and never touches one.** It is given a credential and the
  broker's address like any other, and its status carries the broker phase; it connects to
  nothing until a component of it names a topic.
- **An installation with no broker.** A service that declares no topic is rendered exactly as
  before this feature, with no broker phase. One that declares a topic reports the broker phase
  failed, naming the missing broker, and is not otherwise held back.
- **A topic named by a component and declared by nobody.** A mistyped name, or a sample that
  publishes wherever it finds a broker: the topic is not made, the component waits, and the log
  names it. The descriptor is the only place a topic comes from.
- **An installation that gains a broker.** Every service is told where it is, so every service is
  rolled once, and each service certificate is reissued once with its common name. Neither refuses
  a request, and a service that names its own broker is untouched.
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
  cluster; if not, every service supplies its own and the feature is off. Whether Strimzi's topic
  and user operators can manage a cluster they did not create is for the plan to verify; nothing
  else in this feature depends on the answer.
- **Consumer group ids.** The qualified form from 024-replayable-topics is what the ACL grants
  group access to; the old unqualified form is refused by the broker, which is deliberate.

## Requirements *(mandatory)*

### Functional Requirements

**Proof of what is built**

- **FR-001**: A k3s case MUST deploy a process-hosted service with a topic, against a Kafka in the
  cluster named in its descriptor, and assert it becomes ready and publishes.

**Declaration and provisioning**

- **FR-002**: A descriptor MUST be able to declare topics by name and partition count, validated
  by `ServiceSpec.problems`, projected onto the resource by the control plane and declared in the
  CRD schema with `CrdSchemaSuite` holding the two together. A web-hosted service MUST be refused
  them.
- **FR-003**: The operator MUST render, per declared topic, a topic named `<project>.<name>` with
  the declared partitions. For every service that is not web-hosted and names no broker of its
  own, whether or not it declares a topic, it MUST render a credential and ACLs granting read and
  write on the project's topics and the service's qualified consumer groups, and nothing wider. In
  an installation with no broker it MUST render none of this for a service that declares no topic.
- **FR-004**: The operator MUST inject the bootstrap address and TLS settings as `ANKKA_KAFKA_*`
  variables into every service FR-003 gives a credential, which `PlatformVariables` already gives to both programs of a process-hosted service;
  no second list of names is kept. The runtime MUST map a component's declared topic name to the
  project-qualified name.
- **FR-005**: A descriptor that sets any `ANKKA_KAFKA_*` variable MUST be marked supplied and get
  no provisioning, and one that also declares topics MUST be refused.
- **FR-006**: The resource's status MUST report a broker phase in the shape of the database phase,
  treating the broker operator's transient states as waiting.
- **FR-007**: The platform MUST never delete a topic, a credential or an ACL; a re-applied service
  MUST report its topic recovered.

- **FR-011**: The installation's broker MUST create no topic on use. A component that reads or
  publishes to a topic no descriptor of its project declares MUST wait and try again, the service's
  log MUST name the topic, and the service MUST stay ready; what waited MUST flow once the topic is
  declared.

**Platform**

- **FR-008**: A kustomization component MUST provide one broker per installation with a zero-trust
  policy admitting every workload namespace, enabled in the local overlay and placeholdered in the
  cloud overlay.
- **FR-009**: The broker's credential for a service MUST be the service's own certificate, which
  in an installation with a broker carries a common name naming its project and service. No other
  credential is generated, stored or copied, and the broker MUST be given the service authority's
  certificate and never its key.

**Documentation**

- **FR-010**: The topics guide and the configuration reference MUST describe declaration,
  qualification, the escape hatch and the retention rule, and the limitations page MUST drop "the
  platform provides no broker".

### Key Entities

- **Topic declaration**: name and partitions on a descriptor; rendered as a project-qualified
  topic.
- **Broker credential**: the service's identity on the broker, certificate-backed.
- **Broker status**: phase, topic names, recovered flag and detail on the resource's status.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A process-hosted service with a topic becomes ready and publishes on a real cluster,
  in a suite that runs with the others.
- **SC-002**: A service in another project is refused by the broker for a topic it did not
  declare, measured by the broker's own authorization failure, not by a platform check.
- **SC-003**: A service declaring a topic needs no broker configuration in its descriptor and
  publishes on first deploy.
- **SC-004**: Deleting and re-applying a service loses no message on its topic.

## Assumptions

- Strimzi is the broker: `KafkaTopic` and `KafkaUser` as resources are what make the operator's
  rendering a pure function, where a plain StatefulSet would need the operator to speak the admin
  protocol itself. The plan measures what installing it costs the k3s suites' startup.
- The installation's service authority can be trusted by the broker for TLS client
  authentication, as the per-project database authority is trusted by Postgres.
- 024-replayable-topics lands first or together, since the ACLs grant the qualified group ids.
- Kafka remains the only broker; the two-method broker interface is unchanged.
- `PlatformVariables` stays the one place a platform variable's name is said; a broker variable
  this feature adds is added there.

## Dependencies

- Gates every domain stage going to production and no stage's build: a Kafka the installation
  supplies is enough to develop against.
- The proof of FR-001 needs no provisioning and can be merged on its own.
- 024-replayable-topics: qualified consumer group ids are what the ACLs grant.
- 023-secret-store, which is merged, delivered the one declaration of platform variables this
  feature reads; 026-telemetry-export adds its names to the same one.
- 025-polyglot-service-client: none.

## Open Questions

- Whether the installation's broker is also where the platform's own future needs go, and so
  whether the `platform` project should hold topics.
