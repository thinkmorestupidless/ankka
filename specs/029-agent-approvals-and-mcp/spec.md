# Feature Specification: Agent Approvals and MCP — A Tool That Waits for a Person, Tools That Reach Other Services

**Feature Branch**: `029-agent-approvals-and-mcp`

**Created**: 2026-10-03

**Status**: Draft

**Input**: User description: "Three additions to agents. First, a tool can be declared as
requiring approval: when the model calls it, the loop records the pending call in the session,
answers the caller that approval is awaited, and stops; a `decide` command on the session, or on
an autonomous agent's instance, resumes the loop with the decision, and the pending call survives a
restart because the session is a journal. Second, an agent's tools get the service client, so a tool
that reaches another service does so as the service, with its certificate, and the called service's
access control list decides. Third, an agent definition may list MCP servers; at start the runtime
connects to each, discovers its tools, and offers them to the model beside the agent's own, with
approval declarable per server. All three reach Python and TypeScript agents through the sidecar
protocol. Out of scope: delegation between autonomous agents, teams and shared backlogs,
per-instance overrides of a definition, MCP transports other than streamable HTTP, and MCP
resources and prompts."

## Context

An agent's tool today is a closure. `FunctionTool` in `modules/agent` is built with
`FunctionTool.named(n).describedAs(d)` and the rest, carries a schema from `SchemaType`, and has
`invoke(Json): Either[String, String]`, where a `Left` is an error message shown to the model.
`FunctionTool.raw` is the shape a sidecar's remote tool takes, called through `InvokeTool` in
`agent.proto`. The request loop in `AgentLoop` runs every tool the model asks for, in order, feeds
the results back, and loops until the model answers or `maxToolCallSteps` is reached, with the
conversation in `SessionMemoryEntity`, an event sourced entity. The autonomous `IterationLoop`
records each iteration's start, the model's response, its completion and then the tool results,
and resumes from `resumePoint` after a crash so a recorded model call is never repeated and a tool
runs at least once. An instance can be suspended and resumed by an operator through `Calls`, which
stops it before the next model call. That is the whole of what can interrupt a loop.

Three things a service cannot build on that.

- **Nothing waits for a person.** A tool runs when the model asks. A service whose agent may move
  money, close an account or send a message to a customer wants the model to propose the call and
  a person to approve it before it runs. The only way to build that today is a workflow around the
  agent, which the orchestration guide describes for long processes: the workflow pauses, a person
  resumes it. It works, costs every team the same thirty lines, and splits one conversation across
  two components so that the model's proposal and the person's answer live in different journals.
- **A tool has no identity.** An agent's context has no `ServiceClients`; only an HTTP endpoint
  receives one through `EndpointClients.services`. A Scala tool that calls another service does
  so with plain HTTP and arrives as nobody; a Python or TypeScript tool runs in the process and
  has no service client at all. The called service's access control list, which admits by
  certificate, cannot admit it.
- **Tools are the agent's own functions, and nothing else.** There are no MCP tools; the
  limitations page says so. A service that wants its agent to use a tool server someone else runs
  wraps each tool by hand.

Four decisions shape this feature.

- **An approval is a journaled pause, not a new store.** A tool declared `requiresApproval` is
  offered to the model as any tool is. When the model calls it, the loop appends a pending-call
  event to the session and stops, and the handler's caller receives an outcome saying approval is
  awaited, with the approval id and the proposed arguments. A new `decide` command on the session
  resumes the loop on that session: approved runs the tool and continues; refused feeds the
  refusal to the model as the tool's result, so the model can answer the person. The session is
  already an event sourced entity, so the pending call survives a restart with nothing added, and
  requests to one session are already one at a time, so a decision and a new turn cannot
  interleave. The loop gains a resume point; nothing gains a table.
- **An autonomous agent pauses the same way, and its clock stops.** The pending call is a
  notification, `ApprovalRequested`, and `decide` is a call on the instance. The iteration that
  asked is not counted as failed and the task's iteration budget does not advance while an answer
  is awaited, because an agent waiting for a person is not working. `remember-entities` keeps the
  instance alive across a crash as it does today.
- **Tools get the service client.** `ServiceClients` is added to the agent's context beside the
  component client, the same value an endpoint receives. The sidecar protocol's `Request`, which
  025-polyglot-service-client adds, is how a Python or TypeScript tool makes the same call.
