# Feature Specification: Pipelines Are Services — What ankka Gains So That ankka-flow Can Retire

**Feature Branch**: `037-pipelines`

**Created**: 2026-10-07

**Status**: Draft

**Input**: User description: "Bring ankka-flow into ankka. A streaming pipeline — a stage in any
language that reads one topic and writes another, wired over topics, for ingestion from an outside
system into a project, for streams between services and between projects — is another tool in the
ankka toolbox rather than a second platform with its own operator, CLI, docs and SDKs. An ankka
consumer that reads a topic and publishes to another is already that stage; ankka-flow wraps a set
of them into a deployable unit, and ankka already has that unit, the service and the project. So
rather than carry ankka-flow's machinery across, give ankka the few things it lacks that ankka-flow
had, as ordinary ankka features, and retire ankka-flow."

## Context

ankka-flow was built beside ankka as a separate platform: streamlets in any language, each a
process with a platform sidecar owning Kafka, wired by a file over topics into a pipeline, with its
own operator, resource, CLI, MCP server, documentation site and two SDKs. Joining it to ankka — a
pipeline reading what a service publishes — turned out to need ankka-flow to learn a copy of what
ankka already has: the project, the broker and its credentials, the topic declarations. The
questions that integration raised were the cost of the separation made visible.

Set side by side, a streamlet and an ankka consumer over a topic source are the same machine. A
process in any of ankka's four languages reads one topic under its own group, handles each
message, publishes to another topic, and the broker's offset is committed only once every message
it published has been accepted. A message that cannot be handled is redelivered until it can be.
Ordering is the broker's, by key. The consumer lives in a project, on the installation's broker,
with its certificate, its topology, its logs, the console and the MCP server. A pipeline as a
deployable unit is a service with several consumers, or several services.

What ankka-flow has that ankka's consumer does not is a short list, and each item is worth more as
an ankka feature than as a flow feature, because it then serves services too:

- **A contract on a topic, checked when the sides are declared.** ankka-flow refuses two
  streamlets that disagree on a topic's contract before the pipeline runs. ankka has no contracts:
  a declared topic is a name and a partition count, and the wire's type header is the constant
  `message` for every ordinary message.
- **Topic settings.** A declared topic has partitions and nothing else; graph deltas need a
  compacted topic, and ankka's own graph page still says the ankka-flow pipeline creates one.
- **A topic on another broker.** A service has one broker, the installation's or one it brings;
  ankka-flow mixes brokers per topic. Ingestion from an outside broker into a project is the gap.
- **Partitions in parallel.** A streamlet reads the partitions it holds in parallel and is handed
  batches; ankka handles messages one at a time per subscription.
- **The graph merge sink**, ankka-flow's one built-in stage, which fills a Neo4j store from a
  delta topic. ankka's graph documentation ends by pointing at ankka-flow for it.
- **The weight of a stage.** A streamlet pod is a process and a sidecar; a service's process
  container is fixed at 100m and 128Mi with no way to ask for more.
- **Lag and reset.** ankka-flow shows how far a pipeline is behind; ankka has the metric but not
  the line in a service's status. Reset exists in ankka as a topic source's version.

