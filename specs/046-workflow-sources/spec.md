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

- **A workflow is a source, declared as an entity is.** `ChangeSource.stateOf(CheckoutWorkflow)` in
  Scala, the key value entity's verb overloaded on a workflow companion; `source = CheckoutWorkflow`, `static readonly source = CheckoutWorkflow` and
  `Source::of(Checkout)` where a process or a module already names an entity. A plain view, a keyed
  view and a consumer may each read one, and a keyed view may read workflows beside entities. The
  sidecar's `Source.ComponentRef` with kind `WORKFLOW` is accepted at the next protocol version, and a
  runtime below it refuses the component at discovery, naming the version, as it refuses any unknown
  source.
- **A change is a state the workflow recorded, stamped with the standing.** As in Akka, a workflow
  source delivers the workflow's state each time the workflow records it, and nothing for a transition,
  pause, end, failure or retry that records no state. Unlike Akka, each change also carries the
  **standing** as it stands once the whole effect that recorded the state is applied: running, paused,
  completed or failed, the step it is on or waits after, its retries and, when it failed, why — what
  `WorkflowLifecycle` answers, delivered rather than asked for. The engine knows the effect's outcome
  when it writes the `state` record, so `updateState(s).thenEnd` is one change whose standing is
  completed, and a compensation step that records the failure in the state and fails the workflow is one
  change whose standing is failed. A handler is handed the state as its change, and reads the standing
  from the change's context beside the subject and the sequence number; a view that lists only states
  never looks.
- **In order, exactly once for a view.** A workflow's journal is an event journal, so a workflow source
  has an event sourced source's guarantees: every state in the order the workflow recorded it, a view's
  row and its offset written in one transaction, a consumer at least once. What records no state is
  invisible to a reader, as it is in Akka; the documentation says a step whose failure must be seen
  records it in the state, and a caller that wants to know a workflow ended whatever its last step did
  awaits it (048).
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

## Clarifications

### Session 2026-10-10

- Q: What is the word for where a workflow has got to (running, paused, completed or failed, the step,
  retries, the reason)? → A: **standing**. The glossary holds *lifecycle* to the deployed service's word
  and *status* to a gRPC call's; the Scala class `WorkflowLifecycle` keeps its name for now and the
  documentation calls what it answers the standing.
- Q: Which records are changes: every record, every record with an opt-in filter, only those that
  change the state or the status, or state updates alone as Akka does? → A: **State updates alone, as
  Akka does, each stamped with the standing as it stands once the whole effect is applied** (D2). A
  `state` record is the one change; the engine knows the effect's outcome when it writes the record, so
  `updateState(s).thenEnd` delivers one change whose standing is completed. A transition, pause, end,
  failure, retry or timeout that updates no state delivers nothing, and a step that wants its failure
  seen records it in the state in its compensation. A `state` record from before this release carries
  a standing of unknown. This avoids carrying or folding the state for records that hold none.
- Q: Is the topology's connection from a workflow to a view or consumer a new kind, "workflow
  subscription", or the existing "state subscription"? → A: **workflow subscription**, a kind of its
  own, so the topology and the console tell a workflow's readers from a key value entity's at a glance.
- Q: Is the Scala declaration a new verb, `changesOf(Workflow)`, or the key value entity's
  `stateOf(Workflow)`? → A: **`stateOf(Workflow)`**, overloaded on a workflow companion: the change
  type is the workflow's state, and the standing sits on the change's context beside the subject and
  the sequence number, as a handler reads them today.
- Q: What standing does a `state` record from before this release carry: unknown as a fifth value, no
  standing at all, or one derived by reading the old records forward? → A: **unknown**, a standing
  value a handler can switch on; a rebuild over old journals shows it until each workflow next records
  a state, and the documentation says so. Nothing is derived or invented.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A view lists where every checkout stands (Priority: P1)

An operator wants a page of checkouts: open, paused waiting for approval, completed, failed and why.
The developer declares a view that reads the `checkout` workflow and writes one row per checkout from
the change's state and standing, and a declared query `by-standing` that takes a standing. The page
asks it with "failed" and gets every checkout whose compensation recorded its failure, with the reason,
and with "completed" every checkout that ended — none of which the workflow's author wrote into the
state as a status field.

**Why this priority**: This is the feature: a workflow's progress as data the service can read, without
every author copying the engine's words into their own state before each transition.

**Independent Test**: In the test kit, run a checkout to its end and a second one to a failure whose
compensation records the state; read the view's rows and the `by-standing` query; assert the rows carry
the final states and the standings the lifecycle query answers, and that the failed row holds the
reason. Run a third that fails without recording its state and assert no change arrives for the
failure.

