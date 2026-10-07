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
  offered to the model as any tool is. When the model calls it, the loop records an approval
  request in the session and stops, and the handler's caller receives an outcome saying approval is
  awaited, with the approval id and the proposed arguments. A new `decide` command on the session
  resumes the loop on that session: approved runs the tool and continues; refused feeds the
  refusal to the model as the tool's result, so the model can answer the person. The session is
  already an event sourced entity, so the approval request survives a restart with nothing added, and
  requests to one session are already one at a time, so a decision and a new turn cannot
  interleave. The loop gains a resume point; nothing gains a table.
- **An autonomous agent pauses the same way, and its clock stops.** The approval request is a
  notification, `ApprovalRequested`, and `decide` is a call on the instance. The iteration that
  asked is not counted as failed and the task's iteration budget does not advance while an answer
  is awaited, because an agent waiting for a person is not working. `remember-entities` keeps the
  instance alive across a crash as it does today.
- **Tools get the service client.** `ServiceClients` is added to the agent's context beside the
  component client, the same value an endpoint receives. The sidecar protocol's `Request`, which
  025-polyglot-service-client adds, is how a Python or TypeScript tool makes the same call.
- **MCP tools are discovered at start and named for where they came from.** An agent definition
  lists servers by name, each with an address. At start the runtime connects over streamable HTTP, presenting the
  service's certificate when the server is itself an ankka service, discovers the tools, and
  offers them under `mcp__<server>__<tool>`. A server that cannot be reached at start fails the
  start naming the server, by the same rule an unregistered component fails at startup rather than
  at its first request. Approval is declarable per server, so every tool of an untrusted server
  waits for a person.

What this feature is not: it is not delegation, hand-off or a team of autonomous agents; it is
not a per-instance override of a definition; it is not MCP over stdio or any transport but
streamable HTTP; and it is not MCP resources or prompts, only tools.

## Clarifications

### Session 2026-10-04

- Q: What is a tool call that is waiting for a person called? → A: An approval request, awaiting a
  decision ("pending call" is refused); a suspended autonomous agent is resumed, and "instance"
  stays a running copy of a service.
- Q: Who receives what the loop produces after a decision? → A: `decide` returns it, exactly as the
  agent's handler would have: the model's answer, or a further approval request.
- Q: Should an approval request that nobody decides expire? → A: Only when the tool, or the MCP
  server, declares a time limit; when it passes, the approval request is decided as refused with a
  note saying it expired, and the loop goes on as for any refusal.
- Q: Should a decision record who made it? → A: Yes, and it is required: `decide` carries the name
  of who decided, supplied by its caller, and a decision without one is refused. It is recorded
  with the decision and shown in the history; it is never what authorizes the decision.
- Q: How does an agent authenticate to an MCP server that is not an ankka service? → A: A listed
  server may carry HTTP headers whose values are taken from the service's variables (on the
  platform, from a project secret); no OAuth flow, and no value read while the service runs.
- Q: Are an MCP server's results checked by the agent's guardrails? → A: By guardrails of their
  own: an agent may declare result guardrails, a separate kind from its input and output
  guardrails, which check the result of every tool call to an MCP server's tool before the model
  is told it.

### Planning 2026-10-04

Found while planning, with the reason in [research.md](research.md):

- An MCP server's tool is named `mcp__<server>__<tool>`, and a server's name is lower-case
  letters, digits and hyphens: a model's provider refuses a tool name with a colon (R14).
- A header's value is taken from a variable whose name starts `ANKKA_MCP_`, and a server's address
  may be given by `ANKKA_MCP_<SERVER>_URL`: behind the sidecar it is the platform's program that
  connects, and only variables the platform declares reach it (R16).
- The runtime adds an MCP server's tools to every plan of the agent; a process's `Plan` does not
  name them (R14).
- A request agent's approved tool runs at most once: a turn cut off after its last decision is
  ended, not resumed (R4).
- A time limit needs the service's `TimerRuntime` (R10).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A request agent waits for approval (Priority: P1)

A developer building a support agent marks the `issue_refund` tool as requiring approval. A
customer asks for a refund; the model decides to call the tool. The customer's client receives,
instead of an answer, an approval request naming the tool and the amount. A supervisor approves it
through the service's own endpoint, which calls `decide` on the session; the tool runs, the model
sees its result, and `decide` answers with what the model said, which the service relays to the
customer. When the supervisor refuses, the model is told, and its answer says so.

**Why this priority**: This is the capability the domain plan's confirm-before-write needs and
every team would otherwise rebuild.

