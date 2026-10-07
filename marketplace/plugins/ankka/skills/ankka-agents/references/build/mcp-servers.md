# MCP servers

> Offer an agent the tools of MCP servers beside its own — where each server is, the credential it is sent, approval for a whole server, result guardrails that check what a server answers — and test it against a scripted server.

Source: https://docs.ankka.cloud/build/mcp-servers/
An agent can list **MCP servers** — programs that offer tools over the Model Context Protocol — and the
model is offered their tools beside the agent's own. The platform connects to each server when the
service starts, reads its tools, and offers each as `mcp__<server>__<tool>`: a server `tickets` with a
tool `create` gives the model `mcp__tickets__create`. When the model calls one, the platform sends the
call to the server and tells the model what it answered. Your code never runs a server's tool, and in
Python and TypeScript the process never sees a server's credential.

## Listing a server

A server is listed under a name of lower-case letters, digits and hyphens, with where it is and,
optionally, the headers it is sent and whether its tools wait for a person.

**Scala**

```scala
object McpAgent extends Agent.Companion[McpAgent](ComponentId("ticket-agent")):

  override def mcpServers: Vector[McpServer] = Vector(
    // Found at ANKKA_MCP_TICKETS_URL, and sent the credential in ANKKA_MCP_TICKETS_TOKEN.
    McpServer.named("tickets").header("Authorization", "ANKKA_MCP_TICKETS_TOKEN"),
    // Every tool of this one waits for a person; its variable replaces the address given here.
    McpServer.at("guarded", "https://guarded.example.com/mcp").requiresApproval
  )

  // What an MCP server answers is checked before the model is told it.
  override def resultGuardrails: Vector[Guardrail] = Vector(
    Guardrail.forbidding("no-instructions", "(?i)ignore what you were told".r)
  )
```

**Python**

```python
async def _refund(agent: Agent, arguments: RefundArguments) -> str:
    assert agent.client is not None
    await agent.client.for_event_sourced_entity("conformance", arguments.id).call("record").invoke("refunded", reply=str)
    return f"refunded {arguments.id}"


async def _ask_scripted(agent: Agent, arguments: PathArguments) -> str:
    # A tool calls another service as this service: the called service's ACL can admit it by name.
    return await agent.services("scripted").get_text(arguments.path)


def _no_instructions(tool: str, text: str) -> str | None:
    return "the result tries to instruct whoever reads it" if "ignore what you were told" in text.lower() else None


class Approver(Agent):
    component_id = "approver"
    tools = {
        "refund": Tool("Refunds what was recorded under an id. A person approves every refund.", _refund, RefundArguments, approval=True),
        "ask_scripted": Tool("Asks the scripted service for what is at a path.", _ask_scripted, PathArguments),
    }
    # Both found at ANKKA_MCP_<NAME>_URL; every tool of `guarded` waits for a person.
    mcp_servers = {"tickets": McpServer(), "guarded": McpServer(approval=True)}
    result_guardrails = {"no-instructions": ResultGuardrail(_no_instructions)}

    @command("ask")
    def ask(self, question: str) -> AgentEffect[str]:
        return self.effects.system_message("You approve refunds.").user_message(question).tools("refund", "ask_scripted").then_reply()
```

**TypeScript**

```ts
export class Approver extends Agent {
  static readonly componentId = "approver"
  static readonly tools = {
    refund: tool(
      "refund",
      "Refunds what was recorded under an id. A person approves every refund.",
      RefundArguments,
      async (a: Approver, input) => {
        await a.client.of(Conformance, input.id).call(Conformance.handlers.record).invoke("refunded")
        return `refunded ${input.id}`
      },
      { approval: true },
    ),
    // A tool calls another service as this service: the called service's ACL can admit it by name.
    askScripted: tool("ask_scripted", "Asks the scripted service for what is at a path.", PathArguments, (a: Approver, input) =>
      a.services.service("scripted").getText(input.path),
    ),
  }
  // Both found at ANKKA_MCP_<NAME>_URL; every tool of `guarded` waits for a person.
  static readonly mcpServers = { tickets: mcpServer("tickets"), guarded: mcpServer("guarded", { approval: true }) }
  static readonly resultGuardrails = {
    noInstructions: resultGuardrail("no-instructions", (_tool, text) =>
      /ignore what you were told/i.test(text) ? "the result tries to instruct whoever reads it" : null,
    ),
  }
  static readonly handlers = {
    ask: command("ask", s.string, s.string, (a: Approver, question) =>
      a.effects.systemMessage("You approve refunds.").userMessage(question).tools("refund", "ask_scripted").thenReply(),
    ),
  }
}
```

An autonomous agent lists servers the same way, on its definition in Scala
(`define.mcpServers(...).resultGuardrails(...)`) and as class attributes in Python and TypeScript.

## Where a server is

A server's address is one of three things:

| Declared as | Where the platform connects |
|---|---|
| a URL (`McpServer.at(name, url)`, `url=…`) | that URL, unless `ANKKA_MCP_<NAME>_URL` is set, which replaces it |
| an ankka service (`McpServer.service(name, service, path)`, `service=…`) | that service, called as this one: on the platform it presents this service's certificate, so the server's ACL admits it by name |
| neither (`McpServer.named(name)`, `McpServer()`) | the URL in `ANKKA_MCP_<NAME>_URL`, which must be set |

