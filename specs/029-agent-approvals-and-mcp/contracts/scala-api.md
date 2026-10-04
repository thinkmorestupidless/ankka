# Contract: the Scala API

What a developer writing a Scala service sees. Decisions are in [research.md](../research.md);
stored forms are in [data-model.md](../data-model.md). Signatures are the intended shape; names
inside `private[ankka]` are not part of the contract.

## Declaring that a tool requires approval

```scala
val issueRefund = FunctionTool
  .named("issue_refund")
  .describedAs("Refund an order")
  .param[String]("orderId", "the order")
  .handle(orderId => refunds.issue(orderId))
  .requiresApproval                    // waits until decided
  // or .requiresApproval(30.minutes)   // refused by the platform when the limit passes
```

A tool that declares nothing behaves as today. A time limit needs `TimerRuntime` registered
(R10).

## Calling an agent that may wait

```scala
enum AgentOutcome[+O]:
  case Answered(value: O)
  case AwaitingApproval(requests: Vector[ApprovalRequest])

final case class ApprovalRequest(
    id: String, tool: String, arguments: Json, requestedAt: Instant, expiresAt: Option[Instant],
    decision: Option[Decision])

final case class Decision(approvalId: String, approved: Boolean, by: String, note: Option[String],
    at: Instant, expired: Boolean)
object Decision:
  def approved(by: String): Decision
  def refused(by: String, note: String = ""): Decision
```

On `AgentCalls` (`componentClient.forAgent(sessionId)`):

| Method | Answers |
|---|---|
| `ask(handle).invoke(input)` | `AgentOutcome[O]` |
| `call(handle).invoke(input)` | `O`, as today; throws `ApprovalAwaited(requests)` when the turn waits |
| `streamParts(handle)(input)` | `Source[AgentPart, NotUsed]`; `AgentPart.Text(text)`, then at most one `AgentPart.AwaitingApproval(requests)`, last |
| `stream(handle)(input)` | `Source[String, NotUsed]`, as today; fails with `ApprovalAwaited` at its end when the turn waits |
| `decide(handle)(approvalId, decision)` | `AgentOutcome[O]`: the answer, or the requests still or newly awaiting |
| `approvals()` | the session's approval requests awaiting a decision |

`handle` in `decide` is the handle of the handler that was called. For a stream handle the
outcome's value is the whole text.

### Refusals

| Case | Code |
|---|---|
| a request on a session with an approval request awaiting | `Conflict` |
| `decide` for a request the session, or a tool result in its history, shows was decided or expired | `Conflict` |
| `decide` for an id with no record — never asked, discarded, of a turn that was cut off, or of a history that is not written or was compacted away | `NotFound`, naming the id |
| `decide` on a session that holds nothing | `NotFound`, naming the session |
| `decide` with a blank `by` | `BadRequest` |
| `decide` with a handle that is not the suspended handler's | `BadRequest`, naming the handler that waits |
| a time limit with no `TimerRuntime` | `Internal`, naming it; nothing is recorded |
| the continuation fails after the last decision | the failure's own code; the turn is ended |

A handler may not be named with the prefix `ankka:`.

## An endpoint that serves approvals

```scala
post("/chat/{session}") { (session: String, q: Question) =>
  clients.componentClient.forAgent(SessionId(session)).ask(SupportAgent.ask).invoke(q) match
    case AgentOutcome.Answered(text)          => Respond(Reply(text), 200)
    case AgentOutcome.AwaitingApproval(asked) => Respond(Awaiting(asked), 202)
}

post("/chat/{session}/approvals/{id}") { (session: String, id: String, d: Verdict) =>
  val by = RequestContext.principal.subject        // who may decide is this route's ACL
  clients.componentClient.forAgent(SessionId(session))
    .decide(SupportAgent.ask)(id, if d.approve then Decision.approved(by) else Decision.refused(by, d.note))
}
```

For a stream, `http` gains `SseEvent(name: Option[String], data: String)` with
`SseEvent.data(text)` and `SseEvent.json(name, value)`, and a stream route accepts a source of
them, so the approval request is sent as an event named `approval`. A stream route of `String`
is unchanged.

## An autonomous agent