**Acceptance Scenarios**:

- added `features/workflow-sources/views.feature`: a row holds the state a workflow ended with and the standing completed
- added `features/workflow-sources/views.feature`: a row of a workflow whose compensation recorded its failure has the standing failed and the reason
- added `features/workflow-sources/views.feature`: a workflow that fails without recording its state delivers no change
- added `features/workflow-sources/views.feature`: a row of a paused workflow names the step it waits after
- added `features/workflow-sources/views.feature`: a row holds the last state recorded and the step the workflow moved to with it
- added `features/workflow-sources/views.feature`: a declared query lists the rows of one standing

---

### User Story 2 - A consumer reacts when a workflow ends (Priority: P1)

When a transfer workflow completes, a notice must be published to the `transfers-settled` topic; when
it fails, a ledger entity must be told. The developer declares a consumer that reads the `transfer`
workflow, ignores every change whose standing is running or paused, publishes on completed and calls
the ledger on failed; the transfer's compensation step records the failure in the state so the consumer
sees it. Nothing else in the workflow knows the consumer exists.

**Why this priority**: Reacting to the end of a process is the commonest reason to read one, and today
the only ways are polling or a step that does the publishing itself.

**Independent Test**: With the in-memory broker, run a transfer to its end and another to a failure;
assert one message on `transfers-settled` for the first, with the transfer's id as subject, and one
call to the ledger for the second, and that neither happened for the states recorded in between.

**Acceptance Scenarios**:

- added `features/workflow-sources/consumers.feature`: a consumer publishes once when a workflow ends as completed
- added `features/workflow-sources/consumers.feature`: a consumer calls an entity once when a workflow records its failure
- added `features/workflow-sources/consumers.feature`: a consumer is handed one change for a state recorded with a transition, stamped with the step moved to
- added `features/workflow-sources/consumers.feature`: a deleted workflow runs the consumer's deletion handler
- added `features/workflow-sources/consumers.feature`: a workflow that times out without recording its state delivers no change

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

- added `features/workflow-sources/views.feature`: a view that reads a workflow at a higher version is rebuilt from every recorded state
- added `features/workflow-sources/views.feature`: a state recorded before the platform stamped standings is delivered with the standing unknown
- added `features/workflow-sources/views.feature`: a restarted view reads no state of a workflow again
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

- **A record that carries no state.** A `transition`, `pause`, `end`, `fail` or `retry` record is not
  a change. A workflow that ends, fails, times out or pauses without recording its state is invisible
  to a reader until it next records one; the documentation says so, and says a step whose outcome must
  be seen records it.
- **One effect, several records.** A command or step that updates the state and transitions, pauses,
  ends or fails journals the `state` record first and the rest after it; the one change is the `state`
  record, stamped with the standing after the whole effect, so a reader never sees a state with a
  standing the effect went on to change.
- **Records written before this feature.** A `state` record from an earlier release carries no stamp
  (FR-007). A rebuild over old journals holds a row for every workflow that recorded a state, with the
  standing `Unknown` until each next records one.
- **A workflow that has not started.** A workflow id that has recorded nothing delivers nothing; a
  view has no row for it until the first command.
- **A deleted workflow created again.** As an entity: the sequence numbers continue, the deletion
  handler ran for the old life, and the new life's changes write the row again.
- **A workflow's state type changes.** The source's decoder is the workflow's own serializer, so a
  state the current codec cannot read fails the change as an entity's event would, and is retried;
  the rules for changing a stored type are the workflows guide's.
- **A paused workflow resumed by a command.** A change only if the command updates the state; its
  standing is then running, on the step the command chose.
- **A workflow timed out.** The engine's timeout journals a failure and no state, so no change arrives;
  a workflow whose timeout must be seen declares a failover step that records it.
- **Several instances.** A plain view over a workflow is sliced across instances as a view over an
  entity is, and a keyed view takes its lock; nothing about a workflow source changes who writes.
- **A view in a module.** A module's view reads a workflow as it reads an entity, through
  `ankka1_view`; the request carries the standing beside the state.

## Requirements *(mandatory)*

### Functional Requirements

**Declaring**

- **FR-001**: A plain view, a keyed view and a consumer MUST be able to declare a workflow as a source,
  in Scala as `ChangeSource.stateOf` of the workflow's companion, and in Python, TypeScript and Rust
  where an entity source is named today. The change type is the workflow's state type; the standing is
  read from the change's context. A keyed view MAY read workflows and entities together.