**Independent Test**: With the scripted model: script a tool call to an approval-requiring tool,
call the handler, assert the awaiting outcome and that no tool ran; restart the test service;
call `decide`; assert the tool ran exactly once and the scripted model's next turn received its
result.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/agents/approvals.feature`: a tool call that requires approval gives the caller an approval request instead of an answer
- added `features/agents/approvals.feature`: an approved tool call runs once and the model is told its result
- added `features/agents/approvals.feature`: an approval request is still awaiting a decision after the service restarts
- added `features/agents/approvals.feature`: a refused tool call never runs and the model is told the note
- added `features/agents/approvals.feature`: an approval request is decided once
- added `features/agents/approvals.feature`: a decision for an approval request the session does not hold is refused
- added `features/agents/approvals.feature`: a decision sent to a session no agent has used is refused
- added `features/agents/approvals.feature`: a session with an approval request awaiting a decision takes no new request
- added `features/agents/approvals.feature`: a tool that requires no approval runs beside one that waits
- added `features/agents/approvals.feature`: two tool calls made together are two approval requests, each decided alone
- added `features/agents/approvals.feature`: a session shows an approval request that is awaiting a decision
- added `features/agents/approvals.feature`: a session with no approval request is read as it was recorded
- added `features/agents/approvals.feature`: a stream ends with the approval request as its last part
- added `features/agents/approvals.feature`: a tool that requires no approval runs when the model calls it
- added `features/agents/approvals.feature`: compaction keeps an approval request that is awaiting a decision
- added `features/agents/approvals.feature`: an approval request with a time limit is refused when nobody decides it in time
- added `features/agents/approvals.feature`: an approval request with no time limit waits however long nobody decides it
- added `features/agents/approvals.feature`: a decision for an approval request that has expired is refused
- added `features/agents/approvals.feature`: an approval request's time limit holds after the service restarts
- added `features/agents/approvals.feature`: a session shows who decided an approval request
- added `features/agents/approvals.feature`: a decision that does not say who made it is refused
- added `features/agents/languages.feature`: a tool that requires approval waits for a decision in every language
- added `features/agents/languages.feature`: an approved tool call runs once after a restart in every language

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

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/agents/autonomous-approvals.feature`: every subscriber is told of an autonomous agent's approval request
- added `features/agents/autonomous-approvals.feature`: waiting for a decision uses none of the task's budget
- added `features/agents/autonomous-approvals.feature`: an autonomous agent's approved tool call runs once and the task goes on
- added `features/agents/autonomous-approvals.feature`: an autonomous agent's refused tool call never runs and the task goes on
- added `features/agents/autonomous-approvals.feature`: an autonomous agent's approval request is still awaiting a decision after the service restarts
- added `features/agents/autonomous-approvals.feature`: cancelling a task discards its approval requests
- added `features/agents/autonomous-approvals.feature`: suspending and resuming an autonomous agent leaves its approval request as it was
- added `features/agents/autonomous-approvals.feature`: a tool approved before the service stopped runs again when its result was not recorded
- added `features/agents/autonomous-approvals.feature`: an autonomous agent's approval request that expires is refused and the task goes on
- added `features/agents/autonomous-approvals.feature`: an autonomous agent shows who decided an approval request
- added `features/agents/languages.feature`: an autonomous agent's wait for a decision uses none of the task's budget in every language

---

### User Story 3 - A tool calls another service as the service (Priority: P2)

A developer's agent has a tool that reads a customer's balance from the wallet service. The tool
calls it through the service client in the agent's context; the wallet's endpoint admits the
agent's service by name and refuses everything else. The same tool, written in Python, makes the
same call through the sidecar.

**Why this priority**: Without it a tool is a caller with no name, and the domain plan's agents
cannot reach any service whose list admits by certificate.

