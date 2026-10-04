# Quickstart: validating agent approvals and MCP

The runs that show the feature works, cheapest first. Docker is required from step 2. Contracts:
[scala-api](contracts/scala-api.md), [protocol](contracts/protocol.md),
[sdk-apis](contracts/sdk-apis.md). Data: [data-model.md](data-model.md).

Every command switches the k3s suites off unless it is one; a k3s run is minutes, and belongs
under `caffeinate -i` on a laptop.

## 1. Pure: tools, folds, the MCP client

```bash
sbt -Dankka.cluster.tests=off 'agent/testOnly *FunctionToolSuite *McpClientSuite *McpServerSuite *GuardrailsSuite' \
    'core/testOnly *PlatformVariablesSuite'
```

Expect: a tool's approval and time limit; a server's name rule and the refusals where a
definition is built; the client against `TestMcpServer` answering as JSON and as an event stream,
a cursor across two pages, a lost session started again once, an error result and a JSON-RPC
error each as a tool error; `ANKKA_MCP_` routed to the platform's program and withheld from a
module.

## 2. Approvals on a real journal

```bash
sbt -Dankka.cluster.tests=off 'testkit/testOnly *ApprovalSuite *SessionMemoryCompatibilitySuite *HttpSseSuite'
sbt -Dankka.cluster.tests=off 'testkit/testOnly *AutonomousApprovalSuite *ResumePointSuite *EventCompatibilitySuite'
```

Expect one case per scenario of `features/agents/approvals.feature` and
`autonomous-approvals.feature`, including a restart between the model's call and the decision, an
expiry at a limit of two seconds, and a wait during which the model's call count holds.

To see it fail: make the loop run a tool that requires approval (drop the check in the shared
tool runner); the first case of each suite goes red on "the tool has not run". Make
`IterationStarted` not clear `approvals`; the next iteration waits for ever and the
approved-continues case times out.

## 3. MCP servers and result guardrails in a whole service

```bash
sbt -Dankka.cluster.tests=off 'testkit/testOnly *McpAgentSuite'
```

Expect one case per scenario of `features/agents/mcp-servers.feature` except the one where the
server is a service: tools offered under their names, a call reaching the scripted server, a
server down at start failing the start by name, an unset variable failing it by name, a
credential in no trace (after asserting the tool call's span is there), a refused result never
reaching the model's next request or the session.

## 4. A tool calling a service, and an MCP server that is one

```bash
sbt -Dankka.cluster.tests=off 'testkit/testOnly *AgentServiceCallSuite'
```

Two services under TLS in one JVM: the called one admits only the caller's name. Expect the call
admitted, a second caller refused, the refusal reaching the model as the tool's error, and the
service call recorded inside the tool call.

## 5. A process, through the real sidecar

```bash
sbt -Dankka.cluster.tests=off 'sidecar/testOnly *ProtocolSuite *RemoteAgentSuite *RemoteAutonomousAgentSuite'
sbt -Dankka.cluster.tests=off 'sidecar/testOnly *ConformanceSuite -- *approval.* *mcp.*'
cd sdks/python && uv sync && uv run pytest -q && uv run mypy && ANKKA_CONFORMANCE_ONLY='*approval.*' uv run conformance && ANKKA_CONFORMANCE_ONLY='*mcp.*' uv run conformance
cd sdks/typescript && npm ci && npm run proto && npm run typecheck && npm test && ANKKA_CONFORMANCE_ONLY='*approval.*' npm run conformance && ANKKA_CONFORMANCE_ONLY='*mcp.*' npm run conformance
cd sdks/rust && cargo test --workspace && ./conformance.sh
```

Expect the cases of [protocol.md](contracts/protocol.md) per target, and the Rust run unchanged
in what it passes. **Read what each run says it ran**: a filter without its leading `*` matches
nothing and reports green, and the Rust script must print both shapes.

## 6. A real MCP server

```bash
npx -y @modelcontextprotocol/server-everything streamableHttp &     # prints its port
sbt -Dankka.spikes=on -Dankka.mcp.spike.url=http://127.0.0.1:3001/mcp 'agent/testOnly *McpServerSpike'
```

Expect `initialize`, the tool list and one `tools/call` (`echo`) to succeed against a server this
repository did not write. This is the check the scripted server cannot be: it was written beside
the client.

## 7. k3s

```bash
caffeinate -i sbt 'controlPlane/testOnly *EndToEndClusterSuite -- *tool*calls*'
```

Expect a tool's call admitted by a caller list that names the agent's service, and the same
route refused from the node (SC-003).

## 8. Documentation and features

```bash
just docs-sync && just docs
just features
```

Expect the docs build to pass with the new page in the nav and a skill, every sample taken from
tested code, and the features check to find nothing in `features/agents/` or spec 029.

## 9. By hand

`docker compose up -d`, a service with one agent and `issue_refund` requiring approval, the
default scripted model replaced by a real one:

```bash
curl -s localhost:9000/chat/s1 -d '{"text":"refund order 7"}'           # 202, an approval request
curl -s localhost:9000/chat/s1 -d '{"text":"hello?"}'                   # 409, awaiting a decision
curl -s localhost:9000/chat/s1/approvals/<id> -d '{"approve":true}'     # 200, the model's answer
curl -s localhost:9000/chat/s1/approvals/<id> -d '{"approve":true}'     # 409, decided
```

Stop the service between the first and third call to see the request survive.

## The whole build

```bash
caffeinate -i sbt buildAll
```
