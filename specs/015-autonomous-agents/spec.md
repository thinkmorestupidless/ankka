# Feature Specification: Autonomous Agents — Tasks and the Durable Agent Loop

**Feature Branch**: `015-autonomous-agents`

**Created**: 2026-09-28

**Status**: Draft

**Input**: User description: "Autonomous agents, phase 1: tasks and the durable agent loop. Add a
second kind of agent to ankka, modelled on Akka's Autonomous Agent
(https://doc.akka.io/sdk/autonomous-agents.html), alongside today's request/response agents, which
stay as they are. […] Success looks like: a developer writes a question-answering autonomous agent
with a couple of tools, runs a task against it from an endpoint, reads the typed result later, sees
its progress over SSE, kills the service mid-task and watches it finish after restart — in Scala and
in Python, with a unit test using the scripted model."

## Context

An ankka agent today is a request/response component. A caller names a session, sends a message,
and waits while the platform runs one turn: the model is called, the tools it asks for are run, the
model is called again until it stops asking, and the reply — text, a token stream, or a decoded
value — goes back to the caller. Session memory is written only once that reply has passed the
output guardrails, so a turn is all-or-nothing: a process that dies half-way through a turn loses
the tool rounds already paid for, and the caller sees a failure. Work that spans several turns is
the caller's problem, and the documented answer is a workflow — the multi-agent planner sample
consults a selector and then several specialists from workflow steps, each step a durable commit
point, precisely because an agent's own turn is not one.

That shape is right for a chat message and wrong for a job. "Answer this question, using these
tools, and give me a structured answer when you are done" has no natural caller waiting on the
other end of a socket; it has a *result* someone reads later, progress someone might watch, and a
budget after which it should give up. Writing a workflow to loop over an agent's turns reimplements
the loop the platform already runs, badly, and every developer who wants an agent that works
unattended writes it again.

Akka's answer is a second component, the **autonomous agent**, and this feature brings it to ankka
in its first phase. The two kinds sit side by side; nothing about today's agents changes.

- A **task** is a durable record with its own identity, created by whoever wants the work done and
  outliving the agent that does it. It has a type — a name, a description, the shape of its result,
  and optional rules a result must satisfy — plus instructions, optional attachments, and
  dependencies on other tasks. It moves through pending, assigned, in progress, and one of
  completed, failed or cancelled; a result a rule rejects sends it back to the model with the reason.
- An **autonomous agent** is a component declared by its definition: what it is for, how it should
  behave, which tools and guardrails it has, which model it uses, and which task types it accepts,
  each with an iteration budget. An instance is named by an id the caller chooses, works one task
  at a time, queues the rest, and *iterates*: it calls the model, runs the tools the model asks
  for, records the iteration, and goes round again until the model calls a built-in tool to
  complete the task with a typed result or to say it cannot. Every iteration is saved, so an
  instance that dies mid-task resumes where it stopped, on any node.
- A **client** runs a task and reads the result later, assigns tasks to an existing instance,
  suspends and resumes it, terminates it, asks it where it is, and subscribes to a live stream of
  what it is doing — which an endpoint can forward to a browser as server-sent events.

Three things make this a small feature rather than a large one:

- **The loop exists.** The platform already assembles a model request from instructions, history,
  context and tools; already dispatches tool calls, runs guardrails and counts tokens; already
  answers from a scripted model in tests. The autonomous loop is that loop with a different
  stopping rule and a journal entry per round.
- **Durable records are the platform's native material.** A task is an event sourced entity. An
  instance's working history is session memory, which is an event sourced entity already, with
  compaction and durability that come for free. A workflow is an event sourced entity whose events
  are step transitions, and an autonomous agent's iterations are the same shape with the model
  choosing the next step.
- **Python and TypeScript already run the loop in the sidecar.** The other process is asked to
  plan a request, run a tool and check a guardrail; the sidecar does everything else. An autonomous
  agent asks it for two of those and one more: to run a tool, to check a guardrail, and to check a
  task rule — its definition is declared once, so there is nothing to plan per task.

What this phase is *not* is multi-agent coordination. Akka's autonomous agents can delegate
subtasks to workers, hand a task on to a specialist, lead a team over a shared backlog, and
moderate a conversation between participants. Each of those is a capability that exposes tools to
the model and a runtime behaviour behind them, and each is built on the task model and the loop
this phase delivers. The task record and the agent definition must leave room for them — a task
already has an assignee that can change, and a definition already has a list of capabilities — but
none of them ships here.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A task is run and its typed result read later (Priority: P1)

A developer declares a task type — "answer a question", whose result is an answer with a
confidence — and an autonomous agent that accepts it, with instructions and a couple of tools. An
endpoint creates a task with the question as its instructions and asks the agent to run it; the
call returns a task id at once. The agent iterates: it calls the model, runs the tools the model
asks for, and goes round again until the model calls the built-in completion tool with an answer.
The endpoint, or anyone else holding the id, reads the task and finds it completed with the typed
result; a caller that would rather wait can block until the task reaches a terminal state.

**Why this priority**: This is the component. Without a task that runs to a typed result nothing
else in the feature has anything to observe, suspend or resume.

**Independent Test**: In a unit test with the scripted model: script two tool calls and a
completion, run a task, wait for the terminal state, assert the result decodes to the declared
type and the tools were called with the model's arguments. In an integration test against a real
service: post a question to an endpoint, poll the task, and read the answer.

**Acceptance Scenarios**:

1. **Given** an agent that accepts the "answer" task type, **When** a caller runs a task with
   instructions, **Then** the call returns a task id before any model call has been made, and the
   task is recorded as assigned to a new instance.
2. **Given** a running task, **When** the model calls the built-in completion tool with a result
   that conforms to the task type's result shape, **Then** the task is completed, the result is
   readable by the task id, and the instance's iteration count and token usage are recorded.
3. **Given** a running task, **When** the model calls the built-in failure tool with a reason,
   **Then** the task is failed with that reason and no further model call is made for it.
4. **Given** a running task, **When** the model calls the completion tool with a result that does
   not conform to the result shape, **Then** the task is not completed, the decoding error goes back
   to the model as the tool's result, and the next iteration proceeds.
5. **Given** a completed task, **When** a caller reads it, **Then** it sees the status, the typed
   result, when it was created, started and completed, and which instance completed it.
6. **Given** a task that is not yet terminal, **When** a caller blocks for its result, **Then** the
   call returns when the task completes, fails or is cancelled, or fails with a timeout the caller
   chose.
7. **Given** a task in a Python service, **When** the same scenario runs, **Then** the task record,
   the iteration loop and the result live in the sidecar, and the Python process is asked only to
   plan, to run a tool and to check a guardrail or rule.

---

### User Story 2 - Work survives the process (Priority: P1)

A task is half-way through — three iterations recorded, the model has just asked for a tool — when
the service's process is killed. When the service comes back, the instance resumes on whichever
node now hosts it, picks up the task where its journal says it was, and finishes it. The caller
reading the task sees it complete. No iteration that was recorded is paid for twice.

**Why this priority**: Durability is the whole reason to have a second component; a loop that
restarts from the beginning is what a workflow around a request agent already gives.

**Independent Test**: In the whole-service test kit: script a long task, restart the service after
the second iteration is recorded, and assert the task completes with the scripted model having been
called exactly once per recorded iteration plus the remainder. In a real cluster: kill the process
mid-task and watch the task finish.

**Acceptance Scenarios**:

1. **Given** an instance with a task in progress, **When** its process dies and the service
   restarts, **Then** the instance resumes the task from its last recorded iteration and completes
   it without the caller doing anything.
2. **Given** an instance with a task in progress and two tasks queued, **When** the process dies
   and restarts, **Then** the queued tasks are still queued, in order, and are run after the current
   one.
3. **Given** the model has asked for tools and the process dies after a tool has run but before the
   iteration is recorded, **When** the instance resumes, **Then** it runs those tools again from the
   recorded model response rather than calling the model again, and the documentation says a tool
   may therefore run more than once.
4. **Given** a rolling deployment of the service, **When** an instance moves between nodes, **Then**
   its task continues on the new node and no iteration is lost or duplicated.

---

### User Story 3 - The budget stops a task that will not finish (Priority: P1)

A task type carries the number of iterations an agent may spend on one task. As the agent nears
it, the model is told how many iterations remain. If the budget runs out, the task fails with a
reason saying so, the agent moves on to its next queued task, and an observer was warned before it
happened.

**Why this priority**: An unattended loop with no ceiling is a bill with no ceiling. The budget is
what makes running an agent unattended safe enough to do.

**Independent Test**: Script a model that never completes; run a task with a budget of three;
assert the task fails after the third iteration with a reason naming the budget, the model's last
request said how many iterations remained, and the next queued task starts.

**Acceptance Scenarios**:

1. **Given** a task type with a budget of N iterations, **When** the model has neither completed
   nor failed the task after N iterations, **Then** the task is failed with a reason naming the
   budget and the instance starts its next queued task.
2. **Given** a task nearing its budget, **When** the model is called, **Then** the request tells it
   how many iterations remain.
3. **Given** a task that fails for any reason, **When** the instance has other tasks queued,
   **Then** they run unaffected, in order.
4. **Given** a task failed by the budget, **When** a caller reads it, **Then** the status is failed,
   the reason names the budget, and no result is present.

---

### User Story 4 - A result is held to the task's rules (Priority: P2)

A task type declares rules a result must satisfy beyond having the right shape — an answer must
cite a source, a summary must be under a length. When the model completes a task with a result a
rule rejects, the task is not completed; it is marked result-rejected, the reason is put in front
of the model, and the next iteration gives it the chance to try again, within the same budget.

**Why this priority**: The shape check catches the wrong type; a rule catches the wrong content. It
is what turns "the model said it was done" into "the work meets the bar".

**Independent Test**: Declare a rule that rejects an empty source list; script a completion without
sources and then one with; assert the task passed through result-rejected, the second model
request carried the rejection reason, and the task completed with the second result.

**Acceptance Scenarios**:

1. **Given** a task type with a rule, **When** the model completes the task with a result the rule
   rejects, **Then** the task's status is result-rejected with the rule's reason, and the reason is
   in the next model request.
2. **Given** a task type with several rules, **When** a result is checked, **Then** the rules run in
   declaration order and the first rejection is the one reported.
3. **Given** a result-rejected task, **When** the model completes it with a result every rule
   accepts, **Then** the task is completed with that result.
4. **Given** a task in a Python service whose rule is written in Python, **When** the model
   completes the task, **Then** the sidecar asks the Python process to check the rule and acts on
   its answer, as it does for a guardrail.

---

### User Story 5 - An instance is driven from outside (Priority: P2)

An operator's endpoint, or another component, needs more than "run one task": assign several tasks
to a named instance and have them worked in order; pause an instance at the end of its current
iteration and resume it later; terminate an instance for good; and ask an instance what it is doing
— its phase, whether it is suspended, its token usage, the task it is working and the tasks
waiting.

**Why this priority**: These are what make an autonomous agent operable rather than merely
startable, and they are what a UI or a scheduler builds on.

**Independent Test**: Assign three tasks to an instance; assert they run in order and the state
query lists the current and the queued ones; suspend it mid-task and assert no model call is made
until resume; terminate it and assert the tasks it had are no longer assigned, its id refuses new
tasks, and the task records still exist.

**Acceptance Scenarios**:

1. **Given** an instance with a caller-chosen id, **When** several tasks are assigned to it,
   **Then** they are worked one at a time in the order assigned, each waiting for its dependencies.
2. **Given** an instance working a task, **When** it is suspended, **Then** the current iteration
   finishes, no further model call is made, the task stays in progress, and queued tasks stay
   queued; **When** it is resumed, **Then** work continues from the next iteration.
3. **Given** an instance, **When** it is terminated, **Then** it stops at the next iteration
   boundary, its current and queued tasks return to pending with no assignee and a note that their
   assignee was terminated, the task records remain readable, and the instance id refuses any
   further assignment permanently.
4. **Given** any instance, **When** its state is queried, **Then** the answer gives its phase (idle,
   working, suspended, terminated), the current task id if any, the queued task ids in order, the
   iterations spent on the current task, and its token usage in total.
5. **Given** an instance working a task with two more queued, **When** a caller cancels the task
   in progress, **Then** the instance stops it at the end of the current iteration, the task is
   cancelled with the caller's reason, and the next queued task starts; **When** a caller cancels a
   queued task, **Then** it leaves the queue at once and the others keep their order.
6. **Given** an instance that has finished every task it was given, **When** it is left alone,
   **Then** it consumes no resources beyond its journal until it is next addressed, and addressing
   it again brings it back with its state intact.

---

### User Story 6 - Progress is watched live (Priority: P2)

A developer's endpoint subscribes to an instance's notifications and forwards them to a browser as
server-sent events. The viewer sees the instance activate, each iteration start and finish, each
task assigned, started and completed, a result rejected and why, and a warning when a task is
approaching its budget or has failed several iterations in a row. Subscribing shows what happens
from now on; what happened before is read from the task's own record.

**Why this priority**: An unattended process that cannot be watched is one nobody trusts to run
unattended. This is also the shape a console and a scheduler will consume later.

**Independent Test**: Subscribe before running a task with a scripted model that rejects once and
then completes; assert the notifications arrive in order — activated, task assigned, task started,
iteration started, iteration completed, result rejected, iteration started, iteration completed,
task completed, deactivated — and that a subscriber joining after the second iteration sees none
of the first.

**Acceptance Scenarios**:

1. **Given** a subscriber to an instance's notifications, **When** the instance works a task,
   **Then** the subscriber receives, in order, every lifecycle, task and struggle event the
   instance emits, each carrying the instance id, the task id where one applies, and when it
   happened.
2. **Given** a subscriber that joins mid-task, **When** it subscribes, **Then** it receives only
   events from that moment on, and no replay.
3. **Given** a task at 80% of its iteration budget, **When** the next iteration starts, **Then** an
   approaching-budget event is emitted once, and not again for that task unless the task is
   result-rejected and resumes.
4. **Given** an endpoint that forwards notifications over server-sent events, **When** a browser
   connects, **Then** each event is one server-sent event whose `data` field is a JSON string
   containing the notification's JSON — the platform's rule for every server-sent event, so a
   reader parses the field and then the notification.
5. **Given** a task in a Python service, **When** an endpoint in that service subscribes, **Then**
   it receives the same notifications from the sidecar.

---

### User Story 7 - It can be tested with a script (Priority: P2)

A developer writes a unit test for their autonomous agent with the scripted model the testkit
already offers. The script can complete a task with a typed result, fail it with a reason, answer
differently depending on what the model was asked or what a tool returned, and the test can wait
for the task to reach a terminal state. If the agent asks the model for more than the script
holds, the test fails loudly rather than the task quietly stalling.

**Why this priority**: The feature is not done until the sample that demonstrates it has a test,
and every developer's first autonomous agent needs one.

**Independent Test**: The question-answering sample's own suite: scripted tool calls, a scripted
rejection and completion, a scripted failure, a budget exhaustion, a restart — each a test of a few
lines.

**Acceptance Scenarios**:

1. **Given** a scripted model, **When** a test scripts "complete the task with this result",
   **Then** the agent's task completes with that result decoded to the declared type.
2. **Given** a scripted model, **When** the script is conditioned on the request's content or on a
   tool's result, **Then** the matching response is used and the others are not consumed.
3. **Given** a test that waits for a task, **When** the task reaches a terminal state, **Then** the
   wait returns its record; **When** it does not within the test's timeout, **Then** the wait fails
   naming the task's last status.
4. **Given** a script that has run out, **When** the agent makes another model call, **Then** the
   task fails with an error naming the exhausted script, and the failure is visible to the test.
5. **Given** the Python testkit, **When** a Python developer writes the same test, **Then** the same
   four scenarios are available against the sidecar.

---

### User Story 8 - The component is documented as a property of the platform (Priority: P3)

A developer reads what an autonomous agent is, when to choose it over a request agent or a
workflow, how to declare a task type and an agent, how to run and watch a task, what the budget and
rules do, what happens on a crash and to a tool that ran twice, and what this phase does not do —
in the concept, build and reference pages, in the Scala and Python SDK pages, in the divergences
page, and in the limitations page. The skills carry the new pages, and the samples the pages quote
are tested code.

**Why this priority**: The documentation is how the feature is used and how its edges are known;
an undocumented crash semantic is a bug report waiting to happen.

**Independent Test**: The docs build passes with the new pages in the navigation and a skill;
every quoted sample is included from tested code; the limitations page names what phase 1 leaves
out; the protocol reference table is regenerated and its prose mentions every new fact.

**Acceptance Scenarios**:

1. **Given** the concepts section, **When** a reader looks for autonomous agents, **Then** a page
   explains the component, the task, the loop, the budget, the crash semantics and when to use it,
   standing alone.
2. **Given** the build section, **When** a developer follows the guide, **Then** they can write the
   question-answering agent in Scala and in Python from included, tested samples.
3. **Given** the limitations page, **When** a reader looks for what autonomous agents cannot do,
   **Then** delegation, handoff, teams, moderation, MCP tools and per-instance overrides are listed
   as not implemented, and the TypeScript SDK's testkit and notification stream as not yet
   available.
4. **Given** the divergences page, **When** a reader compares with Akka, **Then** every deliberate
   difference in this feature is listed with its reason.

---

### Edge Cases

- **A task with no result shape.** It completes with text. The completion tool takes a string.
- **A dependency that fails or is cancelled.** Every task depending on it, directly or through
  another dependency, is cancelled with a reason naming the failed one; an instance those tasks
  were queued on skips them.
- **A dependency that does not exist.** Creating the task is refused; a dependency must name a
  task that has been created.
- **A cycle of dependencies.** Creating the task that would close the cycle is refused.
- **A task assigned to an instance whose definition does not accept its type.** Assignment is
  refused, and the task stays pending.
- **A task assigned twice.** Assignment to a second instance while it is assigned or in progress is
  refused. A pending task — including one returned to pending by a termination — may be assigned.
- **A completed, failed or cancelled task assigned again.** Refused; a terminal task is final.
- **Cancel a task the model is completing.** The iteration in flight finishes; if it completed the
  task, the completion wins and the cancel is refused as a change to a terminal task; otherwise the
  task is cancelled and a completion that arrives later is refused by the record, which the agent
  reads back and reports as cancelled.
- **Cancel a task that is waiting on a dependency.** Cancelled at once; the dependency is
  untouched.
- **Run a single task for a type the agent does not accept.** Refused before any instance exists.
- **The model completes the task and asks for other tools in the same response.** The completion
  is taken, the other tool calls are not run, and the iteration is recorded as the last.
- **The model calls the completion tool twice in one response.** The first is taken; the second is
  answered with an error result that the task is already complete, and not run.
- **The model asks for a tool that does not exist.** As today: an error result goes back to the
  model and the iteration continues.
- **A tool throws.** As today: the error is the tool's result and the iteration continues; the
  task does not fail.
- **The model call itself fails** — the provider is down, the request times out. The iteration is
  failed and retried after a backoff; the retry does not consume the budget. After a configurable
  number of consecutive iteration failures the task fails with the last error, and a struggle
  event is emitted before that.
- **Input guardrails reject the task's instructions.** The task fails before any model call, with
  the guardrail's reason.
- **Output guardrails reject a completion result.** The task is result-rejected with the
  guardrail's reason, exactly as a rule rejection, and the budget applies.
- **The working history outgrows the context window.** Compaction runs on it as on any session,
  because it is a session; the task's instructions and attachments are always kept in full.
- **Suspend an idle instance.** It suspends; a task assigned to it waits until resume.
- **Suspend or resume a terminated instance.** Refused.
- **Terminate an instance that never existed.** Creates it terminated; the id is burned.
- **Terminate twice.** The second is a no-op.
- **Read a task that does not exist.** Not found, distinct from a task that exists and is pending.
- **Block for a result on a task that is already terminal.** Returns at once.
- **An attachment by reference.** The reference is shown to the model as a reference; nothing is
  fetched by the platform in this phase, and a tool may fetch it.
- **Two instances of a service, one task.** A task is worked by exactly one instance at a time,
  wherever in the cluster it is hosted; a caller on any node reads the same record.
- **Notifications with no subscriber.** Nothing is retained; the task's record is the durable
  account.
- **A subscriber that falls behind.** Events for it are dropped from the oldest with one event
  saying how many were dropped, rather than the agent slowing down.

## Requirements *(mandatory)*

### Functional Requirements

**Task types and tasks**

- **FR-001**: A developer MUST be able to declare a task type with a name that is its wire name, a
  description the model reads, an optional result shape given as a type whose values can be
  encoded and decoded by the platform, and zero or more rules, each of which inspects a result and
  either accepts it or rejects it with a reason.
- **FR-002**: A task type's name MUST be declared separately from anything in the developer's code
  that could be renamed, for the reason every handler's wire name is: a task record names its type,
  and a rename must not orphan the records.
- **FR-003**: A caller MUST be able to create a task from a task type with instructions, optional
  attachments — each inline text content or a reference by URI, with a name and a content type —
  and zero or more dependencies naming existing tasks; the caller MAY choose the task's id, and
  the platform MUST generate one otherwise.
- **FR-004**: A task MUST be a durable record with a status of pending, assigned, in progress,
  result-rejected, completed, failed or cancelled; the record MUST carry its type name, its
  instructions and attachments, its dependencies, its assignee if any, its result if completed, its
  reason if failed, cancelled or result-rejected, the iterations spent on it, the tokens spent on
  it, and when it was created, assigned, started and reached its terminal state.
- **FR-005**: Completed, failed and cancelled MUST be terminal: a terminal task refuses every
  further change.
- **FR-006**: A task MUST NOT start until every task it depends on is completed; a task whose
  dependency fails or is cancelled MUST be cancelled with a reason naming it, and the cancellation
  MUST propagate to tasks that depend on the cancelled one.
- **FR-007**: The result of every completed dependency MUST be put in front of the model when the
  dependent task starts, named by the dependency's id and type.
- **FR-008**: Creating a task MUST be refused when a dependency names a task that does not exist or
  when the dependency would close a cycle.
- **FR-009**: Anyone holding a task id MUST be able to read the record, decode its result as the
  declared type, and block until the task is terminal with a timeout of their choosing.
- **FR-010**: A failed task MUST NOT be retried by the platform; reattempting is a new task.
- **FR-010a**: A caller holding a task id MUST be able to cancel a task that is pending, assigned,
  in progress or result-rejected, with an optional reason. A pending or assigned task is cancelled
  at once and leaves its instance's queue; an in-progress task is cancelled at its instance's next
  iteration boundary, after which no further model call is made for it and the instance proceeds
  to its next task. Cancelling a terminal task is refused, and the cancellation propagates to
  dependents as a failed dependency does.

**The agent and its definition**

- **FR-011**: A developer MUST be able to declare an autonomous agent with a component id, a
  description of what it is for, optional instructions, tools, input and output guardrails, a
  model, and the task types it accepts, each with a maximum number of iterations per task; a
  definition MUST have a description and at least one accepted task type.
- **FR-012**: An autonomous agent MUST be registered explicitly like every component; an
  unregistered one fails at startup, not at its first task.
- **FR-013**: An instance MUST be addressed by an instance id the caller chooses; an instance is
  created on first use and hosted wherever the platform places it, with requests for one id handled
  one at a time.
- **FR-014**: An instance MUST work one task at a time, in the order tasks were assigned, each
  waiting for its dependencies; a task that cannot start because a dependency is not yet complete
  MUST NOT block a later task whose dependencies are.
- **FR-015**: For each iteration the platform MUST assemble the model request from the agent's
  description and instructions, the task's type description and result shape, the task's
  instructions and attachments, its dependencies' results, the iterations remaining in the budget,
  the working history so far, the agent's tools and two built-in tools: one that completes the task
  with a result and one that fails it with a reason.
- **FR-016**: The model MUST be able to end a task only through the built-in tools; a response with
  no tool call is recorded and the next iteration begins.
- **FR-017**: A completion result MUST be decoded as the declared result shape and then checked by
  the type's rules in declaration order, then by the output guardrails; a result that does not
  decode MUST be answered to the model as the tool's error and MUST NOT change the task's status;
  a result a rule or guardrail rejects MUST put the task in result-rejected with the reason, which
  the next model request MUST carry.
- **FR-018**: When a task has spent its budget without completing or failing, the platform MUST
  fail it with a reason naming the budget, and the instance MUST proceed to its next task.
- **FR-019**: Input guardrails MUST run against a task's instructions before its first model call,
  and a rejection MUST fail the task with the guardrail's reason.
- **FR-020**: A tool's failure MUST be the tool's result, not the task's; a model call's failure
  MUST fail the iteration, which is retried after a backoff without consuming the budget, and a
  configurable number of consecutive iteration failures MUST fail the task.

**Durability**

- **FR-021**: Every iteration MUST be recorded durably before the next model call: the model's
  response, including any tool requests, MUST be recorded before any of those tools run, and the
  tools' results MUST be recorded before the next model call.
- **FR-022**: An instance whose process stops mid-task MUST resume from its last recorded point on
  restart or on any node that next hosts it, re-running the tools of a recorded response whose
  results were not recorded rather than calling the model again; the documentation MUST state that
  a tool may therefore run more than once for one request and that tools with side effects should
  be written to tolerate that.
- **FR-023**: An instance's working history for a task MUST be kept as session memory — one
  session per task — so that compaction, durability and observation apply to it unchanged; the
  task's instructions, attachments and dependency results MUST be kept whole through compaction.
- **FR-024**: An instance's own state — its phase, its queue, its current task and the iterations
  spent on it, its token usage, whether it is suspended or terminated — MUST be durable and MUST
  survive a restart and a move between nodes.

**Client operations**

- **FR-025**: A caller MUST be able to run a single task: the platform creates an instance with a
  generated id, assigns the task, returns the task id at once, and the instance terminates itself
  when the task is terminal.
- **FR-026**: A caller MUST be able to assign one or more existing pending tasks to an instance by
  id; assignment MUST be refused for a task type the agent does not accept, for a task that is not
  pending, and for a terminated instance.
- **FR-027**: A caller MUST be able to suspend an instance, which stops it after its current
  iteration and before the next model call, leaving its current task in progress and its queue
  intact; and to resume it.
- **FR-028**: A caller MUST be able to terminate an instance permanently: it stops at its next
  iteration boundary, its current and queued tasks return to pending with no assignee and a reason
  naming the termination, and the id refuses every further operation but a state query.
- **FR-029**: A caller MUST be able to query an instance's state: phase, suspended, current task
  id, iterations spent on it, queued task ids in order, and token usage in total.
- **FR-030**: Every client operation MUST be available to an endpoint and to any component through
  the component client, and MUST be available to a Python and a TypeScript process through the
  sidecar.
- **FR-031**: An idle instance MUST cost nothing beyond its journal until it is next addressed.

**Notifications**

- **FR-032**: A caller MUST be able to subscribe to an instance's notifications as a live stream
  that starts at subscription and replays nothing.
- **FR-033**: The stream MUST carry lifecycle events (activated, deactivated, iteration started,
  iteration completed, iteration failed, suspended, resumed, terminated), task events (assigned,
  started, completed, failed, cancelled, result rejected, waiting on a dependency, dependency
  resolved) and struggle events (approaching the iteration budget, repeated iteration failure,
  stuck on a dependency), each with the instance id, the task id where one applies, the time, and
  the event's own detail — the reason of a rejection or failure, the iteration number, the
  remaining budget.
- **FR-034**: Struggle events MUST be emitted once per condition and not again until the condition
  resets; the approaching-budget threshold and the repeated-failure count MUST be configurable,
  with defaults.
- **FR-035**: A notification MUST be encodable as JSON so that an endpoint can forward it as a
  server-sent event using the streaming support the platform has, and MUST be decodable in Python
  and TypeScript.
- **FR-036**: A subscriber that cannot keep up MUST NOT slow the instance; the stream drops its
  oldest events for that subscriber and says so.

**Polyglot**

- **FR-037**: The sidecar protocol MUST let a process declare autonomous agents in discovery — id,
  description, instructions, accepted task types with budgets and result schemas, the rules each
  type has, tools and guardrails — and let the sidecar ask the process to run a tool, check a
  guardrail and check a task rule; a definition declared in discovery leaves nothing to plan per
  task, so no plan request is made; the task record, the instance and the loop MUST live in the
  sidecar.
- **FR-038**: The Python SDK MUST offer the declaration, the client operations, the notification
  stream and the testkit support this feature specifies, and its conformance suite MUST cover them.
- **FR-039**: The TypeScript SDK MUST offer the declaration of an autonomous agent and its task
  types, and the client operations, in this phase; its testkit support, notification stream, guide
  and sample follow in their own feature, and the limitations page MUST say so.

**Testing**

- **FR-040**: The scripted model MUST be able to script a completion with a typed result, a
  completion with raw JSON, a failure with a reason, and MUST be able to condition a response on
  the request's content and on a tool's result.
- **FR-041**: Both test kits MUST offer a wait for a task's terminal state with a timeout that
  fails naming the task's last status, and the whole-service kit MUST be able to restart the
  service mid-task.
- **FR-042**: An exhausted script MUST fail the task with an error naming the script, visible to
  the test, never a stalled task.
- **FR-043**: The sidecar's conformance suite MUST gain cases for the autonomous agent so that the
  Scala reference and every SDK are held to the same behaviour; for TypeScript that is the
  declaration and the client operations it ships.

**Documentation and samples**

- **FR-044**: A sample MUST exist — a question-answering agent with a couple of tools, an endpoint
  that runs a task, reads it and streams its notifications — in Scala and in Python, with tests,
  and the documentation MUST quote it through tested includes.
- **FR-045**: The documentation MUST add a concept page, a build page, reference entries for the
  protocol, the Scala and Python SDKs, a divergences entry for every deliberate
  difference from Akka, and a limitations entry for what this phase leaves out; every page MUST be
  in the navigation and in a skill.

### Key Entities

- **Task type**: a declared kind of work — wire name, description, optional result shape, rules.
  Declared in code, referenced by name in every task record.
- **Task**: a durable record of one piece of work — id, type name, instructions, attachments,
  dependencies, status, assignee, result or reason, iteration and token counts, timestamps. Exists
  independently of any agent; terminal states are final.
- **Attachment**: content that travels with a task — a name, a content type, and either inline
  text or a URI reference.
- **Autonomous agent definition**: what an agent is for and how it works — description,
  instructions, tools, guardrails, model, accepted task types with budgets. Holds a list of
  capabilities of which task acceptance is the only kind in this phase.
- **Agent instance**: one named process of an agent — phase, current task and its iteration count,
  queue, suspended, terminated, token usage. Durable; one task at a time.
- **Iteration**: one recorded round of an instance's work on a task — the model's response, the
  tools it asked for and their results, the tokens spent.
- **Working history**: the conversation an instance builds while working one task, held as a
  session of session memory, compacted like any session.
- **Notification**: one event in an instance's live stream — lifecycle, task or struggle — with
  instance id, task id, time and detail.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A developer can write the question-answering agent, its task type, an endpoint and a
  unit test in Scala from the documentation alone, in under an hour, and the same in Python.
- **SC-002**: A task run against a real service completes with a typed result readable by its id,
  and a caller blocking for it returns within one second of the completion.
- **SC-003**: A service killed mid-task and restarted finishes the task, with the number of model
  calls equal to the number of iterations the task needed — no recorded iteration is repeated.
- **SC-004**: A task with a budget of N iterations against a model that never completes fails
  after exactly N model calls, and the instance's next queued task starts within one second.
- **SC-005**: A subscriber to an instance's notifications receives every event of a task's run in
  order, and one that subscribes mid-run receives none from before.
- **SC-006**: Every acceptance scenario in this specification has an automated test, in the unit
  test kit where it needs no runtime and in the whole-service kit where it does, and the Python
  scenarios run through the sidecar against the same Postgres.
- **SC-007**: The sidecar conformance suite passes for the Scala reference and the Python SDK with
  the autonomous agent cases added.
- **SC-008**: Existing request agents, their tests, their documentation and the multi-agent
  planner sample are unchanged by this feature.
- **SC-009**: The docs build passes with every new page in the navigation and a skill, every
  included sample from tested code, and the generated reference tables' prose covering every new
  fact.

## Clarifications

### Session 2026-09-28

- Q: Does the TypeScript SDK ship the whole feature in this phase, or only the protocol support?
  → A: The declaration and the client operations only. Its testkit, notification stream, guide
  and sample follow in their own feature; the limitations page says so.
- Q: May a caller cancel a task from outside, or is cancellation only the consequence of a failed
  dependency or a termination? → A: Yes — pending, assigned, in-progress or result-rejected; an
  in-progress task stops at its instance's next iteration boundary.

## Assumptions

- **Crash semantics: tools are at-least-once.** The model's response is recorded before its tools
  run, and their results are recorded before the next model call; a crash between the two re-runs
  the tools from the recorded response and never re-calls the model for a recorded iteration. This
  is the cheaper and more honest of the two choices the description offered: a model call is the
  expensive, non-deterministic step, a tool call is the developer's own code, and documenting "a
  tool may run twice, write it to tolerate that" is a rule developers already live by for consumers
  and timers. Exactly-once tools would need every tool to be a durable step of its own, which is a
  workflow.
- **One session per task, not per instance.** An instance's working history is narrow — it sees
  only the task it is working on — which is Akka's reasoning for autonomous agents over one long
  conversation, and it means an instance that has worked ten tasks carries none of them into the
  eleventh. The session id is derived from the task id, so anyone holding the id can read the
  history through session memory as they can any session.
- **Termination returns tasks to pending rather than failing them.** The record keeps its
  instructions and dependencies; a caller may assign it to another instance, which starts it
  afresh with a new working history. This is the shape handoff needs later — a task's assignee
  changes — without building handoff now.
- **Cancellation is cooperative at the iteration boundary.** A cancel of an in-progress task
  never interrupts a model call or a tool in flight; it takes effect when the instance next looks
  at its task, which bounds the wait by one iteration and keeps every recorded iteration whole.
- **Attachments by reference are not fetched.** The reference is shown to the model as a
  reference; a tool fetches it if the agent needs the content. Fetching arbitrary URIs from the
  platform is an egress and a credential question, and the limitations page says so.
- **Struggle defaults.** Approaching-budget fires when 80% of the budget is spent; repeated
  iteration failure after three consecutive failed iterations; stuck on a dependency after the
  task has waited five minutes. Each is a setting with that default.
- **Iteration failure defaults.** A failed model call is retried after a backoff starting at one
  second and doubling to a minute; five consecutive failures fail the task.
- **A "run single task" instance id is generated** and reported in the task's record as its
  assignee, so its notifications can still be subscribed to by reading the task.
- **Blocking for a result runs on the caller's thread**, which is a virtual thread for every
  endpoint and component, so it costs nothing to hold; the default timeout is the same as a
  workflow's default, and a caller chooses its own.
- **Tokens, not money**, as today: the instance's and the task's usage are reported in tokens.
- **The local console is not extended in this phase.** Instances and tasks are readable through
  their records and notifications; showing them in the console is the console's own feature.
- **Access control is the endpoint's.** The client operations are available to components and
  endpoints as every component client operation is; who may run, read or terminate is decided by
  the endpoint's ACL, and the platform exposes nothing automatically.
- **Two ankka services still never share a database.** Task records and instance journals are
  per service, like every entity's.

## Out of Scope

- **Delegation, handoff, team leadership and moderation.** Each is a later phase on this one's task
  model; the definition's capability list and the task's changeable assignee are the room left for
  them.
- **MCP tools.** Tools are declared functions, as for request agents.
- **Per-instance overrides of a definition's instructions or capabilities.**
- **A price table.** Tokens only.
- **Multi-region.** One cluster, as everything in ankka.
- **Fetching attachments by URI on the platform's behalf.**
- **A task queue independent of an instance** — a shared backlog several instances draw from is
  team leadership.
- **Persisted notifications or a notification history.** The task record is the durable account;
  the stream is live.
- **Showing instances and tasks in the local console.**
- **The TypeScript SDK's testkit, notification stream, guide and sample.** The declaration and
  the client ship here so a TypeScript service can run and read a task; the rest is its own
  feature.
- **Any change to request/response agents.** They stay as they are, and a workflow around them
  remains the documented way to coordinate several of them.