What is deliberately not carried: a second workload kind, a second sidecar, a second protocol, a
second pair of SDKs, batches handed to a handler (an aggregation across messages belongs in an
entity or a view, which is durable where a batch is not), a wiring file (a topic's name is where
two components meet, and a project's declared topics are the wiring), managed topics created and
deleted with a deployment (the platform keeps what it made, by design), and ankka-flow's
vocabulary ("blueprint" is a reasoning pattern here; "pipeline" and "sink" already mean what
ankka-flow meant by them).

The retirement itself — archiving the ankka-flow repository, its final SDK versions pointing here,
its formula and plugin withdrawn, its site redirected — is ankka-flow's own change and is not
specified here. This specification says what ankka must have before that can happen.

## Clarifications

### Session 2026-10-07

- Q: What is a contract — a name alone, or a name with a schema the platform holds? → A: A name
  with a schema. The project holds the schema with the declaration; a component states the
  contract by name and the schema it was built against, and the platform refuses a side whose
  schema is not the declared one. A member fetches a topic's schema from the project to build
  against it. Messages are not checked against the schema at runtime.
- Q: How is the graph merge sink delivered — a service image the platform publishes, or a
  component kind a developer registers in a service of their own? → A: Both, layered: the sink is
  a component in a module of its own, which a developer may register in a Scala service, and the
  platform publishes a service image built from that component, which a member deploys into a
  project with the topic, the store's address, a project secret for its credential and a version.
  A sink inside a process-hosted service's own process is not provided.
  *Revised during implementation*: the platform holds the sink and its rules behind a store
  interface, with an in-memory reference store; a store over a database (Neo4j) and the ready
  image are not the platform's but ankka-contrib's, a repository of integrations released against
  a published ankka version. Reasoning: the delta contract is ankka's, so the reader side must be
  proven in the same build as the writer; Neo4j is one store, with a driver and an image on the
  release train, and belongs with other integrations rather than in the platform.
- Q: How does a component state the schema it was built against? → A: It names the schema
  document kept in the project, fetched from the project's declaration; the SDK fingerprints that
  document after normalising it, and discovery carries the contract's name and the fingerprint.
  No SDK derives a schema from a type.
- Q: What credential does a declared broker's project secret hold? → A: One of two shapes, named
  by the declaration: a certificate (a client certificate, its key and the authority), or SASL
  (PLAIN or SCRAM) over TLS (a user, a password and the authority). A declaration whose secret
  lacks what its shape needs is refused. Arbitrary client properties are not passed through.
- Q: Where is a contract checked? → A: At the service's start. The platform hands a service its
  project's topic declarations (name, contract, schema fingerprint); the runtime compares each
  component's statement when the component is registered or discovered, refuses a disagreeing
  one there, and the service reports it in its status and never becomes Ready. A declaration
  changed after a service started is checked at its next start; the project's topic listing names
  the services not yet checked against it.
- Q: Is reading partitions in parallel the default, or asked for? → A: Asked for. A topic
  source that says nothing reads one message at a time as today; one that asks for it reads the
  partitions its instance holds at once.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A declared topic carries a contract, and every side is checked (Priority: P1)

A member declares a topic's contract with the topic: a name and the schema of what the topic
carries, which the project holds. A member fetches the schema from the project to build against
it. A component that reads the topic or publishes to it states the contract by name and the
schema it was built against, and a service whose component states a different name, a different
schema, or none where the topic has a contract, is refused before it reads or publishes a
message, naming the topic, what the project declares and what the component states. A topic
declared without a contract is checked against nothing, so every project as it is today is
unaffected. The contract's name travels on the wire as each message's type, so a reader outside
ankka can tell what a topic carries. No message is checked against the schema as it flows.

**Why this priority**: It is the one idea ankka-flow had that ankka lacks, and the reason two
sides of a topic can disagree silently today.

**Independent Test**: A project declares `orders` with the contract `order.v1` and its schema; a
service whose consumer publishes `order.v1`, built against that schema, becomes Ready and its
messages carry that type; a service whose consumer states `order.v2`, or `order.v1` built against
another schema, is refused with both sides named in its status; the same service on a project
whose `orders` has no contract becomes Ready.

**Acceptance Scenarios**:

- added `features/topics/contracts.feature`: a topic is declared with a contract and its schema
- added `features/topics/contracts.feature`: a member fetches a topic's schema from the project
- added `features/topics/contracts.feature`: a component that states the declared contract is accepted
- added `features/topics/contracts.feature`: a component that states another contract is refused, naming both
- added `features/topics/contracts.feature`: a component built against another schema is refused, naming both
- added `features/topics/contracts.feature`: a component that states no contract on a topic that has one is refused
- added `features/topics/contracts.feature`: a topic without a contract checks nothing
- added `features/topics/contracts.feature`: a published message carries the contract as its type
- added `features/topics/contracts.feature`: a contract is shown with the topic

---

### User Story 2 - A declared topic has settings (Priority: P1)

A member declares a topic compacted, with its partitions, and the platform makes it so on the
installation's broker, whether the topic is new or already made. A delta topic is declared
compacted by the project that owns it, and nothing else creates topics.

**Why this priority**: Without it a graph's delta topic cannot exist in an installation that
makes its own topics, and the documentation's answer is ankka-flow.

**Independent Test**: A member declares `cart-deltas` compacted with 3 partitions; the broker holds
it compacted; declaring an existing topic compacted changes it; `topics list` shows it.

**Acceptance Scenarios**:

- added `features/broker/compaction.feature`: a topic declared compacted is made compacted
- added `features/broker/compaction.feature`: a topic already made is compacted when its declaration says so
- added `features/broker/compaction.feature`: a compacted topic keeps the last message under each key
- added `features/broker/compaction.feature`: the topics of a project show which are compacted

---

### User Story 3 - The graph merge sink is ankka's (Priority: P1)

A member fills a graph store from a delta topic with something ankka provides: the sink reads the
topic from its start, applies each delta only when its version is newer than the element's in the
store, refuses a delta that breaks a delta's rules and says so, and is rebuilt from the topic's
start at a higher version. It is a component in a module of its own, which a developer registers
in a Scala service with the store of their choice: the in-memory reference store the module ships,
a store over a database from ankka-contrib (Neo4j, with a ready image a member deploys into a
project like any service), or one of their own against the store interface. The graph
documentation tells the whole story, from publishing deltas to a filled store, and no page points
at ankka-flow.

**Why this priority**: Graph deltas are an ankka feature whose second half lives in ankka-flow;
retiring ankka-flow without this breaks ankka's graph story.

**Independent Test**: The sink, registered in a service with the in-memory store, fills the
store with every element of the platform's fixtures; a refused delta is named in the sink's log
and status; registered again at a higher version, the sink refills the emptied store from the
topic; ankka-contrib's Neo4j store, under the same suite, holds the same elements.

**Acceptance Scenarios**:

- added `features/graph-deltas/sink.feature`: the sink fills a store from a delta topic
- added `features/graph-deltas/sink.feature`: the sink applies a delta only when its version is newer
- added `features/graph-deltas/sink.feature`: the sink refuses a delta that breaks the rules and says which
- added `features/graph-deltas/sink.feature`: the sink at a higher version builds the store again from the topic
- added `features/graph-deltas/sink.feature`: the sink's fixtures are ankka's own
- added `features/graph-deltas/sink.feature`: a developer registers the sink in a service of their own
- added `features/graph-deltas/sink.feature`: a store over a database applies deltas as the reference store does
- changed `features/graph-deltas/documentation.feature`: the documentation describes publishing deltas and the rules of a delta
- added `features/graph-deltas/documentation.feature`: the documentation tells the graph story to the end without ankka-flow

---

### User Story 4 - A topic on another broker (Priority: P2)

A member declares a broker on a project — its address and the project secret holding its
credential — and a component names that broker for a topic it reads or publishes to. The service
keeps the installation's broker for every other topic. Messages from an outside system's Kafka
flow into a project's topics through one consumer, with no second broker configured on the whole
service.

**Why this priority**: Ingestion from outside is the first pipeline anyone builds, and today it
needs a service that lives wholly on the outside broker.

**Independent Test**: A project declares the broker `legacy`; a consumer reads `events` from
`legacy` and publishes to the project's `orders`; messages produced on the outside Kafka arrive on
`orders`; the consumer's group on `legacy` is the project's by name.

**Acceptance Scenarios**:

- added `features/topics/brokers.feature`: a broker is declared on a project with its credential in a project secret
- added `features/topics/brokers.feature`: a declaration whose secret lacks what its shape needs is refused
- added `features/topics/brokers.feature`: a topic source names a declared broker and reads from it
- added `features/topics/brokers.feature`: a consumer reads from a declared broker and publishes to the installation's
- added `features/topics/brokers.feature`: a component naming a broker the project has not declared is refused
- added `features/topics/brokers.feature`: a declared broker's credential never reaches the process

---

### User Story 5 - A topic source reads its partitions in parallel (Priority: P2)

A consumer or view over a topic that asks for it handles the partitions its instance holds at
once, each partition in order, and commits a partition's offset only once the message's effects
are accepted. Order within a key is kept; throughput grows with partitions, as it does for a
streamlet. A topic source that does not ask reads one message at a time, as today.

**Why this priority**: It is the one place a streamlet outperforms a consumer, and the difference
matters for ingestion at volume.

**Independent Test**: A consumer over a 4-partition topic with a slow handler handles four
messages at once; messages under one key arrive in order; a failed message holds its partition
and no other; a crash mid-batch redelivers only what was not committed.

**Acceptance Scenarios**:

- added `features/topics/parallelism.feature`: partitions held by one instance are handled at once when asked for
- added `features/topics/parallelism.feature`: a topic source that does not ask reads one message at a time
- added `features/topics/parallelism.feature`: messages under one key are handled in order
- added `features/topics/parallelism.feature`: a message that cannot be handled holds its partition and no other
- added `features/topics/parallelism.feature`: a partition's offset is committed only after its message's publications are accepted

---

### User Story 6 - A process is sized by its descriptor, and a stage needs no database (Priority: P3)

A descriptor says what the process container gets, so a stage that does real work is not held at
the platform's minimum, and a service with no entity, view or workflow — a consumer alone — is
deployed without a database being provisioned for it.

**Why this priority**: A pipeline stage is cheap in ankka-flow; the same stage in ankka should
not cost a database it never opens.

**Independent Test**: A process-hosted descriptor asking for 1 CPU and 1 GiB renders a process
container with those; a consumer-only service deployed without a database becomes Ready and reads
its topic.

**Acceptance Scenarios**:

- added `features/deploying/process-resources.feature`: a descriptor sizes the process container
- added `features/deploying/process-resources.feature`: a descriptor that says nothing keeps the platform's size
- added `features/deploying/process-resources.feature`: a service with only consumers runs without a database

---

### User Story 7 - A service says how far behind each topic source is (Priority: P3)

`services get`, the console and the MCP server show each topic source of a service with its
group, its start position, its version and how far behind it is, so nobody needs the broker to
learn whether a pipeline keeps up.

**Acceptance Scenarios**:

- added `features/topics/status.feature`: a service's status lists each topic source with how far behind it is
- added `features/topics/status.feature`: the console and the server show the same

### Edge Cases

- **A contract declared on a topic services already use without one.** Each service is checked
  at its next start, not stopped where it runs; the project's topic listing names services that
  have not been checked against the new contract.
- **A contract changed on a topic.** A new name; every side stating the old one is refused at
  its next start. Compatibility between names is out of scope.
- **A topic declared compacted whose partitions are reduced.** Refused, as today: never fewer
  partitions.
- **A declared broker that cannot be reached.** The topic source retries with backoff, and every
  topic source on that broker reports the broker and the connection error as the change it is
  failing on, in the service's status.
- **A broker declared or removed.** Every service of the project is given or relieved of its
  credential, so each rolls once, as a changed descriptor rolls it.
- **A project secret a declared broker names.** It cannot be removed while a broker names it; the
  broker is removed first.
- **A sink whose store is unreachable.** Reads nothing, commits nothing, retries; its status says
  so.
- **A delta the sink refuses.** The change fails: the delta is named in the log and the status
  with its key and the rule it broke, and it is handed to the sink again, as any change a consumer
  cannot handle is, so the partition it is on waits and the others read on. ankka's SDKs refuse to
  describe such an element, so only a writer outside ankka can produce one. Nothing is skipped.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: A declared topic MAY carry a contract, a name with a schema, set and shown with the
  topic; a member MUST be able to fetch the schema from the project.
- **FR-002**: A component MUST be able to state the contract of each topic it reads or publishes
  to, by name and by the schema document it was built against, kept in the project and
  fingerprinted by the SDK after normalising it, in every SDK and through the sidecar's
  discovery; the fingerprint of one document MUST be the same in every SDK.
- **FR-003**: A service with a component whose stated contract differs from the project's in name
  or schema, or states none where the project declares one, MUST be refused at the service's
  start, before the component reads or publishes, with both sides named in the service's status;
  the platform MUST hand a service its project's topic declarations for that check.
- **FR-004**: A message published to a topic with a contract MUST carry the contract's name as
  its type; no message is checked against the schema as it flows.
- **FR-005**: A declared topic MAY be compacted; the platform MUST make it so, for a new topic and
  for one already made.
- **FR-006**: ankka MUST provide the graph merge sink as a component in a module of its own,
  over a store interface with an in-memory reference store: a store filled from a delta topic
  under the delta rules, rebuilt at a higher version, with its fixtures held here. A store over a
  database, and a service image of the sink into it, are ankka-contrib's.
- **FR-007**: A project MAY declare a broker by name, with its address, the credential's shape
  (a certificate, or SASL PLAIN or SCRAM over TLS) and the project secret holding it, refused when
  the secret lacks what the shape needs; a component MAY name a declared broker for a topic; the
  credential MUST reach only the platform's container.
- **FR-008**: A topic source that asks for it MUST handle the partitions an instance holds in
  parallel and each partition in order, committing a partition only after its message's
  publications are accepted; one that does not ask MUST read as today.
- **FR-009**: A descriptor MUST be able to size the process container; a service with no stateful
  component MUST be deployable without a provisioned database.
- **FR-010**: A service's status MUST list each topic source with its lag, shown by the CLI, the
  console and the MCP server.
- **FR-011**: No page of the documentation MAY depend on ankka-flow for any part of the graph,
  topic or pipeline story.
- **FR-012**: Every existing suite MUST pass; a project with no contracts, no compacted topics and
  no declared brokers MUST behave as it does today.

### Key Entities

- **contract**: a name and the schema of what a topic carries, declared on the topic and held by
  the project, stated by each side with the schema it was built against, its name carried as a
  message's type.
- **declared broker**: a broker a project names, with an address, a credential shape (a
  certificate, or SASL over TLS) and a project secret, which a component may name for a topic.
- **sink**: as already defined: the part of a pipeline that applies deltas to a store; now a
  component ankka provides, with a store interface; a store over a database and a service image
  are ankka-contrib's.
- **topic settings**: a declared topic's partitions and whether it is compacted.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Two services disagreeing on a declared topic's contract cannot both be Ready.
- **SC-002**: The shopping cart's graph is filled and rebuilt with nothing from ankka-flow
  deployed.
- **SC-003**: Messages from a Kafka outside the installation reach a project's topic through one
  consumer, with no change to the rest of the service.
- **SC-004**: A consumer over N partitions with a handler of fixed cost handles N messages in the
  time it handled one.
- **SC-005**: `grep -ri ankka-flow docs/` finds nothing but the contributing page's note on the
  shared docs tool.
- **SC-006**: Every existing feature and suite passes unchanged.

## Assumptions

- **A schema is a JSON Schema document**, the format ankka's messages already have; the
  normalisation before fingerprinting is a planning decision, and the fixtures prove every SDK
  fingerprints one document the same way.
- **The stage-without-a-database case may already work**; if `provisionDatabase: false` with no
  supplied database already runs a consumer-only service, its scenario is the proof and no change
  is needed.
- **ankka-flow's retirement follows this feature's release**, in its own repository: a final
  note, archive, and its SDKs' last versions pointing here.

## Out of Scope

- A pipeline resource, a wiring file, or any grouping beyond the project.
- Batches handed to a handler; windows; state in the process.
- Checking messages against a schema as they flow; Avro or Protobuf schemas; compatibility
  between contract names or schema versions; a registry outside the project.
- Topic retention and other settings beyond compaction.
- Publishing from one message to several brokers at once.
