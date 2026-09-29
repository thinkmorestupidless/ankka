# Quickstart: validating autonomous agents

Each scenario names the suite that automates it and, where one exists, the by-hand check. Docker
is required for everything past §1. No k3s suite is needed by this feature; pass
`-Dankka.cluster.tests=off` to keep the rest of the tree fast.

## 0. The spike, first

```bash
sbt 'agent/testOnly *RememberEntitiesSpike'
```

Expected (R4): a sharded entity type initialised with `rememberEntities = true` and the
event-sourced store over the r2dbc journal is restarted, on the same `AnkkaTestKit` Postgres, after
`restartService()` with no message sent to it; the journal holds rows whose persistence id starts
`/sharding/`; no DDL was added. If this fails, the plan's fallback (an instances table and a
sweeper) replaces R4 before anything else is built.

## 1. Offline: the records and the schema

```bash
sbt 'agent/testOnly *TaskEntitySuite *InstanceEntitySuite *JsonSchemaSuite *NotificationCodecSuite *AutonomousAgentDefinitionSuite *EventCompatibilitySuite'
```

Expected: every transition and refusal in `data-model.md` §2 and §3 on `EventSourcedTestKit`;
`JsonSchema.derived` for the sample's `Answer` equals the schema in the contract, and a sum-typed
field does not compile; every notification round-trips with `"type"`; a definition with no
description, no acceptance, a budget of 0 or a tool named `complete_task` is refused at
`descriptor`; the events' JSON fixtures are unchanged.

## 2. Whole service: the loop, the budget, the rules, the crash

```bash
sbt 'testkit/testOnly *AutonomousAgentSuite'
```

Expected, with `TestModelProvider`: US1 — a task runs to a typed result, `awaitTask` returns it,
the model was called once per iteration; US3 — a never-completing script fails the task after
exactly N calls and the next queued task starts; US4 — a rejected result reaches the next request
and the task completes on the accepted one; US5 — assign, suspend (no model call while suspended),
resume, terminate (tasks back to pending, id refuses assignment), state; US6 — a subscriber sees
the ordered sequence and a late subscriber sees no replay; US7 — an exhausted script fails the task
naming the script. US2 — `restartService()` after the second iteration is recorded: the task
completes with no further message from the test, and `model.callCount` equals the iterations the
task needed.

```bash
sbt 'testkit/testOnly *AutonomousAgentHandoffSuite'
```

Expected: a second node started with `startPeer()` finishes a task the first node was working when
the first stops; no iteration is repeated.

## 3. The sidecar and the SDKs

```bash
sbt sidecar/Docker/publishLocal
sbt 'sidecar/testOnly *RemoteAutonomousAgentSuite *ProtocolSuite'
sbt 'sidecar/testOnly *ConformanceSuite -- *auto.*'                                   # the Scala reference, in-process
cd sdks/python && uv sync && uv run pytest -q && uv run mypy && uv run conformance     # Python: unit, example, conformance
cd sdks/typescript && npm ci && npm run proto && npm run typecheck && npm test && npm run conformance
```

Expected: `ProcessDouble` cases — a rule check that never answers is an iteration failure and a
retry; a process restart mid-task resumes it; a 1.1 SDK is accepted by the 1.2 sidecar and a 2.0
one refused. Every `auto.*` case in `contracts/conformance.md` green on all three targets;
`test_cart.py`'s scripted-sidecar autonomous test green, including `kit.restart()` mid-task.

## 4. By hand: the sample

```bash
docker compose up -d
sbt shoppingCart/run
curl -X POST localhost:9000/catalogue/ask -d 'How many red items are there?'    # → {"taskId": "…", "instanceId": "…"}
curl -N localhost:9000/catalogue/answerer/<instanceId>/notifications             # SSE, JSON per event
curl localhost:9000/catalogue/tasks/<taskId>                                      # status, then the answer
```

With `ANTHROPIC_API_KEY` set the answerer uses the real model; without it the sample scripts a
`TestModelProvider` so the flow runs offline. Kill `sbt shoppingCart/run` between the first and the
last curl, start it again, and read the task: it completes. The Python sample does the same from
`sdks/python/examples/shopping_cart` with the sidecar from `sbt sidecar/Docker/publishLocal`.

## 5. Docs

```bash
just docs-sync && just docs
```

Expected: both new pages in the navigation and in `ankka-agents` (and `ankka-python`); every
`include:` fresh; the protocol table lists `CheckTaskResult`; `limitations.md` names what this phase
leaves out; the rendered skills in `marketplace/` and `ankka.g8/` refreshed; `sbt compile`
warning-free.
