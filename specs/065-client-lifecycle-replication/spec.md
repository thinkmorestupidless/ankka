# Feature Specification: Every client reads a workflow's standing and how a copy stands

**Feature Branch**: `065-client-lifecycle-replication`

**Created**: 2026-11-10

**Status**: Draft

**Input**: User description: "Closes issue #237: every client reads a workflow's lifecycle and a
copy's replication status. Today only the Scala, Java and Kotlin component clients can ask a workflow
for its lifecycle and ask a replicated event sourced entity's copy for its replication status. The
Python, TypeScript, Rust and Go clients have neither. A service written in Python, TypeScript, Rust
(a WebAssembly module) or Go can read both through its component client, with the same meaning and
the same refusals as the Scala, Java and Kotlin clients. Each SDK's unit and integration test kits
can answer and exercise these reads. The process protocol gains the reads in protocol 1.24; a runtime
older than 1.24 refuses, and the SDK says which protocol the call needs. The conformance suite proves
each language. The three docs pages gain the four languages' tabs from tested samples, the exclusion
comments and the limitation line come out. Out of scope: changing what the Scala, Java or Kotlin
calls answer; new lifecycle states; writes to replication from the four SDKs."

## Context

A workflow's state is its author's type and says what the process has decided, not whether the
engine is still running it: a plan whose state reads "consulting" looks the same whether a model call
is in flight or the workflow failed an hour ago. So every workflow answers a read of its standing the
platform implements itself (the clients' `lifecycle` call): whether it has not started, is running, paused, completed or failed, the
step it waits on, the retries each step has had, and why it failed. Likewise any copy of a replicated
event sourced entity answers, from its own journal, how it stands: its home, its replication set, a
handover waiting to complete, the other home when it is in conflict, and how much of the history the
copy holds.

Only a Scala, Java or Kotlin service can make these reads today. A Python, TypeScript, Rust or Go
service cannot, so the pages that teach them show three languages of seven, and the limitations page
tells the other four to record in their own state whether a workflow is still going, which is the
very workaround the standing read exists to remove. This feature gives the four the same two reads,
answered by the same platform, so a page shows all seven and the limitation goes.

## Clarifications

### Session 2026-11-10

- Q: What do the Python, TypeScript, Rust and Go clients call the two reads? → A: `lifecycle` and
  `replication`, as the Scala, Java and Kotlin calls are, in each language's case. The documentation's
  prose keeps the glossary's word, standing.
- Q: How much of how a copy stands is proved in each of the four languages? → A: Every field, from
  one fixture: per language, a copy's home, replication set and position read end to end, a copy of
  an entity that is not replicated refused end to end, and a handover waiting and a conflict decoded
  through the component test kit. A handover waiting, a conflict and an installation outside the
  replication set end to end stay proved once, in Scala.
- Q: How does Rust's component test kit answer the two reads, since it runs entities and workflows in
  memory? → A: Both ways: from the workflow or the entity it runs, and from an answer a test sets for
  an id, which wins over what it runs.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A service in any language reads a workflow's standing (Priority: P1)

A developer writing a Python, TypeScript, Rust or Go service asks a workflow, through the service's
component client, where the platform says it has got to, and is answered as a Scala service is: the
standing, the step it waits on, the retries per step and the failure's reason. An endpoint can show "failed: the
model provider refused" for a plan whose own state still reads "consulting".

**Why this priority**: the standing read is the one the limitations page sends people around today,
and the multi-agent planner, the guide's own sample of an agent workflow, depends on it.

**Independent Test**: in each of the four languages, start a workflow, read its standing while it
runs, after it pauses, after it completes, and after a step fails for good, and compare each answer
with what the Scala client reads for the same workflow.

**Acceptance Scenarios**:

- added `features/polyglot/standing.feature`: a handler reads a running workflow's standing in every language
- added `features/polyglot/standing.feature`: a handler reads a failed workflow's standing with the reason and the retries in every language
- added `features/polyglot/standing.feature`: a handler reads the standing the platform records for a workflow in every language

---

### User Story 2 - A service in any language reads how a copy of a replicated entity stands (Priority: P2)

A developer writing a Python, TypeScript, Rust or Go service reads, through the component client, the
replication status of a replicated event sourced entity's copy at this installation: the home, the
replication set, a handover waiting, a conflict, and the position its history reaches. The answer and
the refusals are the Scala client's.

**Why this priority**: replication is newer and used by fewer services than workflows; it completes
the same gap on the same pages.

**Independent Test**: in each of the four languages, read the status of a replicated entity's copy
and compare it with what the Scala client reads for the same copy; read one that is not replicated
and compare the refusal.

**Acceptance Scenarios**:

- added `features/replication/status.feature`: a handler reads the home, the replication set and the position of a copy in every language
- added `features/replication/status.feature`: a handler reads a handover waiting
- added `features/replication/status.feature`: a handler reads a conflict
- added `features/replication/status.feature`: a handler reading how a copy of an entity that is not replicated stands is refused in every language
- added `features/replication/status.feature`: an installation outside the replication set answers with the copy it last held

---

### User Story 3 - A developer tests these reads without a cluster (Priority: P2)

A developer tests a component that reads a standing or a replication status with the language's
own unit test kit, telling the kit what to answer, and with the integration test kit, against a real
runtime.

**Why this priority**: a call a developer cannot test is a call they will not use; every client call
in the four SDKs is answerable in both kits.

**Independent Test**: in each language, a unit test of an endpoint that reads a standing passes with
the kit answering a failed standing, and an integration test reads a real workflow's standing.

**Acceptance Scenarios**:

- added `features/polyglot/standing.feature`: the component test kit answers a workflow's standing it was told
- added `features/polyglot/standing.feature`: the Rust component test kit answers the standing of a workflow it runs
- added `features/polyglot/standing.feature`: an answer a Rust component test kit was told wins over the workflow it runs
- added `features/polyglot/standing.feature`: the component test kit refuses a standing it was not told
- added `features/polyglot/standing.feature`: the test kit reads a workflow's standing from the running service
- added `features/replication/status.feature`: the component test kit answers how a copy stands as it was told
- added `features/replication/status.feature`: the component test kit answers a handover waiting and a conflict as it was told
- added `features/replication/status.feature`: the Rust component test kit answers how a copy stands from the entity it runs
- added `features/replication/status.feature`: the test kit reads how a copy stands from the running service

---

### User Story 4 - A service on an older runtime is told why the read fails (Priority: P3)

A developer whose service runs against a runtime older than the protocol that carries these reads
calls one, and is told in the error which protocol it needs, not a bare "unimplemented".

**Why this priority**: it only bites during an upgrade, but then it decides whether the person
understands the failure.

**Independent Test**: point an SDK at a runtime that answers the read as unimplemented and check the
error names protocol 1.24.

**Acceptance Scenarios**:

- added `features/polyglot/standing.feature`: a runtime from before the read refuses a workflow's standing, naming the protocol version
- added `features/replication/status.feature`: a runtime from before the read refuses how a copy stands, naming the protocol version

---

### User Story 5 - The pages show every language (Priority: P3)

A reader of the workflows, multi-agent orchestration and event sourced entities pages sees the
standing and replication samples in all seven languages, each from code a test compiles and runs,
and the limitations page no longer says only three languages can read them.

**Why this priority**: the pages are how people learn the call exists; they follow the code.

**Independent Test**: build the documentation; the three sections carry seven tabs, the documentation
check passes with no exclusion comment on them, and the limitation line is gone.

**Acceptance Scenarios**:

- added `features/documentation/workflow-sources.feature`: the documentation shows a workflow's standing read in every language
- added `features/documentation/replicated-entities.feature`: the documentation shows how a copy stands read in every language

### Edge Cases

- A workflow that was deleted after it ended: its standing reads as the Scala client reads it.
- A workflow read while a step is running: the read does not wait for the step; it sees the last
  journalled transition, as any query of a workflow does.
- A replication status read at an installation outside the replication set: answered with the copy
  that installation last held, as the Scala client reads it.
- A read of a standing or a replication status of a component the service does not have, or of a
  component of the wrong kind (a standing of an entity, a replication status of a workflow): refused
  before any call is made where the SDK can tell, otherwise refused by the platform as Scala's is.
- A replicated entity in conflict: the read is still answered; reads are not refused by a conflict.
- An integration test kit or a unit test kit told nothing about a read: it refuses with an error that
  says the read was not stubbed, as each kit does for any unstubbed call.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The Python, TypeScript, Rust and Go component clients MUST each have a call named
  `lifecycle`, in the language's case, that reads a workflow's standing by the workflow's component and id, answering the standing, pending step,
  retries per step and failure reason.
- **FR-002**: The four clients MUST each have a call named `replication`, in the language's case,
  that reads a replicated event sourced entity's
  replication status by the entity's component and id, answering its home, replication set, pending
  handover, conflict and position.
- **FR-003**: Each answer MUST carry the same values the Scala client reads for the same workflow or
  copy at the same moment, and each refusal the same error code.
- **FR-004**: The standing MUST read as one of not started, running, paused, completed and
  failed, in each language's idiom, with no new status added.
- **FR-005**: Both reads MUST be reads: they run no handler of the developer's, change nothing and
  journal nothing.
- **FR-006**: The process protocol MUST carry both reads from protocol 1.24, and the runtime MUST
  declare 1.24.
- **FR-007**: A read against a runtime older than 1.24 MUST fail with an error that names protocol
  1.24 as the one it needs.
- **FR-008**: A Rust module MUST make both reads through the module's host calls, with the same
  answers and refusals as a process.
- **FR-009**: Each of the four SDKs' component test kits MUST let a test say what each read answers
  for a given component and id, and refuse a read nothing was said about. Rust's kit, which runs
  workflows and entities in memory, MUST also answer from the workflow or entity it runs, and an
  answer a test set for an id MUST win over it.
- **FR-010**: Each of the four SDKs' test kits MUST make both reads against the running service.
- **FR-011**: The conformance suite MUST prove, for each of the four languages, a standing read of a
  running and of a failed workflow, and the home, replication set and position of a replicated copy
  and the refusal of a copy that is not replicated, each against the Scala client's answer. A
  handover waiting and a conflict MUST be proved decoded in each language's component test kit; end
  to end they are proved once, in Scala.
- **FR-012**: The workflows page's "Domain state and lifecycle", the multi-agent orchestration page's
  standing sample and the event sourced entities page's "Reading how a copy stands" MUST show all
  seven languages from tested samples, with their exclusion comments removed.
- **FR-013**: The limitations page MUST no longer say that only a Scala, Java or Kotlin client reads a
  workflow's standing or a copy's replication status.
- **FR-014**: What the Scala, Java and Kotlin calls answer MUST NOT change.

### Key Entities

- **Standing**: where the platform says a workflow has got to with one workflow: a status (not started,
  running, paused, completed, failed), the step it waits on if any, the number of retries each step
  has had, and the failure's reason if it failed.
- **Replication status**: how one copy of a replicated entity stands, from that copy's own journal:
  the home, the replication set, the installation a handover goes to if one is waiting, the other
  home if the entity is in conflict, and the position its history reaches.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In each of the four languages, every standing scenario of User Story 1, and the read
  and the refusal of User Story 2, read the same values as the Scala client for the same workflow or
  copy: all equal, none skipped.
- **SC-002**: The three documentation sections show 7 language tabs each, every one from a sample a
  test compiles and runs, and the documentation check passes.
- **SC-003**: A read against a runtime older than 1.24 fails, in each of the four languages, with an
  error naming protocol 1.24.
- **SC-004**: No test of the Scala, Java or Kotlin standing or replication reads changes.
- **SC-005**: The limitations page names no language that cannot read a standing or a replication
  status.

## Assumptions

- The two reads are already answered by the runtime for any workflow and any replicated entity's
  copy, under reserved names no developer handler can take; this feature carries them to the four
  SDKs and does not change how they are answered.
- A Rust service is a WebAssembly module and makes calls through host imports; Python, TypeScript and
  Go services are processes beside a sidecar and make calls through the callback protocol. Both paths
  gain the reads.
- Each language names the standings and the answers' fields in its own idiom (snake case
  in Python and Rust, camel case in TypeScript, exported fields in Go), as each already names the
  other answers it reads.
- Waiting for a handover to complete and asking for one stay with the Scala, Java and Kotlin clients;
  they are writes or waits, out of scope here.
- A view or a consumer reading a workflow is already handed the standing with each state (protocol
  1.16); that stays as it is.
