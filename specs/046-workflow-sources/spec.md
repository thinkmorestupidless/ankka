# Feature Specification: Workflow Sources — A View or a Consumer Reads a Workflow

**Feature Branch**: `046-workflow-sources`

**Created**: 2026-10-10

**Status**: Draft

**Input**: User description: "Akka's SDK lets a Consumer or a View's TableUpdater consume a Workflow
(`@Consume.FromWorkflow`), delivering the workflow's state changes. ankka's `ChangeSource` has three
cases — an event sourced entity's events, a key value entity's state, a topic — and no workflow. Let a
view or a consumer read a workflow, in every language, so that a process's progress can be listed and
reacted to without the workflow's author writing a consumer-shaped command into every step."

## Context

A view or a consumer reads one or more **sources**, declared from the source component's own companion
so the change type and its decoder come from one place: `ChangeSource.eventsOf(ShoppingCartEntity)`,
`ChangeSource.stateOf(CheckoutLog)`, `ChangeSource.fromTopic("stock-events", serializer)`
(`modules/sdk/.../ChangeSource.scala`). `DeclaredSource` in `runtime` names the same three for the
topology (`Events`, `State`, `Topic`), and the sidecar's `Source` message carries a `ComponentRef` of
any `Kind` or a `topic`. The `Kind` enum already has `WORKFLOW = 2`; nothing accepts it as a source.
A Python view says `source = CheckoutLog`, a TypeScript one `static readonly source = CheckoutLog`, a
Rust one `Source::of(CheckoutLog)`; each is turned into a `ComponentRef` by the SDK, so the shape of a
workflow source in every language is already drawn, and refused.

A workflow is hosted as an `EventSourcedBehavior` whose events are its own transitions
(`WorkflowHost`): `StateUpdated(state)`, `TransitionedTo(step)`, `Paused(onTimeout, deadline)`,
`Ended`, `Failed(message)`, `RetryRecorded(step)` and `Deleted`, journalled as `WorkflowRecord` under
the manifests `state`, `transition`, `pause`, `end`, `fail`, `retry` and `delete`, with a snapshot every
hundred events. The journal is written through the same persistence plugin an entity's is, so
`eventsBySlices` — what `ProjectionRuntime` reads an event sourced source with — can read it today. What
it cannot do is hand a handler anything useful: only a `state` record carries the state, and the records
that say the most about a process (`end`, `fail`, a `transition`) carry none.

The workflow's own picture of where it stands is `WorkflowLifecycle` (`status` of `Running`, `Paused`,
`Completed` or `Failed`, the pending step, retries per step, the failure reason), answered by the
reserved `ankka:lifecycle` query the engine implements itself, "especially when the workflow's own
handlers cannot tell you anything useful". A workflow's domain state says what the process decided and
nothing about whether the engine is still running it: a plan whose state reads "consulting" looks the
same whether a model call is in flight or the workflow failed an hour ago (`docs/build/workflows.md`,
Domain state and lifecycle).

Akka delivers a workflow's **state changes** and nothing else, and its documentation tells an author
who needs "the change origin or executed steps" to encode them in the state class. That is the
asymmetry this feature declines to copy: ankka already keeps the engine's view apart from the state,
and a reader of a workflow wants both — a dashboard lists failed transfers by the engine's word, not by
a status field every author must remember to set before `thenEnd`.

What a view or a consumer over a workflow gets today is nothing. The shopping cart's
`CheckoutWorkflowSuite` learns a checkout has ended by polling the lifecycle query in an `eventually`;
a service that wants "every checkout that failed this hour" has to write a consumer-shaped command
into the workflow's own steps, or a view over an entity the workflow writes to for the purpose.
`docs/reference/limitations.md` does not list the gap, and `docs/reference/akka-divergences.md` does
not mention workflows as sources at all.

This feature makes these decisions.

- **A workflow is a source, declared as an entity is.** `ChangeSource.changesOf(CheckoutWorkflow)` in
  Scala; `source = CheckoutWorkflow`, `static readonly source = CheckoutWorkflow` and
  `Source::of(Checkout)` where a process or a module already names an entity. A plain view, a keyed
  view and a consumer may each read one, and a keyed view may read workflows beside entities. The
  sidecar's `Source.ComponentRef` with kind `WORKFLOW` is accepted at the next protocol version, and a
  runtime below it refuses the component at discovery, naming the version, as it refuses any unknown
  source.
- **A change is the state and the standing together.** Each change a workflow source delivers carries
  the workflow's state as it stood after what the workflow recorded and its **standing**: running,
  paused, completed or failed, the step it is on, its retries and, when it failed, why — what
  `WorkflowLifecycle` answers today, delivered rather than asked for. A handler reads
  `change.state` and `change.standing`; a view that lists only states ignores the second.