A tool requires approval as above. On `AutonomousAgentCalls`:

| Method | Answers |
|---|---|
| `decide(approvalId, decision)` | `Done`, once the decision is recorded; the task goes on as its notifications show |
| `state()` | `AgentState`, now with `awaiting: Vector[ApprovalRequest]` |
| `notifications()` | now also `Notification.ApprovalRequested` and `Notification.ApprovalDecided` |

Refusals are `decide`'s above, with "instance" for "session"; a request discarded by a cancelled
task is `NotFound`.

## The service client in a tool

```scala
trait AgentContext:            // and AutonomousAgentContext
  def services: ServiceClients // new; the value an endpoint receives

final class SupportAgent(context: AgentContext) extends Agent:
  private val readBalance = FunctionTool.named("read_balance").describedAs("…")
    .param[String]("customer", "the customer")
    .handle(c => context.services("wallet").getText(s"/balances/$c"))
```

A refusal the called service makes is thrown in the tool as `ServiceCallFailed`, and reaches the
model as the tool's error, as any exception in a tool does.

## MCP servers

```scala
object SupportAgent extends Agent.Companion[SupportAgent](ComponentId("support")):
  override def mcpServers = Vector(
    McpServer.at("tickets", "https://tickets.example.com/mcp")
      .header("Authorization", variable = "ANKKA_MCP_TICKETS_TOKEN")
      .requiresApproval(30.minutes),
    McpServer.service("catalogue", service = "catalogue", path = "/mcp"),   // an ankka service
    McpServer.named("search")                                               // address from ANKKA_MCP_SEARCH_URL
  )
  override def resultGuardrails = Vector(
    Guardrail.forbidding("no-instructions", "(?i)ignore (all|previous)".r),
    Guardrail.judged("no-injection").onResult(Refuse.ifYes(Questions.isInjection))
  )
```

An autonomous agent's definition has `.mcpServers(...)` and `.resultGuardrails(...)`.

- Each tool is offered to the model as `mcp__<server>__<tool>`, with the server's description and
  schema, beside the tools of every effect the agent's handlers return.
- Refused where the definition is built: a server's name outside `[a-z0-9-]`, one name twice, a
  header's variable that does not start `ANKKA_MCP_`, a tool of the agent's own named `mcp__…`.
- Fails the start, naming the server and the agent: no address; a server that cannot be reached
  or refuses the platform; a header's variable that is not set (named too); a tool whose whole
  name is longer than 128 characters (named too); a time limit with no `TimerRuntime`.

### Result guardrails

`Guardrail` gains `def checkResult(text: String): Either[String, Unit]`, allowing by default.
`Guardrail.forbidding` and `Guardrail.maxInputLength`-style guardrails check a result as they
check input; a judged guardrail checks one with `onResult(rules*)`. A refusal withholds the
result: the model is told an error naming the guardrail and its reason, and the refused text is
kept nowhere. A fault is `GuardrailCheckFailed`, as for any guardrail.

## Configuration

| Key | Variable | Default | Meaning |
|---|---|---|---|
| `ankka.agent.mcp.connect-timeout` | `ANKKA_MCP_CONNECT_TIMEOUT` | `10s` | per server, at start |
| `ankka.agent.mcp.call-timeout` | `ANKKA_MCP_CALL_TIMEOUT` | `60s` | per tool call |
| — | `ANKKA_MCP_<SERVER>_URL` | none | a server's address; replaces the definition's |
| — | `ANKKA_MCP_…` named by a header | none | a header's value |

All `ANKKA_MCP_` variables go to the platform's program only, and a module's `config` answers
them absent.

## Test kits

- `TestMcpServer` (in `ankka-agent`, beside `TestModelProvider`): `tool(name, description,
  schema)(answer)`, `failNext(tool, error)`, `answerAsEventStream(on)`, `requests`, `url`,
  `stop()`. Loopback, ephemeral port.
- `AnkkaTestKit.start(..., variables = Map("ANKKA_MCP_TICKETS_URL" -> server.url))` sets the
  variables the runtime reads, without touching the environment.
- Approvals need nothing new: `TestModelProvider.expectToolCall`, then `ask`, `restartService()`
  and `decide`.
