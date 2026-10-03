# Feature Specification: Multi-Source Views — Several Sources, an Explicit Row Key, and a Recursive Read

**Feature Branch**: `031-multi-source-views`

**Created**: 2026-10-03

**Status**: Draft

**Input**: User description: "A view today reads exactly one source into one table keyed by that
source's id. Two shapes a service needs cannot be built that way: a tree, where every node's row
carries its parent and a question is 'everything under this node', and a chain, where a row about
one thing carries ids copied from two or three other things and a question is 'every round in
this session for this player'. Let a view declare several sources with a handler per source, and
let each handler say which row it is updating by key, so one change can update several rows and
several sources can update one row. Then let a query over a view's table be a read-only SQL
statement that may be recursive, validated to touch only that table, so a tree is walked in the
database. No graph store: nothing here needs more than a tree walk in SQL, and a graph store
would be a second database for the operator to provision. Out of scope: joining a topic source
and an entity source in one view, and any change to how a single-source view is written."

## Context

A view in ankka is a queryable projection of one source's changes. `ViewDescriptor` in
`modules/sdk/src/main/scala/com/thinkmorestupidless/ankka/sdk/View.scala` holds a single
`source: ChangeSource[Src]`, which is an entity's events, a key value entity's state or a broker
topic. The runtime reads the source in order and hands each change to the view's handler with the
current row for that source id, and the handler returns `updateRow`, `deleteRow` or `ignore`.
The row's key is always the id of the entity the change came from; the views page says so and
says why: re-keying a row by an attribute would orphan the old row the first time the attribute
changed.

The storage is `ViewStore.scala` in `modules/runtime`: one table per view, named by
`View.tableFor(componentId)` as `ankka_view_` plus the component id with every non-alphanumeric
character replaced, with the columns `row_key`, `payload` as JSON text and `updated_at`. Writes
are upsert and delete by key. Reads are `selectByKey` and `selectWhere`, the latter a where
clause over expressions on the payload, through `ViewClient.scala`. The sidecar protocol's
`ViewDetail` in `discovery.proto` carries one `Source`, a row manifest and the query names. The
views page's limits say that joining two sources into one view is not supported, and the
limitations page says multi-table views, rebuild on deploy and Akka's snapshot-handler
optimisation are not built.

Two questions a generic service asks cannot be answered by any view that exists today.

The first is a tree. An organisation chart, a category hierarchy, a reseller network: each node
has a parent, and the question is the subtree under a node to any depth. A single-source view
over the node entity can hold each node's parent id in its row, which is enough data, but
`selectWhere` is one flat predicate and there is no way to ask the database to follow parent ids
until it runs out.

The second is a chain across entities. An order carries a customer id; a shipment carries an
order id; the question is every shipment for a customer. The designing page's answer is that the
entity which owns the rule copies what it needs into its own events, and that is right for the
rule. For the read it means a view over shipments whose handler writes a row that carries the
customer id copied from the shipment's events, and a query on that id. A single-source view can
do this today as long as the shipment's events carry the customer id. What it cannot do is keep
one row up to date from two sources: a row per shipment that also shows the customer's current
name, because the customer's events arrive at a different view.

Neither needs a graph database. A tree walk is a recursive common table expression over one
table, which Postgres has had for fifteen years. A row that two sources update is a table with
two writers. Provisioning Neo4j or anything like it would be a second kind of backing service
for the operator, a second credential, a second thing that is never deleted, and a second query
language in every SDK, for questions SQL answers.

The decisions this feature makes:

- **A view may declare several sources, each with its own handler.** The runtime runs one
  projection per source, each with its own offset, so each source is still read in order and
  exactly once from an entity. All of them write the same table.
- **A handler names the row it updates.** `updateRow(key, row)` and `deleteRow(key)` take an
  explicit key, and a handler may return several of them for one change. The single-source
  shape, where the key is the source id, is unchanged and stays the default.
- **The table is unchanged.** One `row_key`, one JSON payload, one `updated_at`. A multi-source
  view is a single-source view with more writers, and the view client, the local console and the
  storage need no new shape.
- **The explicit key moves the orphan risk to the handler.** The views page refused re-keying
  because the runtime could not know the old key. With explicit keys the handler can, and the
  rule becomes: a change that moves a row deletes the old key and writes the new one in the same
  effect. The docs say this plainly, and nothing by inference: the runtime never deletes a row it
  was not told to.
