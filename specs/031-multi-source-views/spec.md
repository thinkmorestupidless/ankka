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
  projection per source, each with its own offset, so each source is still read in order, and
  exactly once from an event sourced entity. All of them write the same table.
- **Such a view handles one change at a time.** A handler reads rows and writes whole rows, so
  two changes handled at once could lose an update or miss a row. A view that reads several
  sources, or whose handlers name row keys, handles one change at a time across every source and
  every instance: what the handler reads and every row it writes are one step, and for an event
  sourced entity so is the record of how far the source has been read (a key value entity's
  changes are at least once, as they are for any view). The price is one writer for that view,
  and it is stated: a single-source view with the default key is sliced across instances as
  before.
- **A handler names the row it updates.** `updateRow(key, row)` and `deleteRow(key)` take an
  explicit key, and a handler may return several of them for one change. The single-source
  shape, where the key is the source id, is unchanged and stays the default.
- **A handler may read its own view's table.** A change often does not carry the keys of the
  rows it affects: a customer's rename says nothing of that customer's shipments. While handling
  a change a handler may read one row by key, or rows by a query, from the table it writes, and
  from no other.
- **The table is unchanged.** One `row_key`, one JSON payload, one `updated_at`. A multi-source
  view is a single-source view with more writers, and the view client, the local console and the
  storage need no new shape.
- **The explicit key moves the orphan risk to the handler.** The views page refused re-keying
  because the runtime could not know the old key. With explicit keys the handler can, and the
  rule becomes: a change that moves a row deletes the old key and writes the new one in the same
  effect. The docs say this plainly, and nothing by inference: the runtime never deletes a row it
  was not told to.
- **A view declares its queries, and a recursive query is one of them.** Beside its sources a view
  declares queries by name: each is a SQL statement over the view's table, which may use
  `WITH RECURSIVE`, and the names of the values it takes. The runtime checks every statement once,
  at startup: it must be a single `SELECT` or `WITH ... SELECT`, and every table it names must be
  the view's own. A statement that fails stops the service from starting, naming the view and the
  query, so no such statement ever reaches the database. A caller asks by name and gives the
  values, which are bound and never spliced into the text. The rows come back as the other reads'
  do. A declared query is named in discovery, so it is the same shape in every SDK, and it is the
  read a view's own handler uses to find rows.
- **Rebuild is feature 024's mechanism, extended to entities.** 024 gave a topic-sourced view a
  declared version, a recorded version, an emptying that happens once however many instances
  start, and the rule that an instance behind the recorded version stops writing; it refuses a
  version on a view that reads an entity, because there it would have done nothing. This feature
  lifts that refusal for views: any view that reads entities, one source or several, may declare
  a version, and raising it empties the table and reads every source again from its first event
  or state. Journals are complete, so the rebuild is too. A consumer that reads an entity is
  still refused a version.

What this feature is not: a join engine, a graph store, a change to a single-source view that
declares no version, or a way to query across two views' tables. A query names one table. A view
over a topic and an entity together is out of scope because only the entity half is rebuildable,
and the limitations page will say so.

## Clarifications

### Session 2026-10-04

- Q: Feature 024 is built and refuses a version on any view that reads an entity; User Story 3
  needs a view of several entity sources to be rebuilt when its version is raised. Which rule for
  versions on entity-sourced views? → A: Every entity-sourced view, one source or several, may
  declare a version and is rebuilt from its journals when it is raised, reusing 024's recorded
  version, locking and behind rule. A consumer that reads an entity is still refused. 024's
  scenario and the limitation "a view over an entity is not rebuilt" change; a view that declares
  no version behaves exactly as before.
- Q: A view handler today sees only the row for its own source id. How does a handler find rows
  whose keys the change does not carry, as a customer rename must find that customer's
  shipments? → A: While handling a change a handler may read its own view's table, one row by
  row key and rows by a query, and then names the keys it writes. It reads no other table.
- Q: A handler reads then writes whole rows, so two sources, or two slices of one source, can
  lose an update or miss a row written at the same moment. What holds? → A: One change at a
  time. A view that reads several sources, or names row keys, handles one change at a time across
  all its sources and instances; the read, the writes and the record of how far it has read are
  one step. Such a view has one writer. A single-source view with the default key keeps its
  parallelism.
- Q: Is a recursive query a statement passed at the call, or a query declared with the view? →
  A: Declared with the view, under a name, with named values. The platform checks the statement
  once at startup; one that writes, holds a second statement or reads another table stops the
  service from starting. Callers, and the view's own handler, ask by name and give values, which
  are bound. The same shape in all four SDKs.