- **MCP tools are discovered at start and named for where they came from.** An agent definition
  lists servers by URL. At start the runtime connects over streamable HTTP, presenting the
  service's certificate when the server is itself an ankka service, discovers the tools, and
  offers them under `mcp:<server>:<tool>`. A server that cannot be reached at start fails the
  start naming the server, by the same rule an unregistered component fails at startup rather than
  at its first request. Approval is declarable per server, so every tool of an untrusted server
  waits for a person.

What this feature is not: it is not delegation, hand-off or a team of autonomous agents; it is
not a per-instance override of a definition; it is not MCP over stdio or any transport but
streamable HTTP; and it is not MCP resources or prompts, only tools.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A request agent waits for approval (Priority: P1)

A developer building a support agent marks the `issue_refund` tool as requiring approval. A
customer asks for a refund; the model decides to call the tool. The customer's client receives,
instead of an answer, an approval request naming the tool and the amount. A supervisor approves it
through the service's own endpoint, which calls `decide` on the session; the tool runs, the model
sees its result, and the customer receives the answer. When the supervisor refuses, the model is
told, and tells the customer.

**Why this priority**: This is the capability the domain plan's confirm-before-write needs and
every team would otherwise rebuild.

**Independent Test**: With the scripted model: script a tool call to an approval-requiring tool,
call the handler, assert the awaiting outcome and that no tool ran; restart the test service;
call `decide`; assert the tool ran exactly once and the scripted model's next turn received its
result.

**Acceptance Scenarios**:

1. **Given** an agent whose plan offers a tool declared `requiresApproval`, **When** the model
   calls it, **Then** the handler's caller receives an outcome saying approval is awaited,
   carrying an approval id, the tool's name and the arguments the model proposed, and the tool
   has not run.
2. **Given** that pending call, **When** the service is restarted and `decide(approvalId,
   approved)` is called on the session, **Then** the tool runs exactly once with the proposed
   arguments, the model's next turn receives its result, and the loop continues to the model's
   answer.
3. **Given** that pending call, **When** `decide(approvalId, refused, note)` is called, **Then**
   no tool runs, the model's next turn receives the refusal and the note as the tool's result,
   and the loop continues.
4. **Given** a decided approval, **When** `decide` is called again for the same id, **Then** it is
   refused as a conflict and nothing runs again.
5. **Given** a pending call, **When** `decide` is called with an id the session does not hold,
   **Then** it is refused naming the id.
6. **Given** a pending call, **When** a new request arrives on the same session, **Then** it is
   refused as a conflict saying an approval is awaited, so a decision and a turn never interleave.
7. **Given** a model that calls two tools in one turn, one requiring approval, **When** the loop
   runs, **Then** the tool that needs no approval runs, the other is recorded as pending, and the
   caller receives the awaiting outcome.
8. **Given** a session with a pending call, **When** its conversation is read, **Then** the
   pending call is part of the history, and a session recorded before this feature reads as it
   did.
9. **Given** a pending call on a streaming handler, **When** the model asks for the tool, **Then**
   the stream ends with the awaiting outcome as its last frame rather than hanging.
10. **Given** a tool with no approval declared, **When** the model calls it, **Then** it runs as
    today, so a service that declares nothing changes nothing.

---

### User Story 2 - An autonomous agent waits for approval (Priority: P1)

An operations agent working a task decides to call `restart_service`, a tool that requires
approval. Its subscribers receive an `ApprovalRequested` notification. An hour later an operator
calls `decide` on the instance; the tool runs, the iteration completes, and the task continues
with its budget where it was.

**Why this priority**: The autonomous host is where an unattended agent most needs a person in
the loop, and its budget rules make "waiting" a case the loop must know about.

**Independent Test**: With the scripted model in the testkit's autonomous harness: script a call
to an approval-requiring tool, assert the notification and that the iteration is neither failed
nor counted; call `decide`; assert the tool ran once and the task continued.

**Acceptance Scenarios**:

1. **Given** an autonomous agent working a task, **When** the model calls a tool that requires
   approval, **Then** every subscriber receives `ApprovalRequested` with the approval id, the
   tool and the arguments, and the instance records the pending call.
2. **Given** a pending call, **When** an hour passes, **Then** the task is not failed by its
   iteration budget or by consecutive failures, and no model call is made.
3. **Given** a pending call, **When** `decide` approved is called on the instance, **Then** the
   tool runs once, the iteration completes and is counted once, and the task continues.
4. **Given** a pending call, **When** the host crashes and the instance is restored by
   remembered entities, **Then** the pending call is still awaiting and `decide` still works.
5. **Given** a pending call, **When** the task is cancelled, **Then** the pending call is
   discarded, `decide` for it is refused, and nothing runs.