- **Every record is a change, in order, exactly once for a view.** A workflow's journal is an event
  journal, so a workflow source has an event sourced source's guarantees: every change in the order
  the workflow recorded it, a view's row and its offset written in one transaction, a consumer at
  least once. A retry, a pause and a transition are changes too, because a reader that watches for a
  stuck step needs them; a reader that wants only ends reads the standing and ignores the rest.
- **A deletion is the deletion handler's, and a rebuild is an entity's.** `Deleted` runs the view's
  or consumer's deletion handler, as an entity's does. A view over a workflow declares a version and
  is rebuilt by raising it, reading every record again from the first; a topic and a workflow may not
  be sources of one view, for the reason a topic and an entity may not.
- **The topology shows it.** A connection from a workflow to a view or consumer is a **workflow
  subscription**, beside the event, state and topic subscriptions the topology draws today, in every
  language.

What this feature is not: a way for a caller to wait for a workflow (048 is), a change to what a
workflow journals for its own recovery, or a source over an agent's session, an autonomous agent's
instance or a blueprint run — each is an entity already, and a view reads those as it always did.
It is not a change to the lifecycle query, which stays for a caller that has a workflow id in hand.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A view lists where every checkout stands (Priority: P1)

An operator wants a page of checkouts: open, paused waiting for approval, completed, failed and why.
The developer declares a view that reads the `checkout` workflow and writes one row per checkout from
the change's state and standing, and a declared query `by-standing` that takes a standing. The page
asks it with "failed" and gets every failed checkout with the step it failed on and the reason, none
of which the workflow's author wrote into the state.

**Why this priority**: This is the feature: a workflow's progress as data the service can read, without
every author copying the engine's words into their own state before each transition.

**Independent Test**: In the test kit, run a checkout to its end and a second one to a failure; read
the view's rows and the `by-standing` query; assert the rows carry the final states and the standings
the lifecycle query answers, and that the failed row names the step and the reason.

**Acceptance Scenarios**:

- added `features/workflow-sources/views.feature`: a row holds the state a workflow ended with and the standing completed
- added `features/workflow-sources/views.feature`: a row of a failed workflow names the step it failed on and the reason
- added `features/workflow-sources/views.feature`: a row of a paused workflow names the step it waits after
- added `features/workflow-sources/views.feature`: a row holds the state as of the last record and the step the workflow is on
- added `features/workflow-sources/views.feature`: a declared query lists the rows of one standing

---

### User Story 2 - A consumer reacts when a workflow ends (Priority: P1)

When a transfer workflow completes, a notice must be published to the `transfers-settled` topic; when
it fails, a ledger entity must be told. The developer declares a consumer that reads the `transfer`
workflow, ignores every change whose standing is running or paused, publishes on completed and calls
the ledger on failed. Nothing in the workflow knows the consumer exists.

**Why this priority**: Reacting to the end of a process is the commonest reason to read one, and today
the only ways are polling or a step that does the publishing itself.

**Independent Test**: With the in-memory broker, run a transfer to its end and another to a failure;
assert one message on `transfers-settled` for the first, with the transfer's id as subject, and one
call to the ledger for the second, and that neither happened for the changes in between.

**Acceptance Scenarios**:

- added `features/workflow-sources/consumers.feature`: a consumer publishes once when a workflow ends as completed
- added `features/workflow-sources/consumers.feature`: a consumer calls an entity once when a workflow fails
- added `features/workflow-sources/consumers.feature`: a consumer is handed one change for each record, in order, with its sequence number
- added `features/workflow-sources/consumers.feature`: a deleted workflow runs the consumer's deletion handler
- added `features/workflow-sources/consumers.feature`: a change of a workflow timed out has the standing failed and names the timeout

---

### User Story 3 - A workflow is a source in every language (Priority: P2)

A Python, TypeScript or Rust service declares a view or a consumer over one of its workflows exactly
where it would name an entity, and is handed the same change: the state as the workflow's codec reads
it and the standing. An older runtime that does not know a workflow source refuses the component at
discovery and says which protocol version it needs, rather than starting a view that writes nothing.

**Why this priority**: The polyglot SDKs are the platform's promise that a component is written the
same way in any language; a source only Scala can declare breaks it.

**Independent Test**: The conformance suite gains cases for a view and a consumer over the reference
service's checkout workflow in each SDK, asserting the rows written and the messages published; the
sidecar suite asserts a runtime at the previous protocol version refuses the discovery naming the
version.

**Acceptance Scenarios**:

- added `features/workflow-sources/languages.feature`: a view reads a workflow in every language
- added `features/workflow-sources/languages.feature`: a consumer is handed a failed workflow's standing in every language
- added `features/workflow-sources/languages.feature`: a runtime from before workflow sources refuses a program that declares one
- added `features/workflow-sources/languages.feature`: the topology shows a view connected to the workflow it reads in every language
- added `features/workflow-sources/languages.feature`: the component test kit hands a view a workflow change in every language