**Independent Test**: Between two services under mutual TLS, an agent in one calls a second whose
route admits only the first by name; assert the call succeeds and that the same route refuses a
caller the list does not name. On k3s, where a deployed service can be given a scripted model,
assert the same and that the route refuses a plain request from the node.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/agents/service-calls.feature`: a tool's call is admitted by an ACL that names the agent's service
- added `features/agents/service-calls.feature`: a refusal by the called service reaches the model as the tool's error
- added `features/agents/service-calls.feature`: a tool's call to another service is in the trace inside the tool call
- added `features/agents/languages.feature`: a tool's call to another service is admitted in every language

---

### User Story 4 - An agent uses an MCP server's tools (Priority: P2)

A developer lists an MCP server on the agent's definition. At start the runtime discovers its two
tools and offers them to the model as `mcp__tickets__create` and `mcp__tickets__search`. The developer
marks the server as requiring approval, so a call to either waits for a person. When the server is
down at start, the service refuses to start, naming it.

**Why this priority**: It is the way an agent gains tools without the service wrapping each one;
it follows approvals because an untrusted server is the first thing that needs them.

**Independent Test**: Against a scripted MCP server in the test suite: start a service whose
agent lists it, assert the discovered tools appear in the plan, call one through the scripted
model, and assert the request the server saw; stop the server and assert the service's start
fails naming it.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/agents/mcp-servers.feature`: an MCP server's tools are offered to the model under the server's name
- added `features/agents/mcp-servers.feature`: a tool call to an MCP server's tool is made on the MCP server
- added `features/agents/mcp-servers.feature`: an error from an MCP server reaches the model as the tool's error
- added `features/agents/mcp-servers.feature`: every tool of an MCP server that requires approval waits for a decision
- added `features/agents/mcp-servers.feature`: a service whose agent lists an MCP server that cannot be reached does not start
- added `features/agents/mcp-servers.feature`: the platform connects to an MCP server that is a service as the agent's service
- added `features/agents/mcp-servers.feature`: an MCP server's tool with the name of one of the agent's own is offered beside it
- added `features/agents/mcp-servers.feature`: a tool an MCP server gains after the service started is not offered until the service restarts
- added `features/agents/mcp-servers.feature`: a tool an MCP server no longer has fails the tool call with the server's error
- added `features/agents/mcp-servers.feature`: an agent that lists one MCP server twice is refused where it is built
- added `features/agents/mcp-servers.feature`: the platform sends an MCP server the credential its agent lists for it
- added `features/agents/mcp-servers.feature`: a service whose agent takes a credential from a variable that is not set does not start
- added `features/agents/mcp-servers.feature`: an MCP server's credential is shown in no trace
- added `features/agents/mcp-servers.feature`: an MCP server's address is taken from a variable when one is set
- added `features/agents/mcp-servers.feature`: a service whose agent lists an MCP server with no address does not start
- added `features/agents/mcp-servers.feature`: a result guardrail keeps an MCP server's result from the model
- added `features/agents/mcp-servers.feature`: a result a result guardrail lets through reaches the model as the MCP server gave it
- added `features/agents/mcp-servers.feature`: a result guardrail does not check the result of one of the agent's own tools
- added `features/agents/mcp-servers.feature`: an agent with no result guardrail tells the model an MCP server's result as it was given
- added `features/agents/mcp-servers.feature`: an autonomous agent is offered an MCP server's tools
- added `features/agents/mcp-servers.feature`: a result guardrail keeps an MCP server's result from an autonomous agent's model
- added `features/agents/languages.feature`: an MCP server's tools are offered to the model in every language
- added `features/agents/languages.feature`: a result guardrail keeps an MCP server's result from the model in every language

---

### Edge Cases

- **An approval nobody decides.** It waits, for ever unless its tool or MCP server declares a time
  limit. When a declared limit passes, the platform decides it as refused, with a note saying it
  expired: the model is told as for any refusal, the session takes requests again, and an
  autonomous agent's task goes on. A `decide` that arrives afterwards is refused as already
  decided. Nobody is waiting for the model's answer to an expiry; it is in the session's history.
- **A turn cut off after its last decision.** When the service stops, or the continuation fails,
  after a request agent's last decision is recorded and before its turn ends, the turn is ended:
  nothing is added to the history, the session takes requests again, the tool is not run again,
  and whoever sent the decision is told the failure. An autonomous agent keeps its own rule: the
  tool runs again.
- **Two approval requests in one turn**, for two tools or for one tool called twice. Two ids,
  decided independently. The approved tool calls run when the last decision arrives, in the order
  the model made them, not as each is approved.
- **A decision from a different caller than the one who asked.** The session does not know who
  asked; who may decide is the endpoint's access control list, as every authorization is. The name
  a decision carries is what the endpoint says it is: the platform records it and checks only
  that it is there.
- **`decide` on a session with no agent.** Refused, naming the session.
- **An approval-requiring tool in a guardrail's path.** Input and output guardrails see messages
  and replies, never tool calls, so a refusal by guardrail happens before any approval request exists.
- **An approval request and compaction.** Compaction summarises messages; an approval request is not a
  message and is never compacted away.