6. **Given** a pending call, **When** the instance is suspended and resumed by an operator,
   **Then** the pending call is unchanged by either.
7. **Given** a tool recorded as approved but not yet recorded as run when the host stopped,
   **When** the task resumes, **Then** the tool runs again, under the existing rule that a tool
   runs at least once.

---

### User Story 3 - A tool calls another service as the service (Priority: P2)

A developer's agent has a tool that reads a customer's balance from the wallet service. The tool
calls it through the service client in the agent's context; the wallet's endpoint admits the
agent's service by name and refuses everything else. The same tool, written in Python, makes the
same call through the sidecar.

**Why this priority**: Without it a tool is a caller with no name, and the domain plan's agents
cannot reach any service whose list admits by certificate.

**Independent Test**: In the k3s suite, an agent in one service calls a second service whose
route admits only the first by name; assert the call succeeds and that the same route refuses a
plain request from the node.

**Acceptance Scenarios**:

1. **Given** a Scala agent whose context holds `services`, **When** a tool calls
   `services("wallet").get(...)`, **Then** the request carries the service's certificate and the
   wallet's `allowCallers` admits it by name.
2. **Given** the same call to a service whose list does not name the caller, **When** the tool
   runs, **Then** the refusal reaches the tool as the service made it and the tool reports it to
   the model as an error.
3. **Given** a Python agent, **When** a tool makes the same call through the SDK's service
   client, **Then** it is admitted the same way, through the sidecar's `Request`.
4. **Given** a tool that calls a service during a turn, **When** the turn's trace is read,
   **Then** the call appears as a span under the tool's span.

---

### User Story 4 - An agent uses an MCP server's tools (Priority: P2)

A developer lists an MCP server on the agent's definition. At start the runtime discovers its two
tools and offers them to the model as `mcp:tickets:create` and `mcp:tickets:search`. The developer
marks the server as requiring approval, so a call to either waits for a person. When the server is
down at start, the service refuses to start, naming it.

**Why this priority**: It is the way an agent gains tools without the service wrapping each one;
it follows approvals because an untrusted server is the first thing that needs them.

**Independent Test**: Against a scripted MCP server in the test suite: start a service whose
agent lists it, assert the discovered tools appear in the plan, call one through the scripted
model, and assert the request the server saw; stop the server and assert the service's start
fails naming it.

**Acceptance Scenarios**:

1. **Given** an agent definition listing an MCP server with two tools, **When** the service
   starts, **Then** the model's plan carries both tools under `mcp:<server>:<tool>` with the
   schemas the server declared.
2. **Given** a discovered MCP tool, **When** the model calls it, **Then** the runtime invokes it
   on the server with the model's arguments and feeds the server's result back as the tool's.
3. **Given** a server listed as requiring approval, **When** the model calls any of its tools,
   **Then** the call is pending as in User Story 1.
4. **Given** a server that is unreachable at start, **When** the service starts, **Then** the
   start fails naming the server and the agent.
5. **Given** a server that is an ankka service in the cluster, **When** the runtime connects,
   **Then** it presents the service's certificate and the server's list admits it by name.
6. **Given** a server whose tool name collides with one of the agent's own, **When** the service
   starts, **Then** there is no collision, because the MCP tool's name carries the server's.
7. **Given** a Python agent listing an MCP server, **When** the service starts, **Then** the
   sidecar discovers and serves the tools, and the process's `Plan` sees them by name.

---

### Edge Cases

- **An approval nobody decides.** It waits. Whether it expires, and after how long, is an open
  question; until decided, a pending call is pending until `decide` or cancellation.
- **A model that calls the same approval-requiring tool twice in one turn.** Two pending calls,
  two ids, decided independently; the turn resumes when both are decided.
- **A decision from a different caller than the one who asked.** The session does not know who
  asked; who may decide is the endpoint's access control list, as every authorization is.
- **`decide` on a session with no agent.** Refused, naming the session.
- **An approval-requiring tool in a guardrail's path.** Guardrails see messages and replies, never
  tool calls, so a refusal by guardrail happens before any pending call exists.
- **A pending call and compaction.** Compaction summarises messages; a pending call is not a
  message and is never compacted away.
- **An MCP server that changes its tools after start.** Not seen until the service restarts; a
  tool the server no longer has fails the call with the server's error.
- **An MCP server listed twice, or two servers with one name.** Refused where the definition is
  built.
- **MCP tool results and output guardrails.** Whether a server's result is checked as the model's
  output is checked is an open question; today a tool's result is not.
- **A service client call from a tool that outlives its request.** Tools run after the handler
  has returned, as today; the service client is captured at plan time, as the session is.