- **A recursive read is a validated statement.** The view client gains a read whose argument is a
  SQL statement over the view's table that may use `WITH RECURSIVE`. The runtime checks that it
  is a single `SELECT` or `WITH ... SELECT`, that every table it names is the view's own, and
  refuses anything else before it reaches the database. It returns rows as the other reads do.
- **Rebuild comes from feature 024.** A multi-source view over entities is rebuildable because
  journals are complete; a version bump truncating the table and re-reading every source is
  024's mechanism applied to each source.

What this feature is not: a join engine, a graph store, a change to single-source views, or a
way to query across two views' tables. A query names one table. A view over a topic and an entity
together is out of scope because only the entity half is rebuildable, and the limitations page
will say so.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A view walks a tree with a recursive query (Priority: P1)

A developer has a `node` entity whose events carry each node's parent id. They write a view over
it with one row per node holding the parent id, and an endpoint that answers "everything under
this node" with one recursive query on the view's table.

**Why this priority**: The tree is the question that has no answer today, and it needs only the
recursive read, not the multi-source half. It is the smaller change and delivers a whole
capability on its own.

**Independent Test**: With `AnkkaTestKit`, create a thousand nodes in a tree ten deep, wait for
the view, and assert one recursive query returns exactly the subtree under a chosen node. Assert
a statement that names another table, or that writes, is refused before the database sees it.

**Acceptance Scenarios**:

1. **Given** a view whose rows hold a parent id, **When** the endpoint runs a recursive query
   from a node, **Then** it returns every descendant of that node and no other row, in one call.
2. **Given** the same view, **When** the query names a table other than the view's own, **Then**
   the read is refused with an error naming the table, and no statement reaches the database.
3. **Given** the same view, **When** the statement is an `UPDATE`, a `DELETE`, or a `SELECT`
   followed by a second statement, **Then** it is refused before the database sees it.
4. **Given** a Python and a TypeScript service, **When** each runs the same recursive query
   through its view client, **Then** each gets the same rows through the sidecar.
5. **Given** a recursive query over a tree of a thousand nodes, **When** it is run, **Then** it
   returns within the view client's existing query timeout.

---

### User Story 2 - Two sources keep one row up to date (Priority: P1)

A developer writes a view with two sources: the shipment entity, whose handler writes a row per
shipment keyed by shipment id and carrying the customer id; and the customer entity, whose handler
updates the customer's name on every shipment row that carries that customer id. A screen lists a
customer's shipments with the customer's current name without reading a second view.

**Why this priority**: This is the multi-source half, and the chain question from the domain plan
needs it. It is the larger change and is independent of the recursive read.

**Independent Test**: In the testkit, write a view over two entity test kits, apply events to
both, and assert the rows: the count equals the distinct shipment ids, each carries the customer
id from the shipment's events, and a customer rename appears on every row carrying that customer
id. Restart the service and assert nothing is applied twice.

**Acceptance Scenarios**:

1. **Given** a view over two entities' events with a handler per source, **When** events arrive
   on both, **Then** the view's table holds one row per shipment, and a customer event updates
   every row that carries that customer id.
2. **Given** the same view, **When** the service restarts after events on both sources, **Then**
   each source resumes from its own recorded offset, and no row shows a change applied twice.
3. **Given** a handler that returns updates for several keys from one change, **When** the
   change is applied, **Then** every named row is written, and a row the handler did not name is
   untouched.
4. **Given** a handler that moves a row by deleting the old key and writing the new one in one
   effect, **When** the change is applied, **Then** the old key is gone and the new key holds the
   row; the runtime deletes nothing it was not told to.
5. **Given** a view that names a topic and an entity as its sources, **When** the descriptor is
   built, **Then** it is refused with an error saying a topic and an entity may not share a view.
6. **Given** a multi-source view declared in Python and in TypeScript, **When** discovery runs,
   **Then** the sidecar hosts one projection per source, and the local console's topology shows
   the view connected to each.

---

### User Story 3 - A multi-source view is rebuilt when its version changes (Priority: P2)

The developer changes what a row holds, bumps the view's version, and redeploys. The table holds
only rows the new handlers wrote, built from every source from the beginning.

**Why this priority**: Rebuild is what makes a view cheap to change, and a multi-source view has
more reason to change than most. It depends on feature 024's version mechanism and comes after
it.