- **An MCP server that changes its tools after start.** Not seen until the service restarts; a
  tool the server no longer has fails the call with the server's error.
- **An MCP server listed twice, or two servers with one name.** Refused where the definition is
  built.
- **An MCP server that wants a credential.** A header on the listed server, its value taken from
  a variable named `ANKKA_MCP_…` when the service starts. A variable that is not set fails the
  start naming it; a credential the server refuses fails the start as an unreachable server does,
  naming the server.
  A server that wants an OAuth flow, or a credential a person gives while the service runs, is
  not supported.
- **MCP tool results and guardrails.** A server's result is text from a program the service does
  not control, so an agent may declare result guardrails for it. Each runs on the result of a tool
  call to an MCP server's tool, after the server answers and before the model is told. A result
  one refuses is withheld: the model is told, as the tool's error, that the result was refused and
  why, and goes on. The result of the agent's own tool is not checked, as today, and neither is
  the error an MCP server answers with. A result guardrail that cannot decide fails the turn as
  any guardrail that cannot decide does. The model's reply still passes the output guardrails.
- **A service client call from a tool that outlives its request.** Tools run after the handler
  has returned, as today; the service client is a value on the agent's context and is there when
  the tool runs.

## Requirements *(mandatory)*

### Functional Requirements

**Approvals**

- **FR-001**: A tool MUST be declarable as requiring approval, in Scala, Python and TypeScript.
- **FR-002**: When the model calls such a tool, the loop MUST record an approval request in the
  session's journal, carrying an approval id, the tool's name and the arguments, and MUST NOT run
  the tool.
- **FR-003**: The handler's caller MUST receive an outcome saying approval is awaited, carrying
  the approval id, the tool's name and the arguments; on a streaming handler it MUST be the
  stream's last frame.
- **FR-004**: A session MUST accept a `decide` command carrying an approval id, a decision, the
  name of who decided and an optional note. A `decide` that names nobody MUST be refused and
  decide nothing; the name MUST be recorded with the decision and shown where the approval
  request is, and MUST NOT be what authorizes the decision. Approved runs the tool and continues
  the loop — exactly once across a restart before the decision, and at most once when the turn is
  cut off after it (see the edge case); refused continues the loop with the refusal and note as
  the tool's result. `decide` MUST answer its caller as the agent's handler would have: with the
  model's answer, or with an approval request that is still or newly awaiting a decision.
- **FR-005**: `decide` for an id the session holds no record of MUST be refused naming it; for an
  id the session or its history shows was decided, or expired, it MUST be refused as a conflict.
- **FR-006**: A new request on a session with an approval request awaiting a decision MUST be
  refused as a conflict.
- **FR-007**: An approval request MUST survive a restart of the service with no state outside the
  session's journal, and a session recorded before this feature MUST replay unchanged.
- **FR-008**: On an autonomous agent, an approval request MUST be published as an
  `ApprovalRequested` notification and decided by a call on the instance carrying what FR-004's
  does, which answers once the decision is recorded, the task going on as its notifications show.
  While it is awaiting a decision the task MUST NOT advance its iteration count, count a failure,
  or make a model call, however long the wait and whether or not the instance stays in memory.
- **FR-009**: Cancelling a task MUST discard its approval requests; suspend and resume MUST leave
  them unchanged.
- **FR-016**: A tool, and an MCP server, MUST be able to declare a time limit for approval, in a
  service that registers `TimerRuntime`; without it the declaration MUST be refused naming it, at
  start where it is known then and otherwise before anything is recorded. None is the default,
  and an approval request with none waits until it is decided or discarded. When a declared limit
  passes with no decision, the platform MUST decide the approval request as refused by the
  platform, with a note saying it expired, exactly once and across a restart; a later `decide`
  for it is FR-005's.

**Service client**

- **FR-010**: The agent's context MUST hold `ServiceClients`, the same value an endpoint
  receives, in Scala; a Python or TypeScript tool MUST reach the same through the SDK's service
  client over the sidecar's `Request`.

**MCP**

- **FR-011**: An agent definition MUST be able to list MCP servers by name, each with an address
  — a URL, an ankka service and a path, or neither, the variable `ANKKA_MCP_<SERVER>_URL` giving
  it and replacing the definition's when set — and each optionally requiring approval. A server
  with no address MUST fail the start naming the variable; a definition listing one server twice
  or two servers under one name MUST be refused where it is built.
