# Contract: the Python and TypeScript SDKs

The same behaviour as [scala-api.md](scala-api.md), over [protocol.md](protocol.md). The sidecar
runs the loop, keeps the session, connects to MCP servers and enforces approval; the process
declares, and answers a result guardrail's check. The Rust crate gains nothing but its protocol
copy.

## Python (`sdks/python/src/ankka`)

```python
class SupportAgent(Agent):
    component_id = "support"
    tools = {
        "issue_refund": Tool("Refund an order", issue_refund, RefundInput,
                             approval=Approval(within=timedelta(minutes=30))),   # or approval=True
    }
    mcp_servers = {
        "tickets": McpServer(url="https://tickets.example.com/mcp",
                             headers={"Authorization": "ANKKA_MCP_TICKETS_TOKEN"},
                             approval=True),
        "catalogue": McpServer(service="catalogue", path="/mcp"),
        "search": McpServer(),                       # address from ANKKA_MCP_SEARCH_URL
    }
    result_guardrails = {
        "no-instructions": ResultGuardrail(check_result),   # (tool: str, text: str) -> str | None
    }
```

`AutonomousAgent` takes the same three class variables.

Calling:

| Call | Answers |
|---|---|
| `await client.for_agent(session).ask(Agent.handler, input)` | `Answered(value)` or `AwaitingApproval(requests)` |
| `await client.for_agent(session).call(...)` | the value, as today; raises `ApprovalAwaited(requests)` |
| `async for part in client.for_agent(session).stream_parts(...)` | `str` parts, then at most one `AwaitingApproval`, last |
| `await client.for_agent(session).decide(Agent.handler, approval_id, approved=True, by="dana", note=None)` | as `ask` |
| `await client.for_autonomous_agent(Cls, instance).decide(approval_id, approved, by, note=None)` | `None` |

`AgentState` gains `awaiting`; the notification types gain `ApprovalRequested` and
`ApprovalDecided`. A refusal is the `CommandError` it is today, with the codes of the Scala
contract. `decide` against a runtime older than 1.N raises the SDK's "needs protocol 1.N" error.

Unit test kit: `AgentTestKit.of(cls, …, mcp={"tickets": {"create": fn}})`; `AgentReply` gains
`awaiting`; `kit.decide(approval_id, approved, by, note=None)` goes on from it. The kit runs
result guardrails on a scripted MCP tool's result.

## TypeScript (`sdks/typescript/src`)

```ts
class SupportAgent extends Agent {
  static componentId = "support";
  static tools = {
    issueRefund: tool("issue_refund", "Refund an order", RefundInput, issueRefund,
                      { approval: { withinMs: 30 * 60_000 } }),           // or { approval: true }
  };
  static mcpServers = {
    tickets: mcpServer({ url: "https://tickets.example.com/mcp",
                         headers: { Authorization: "ANKKA_MCP_TICKETS_TOKEN" }, approval: true }),
    catalogue: mcpServer({ service: "catalogue", path: "/mcp" }),
    search: mcpServer({}),
  };
  static resultGuardrails = {
    noInstructions: resultGuardrail("no-instructions", (tool, text) => null),
  };
}
```

Calling: `forAgent(session).ask(handler, input)` returns
`{ kind: "answered", value } | { kind: "awaiting-approval", requests }`; `call` throws
`ApprovalAwaited`; `streamParts` yields strings and at most one awaiting part, last;
`decide(handler, approvalId, { approved, by, note })` returns as `ask`;
`forAutonomousAgent(...).decide(approvalId, { approved, by, note })`.

Unit test kit: `AgentTestKit.of(cls, sessionId, model, client, { mcp })`, `ask` returning the
outcome, `decide`.

Erasable syntax only, as the package requires: the outcome is a union of object types, not an
`enum`.

## Both

- A server's key is its name and follows the name rule; the SDK refuses a bad one where the class
  is registered, with the message the sidecar would give.
- The process is never sent an MCP server's tool to run, and never sees a header's value: the
  variable is the sidecar's.
- A result guardrail that raises is a block, as a guardrail that raises is today.
- The conformance reference of each SDK gains the agent the new cases call: one tool with
  approval, the server `tickets` declared by name, one result guardrail, and the routes that call
  `ask` and `decide`. `discovery.lists-every-component` compares an exact set, so the Scala
  reference, both SDK references and the Rust reference's list change together.

## The tool's service call

`client.services(name)` is 025's. This feature adds the scenario and the conformance case that
call it from a tool, when 025 is on `main`.