---

### User Story 4 - A view over a workflow is rebuilt and bounded as a view over an entity is (Priority: P2)

A developer changes what the row holds and raises the view's version; the view is emptied and reads
every workflow's every record again, so completed checkouts from before the change have rows. The same
developer tries to make one view read a topic and a workflow and is refused when the service starts,
with the reason a topic and an entity are refused.

**Why this priority**: The rules a view's reader relies on — rebuildable, exact, one writer per row —
must hold for a workflow source or a developer cannot trust the listing.

**Independent Test**: Run two checkouts to their ends under version 1; restart the service with the
view at version 2 and a different row shape; assert the table holds only version-2 rows for both.
Register a view over a topic and a workflow and assert the service refuses to start, naming the rule.

**Acceptance Scenarios**:

- added `features/workflow-sources/views.feature`: a view that reads a workflow at a higher version is rebuilt from every record
- added `features/workflow-sources/views.feature`: a restarted view reads no record of a workflow again
- added `features/workflow-sources/views.feature`: a view may not read a topic and a workflow together
- added `features/workflow-sources/views.feature`: a view of several sources reads a workflow beside an entity one change at a time
- added `features/workflow-sources/views.feature`: a deleted workflow runs the view's deletion handler

---

### User Story 5 - The documentation says what a workflow source delivers (Priority: P3)

A developer reads the views and consumers guides and finds a workflow in the table of sources, with
what a change carries and the guarantees it has; the workflows guide says a workflow's standing can be
read as data rather than asked for; the divergences page says ankka delivers the standing where Akka
delivers the state alone.

**Why this priority**: A source nobody can find in the sources table is not there.

**Independent Test**: The documentation features assert the sources tables name a workflow, the
workflows guide links to them, and the divergences page has the row.

**Acceptance Scenarios**:

- added `features/documentation/workflow-sources.feature`: the documentation names a workflow among the sources of a view and a consumer
- added `features/documentation/workflow-sources.feature`: the documentation of workflows says a standing can be read as it changes
- added `features/documentation/workflow-sources.feature`: the documentation says a workflow source delivers the standing beside the state

---

### Edge Cases

- **A record that carries no state.** A `transition`, `pause`, `end`, `fail` or `retry` record holds
  no state; the change still carries the state as it stood after the record, which is the state last
  recorded before it. How the runtime has it in hand — carried on every record from this release,
  or folded from the workflow's own records — is the plan's; the spec requires only that every change
  carries it.
- **Records written before this feature.** A view rebuilt over a workflow journalled by an earlier
  release reads those records too. Each is delivered with the state last recorded before it, as any
  record is; nothing is skipped and nothing is invented.
- **One effect, several records.** A command that updates the state and transitions journals two
  records; a view's row is written twice in a row with the same state and a changed standing, and a
  consumer is handed two changes. A plain view's reader sees the last; a consumer that acts on a
  standing acts on the change that first carries it.
- **A workflow that has not started.** A workflow id that has recorded nothing delivers nothing; a
  view has no row for it until the first command.
- **A deleted workflow created again.** As an entity: the sequence numbers continue, the deletion
  handler ran for the old life, and the new life's changes write the row again.
- **A workflow's state type changes.** The source's decoder is the workflow's own serializer, so a
  state the current codec cannot read fails the change as an entity's event would, and is retried;
  the rules for changing a stored type are the workflows guide's.
- **A paused workflow resumed by a command.** The command's transition is a change whose standing is
  running again; a reader counting pauses counts the `pause` records, not the standings.
- **A workflow timed out.** The engine's timeout journals a failure; the change's standing is failed
  and its reason names the timeout.
- **Several instances.** A plain view over a workflow is sliced across instances as a view over an
  entity is, and a keyed view takes its lock; nothing about a workflow source changes who writes.
- **A view in a module.** A module's view reads a workflow as it reads an entity, through
  `ankka1_view`; the request carries the standing beside the state.

## Requirements *(mandatory)*

### Functional Requirements

**Declaring**

- **FR-001**: A plain view, a keyed view and a consumer MUST be able to declare a workflow as a source,
  in Scala from the workflow's companion, and in Python, TypeScript and Rust where an entity source is
  named today. A keyed view MAY read workflows and entities together.
- **FR-002**: The sidecar protocol MUST accept a `Source.ComponentRef` of kind `WORKFLOW` for a view's
  and a consumer's source at the next protocol version, and a runtime at an earlier version MUST
  refuse the component at discovery, naming the version a workflow source needs.
- **FR-003**: A view that reads a topic and a workflow MUST be refused when the service starts, with
  the reason a topic and an entity are refused today.