- Q: How are the glossary terms the features introduce settled? → A: As written: source, table,
  row key, query, declared query, recursive query and statement, with 024's row and rebuild
  widened to cover entities. Refused: "recursive read" and "tree query" for recursive query,
  "named query" for declared query, "primary key" for row key, "SQL" for statement. "handler"
  keeps its present meaning; a feature says the view writes a row for an event.

### Found in planning, 2026-10-04

- A view whose handlers name row keys is declared as a second shape of view, a *keyed view*,
  because how a view is run has to be known before it starts. FR-003 and FR-015 are about that
  shape; a single-source view that is not keyed cannot name a row key (plan, R1).
- A key value entity's changes are at least once, so FR-016 now says which part holds for which
  kind of source (R4).
- No read of a view has a timeout in the database today; the assumption that one existed is
  corrected, and a declared query is given one (R8).
- Every view that reads entities checks the recorded version on each write, whether or not it
  declares one, and a view's version may be raised only once every instance runs a release that
  has this feature (R10, R12).

### Found in analysis, 2026-10-04

- The name a view's progress is recorded under carries its version where no other view's id can
  spell it, as a topic source's group does: `summary` at version 2 and `summary-v2` at version 1
  must not share a name (R10).
- Feature 024's documentation scenario said a view reading an entity has no rebuild. It is
  changed, and referenced under User Story 3.
- A view that reads entities says it is behind in the log only (FR-012).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A view walks a tree with a recursive query (Priority: P1)

A developer has a `node` entity whose events carry each node's parent id. They write a view over
it with one row per node holding the parent id, declare on the view a recursive query that takes
a node, and write an endpoint that answers "everything under this node" by asking that query once.

**Why this priority**: The tree is the question that has no answer today, and it needs only the
declared query, not the multi-source half. It is the smaller change and delivers a whole
capability on its own.

**Independent Test**: With `AnkkaTestKit`, create a thousand nodes in a tree ten deep, wait for
the view, and assert one asking of the declared query returns exactly the subtree under a chosen
node. Assert a service whose view declares a statement that names another table, or that writes,
does not start, and that the database was sent nothing for it.

**Acceptance Scenarios**:

- added `features/views/recursive-queries.feature`: a recursive query answers with every row under a row, to any depth, and no other
- added `features/views/recursive-queries.feature`: a recursive query over a thousand rows is answered within the time a query is given
- added `features/views/recursive-queries.feature`: a recursive query that never ends is stopped when the time a query is given runs out
- added `features/views/declared-queries.feature`: a declared query is asked by name and answered with rows
- added `features/views/declared-queries.feature`: a value given to a declared query is never read as part of its statement
- added `features/views/declared-queries.feature`: a query the view does not declare is refused
- added `features/views/declared-queries.feature`: a declared query that is not given a value it takes is refused
- added `features/views/declared-queries.feature`: a service whose view declares a statement that is not one query of the view's own table does not start
- added `features/views/declared-queries.feature`: a statement that reads another view's table is refused, and the refusal names the table
- added `features/views/declared-queries.feature`: a statement is checked by the tables it reads, not by the names written in it
- added `features/views/languages.feature`: a recursive query answers with the same rows in every language
- added `features/views/languages.feature`: a service whose view declares a statement that reads another table does not start in every language
- added `features/documentation/views.feature`: the documentation of views describes declared queries and the recursive query

---

### User Story 2 - Two sources keep one row up to date (Priority: P1)

A developer writes a view with two sources: the shipment entity, whose handler writes a row per
shipment keyed by shipment id and carrying the customer id; and the customer entity, whose handler
updates the customer's name on every shipment row that carries that customer id. A screen lists a
customer's shipments with the customer's current name without reading a second view.

**Why this priority**: This is the multi-source half, and the chain question from the domain plan
needs it. It is the larger change. It uses a declared query for a handler to find rows, and is
otherwise independent of User Story 1.

**Independent Test**: In the testkit, write a view over two entity test kits, apply events to
both, and assert the rows: the count equals the distinct shipment ids, each carries the customer
id from the shipment's events, and a customer rename appears on every row carrying that customer
id. Restart the service and assert nothing is applied twice.

**Acceptance Scenarios**:

- added `features/views/several-sources.feature`: two sources write one row
- added `features/views/several-sources.feature`: a view of several sources holds one row for each row key it was given
- added `features/views/several-sources.feature`: a restarted view goes on reading each source from where it had reached
- added `features/views/several-sources.feature`: one event writes every row the view names for it and no other
- added `features/views/several-sources.feature`: a view finds the rows an event is about by asking a query of its own
- added `features/views/several-sources.feature`: a view reads a row of its own table by row key while it handles an event
- added `features/views/languages.feature`: a view finds the rows an event is about by asking a query of its own in every language
- added `features/views/several-sources.feature`: a view of several sources handles one event at a time
- added `features/views/several-sources.feature`: two sources writing one row at the same time lose neither write
- added `features/views/several-sources.feature`: the rows one event names are written together or not at all
- added `features/views/several-sources.feature`: a row is moved by deleting it under its old row key and writing it under its new one
- added `features/views/several-sources.feature`: the platform deletes no row the view did not name
- added `features/views/several-sources.feature`: a view may not read a topic and an entity together
- added `features/views/several-sources.feature`: a view of one source that names no row key keeps each row under the entity id it came from
- added `features/views/languages.feature`: a view of several sources reads every one of them in every language
- added `features/views/languages.feature`: the topology shows a view connected to each of its sources in every language
- added `features/documentation/views.feature`: the documentation of views describes the row key a view names
- added `features/documentation/views.feature`: the documentation says that a moved row is deleted and written by the view
- added `features/documentation/views.feature`: the documentation says that a topic and an entity may not share a view

---

### User Story 3 - A view that reads entities is rebuilt when its version is raised (Priority: P2)

The developer changes what a row holds, raises the view's version, and redeploys. The table holds
only rows the new handlers wrote, built from every source from its first event or state. The view
may have one source or several; a view that declares no version is never rebuilt.

**Why this priority**: Rebuild is what makes a view cheap to change, and a multi-source view has
more reason to change than most. It reuses feature 024's version mechanism, which is built, and
changes one rule 024 set: a version on a view that reads an entity is no longer refused.

**Independent Test**: With `AnkkaTestKit`, run a view of two entity sources, raise the version,
restart, and assert the rows were rewritten by the new handlers and every source was read again
from its beginning. Do the same for a view of one source, and assert a view with no version reads
nothing again.

**Acceptance Scenarios**:

- added `features/views/rebuilding.feature`: a view of several sources at a higher version is rebuilt from every source
- added `features/views/rebuilding.feature`: a view of one source at a higher version is rebuilt
- added `features/views/rebuilding.feature`: a view restarted at the same version is not rebuilt
- added `features/views/rebuilding.feature`: a view that declares no version is never rebuilt
- added `features/views/rebuilding.feature`: instances starting together at a higher version rebuild a view that reads entities once
- added `features/views/rebuilding.feature`: during a rolling update the instance at the lower version stops writing a view that reads entities
- added `features/views/rebuilding.feature`: a service rolled back to a lower version leaves a view that reads entities as it is
- changed `features/topics/versions.feature`: a version on a consumer that reads an entity is refused
- changed `features/documentation/topic-sources.feature`: the documentation's limitations say what bounds a topic source's rebuild

---

### Edge Cases

- Two sources deliver changes for the same key at the same moment on two instances: the view
  handles one wholly before the other, so the second handler reads the row the first wrote and
  neither write is lost. The order between two sources is not defined; within one source it is
  the source's own.
- One change names several rows and one of them cannot be written: none of them is, the source's
  progress is not recorded, and the change is handled again.
- A single-source view that names no row key is handed the current row for the source id, as it
  always was. A view whose handlers name row keys is handed none: it reads the rows it needs from
  its own view's table while handling the change, by row key, or by a declared query when the
  change does not carry the keys.
- A recursive query that never terminates, because the data holds a cycle, is ended by the
  database when the service's ask timeout runs out and refused with the timeout, not left running.
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
- **FR-005**: A view MUST be able to declare queries by name, each a read-only statement over the
  view's own table that may use a recursive common table expression, with the names of the values
  it takes. The view client MUST answer a declared query asked by name with values, binding the
  values and never splicing them into the statement.
- **FR-006**: The runtime MUST check every declared statement at startup and refuse to start a
  service one of whose statements is not a single `SELECT`, names any table other than the view's
  own, or writes, naming the view and the query, so that no such statement reaches the database.
  A query the view does not declare, or one not given a value it takes, MUST be refused at the
  call.
- **FR-007**: A view that names a topic and an entity together MUST be refused when its
  descriptor is built, with an error saying why.
