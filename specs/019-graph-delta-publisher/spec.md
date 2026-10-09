# Feature Specification: Graph Delta Publisher — a Service Publishes Its Entities as a Graph

**Feature Branch**: `019-graph-delta-publisher`

**Created**: 2026-10-01

**Status**: Draft

**Input**: User description: "Spec the ankka delta publisher feature in the ankka repo: publishing
graph deltas directly from an ankka service, the other half of ankka-flow's graph story. It needs a
consumer that can publish several messages per event, and it takes versions from the entity's
sequence number."

## Context

[ankka-flow](https://github.com/thinkmorestupidless/ankka-flow) keeps a graph database in step with
a topic of **graph deltas**. A delta is one element's whole state — a node, an edge, or a tombstone
for either — with a version, under the contract `ankka.graph-delta.v1`. Its built-in merge sink
reads a delta topic and applies each delta if its version is newer than what the graph holds. Since
ankka-flow 0.3.0 a delta's record key must be its **element key**, `node:<id>` or `edge:<id>`; a
delta topic is compacted by default; and a graph can be rebuilt from its delta topic alone.

Today the only way to fill a delta topic from an ankka service is in two hops. A consumer in the
service publishes a message about each change to an ordinary topic, and a **mapper** streamlet in an
ankka-flow pipeline — a second program, in a second image, with its own deployment — reads that
topic and turns each message into deltas. The mapper exists only because the service cannot write
deltas itself:

- **A consumer publishes at most one message per change.** One change usually touches several
  elements. A checkout is a cart node, a checkout node and the edge between them: three deltas.
- **A published message's record key is always the source entity's id.** A delta's key must be its
  element's, and the three deltas of one checkout have three different keys.
- **Nothing builds a delta.** The author would write the JSON, the key and the version by hand, and
  the merge sink refuses the batch when any of them is wrong.

Meanwhile the service already has what a delta needs and the mapper has to reconstruct. A version
must rise with the history of the entity that owns the element, and every change a consumer handles
arrives with exactly that: the entity's sequence number. A mapper reading an ordinary topic never
sees it and has to invent a substitute, such as an event time.

This feature removes the second hop. A consumer can publish **several messages for one change, each
under its own key**, and a **graph delta builder** turns "this node, this edge, this one is gone"
into correctly keyed, correctly versioned delta records. A service then publishes its entities as a
graph with one consumer, and the pipeline that fills the graph database is the built-in sink alone:
no mapper, no second image.

ankka does not come to depend on ankka-flow. The contract is ankka-flow's; ankka writes records that
honour it, and a fixture shared between the two repositories proves they agree.

## Clarifications

### Session 2026-10-01

- Q: Is this a new kind of component? → A: No. It is the consumer, which gains the ability to
  publish several messages for one change, and a builder for the messages that are deltas.
- Q: Does ankka create the delta topic, or check that it is compacted? → A: No. ankka creates and
  alters no topics, as today. The pipeline that reads the topic declares it; ankka-flow creates a
  managed delta topic compacted and reports one that exists and is not.
- Q: Does a delta published twice do harm? → A: No. A redelivered change publishes the same deltas
  at the same versions, and the sink passes over a delta that is not newer than what it holds.
  Publishing a graph is the first consumer reaction that is safe to repeat without the author doing
  anything.
- Q: Which SDKs gain the several-message result and the delta builder? → A: All four that have
  consumers: Scala, Python, TypeScript and Rust, the last for services hosted as WebAssembly
  modules.
- Q: What is a graph-publishing consumer handed for an event sourced entity? → A: The event, as
  every consumer is today. The entity's state as of that event, as a new kind of source, is a later
  feature; an author whose events do not carry an element's whole state reads the entity's current
  state through the component client.

### Found in planning, 2026-10-01

- **A key value entity's deletion reaches nobody today.** The entity's row is removed outright, so
  no view and no consumer is told, and an entity created again under the same id starts its
  revisions from one. FR-019 and FR-020 cannot hold over that. The plan makes a key value deletion
  a recorded state, as an event sourced deletion already is a recorded event: the row stays,
  marked deleted and holding the empty state, at the next revision. A view over a key value entity
  then has its row removed when the entity is deleted, which it should always have had.
- **Only an in-process Scala consumer is handed zero for a key value source.** Consumers behind a
  sidecar and in WebAssembly modules already receive the revision.
- **A newer SDK on an older runtime would lose messages silently**: the older runtime reads a reply
  it does not know as "ignore". The plan has the runtime say on each request what it accepts, and
  an SDK fail a change rather than send several messages to a runtime that has not said so.
- **The contract reserves no labels**, only the property names `id`, `_version` and `_deleted`.

### Session 2026-10-05 (glossary)

- Q: The topology terms refuse "graph" and "edge"; do graph deltas reclaim them? → A: No. The features say **relationship** and **store**; the "edge:" prefix of an element key stays quoted data.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A consumer publishes several messages for one change (Priority: P1)

A developer's consumer handles a change that matters to more than one reader, or to more than one
thing. Instead of choosing one message to publish, the handler returns several. Each goes to the
consumer's topic, in the order given, and each may name its own record key; a message that names
none is keyed by the source entity's id, as every published message is today. The change counts as
handled only when every one of its messages has been accepted by the broker.

**Why this priority**: It is the missing platform capability, and it is useful with no graph in
sight: a consumer that fans one change out into one message per line item needs exactly this.
Everything else in the feature is built on it.

**Independent Test**: A consumer that returns three messages with three keys for each event, run
against the in-memory broker and against Kafka. Read the topic back: three records per event, in
order, under the keys given. Make the second publication fail: the change is redelivered, and
afterwards the topic holds all three.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/graph-deltas/several-messages.feature`: a change's messages are published in the order the handler gave them
- added `features/graph-deltas/several-messages.feature`: a message is published under the key it names, else under its entity's id
- added `features/graph-deltas/several-messages.feature`: a change whose messages the broker does not all accept is delivered again
- added `features/graph-deltas/several-messages.feature`: a change for which the handler publishes no message is handled
- added `features/graph-deltas/several-messages.feature`: a consumer that may publish several messages and has no topic is refused
- added `features/graph-deltas/several-messages.feature`: a consumer that publishes one message for each change publishes what it always has

---

### User Story 2 - A service publishes its entities as graph deltas (Priority: P1)

A developer wants the service's entities to appear in a graph: a node for each cart, a node for
each checkout, an edge from one to the other. They write one consumer over the entity. In its
handler they say which elements a change leaves in which state — this node with these labels and
properties, this edge of this type between these two ids, this element gone — and return them. They
write no JSON, no record key and no version. Each element is published as a delta the ankka-flow
merge sink accepts: keyed by its element key, versioned by the change's sequence number.

**Why this priority**: It is the point of the feature. Without the builder, story 1 lets an author
publish deltas by hand, and a hand-built delta with the wrong key stalls the pipeline that reads it.

**Independent Test**: A consumer over the sample shopping cart that publishes a cart node, a
checkout node and the edge between them. Read the topic back and compare every record's key and
value with the shared fixture, and read every record with the ankka-flow sink's own reader: all are
accepted.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/graph-deltas/publishing-deltas.feature`: a node is published as one node delta under its element key
- added `features/graph-deltas/publishing-deltas.feature`: a relationship is published as one relationship delta under its element key
- added `features/graph-deltas/versions.feature`: a delta's version is the sequence number of the change it was published for
- added `features/graph-deltas/publishing-deltas.feature`: a node and a relationship with the same element id are published under different keys
- added `features/graph-deltas/publishing-deltas.feature`: a tombstone is published under the element key of the element it marks
- added `features/graph-deltas/publishing-deltas.feature`: an element the sink would refuse cannot be described
- added `features/graph-deltas/publishing-deltas.feature`: an element key is the one the sink computes for the same delta

---

### User Story 3 - Publishing is safe to repeat, and versions never go backwards (Priority: P2)

A service restarts in the middle of a burst, or its consumer is replayed from the start of the
entity's history. Every change it handles again publishes the same deltas at the same versions, so
the sink passes over them and the graph does not move. Whatever the entity has been through —
updates, deletion, being created again under the same id — the versions of the elements it owns
only rise.

**Why this priority**: It is what makes the result trustworthy. A graph that flickers backwards when
a consumer restarts, or keeps a deleted entity's node because a tombstone carried a version lower
than the delta before it, is worse than no graph.

**Independent Test**: Publish a history for one entity, then replay the consumer from the start
against the same topic and a sink that has already applied it: the sink counts every repeated delta
as stale and writes nothing. Delete the entity and create it again: read the versions published and
confirm they rise throughout.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/graph-deltas/versions.feature`: a change delivered again publishes the same deltas
- added `features/graph-deltas/versions.feature`: a delta's version is the sequence number of the change it was published for
- added `features/graph-deltas/versions.feature`: the tombstone published for a deleted entity outranks every earlier delta
- added `features/graph-deltas/versions.feature`: an entity created again under the same entity id comes back in the store
- added `features/graph-deltas/versions.feature`: an element published for a message from a topic must state its version
- added `features/graph-deltas/versions.feature`: a version the handler states is used in place of the sequence number

---

### User Story 4 - A graph in the database with no mapper (Priority: P2)

An operator runs the sample service beside an ankka-flow pipeline whose only streamlet is the
built-in merge sink, reading the topic the service publishes deltas to. Carts are created, changed,
checked out and deleted through the service's own endpoints. The graph database holds the matching
graph. They empty the database, reset the sink, and the graph comes back from the topic.

**Why this priority**: It is the outcome the two projects exist to deliver together, and the only
proof that what ankka writes is what ankka-flow reads, on a real broker with a real sink.

**Independent Test**: On a local cluster with both installed: deploy the sample and the sink-only
pipeline, drive the service, query the graph. Then follow ankka-flow's guide to rebuilding a graph
from its delta topic and compare.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/graph-deltas/store.feature`: carts created, changed and checked out appear in the store
- added `features/graph-deltas/store.feature`: a deleted cart's elements are marked deleted in the store

*Superseded:* a pipeline no longer declares the topic as its own; the project declares it compacted, as `features/graph-deltas/store.feature` says.

- added `features/graph-deltas/store.feature`: a consumer restarted while carts change leaves the store as an uninterrupted run would
- added `features/graph-deltas/store.feature`: a store is built again from the topic alone
- added `features/graph-deltas/store.feature`: the same consumer written in another language fills the store the same way

---

### User Story 5 - It is tested without a broker (Priority: P3)

A developer tests the graph their consumer publishes the way they test any consumer: hand it a
change, look at what it produced. What it produced is a list of elements they can assert on by kind,
id, version, labels and properties, not bytes they have to decode.

**Why this priority**: A mapping from a domain to a graph is exactly the code that goes subtly
wrong, and a test that needs Kafka and Neo4j will not be written.

**Independent Test**: A unit test of the sample's consumer that hands it one event at a stated
sequence number and asserts on the elements returned, with no broker and no runtime started; the
same test in each of the four languages.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/graph-deltas/testing.feature`: a test reads back the elements a consumer published for a change
- added `features/graph-deltas/testing.feature`: a test of a whole service reads each published delta with its key
- added `features/graph-deltas/testing.feature`: a test reads back every message a consumer published and the key each named

---

### User Story 6 - Publishing a graph is documented (Priority: P3)

A developer who has a service and wants a graph finds one guide that takes them from an entity to
elements in a graph database: how to write the consumer, what the rules of a delta ask of them, what
the topic must be and who creates it, and where ankka's part ends and ankka-flow's begins.

**Why this priority**: The rules a writer of deltas keeps are not obvious and are not enforceable by
a builder: a delta is state rather than change, and an element has one writer. An author who has
not read them publishes a graph that looks right for a week.

**Independent Test**: A reader follows the guide with the sample and reaches a graph. The
documentation build passes with the new pages in the navigation and in the skills that carry them.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/graph-deltas/documentation.feature`: the documentation describes publishing several messages for one change
- added `features/graph-deltas/documentation.feature`: the documentation describes publishing deltas and the rules of a delta
- added `features/graph-deltas/documentation.feature`: the documentation says what to do when events do not carry an element's whole state
- added `features/graph-deltas/documentation.feature`: the documentation's reference describes several messages and describing deltas

### Edge Cases

- **One change, the same key twice.** Messages are published in the order returned, so the later one
  is the later record under that key. For two deltas of one element at one version, the sink keeps
  the first and passes over the second; the builder refuses the same element twice in one result.
- **A failure part-way through.** Some of a change's messages are in the topic and some are not.
  The change is redelivered and all are published again. A reader of plain messages must tolerate
  the repeats, as it must today; a reader of deltas does by construction.
- **An element that another entity owns.** A cart's change must not publish the `product` node its
  items point at: two entities' sequence numbers are not comparable, and whichever is higher would
  win for ever. The cart publishes the edge; the sink creates a placeholder for an endpoint nobody
  has described; the product's own entity publishes the product.
- **An element that leaves the state.** When an item is removed from a cart, nothing says "remove
  the edge" unless the author does: the handler for that change publishes a tombstone for it.
- **Events that do not carry the whole state.** A delta built from a thin event describes only part
  of its element, and the sink replaces the element's properties with what it is given. The author
  must build the element from enough: an event that carries it, or the entity's current state read
  through the component client, which may be ahead of the event being handled and converges when
  the later events are handled.
- **A service built with an older SDK.** It never returns several messages and never names a key.
  It runs unchanged; the only thing it can observe is a key value consumer's sequence number, now
  the revision rather than zero.
- **The four builders disagreeing.** A number one language carries and another cannot — an integer
  beyond 64 bits, a whole-number float — must be refused or written the same way by all four. The
  shared fixtures include those rows.
- **A version of zero or less.** Refused by the builder. The contract allows zero, but in ankka zero
  is what a change with no sequence number presents, and a delta published at zero would lose to
  every other delta for its element.
- **A source with no sequence number.** A consumer over a topic must state versions itself.
- **A very large result.** A change whose messages together exceed what one handler reply may carry
  fails as a whole, naming the consumer and the size, rather than being published in part.
- **The topic does not exist, or is not compacted.** ankka does not look. If the broker creates
  topics on first use, the delta topic comes into being uncompacted with the broker's defaults, and
  the pipeline that declares it then reports that. The documentation says to deploy the pipeline
  first.
- **Order across elements.** A change's deltas have different keys and land on different
  partitions, so an edge can be read before its endpoints. The sink already allows for that.
- **More than one consumer publishing the same element.** Nothing detects it. The rule that an
  element has one writer is stated, not enforced.
- **A consumer that publishes deltas and something else.** A consumer has one topic and one message
  type. A consumer that publishes deltas publishes only deltas.
- **Removing an element's record from the topic.** The publisher writes tombstones, which stay in a
  compacted topic. It never writes a record with no value.

## Requirements *(mandatory)*

### Functional Requirements

**Several messages for one change**

- **FR-001**: A consumer's handler MUST be able to return several messages for one change, for
  every source a consumer can read: an event sourced entity's events, a key value entity's state
  changes, and a topic. The deletion handler MUST be able to do the same.
- **FR-002**: The messages of one change MUST be published to the consumer's topic in the order
  returned.
- **FR-003**: Each message MUST be able to name its own record key. A message that names none MUST
  be keyed by the source entity's id, as a published message is today.
- **FR-004**: A message's key and its subject MUST be separate. Naming a key MUST NOT change the
  message's `ce-subject`, which stays the source entity's id unless the author sets it.
- **FR-005**: A change MUST be recorded as handled only after the broker has accepted every one of
  its messages. If any is refused, the change MUST be redelivered, and all of its messages
  published again.
- **FR-006**: An empty list of messages MUST behave as `done`: nothing published, the change
  recorded as handled.
- **FR-007**: A consumer that can return several messages MUST be subject to the checks a producing
  consumer has today: a topic and an output encoding declared together, and a broker configured,
  or it is refused at startup.
- **FR-008**: Every message MUST be framed as a published message is today: the plain encoded body
  as the record's value and CloudEvents attributes as headers, with a new `ce-id` for each.
- **FR-009**: A consumer that produces one message per change MUST behave exactly as before, in
  what it publishes, how it is keyed and when its change counts as handled. No existing consumer
  needs to change.
- **FR-010**: A result too large to be carried from the handler to the runtime MUST fail the change
  as a whole with an error naming the consumer, never publish a part of it.

**Graph deltas**

- **FR-011**: The SDK MUST provide a builder for the elements of `ankka.graph-delta.v1`: a node (id,
  labels, properties), an edge (id, type, the ids it runs from and to, properties), a tombstone for
  a node and a tombstone for an edge.
- **FR-012**: A consumer MUST be able to declare that it publishes graph deltas, and its handlers
  then return elements built by the builder. Such a consumer MUST NOT be able to publish anything
  that is not a delta.
- **FR-013**: Each element MUST be published as one record whose key is the element key — `node:<id>`
  or `edge:<id>`, as UTF-8 text — and whose value is the delta as the contract defines it. The
  author MUST NOT be able to set the key of a delta.
- **FR-014**: The builder MUST refuse, where the element is built and with a message naming the
  fault, whatever the merge sink would refuse: an empty id; an edge without a type or without both
  endpoint ids; a label or type that is not an identifier; a property name the contract reserves;
  a property value that is not a
  string, a number, a boolean or a non-empty list of one of those; a number the contract cannot
  carry; a version that is not a positive integer.
- **FR-015**: The builder MUST refuse the same element — the same kind and id — twice in one
  change's result.
- **FR-016**: A value published by the builder MUST be read back by a reader the SDK provides, for
  tests and for consumers of a delta topic, into the same element.

**Versions**

- **FR-017**: An element's version MUST default to the sequence number of the change being handled.
  The author MUST be able to state a version instead.
- **FR-018**: For an event sourced entity the sequence number MUST be the event's. For a key value
  entity it MUST be the state's revision, delivered to the consumer in every SDK; a consumer over a
  key value entity MUST NOT be handed zero.
- **FR-019**: A deletion MUST reach the deletion handler with a sequence number greater than that of
  every earlier change to the same entity, so a tombstone built there outranks them.
- **FR-020**: The sequence numbers one entity id presents to a consumer MUST never decrease,
  including after the entity is deleted and created again under the same id.
- **FR-020a**: The deletion of a key value entity MUST be a recorded change: delivered to every
  view and consumer over that entity, at the revision after its last update, with the entity's id
  usable again afterwards and its revisions continuing. A view's row for a deleted key value entity
  MUST be removed. State stored before this feature MUST remain readable, and no stored form
  changes.
- **FR-020b**: An entity whose state has expired is not deleted, and no consumer is told. The
  documentation MUST say that the elements of an expired entity are not tombstoned.
- **FR-021**: For a source with no sequence number — a topic — building an element without a stated
  version MUST fail with a message saying so.
- **FR-022**: A change delivered again MUST produce deltas equal to the first delivery's in key,
  version and value, given a handler that is a function of its change.

**What the author maps from**

- **FR-023**: A graph-publishing consumer's handlers MUST receive what a consumer's handlers
  receive today: the event for an event sourced entity, the state for a key value entity, the
  message for a topic. No new kind of source is introduced.
- **FR-023a**: A handler MUST be able to read the source entity's current state through the
  component client and build elements from it, in every SDK. Elements built that way are published
  at the version of the change being handled, and the documentation MUST say that the state read
  may be later than that change and that the graph converges when the later changes are handled.

**Agreement with ankka-flow**

- **FR-024**: ankka MUST NOT depend on any ankka-flow artifact at build time or at run time.
- **FR-025**: The repository MUST carry ankka-flow's element key fixture and a fixture of deltas,
  each naming where it came from, in the place every SDK already copies the protocol from, and each
  of the four SDKs MUST prove against them that it produces the same keys and values the sink
  reads. An element that one SDK refuses, every SDK MUST refuse.
- **FR-026**: The contract's name, fields and rules are ankka-flow's. This feature MUST NOT extend
  or reinterpret them; a delta ankka can build is one `ankka.graph-delta.v1` already defines.

**Languages and the protocol**

- **FR-027**: The several-message result, the delta builder, the delta reader and the test support
  MUST be provided by all four SDKs that have consumers: Scala, Python, TypeScript and Rust. Each
  MUST follow its own language's idiom and keep the same rules: what is refused, what a key is,
  what a version defaults to.
- **FR-028**: The handler protocol MUST be able to carry several messages, each with an optional
  key, in one consumer reply, in a way a handler built against the protocol as it is today is
  unaffected by. The same reply MUST serve a handler behind a sidecar and a handler hosted as a
  WebAssembly module. Every SDK's copy of the protocol MUST match the canonical one.
- **FR-029**: The conformance suite MUST cover the several-message reply — order, keys, the default
  key, the empty list, and redelivery when a publication fails — and every SDK MUST pass it.
- **FR-029a**: A service built with an SDK released before this feature MUST run unchanged on a
  runtime that has it.
- **FR-029b**: A service built with an SDK that has this feature, run on a runtime that does not,
  MUST fail a change whose handler returns several messages or names a key, with an error naming
  both versions. It MUST NOT lose the messages silently.

**Testing**

- **FR-030**: In every SDK, a consumer's unit test MUST be able to hand it a change at a stated
  sequence number and read back every message it returned and the key each named, with no runtime
  and no broker.
- **FR-031**: For a consumer that publishes deltas, the test MUST read back elements — kind, id,
  version, labels or type, endpoints, properties — not bytes.
- **FR-032**: The in-memory broker MUST record each published message's key, so a service test can
  assert on it.

**The sample**

- **FR-033**: The shopping cart sample MUST gain a consumer that publishes its carts and checkouts
  as a graph: nodes, an edge, and tombstones when a cart is deleted. It MUST contain no hand-written
  key, version or delta JSON.
- **FR-033a**: The same consumer MUST exist as tested code in Python, TypeScript and Rust, wherever
  each SDK keeps the examples its documentation is drawn from, and each MUST publish records equal
  to the Scala sample's for the same history.
- **FR-034**: The sample MUST include what is needed to fill a graph database from it with an
  ankka-flow pipeline of the built-in sink alone, and the steps to run it on a local cluster.

**Documentation**

- **FR-035**: The consumers guide and the topics guide MUST cover returning several messages,
  naming a key, the separation of key and subject, and when a change counts as handled.
- **FR-036**: A guide to publishing a graph MUST exist, in the navigation and in the skills that
  carry the build guides. It MUST state the writer's rules in ankka's terms: a delta is an element's
  whole state; an element has exactly one writing entity; ids are prefixed by kind; versions come
  from the entity; a tombstone marks and does not remove.
- **FR-037**: The documentation MUST say that the delta topic has to be compacted, that ankka
  neither creates nor checks it, that the pipeline reading it should declare it and be deployed
  first, and what happens otherwise.
- **FR-038**: Each SDK's reference, the protocol reference and the WebAssembly interface reference
  MUST describe the several-message result and the builder.
- **FR-039**: The guides MUST show the several-message result and the graph consumer in all four
  languages, from tested code.

### Key Entities

- **Change**: one event, state change or deletion of one entity, or one message from a topic, as a
  consumer is handed it. It has a subject (the entity's id) and, from an entity, a sequence number.
- **Published message**: a body, its CloudEvents attributes, and a record key. The key is the
  subject unless the message names another.
- **Result of a change**: an ordered list of published messages, possibly empty. The change is
  handled when all of them are accepted.
- **Element**: a node or an edge of the graph, identified by its kind and id. Nodes and edges are
  separate id spaces.
- **Delta**: one element's whole state at a version, or a tombstone marking it deleted at a version.
- **Element key**: `node:<id>` or `edge:<id>`; the record key of every delta for that element.
- **Version**: a positive integer that rises with the history of the entity that owns the element.
  By default, the change's sequence number.
- **Delta topic**: the topic a graph-publishing consumer publishes to. It belongs to whoever
  declares it; it must be compacted to hold the graph.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For a consumer that returns N messages for one change, all N are in the topic, in
  order and under the keys named, before the change counts as handled. When any one publication is
  made to fail, the change is redelivered every time, and after it succeeds no message is missing.
- **SC-002**: Every existing consumer suite, in every SDK and in the conformance suite, passes
  unmodified, and a single produced message is identical in value, key and headers to what the
  previous version publishes.
- **SC-003**: For every row of the shared fixtures, the key and value each of the four SDKs
  produces equal the fixture's, and ankka-flow's reader accepts every one: no disagreement on any
  row, in any language.
- **SC-003a**: For one scripted history of a cart, the graph consumers in the four languages
  publish the same records: equal keys, versions and deltas.
- **SC-004**: The sample's graph consumer contains no literal record key, no literal version and no
  hand-written delta, and its unit tests run in under a second with nothing started.
- **SC-005**: On a local cluster, a pipeline with no streamlet but the built-in sink produces from
  the sample service a graph equal to the expected one for a scripted run of creations, changes,
  checkouts and deletions; the sink never stalls. The run is made with the Scala sample and repeated
  with at least one service behind a sidecar and one hosted as a WebAssembly module.
- **SC-006**: Replaying the consumer from the start of its source against a graph already built
  writes nothing: every repeated delta is counted stale, and the graph is unchanged.
- **SC-007**: After the database is emptied and only the sink is reset, the graph rebuilt from the
  topic equals the graph before, with no request made to the service.
- **SC-008**: Across deletion and re-creation of an entity under one id, the versions published for
  its elements rise without exception, and the graph ends showing the re-created element live.
- **SC-009**: A reader with the sample and the new guide reaches a graph in the database without
  writing a second program, and the documentation build reports no problems.

## Assumptions

- **A consumer, not a new component.** The descriptor, the registration, the parallelism and the
  delivery guarantee are the consumer's. Nothing new is deployed.
- **At least once, without transactions.** A change's messages are not published atomically. That
  is acceptable because a repeat is harmless for deltas and already the rule for plain messages.
- **One region.** Every change a consumer sees today originates locally. How versions behave when
  an entity is written in more than one region is not addressed.
- **ankka creates and alters no topics**, as today, and has no client that could inspect one. The
  compaction of a delta topic is established and reported by ankka-flow, for a topic the pipeline
  declares as its own.
- **The reader is ankka-flow 0.3.0 or later**: the sink that requires element keys and passes over
  records with no value.
- **The contract is stable at `ankka.graph-delta.v1`.** A later contract version is a later
  feature in both repositories.
- **Sequence numbers across deletion and re-creation** continue for an event sourced entity, whose
  deletion is a journalled record. For a key value entity they restart, which FR-020a corrects,
  because without it a re-created entity never reappears in the graph.
- **A key value consumer in a Scala service is handed zero as its sequence number today**, while a
  view over the same source, and a consumer in any other language, is handed the revision.
  Correcting that is part of this feature (FR-018).
- **What an existing service can observe** is that, and the deletion of a key value entity now
  reaching its views and consumers (FR-020a). Nothing else changes for code that does not use the
  feature.
- **The fixtures are copied from ankka-flow**, as files that name their source; no build reaches
  into the other repository. Keeping the copies equal is a check a release of either makes. The
  element key fixture exists there; a fixture of deltas with every kind of property is added there
  first, read by the sink's own suite, and copied.
- **The end-to-end run uses the local cluster both projects already deploy to**, with the graph
  database ankka-flow's development overlay provides.
- **A guide in ankka-flow** to filling a graph from an ankka service with no mapper is a follow-up
  in that repository; this feature's guide links to what ankka-flow already documents.

## Out of Scope

- The entity's state as a source for an event sourced entity, and any component that keeps a copy
  of state in order to publish it. A later feature.
- Views, workflows, agents or endpoints publishing deltas; only consumers publish to topics.
- Creating, altering or inspecting topics from ankka, including a warning at startup for a delta
  topic that is missing or not compacted.
- Delete markers: records with no value that remove an element's history from a compacted topic.
- Publishing a change's messages atomically, or exactly once.
- Detecting two writers of one element, or an element built from part of its state.
- Versions for entities written in more than one region.
- Any change to `ankka.graph-delta.v1`, to the merge sink, or to how ankka-flow behaves. (A test
  fixture of deltas is added to ankka-flow so that both repositories read the same rows.)
- Other graph databases, and showing the graph in the console.