**What is delivered**

- **FR-004**: A workflow source MUST deliver one change for each record the workflow journalled —
  state, transition, pause, end, failure, retry — in the order recorded, and a deletion to the
  deletion handler.
- **FR-005**: Every change MUST carry the workflow's state as it stood after the record and its
  standing: running, paused, completed or failed; the step it is on or waits after; retries per step;
  the failure reason when it failed. The standing MUST be what the lifecycle query would answer at
  that point.
- **FR-006**: A change MUST carry the record's sequence number and the workflow's id as its subject,
  as an entity's change does.
- **FR-007**: Records written by a release before this feature MUST be delivered, each with the state
  last recorded before it.

**Guarantees**

- **FR-008**: A view over a workflow MUST write a change's rows and the record of how far it has read
  in one transaction, so each change is applied exactly once; a consumer MUST be handed each change at
  least once and in order.
- **FR-009**: A view over a workflow MUST declare a version as a view over an entity may, and a higher
  version MUST rebuild it from every workflow's first record, under the rules of views that read
  entities.
- **FR-010**: A keyed view that reads a workflow MUST handle one change at a time across every source
  and instance, as it does for entities.

**Showing**

- **FR-011**: The topology MUST show a declared connection from a workflow to each view and consumer
  that reads it as a workflow subscription, in every language, and the local console MUST draw it.

**Testing**

- **FR-012**: Every SDK's view, keyed view and consumer test kits MUST accept a workflow change — a
  state and a standing — so a handler over a workflow is unit-tested as one over an entity is.
- **FR-013**: The conformance suite MUST hold a view and a consumer over the reference service's
  workflow, so each SDK proves what it writes and publishes from a workflow's changes.

**Documentation**

- **FR-014**: The views and consumers guides MUST list a workflow among the sources with what a
  change carries and its delivery; the workflows guide MUST say a standing can be read as data; the
  divergences page MUST say ankka delivers the standing where Akka delivers the state alone.

### Key Entities

- **Workflow change**: what a workflow source hands a view or consumer for one record: the workflow's
  id, the record's sequence number, the state as it stood after the record, and the standing.
- **Standing**: where the engine says a workflow has got to: running, paused, completed or failed; the
  step it is on or waits after; retries per step; the failure reason. The lifecycle query's answer,
  as data in a change.
- **Workflow subscription**: a declared connection from a workflow to a view or consumer that reads
  it, in the topology.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A service lists its workflows by standing — every failed one with its step and reason —
  from a view whose author wrote no standing into any state, in Scala, Python, TypeScript and Rust.
- **SC-002**: A consumer acts on a workflow's end within the service's usual projection delay of the
  end being recorded, and acts exactly as many times as workflows ended.
- **SC-003**: A view over a workflow rebuilt by a higher version holds a row for every workflow that
  ever ran, including those journalled before this release.
- **SC-004**: No existing view, consumer or workflow changes behaviour: every suite that passed before
  passes after, and a workflow's journal is readable by the release before.

## Assumptions

- A workflow's journal is read by the same `eventsBySlices` an entity's is, under the workflow's
  component id as entity type, and its persistence id is `PersistenceId(componentId, workflowId)`, so
  slicing and offsets work as for an entity.
- A workflow is deleted by `effects.delete()` and journals `Deleted`; workflows have no expiry.
- The standing a change carries is derived from the records themselves, so it needs no call to the
  running workflow; the engine's `Run` state is a fold of the same records.
- Adding a field to `WorkflowRecord` is additive: the schema is JSON under ankka's manifest, and a
  release before this feature ignores a field it does not know.
- The sidecar's `ViewRequest` and consumer request can carry the standing beside the payload without
  a new rpc; the protocol version rises to say the field is there.
- A module's `ankka1_view` and `ankka1_consumer` take the same request messages.

## Dependencies

- 031-multi-source-views: a keyed view's sources and its one-at-a-time lock; a workflow is one more
  kind of source under the same rules.
- 024-replayable-topics: view versions and the rebuild, which a view over a workflow uses unchanged.
- 019-service-topology: declared connections, which gain a kind.
- 009-polyglot-runtimes and 016-wasm-hosting: the discovery `Source` and the view and consumer
  requests, which gain the standing.
- 048-awaiting-workflows: independent. A caller with a workflow id waits for it (048); a reader of
  many workflows reads them (this feature). Neither needs the other.

## Open Questions

- Whether a reader should be able to ask for ends only — a source option that delivers a change only
  when the standing changes — to spare a consumer the changes it ignores. Nothing requires it; a
  consumer that ignores a change costs one read.
- Whether the standing should carry how long the workflow has been on its step, for a reader that
  looks for stuck ones; the records carry when each was written, so it is derivable later.