- **FR-008**: The sidecar protocol's view detail MUST carry a list of sources, as a minor version
  change, and its declared queries, each a name and a statement that names its own values; the
  Python, TypeScript and Rust SDKs MUST be able to declare a multi-source view, declare a query
  and ask it.
- **FR-009**: A single-source view written before this feature MUST behave exactly as before,
  including its implicit key. It declares no version, so it is never rebuilt.
- **FR-010**: The views page MUST document the explicit key, the rule that a moved row is deleted
  and rewritten by the handler, declared queries and the recursive query, and rebuilding a view
  that reads entities by raising its version; the limitations page MUST say a topic and an entity
  may not share a view, MUST no longer say a view over an entity is not rebuilt, and MUST say that
  such a rebuild is not bounded by what a broker retains.
- **FR-011**: A view that reads entities, with one source or several, MUST be able to declare a
  version. When the declared version is higher than the recorded one the view MUST be emptied
  once, however many instances start, and every source read again from its first event or state.
- **FR-012**: An instance that declares a lower version than the recorded one MUST stop reading
  and writing for that view and say so in its log, in the words a topic-sourced view uses; the
  rows stay as they are. It is not added to the metrics, which list topic sources.
- **FR-013**: A version on a consumer that reads an entity MUST still be refused at registration.
- **FR-014**: While handling a change, a view handler MUST be able to read its own view's table,
  one row by row key and rows by one of the view's declared queries, in every SDK, and MUST NOT
  be able to read any other table through it.
- **FR-015**: A view that reads several sources, or whose handlers name row keys, MUST handle
  one change at a time across all its sources and instances, so that nothing another change
  writes falls between a handler's reads and its writes.
- **FR-016**: Every row one change names MUST be written together or not at all. For an event
  sourced entity the record of how far the source has been read MUST be written with them, so the
  change is applied exactly once; for a key value entity it is recorded afterwards and the change
  may be handled again, as it may for any view over a key value entity.
- **FR-017**: A single-source view whose handler names no row key MUST keep its parallelism,
  and the names its progress is recorded under.

### Key Entities

- **View descriptor**: component id, a list of sources each with a handler, a row serializer, a
  version, and its declared queries.
- **Row**: key, payload, update time. Unchanged.
- **Declared query**: a name, a statement over the view's own table, and the names of the values
  it takes. A recursive query is a declared query whose statement is recursive.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A recursive query returns the subtree of a thousand-node tree in one asking, within
  the service's ask timeout.
- **SC-002**: A statement that writes, names another table, or holds two statements stops the
  service from starting and never reaches the database, shown by a test that counts statements
  at the connection.
- **SC-003**: A two-source view's row count equals the number of distinct keys the handlers
  named, after a restart as before it.
- **SC-004**: Every existing view test passes unchanged, including the conformance suite's view
  cases in every language, except those that held feature 024's refusal of a version on a view
  that reads an entity (one in the runtime, one in the sidecar and one in each SDK), which now
  hold it for a consumer alone.
- **SC-005**: The multi-source view is declarable, and a recursive query is declarable and
  askable, from all four SDKs, shown by a conformance case per SDK.
- **SC-006**: With two sources each writing the same row a thousand times at once on two
  instances, the row holds every write: none is lost.
- **SC-007**: A view that reads entities and is declared at a higher version holds, once the
  rebuild ends, exactly the rows a fresh view at that version would, and was emptied once.

## Assumptions

- A declared query is bounded in the database: its statement is ended when the service's ask
  timeout runs out. The reads that exist today are bounded only by the caller ceasing to wait, and
  that is not changed. A declared query answers with at most the number of rows the other reads
  do unless the caller asks for another limit.
- The reads a Scala service has today (`where`, `ordered`, `count`) are unchanged.
- Validation of the statement is by parsing, not by pattern matching the text, so a comment or a
  literal cannot trick it.
- The views page's rule that a handful of known ids is not a view still holds; this feature does
  not change when a view is the right component.
- The sidecar protocol's version is bumped once for this feature's view detail change, as a
  minor version, and an older SDK that sends one source is hosted as before.

## Dependencies

- **024-replayable-topics**, which is built, for the view version and rebuild mechanism User
  Story 3 extends to views that read entities.
- Gates the domain plan's stage 4, where the affiliate network tree and the player, session and
  round chain are read.

## Open Questions

- Whether a multi-source view over a topic and an entity should ever be allowed, given that only
  the entity half is rebuildable. This spec refuses it; a later feature could allow it with the
  topic half marked as not rebuildable.
- Whether the local console should list a view's declared queries and ask one from its query
  page.