`<NAME>` is the server's name in upper case with `-` as `_`: the server `ticket-desk` is found at
`ANKKA_MCP_TICKET_DESK_URL`. Setting the address in the descriptor's environment, rather than in code,
lets one image talk to a different server in each environment.

The platform speaks the protocol's Streamable HTTP transport, version `2025-06-18`: it initializes a
session, lists the server's tools a page at a time, and calls them, reading an answer sent as JSON or
as an event stream. A session the server forgets is started again once. A tool's error result, and a
protocol error, are told to the model as the tool's error.

## Credentials

A header's value is never written in the declaration. The declaration names the header and an
environment variable starting `ANKKA_MCP_`, and the platform reads the value from that variable when
the service starts:

```scala
McpServer.named("tickets").header("Authorization", "ANKKA_MCP_TICKETS_TOKEN")
```

The value is sent on every request to that server and no other, and appears in no definition,
discovery, log or trace. Variables starting `ANKKA_MCP_` go only to the platform's own container: for a
Python or TypeScript service that is the sidecar, which is what connects to the server, so the process
never holds the credential. A declaration that names a variable without the prefix is refused where the
agent is defined.

Give the value as a project secret, so it is held by the platform rather than written in a descriptor:

```json title="service.json"
{
  "name": "support",
  "service": {
    "image": "registry.example.com/acme/support:1.4.0",
    "env": [
      { "name": "ANKKA_MCP_TICKETS_URL", "value": "https://tickets.example.com/mcp" },
      { "name": "ANKKA_MCP_TICKETS_TOKEN", "secretKeyRef": { "name": "tickets", "key": "token" } }
    ]
  }
}
```

## Approval for a whole server

A server listed with approval has every one of its tools wait for a person, exactly as a tool of the
agent's own that requires approval does: the caller is answered with an approval request, the server is
sent nothing until a person approves, and a refusal is told to the model. A time limit
(`requiresApproval(30.minutes)`, `Approval(within=…)`, `{ withinMs }`) has the platform refuse the request
when it passes, which needs a `TimerRuntime`. See [Agents](agents.md#tools-that-wait-for-a-person).

## Result guardrails

What a server answers is text the agent did not write, and it can try to instruct the model. A **result
guardrail** checks what a server's tool answered before the model is told it. A result it refuses is
never told to the model and never written to the session: the model is told the tool's result was
withheld, naming the guardrail and its reason, and goes on.

Result guardrails are declared apart from an agent's other guardrails, and check only what MCP servers
answer — never the result of the agent's own tools, which are your code, and never a result that is
already an error. In Scala a result guardrail can be any `Guardrail`, including a
[judged guardrail](judgments.md#judged-guardrails) built with `onResult(...)`. In Python and TypeScript it
is a function of the tool's name and the text, answering a reason or nothing.

## What fails the start

The servers are connected when the service starts, and a service whose agent cannot have its servers
does not start, naming the agent and the server:

- a server with no address, because neither a URL nor `ANKKA_MCP_<NAME>_URL` was given;
- a header whose variable is not set;
- a server that does not answer, or refuses the connection, within `ankka.agent.mcp.connect-timeout`;
- two servers under one name, or an agent tool whose own name starts `mcp__`.

A server that goes away after the service started is not a start-up problem: a call to it is the tool's
error, which the model is told. Each call waits at most `ankka.agent.mcp.call-timeout`.

## Testing

`TestMcpServer` is an MCP server on loopback that a test scripts: tools that answer with text or a whole
result, an error result or a protocol error on the next call, a credential it requires, a lost session.
It records every request, so a test asserts what the server was sent. Give the agent runtime the
server's address with `withVariables`, which reads variables from a map instead of the environment:

```scala
tickets = TestMcpServer()
  .tool("create", "Opens a ticket", schema = titleSchema)(args =>
    s"opened '${args("title").flatMap(_.asString).getOrElse("?")}'"
  )
  .tool("search", "Finds tickets")(_ => "2 tickets found")
  // Answers with what it is given, so a test says exactly what the server sends back.
  .tool("fetch", "Fetches a ticket's text")(args =>
    args("text").flatMap(_.asString).getOrElse("")
  )
  .requireHeader("Authorization", Token)
guarded = TestMcpServer().tool("close", "Closes a ticket")(_ => "closed")
kit = AnkkaTestKit.start(
  Seq(McpAgent.descriptor, McpOperator.descriptor, GuardedOperator.descriptor) ++
    AgentRuntime.descriptors,
  Seq(
    AgentRuntime
      .withDefaultModel(model)
      .withJudgments(judge)
      .withVariables(variables(tickets.url).get),
    ProjectionRuntime()
  )
)
```

The Python and TypeScript unit test kits take scripted servers as functions:
`AgentTestKit.of(Agent, mcp={"tickets": {"create": fn}})` and
`AgentTestKit.of(Agent, sessionId, model, client, { mcp: { tickets: { create: fn } } })`. The model calls
them by their offered names, and the agent's result guardrails check what they answer.