**Independent Test**: With `AnkkaTestKit`, run the view, bump the version, restart, and assert
the rows were rewritten by the new handler and every source's offset was reset.

**Acceptance Scenarios**:

1. **Given** a multi-source view with rows written by version one, **When** version two is
   deployed, **Then** the table holds only rows version two's handlers wrote, and both sources
   were read from their beginning.
2. **Given** a multi-source view whose version is unchanged, **When** it is redeployed, **Then**
   no row is rewritten and each source resumes from its offset.

---

### Edge Cases

- Two sources deliver changes for the same key at the same moment on two instances: each
  projection is single-writer for its own source, and the table's upsert is per row, so the last
  write wins per row. The docs say a row updated by two sources must be written whole by each
  handler, from the current row the handler was handed.
- A handler is handed the current row for the key it names, not for the source id. For a change
  that updates several keys, the handler reads each current row through the row lookup the view
  context already offers.
- A recursive query that never terminates, because the data holds a cycle, is bounded by the
  query timeout and refused with the timeout, not left running.
- A statement with a comment or a string literal that mentions another table name: the check is on
  the parsed statement's referenced tables, not on the text.
- A view with ten sources: ten projections, ten offsets, one table. The number of sources is not
  limited by this feature.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: A view MUST be able to declare more than one source, each an entity's events or a
  key value entity's state, with a handler per source.
- **FR-002**: The runtime MUST run one projection per source with its own offset, so each source
  is read in order and resumed independently.
- **FR-003**: A view handler MUST be able to update or delete a row by an explicit key, and
  return several such updates for one change.
- **FR-004**: The view's table shape MUST be unchanged: one key, one payload, one update time.
- **FR-005**: The view client MUST offer a read-only query over the view's own table that may use
  a recursive common table expression.
- **FR-006**: The runtime MUST refuse, before any statement reaches the database, a query that is
  not a single `SELECT`, that names any table other than the view's own, or that writes.
- **FR-007**: A view that names a topic and an entity together MUST be refused when its
  descriptor is built, with an error saying why.
- **FR-008**: The sidecar protocol's view detail MUST carry a list of sources, as a minor version
  change, and the Python, TypeScript and Rust SDKs MUST be able to declare a multi-source view
  and run the recursive read.
- **FR-009**: A single-source view written before this feature MUST behave exactly as before,
  including its implicit key.
- **FR-010**: The views page MUST document the explicit key, the rule that a moved row is deleted
  and rewritten by the handler, and the recursive read; the limitations page MUST say a topic and
  an entity may not share a view.

### Key Entities

- **View descriptor**: component id, a list of sources each with a handler, a row serializer, a
  version, and query names.
- **Row**: key, payload, update time. Unchanged.
- **Recursive read**: a statement, the view it is run against, and the rows it returns.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A recursive query returns the subtree of a thousand-node tree in one call, within
  the view client's query timeout.
- **SC-002**: A statement that writes, names another table, or holds two statements never
  reaches the database, shown by a test that counts statements at the connection.
- **SC-003**: A two-source view's row count equals the number of distinct keys the handlers
  named, after a restart as before it.
- **SC-004**: Every existing view test passes unchanged, including the conformance suite's view
  cases in every language.
- **SC-005**: The multi-source view is declarable and the recursive read is runnable from all
  four SDKs, shown by a conformance case per SDK.

## Assumptions

- The view store's query timeout that bounds `selectWhere` today bounds the recursive read.
- Validation of the statement is by parsing, not by pattern matching the text, so a comment or a
  literal cannot trick it.
- The views page's rule that a handful of known ids is not a view still holds; this feature does
  not change when a view is the right component.
- The sidecar protocol's version is bumped once for this feature's view detail change, as a
  minor version, and an older SDK that sends one source is hosted as before.

## Dependencies

- **024-replayable-topics** for the view version and rebuild mechanism User Story 3 relies on.
- Gates the domain plan's stage 4, where the affiliate network tree and the player, session and
  round chain are read.

## Open Questions

- Whether a multi-source view over a topic and an entity should ever be allowed, given that only
  the entity half is rebuildable. This spec refuses it; a later feature could allow it with the
  topic half marked as not rebuildable.
- Whether the recursive read should be offered as a named query declared on the view's companion,
  as other queries are, rather than a statement passed at the call. A declared query is
  validated once at startup and named in discovery; a passed statement is validated per call.
- Whether the local console should run a recursive read from its query page.