- **FR-012**: At start the runtime MUST connect to every listed server over streamable HTTP,
  presenting the service's certificate where the server is an ankka service, discover its tools,
  and offer them to the model as `mcp__<server>__<tool>` with the server's schemas; a server that
  cannot be reached MUST fail the start naming it.
- **FR-013**: A call to a discovered tool MUST be made on the server with the model's arguments
  and the server's result fed back as the tool's; an error from the server MUST reach the model as
  a tool error.
- **FR-014**: The sidecar MUST discover and serve MCP tools for a process-hosted agent, adding
  them to every plan of that agent; discovery MUST carry the servers, the tools that require
  approval and the result guardrails, and the protocol's client service the approval-request
  outcome and the decision, under a minor version bump.
- **FR-017**: A listed MCP server MUST be able to carry HTTP headers, each with a name and the
  name of a variable its value is taken from when the service starts. The variable's name MUST
  start `ANKKA_MCP_`, and every variable of that prefix MUST reach the platform's program only
  and be withheld from a module. The platform MUST send the headers on every request to that
  server and to no other. A variable that is not set MUST fail the start naming the variable, the
  server and the agent. A header's value MUST NOT appear in the agent's definition, in discovery,
  in a log or in a trace. Headers are not sent in place of the service's certificate: a server
  that is an ankka service is still shown the certificate.
- **FR-018**: An agent, of either kind and in Scala, Python and TypeScript, MUST be able to declare
  result guardrails, separate from its input and output guardrails. Each MUST be run on the
  result of every tool call to an MCP server's tool before the model is told that result, and on
  no other tool's result. A result one refuses MUST NOT reach the model or the session's history;
  the model MUST be told, as the tool's error, that the result was refused and the guardrail's
  reason. A result guardrail that cannot decide MUST be treated as the platform treats any
  guardrail that cannot decide. An agent that declares none MUST behave as if the feature were
  absent. For a process-hosted agent the sidecar MUST ask the process to run them, under the
  protocol version FR-014 names.

**Documentation**

- **FR-015**: The agents and autonomous agents guides MUST document approvals, the service client
  in tools, MCP servers, their credentials and result guardrails with samples from tested code,
  and the limitations page MUST say what MCP support does not cover.

### Key Entities

- **Approval request** (formerly referred to as "pending call"): an approval id, a tool name, the
  model's arguments, an optional time limit, and its decision once made, recorded in the
  session's journal or the instance's record.
- **Decision**: approved or refused, who decided, and an optional note, made once per approval
  id, which it carries. An expiry is a decision the platform made.
- **Result guardrail**: a check an agent declares for the results of tool calls to MCP servers'
  tools; it lets a result through or refuses it with a reason.
- **MCP server**: a name; an address, which is a URL, an ankka service and a path, or a
  variable's value; whether its tools require approval and within what time limit;
  and the headers sent to it, each naming the variable its value comes from; its tools are
  discovered at start and named for it.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An approval-requiring tool runs exactly once across a service restart between the
  model's call and the decision, in the testkit suite.
- **SC-002**: An autonomous task that is awaiting a decision has, after a wait longer than its
  passivation interval, the iteration count it had when the call was made, and the model has not
  been called.
- **SC-003**: A tool's call to a second service is admitted by a caller list that names the first
  service, and the same route refuses a caller the list does not name, between two services under
  mutual TLS; and on k3s where a deployed service can be given a scripted model.
- **SC-004**: Two tools of a scripted MCP server appear in the model's plan by name and one is
  invoked with the model's arguments, in the agent suite.
- **SC-005**: SC-001, SC-002 and SC-004 hold for a Python and a TypeScript agent through the
  sidecar in the conformance suite; SC-003 does once 025-polyglot-service-client is on `main`.

## Assumptions

- A session's one-at-a-time rule and its event sourced memory are sufficient to hold an approval
  request durably; no new table is needed.
- `ServiceClients` built for endpoints can be handed to the agent runtime's contexts without
  change.
- Streamable HTTP is the MCP transport the servers a service will meet speak; stdio servers are
  not hosted in a pod.
- The protocol's minor version bump is accepted by the platform's existing version rule.
- A service built as a module (Rust) is out of scope: the feature reaches Scala, Python and
  TypeScript agents, and a module's agent is unchanged.

## Dependencies

- 025-polyglot-service-client, for the `Request` RPC a Python or TypeScript tool calls through.
  The Scala contexts' `ServiceClients` field, which both features ask for, is added by whichever
  lands first.
- Gates stage 5 of the domain plan: the support agent's confirm-before-write and the proposal
  pipeline's human gates.