## Requirements *(mandatory)*

### Functional Requirements

**Approvals**

- **FR-001**: A tool MUST be declarable as requiring approval, in Scala, Python and TypeScript.
- **FR-002**: When the model calls such a tool, the loop MUST append a pending-call event to the
  session, carrying an approval id, the tool's name and the arguments, and MUST NOT run the tool.
- **FR-003**: The handler's caller MUST receive an outcome saying approval is awaited, carrying
  the approval id, the tool's name and the arguments; on a streaming handler it MUST be the
  stream's last frame.
- **FR-004**: A session MUST accept a `decide` command carrying an approval id, a decision and an
  optional note; approved runs the tool exactly once and continues the loop; refused continues the
  loop with the refusal and note as the tool's result.
- **FR-005**: `decide` for an id the session does not hold MUST be refused naming it; for an id
  already decided it MUST be refused as a conflict.
- **FR-006**: A new request on a session with a pending call MUST be refused as a conflict.
- **FR-007**: A pending call MUST survive a restart of the service with no state outside the
  session's journal, and a session recorded before this feature MUST replay unchanged.
- **FR-008**: On an autonomous agent, a pending call MUST be published as an `ApprovalRequested`
  notification and decided by a call on the instance; while it is pending the task MUST NOT
  advance its iteration count, count a failure, or make a model call.
- **FR-009**: Cancelling a task MUST discard its pending calls; suspend and resume MUST leave
  them unchanged.

**Service client**

- **FR-010**: The agent's context MUST hold `ServiceClients`, the same value an endpoint
  receives, in Scala; a Python or TypeScript tool MUST reach the same through the SDK's service
  client over the sidecar's `Request`.

**MCP**

- **FR-011**: An agent definition MUST be able to list MCP servers by URL, each optionally
  requiring approval; a definition listing one server twice or two servers under one name MUST be
  refused where it is built.
- **FR-012**: At start the runtime MUST connect to every listed server over streamable HTTP,
  presenting the service's certificate where the server is an ankka service, discover its tools,
  and offer them to the model as `mcp:<server>:<tool>` with the server's schemas; a server that
  cannot be reached MUST fail the start naming it.
- **FR-013**: A call to a discovered tool MUST be made on the server with the model's arguments
  and the server's result fed back as the tool's; an error from the server MUST reach the model as
  a tool error.
- **FR-014**: The sidecar MUST discover and serve MCP tools for a process-hosted agent, and the
  protocol's `Plan` MUST carry them and the pending-approval outcome under a minor version bump.

**Documentation**

- **FR-015**: The agents and autonomous agents guides MUST document approvals, the service client
  in tools and MCP servers with samples from tested code, and the limitations page MUST say what
  MCP support does not cover.

### Key Entities

- **Pending call**: an approval id, a tool name, the model's arguments, and its decision once
  made, recorded in the session's journal or the instance's record.
- **Decision**: approved or refused, with an optional note, made once per approval id.
- **MCP server**: a name, a URL, whether its tools require approval; its tools are discovered at
  start and named for it.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An approval-requiring tool runs exactly once across a service restart between the
  model's call and the decision, in the testkit suite.
- **SC-002**: An autonomous task with a pending call for one hour has the iteration count it had
  when the call was made.
- **SC-003**: A tool's call to a second service is admitted by a caller list that names the
  first service and refused from the node, in the k3s suite.
- **SC-004**: Two tools of a scripted MCP server appear in the model's plan by name and one is
  invoked with the model's arguments, in the agent suite.
- **SC-005**: The same four hold for a Python agent through the sidecar in the conformance suite.

## Assumptions

- A session's one-at-a-time rule and its event sourced memory are sufficient to hold a pending
  call durably; no new table is needed.
- `ServiceClients` built for endpoints can be handed to the agent runtime's contexts without
  change.
- Streamable HTTP is the MCP transport the servers a service will meet speak; stdio servers are
  not hosted in a pod.
- The protocol's minor version bump is accepted by the platform's existing version rule.

## Dependencies

- 025-polyglot-service-client, for the `Request` RPC a Python or TypeScript tool calls through
  and for the Scala context plumbing it adds.
- Gates stage 5 of the domain plan: the support agent's confirm-before-write and the proposal
  pipeline's human gates.

## Open Questions

- Should a pending approval expire, and after how long; and if it expires, does the model see a
  refusal or does the turn end?
- Are an MCP server's results subject to the agent's output guardrails, as the model's own output
  is, given that the server is not the model?
- Should `decide` record who decided, as the control plane records an actor, so a session's
  history shows it?