- **FR-002**: The sidecar protocol MUST accept a `Source.ComponentRef` of kind `WORKFLOW` for a view's
  and a consumer's source at the next protocol version. The gate holds from both ends, as socket
  routes' does: a runtime at an earlier version MUST refuse the component at discovery, naming the
  version a workflow source needs, and a runtime at this version MUST refuse a workflow source from a
  program whose SDK states an earlier version, naming the component and both versions, because an
  older SDK would otherwise be handed changes with no standing and no error.
- **FR-003**: A view that reads a topic and a workflow MUST be refused when the service starts, with
  the reason a topic and an entity are refused today.

**What is delivered**

- **FR-004**: A workflow source MUST deliver one change for each state the workflow recorded, in the
  order recorded, and a deletion to the deletion handler; it MUST deliver nothing for a transition,
  pause, end, failure or retry that records no state.
- **FR-005**: Every change MUST carry the state the workflow recorded and its standing as it stands
  once the whole effect that recorded the state is applied: not started, running, paused, completed
  or failed; the step it is on or waits after; retries per step; the failure reason when it failed.
  For a `state` record written by this release the standing's status, retries and failure MUST be
  what the lifecycle query would answer once that effect is applied. The step is the query's answer
  while the workflow runs; for a paused workflow the standing MUST name the step the pause's timeout
  names or, when it names none, the step paused after, which the lifecycle query cannot answer after
  a recovery and the engine has in hand when it records the pause. A `state` record written before
  this release is FR-007's.
- **FR-006**: A change MUST carry the record's sequence number and the workflow's id as its subject,
  as an entity's change does.
- **FR-007**: `state` records written by a release before this feature MUST be delivered with their
  state and the standing `Unknown`; nothing is skipped and no standing is invented. `Unknown` is a
  status word a handler can switch on beside the five the lifecycle query answers.

**Guarantees**

- **FR-008**: A view over a workflow MUST write a change's rows and the record of how far it has read
  in one transaction, so each change is applied exactly once; a consumer MUST be handed each change at
  least once and in order.
- **FR-009**: A view over a workflow MUST declare a version as a view over an entity may, and a higher
  version MUST rebuild it from every workflow's first recorded state, under the rules of views that
  read entities. A version on a consumer over a workflow MUST be refused when the service starts, as
  one on a consumer over an entity is.
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
  change carries and its delivery, and say that what records no state delivers nothing; the workflows
  guide MUST say a standing can be read as data and that a step whose outcome must be seen records it
  in the state; the divergences page MUST say ankka delivers the standing where Akka delivers the state
  alone.

### Key Entities

- **Workflow change**: what a workflow source hands a view or consumer for one state the workflow
  recorded: the workflow's id, the record's sequence number, the state, and the standing once the
  effect that recorded it is applied.
- **Standing**: where the engine says a workflow has got to: not started (a state recorded before any
  transition), running, paused, completed or failed; the step it is on or waits after; retries per
  step; the failure reason. The lifecycle query's answer, as data in a change; `Unknown` on a state
  recorded before this release.
- **Workflow subscription**: a declared connection from a workflow to a view or consumer that reads
  it, in the topology.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A service lists its workflows by standing — every completed one, and every failed one
  whose compensation recorded its state, with the reason — from a view whose author wrote no status
  into any state, in Scala, Python, TypeScript and Rust.
- **SC-002**: A consumer acts on a workflow's end within the same bound an entity-sourced consumer
  meets in the platform's own feature suites (their `eventually` default), measured from the end's
  record to the consumer's publication in the integration suite, and acts exactly as many times as
  workflows ended.
- **SC-003**: A view over a workflow rebuilt by a higher version holds a row for every workflow that
  ever recorded a state, including those journalled before this release.
- **SC-004**: No existing view, consumer or workflow changes behaviour: every suite that passed before
  passes after, and a workflow's journal is readable by the release before.

## Assumptions

- A workflow's journal is read by the same `eventsBySlices` an entity's is, under the workflow's
  component id as entity type, and its persistence id is `PersistenceId(componentId, workflowId)`, so
  slicing and offsets work as for an entity.
- A workflow is deleted by `effects.delete()` and journals `Deleted`; workflows have no expiry.
- The engine builds the whole list of records an effect persists before persisting any, so it knows
  the standing after the effect when it writes the `state` record, and stamping it needs no call and no
  fold.
- Adding an optional field to the `state` kind of `WorkflowRecord` is additive: the journal is
  Jackson CBOR under the `AnkkaSerializable` binding, which reads a missing `Option` as none and
  ignores a property it does not know, so a release before this feature reads the new form and this
  release reads the old. Records of every other kind are unchanged. Both directions are pinned by a
  compatibility suite.
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

- Whether the standing should carry how long the workflow has been on its step, for a reader that
  looks for stuck ones; the records carry when each was written, so it is derivable later.
