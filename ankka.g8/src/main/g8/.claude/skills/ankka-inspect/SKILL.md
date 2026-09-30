---
name: ankka-inspect
description: Verify that an ankka service running on this machine does what its specification says — build and test it, run it, check the components it registered against the design, exercise each acceptance scenario through its real HTTP endpoints, confirm the resulting state with its declared queries, and read the traces — then report each scenario as met or not, with the evidence. Use after implementing a feature or a change, when asked to check, inspect, verify, smoke-test or demo a service against a spec, acceptance criteria or a plan, or before deploying one.
---

# Inspecting a running service against its specification

Tests prove what their author thought to test. Inspection runs the service as it will run deployed, and
checks what the specification says it must do, through the same doors its callers use. It needs the
`ankka mcp` server's local tools: `list_local_services`, `describe_local_service`, `call_local_endpoint`,
`query_local_entity`, `local_traces` and `local_agent_session`. When they are not available, stop and say
that `ankka mcp` is not registered (`references/get-started/coding-agents.md` shows how) rather than
falling back to guessing from the code.

Inspection reports; it does not repair. A scenario that is not met is a finding with its evidence, and
the fix is a separate step, after which inspection runs again from the start.

## 1. Know what "met" means before running anything

Collect the acceptance scenarios first, from the most specific source there is:

1. A feature specification's acceptance scenarios (`specs/<feature>/spec.md`, given / when / then) and
   its measurable success criteria.
2. Acceptance criteria in the task, the issue or the pull request.
3. The user, asked directly.

Never invent a scenario from the code: a check derived from the implementation passes by construction.
If the design has a component table (requirement → component → why, see the `ankka-design` skill),
collect that too.

Write each scenario down as a request, an expected response (status and the part of the body that
matters), and the state that must hold afterwards. A scenario that cannot be written that way is not yet
checkable; say which one and why, rather than checking something nearby.

## 2. Build, test, run

Run the project's own tests first; a service whose tests fail is not inspected.

| Language | Test | Run locally |
|---|---|---|
| Scala | `sbt test` | `sbt schema`, `docker compose up -d`, then `sbt run` (HTTP on 9000) |
| Python | `uv run pytest` | `docker compose up -d` (Postgres and the sidecar), then the process: `uv run python -m <module>.main` |
| TypeScript | `npm test` | `docker compose up -d`, then `npm start` |
| Rust | `cargo test` | `cargo module`, then `docker compose up -d runtime` |

Run the service in the background and keep its output. Then call `list_local_services`: the service must
be listed, under the name it was started with, which every later tool takes as `service`. A service that
is not listed is not running, whatever its terminal says; its output says why.

## 3. Check the shape against the design

Call `describe_local_service`. It returns what the running service registered: each component's kind,
id and declared queries, and every HTTP route.

- Every row of the component table is registered, as the kind the row names. An event sourced entity
  in the table that runs as a key value entity is a finding, even when every scenario passes: the
  difference shows the first time something needs the history.
- A component registered but absent from the table is a finding: the design and the service disagree.
- Every route a scenario calls exists, with the method it uses.

## 4. Exercise each scenario through its endpoints

Use `call_local_endpoint` for every request a scenario makes. It is an ordinary client of the service's
own port, so the endpoint's ACL applies exactly as it does to any caller; send the headers a real caller
would send, and treat a refusal by the ACL as the service's answer, not an obstacle to work around.

- **Use fresh ids for every scenario** (for example `inspect-<scenario>-<time>`). The journal outlives the
  process, so an id used by an earlier run starts with that run's state.
- **A refusal can be the expected answer.** An entity's error effect is the application working: when a
  scenario says a request must be refused, a 4xx with the entity's message meets it. A 5xx never meets a
  scenario.
- **Views and consumers lag.** A write is acknowledged when its events are persisted; a view row or a
  consumer's reaction follows later. Poll a listing for a few seconds before reporting it missing, and
  report the wait. Never decide a scenario from a view that the scenario says must be exact.

## 5. Confirm the state the scenario left

After the requests, read the state with `query_local_entity`, which runs a component's declared queries
against an id. It runs only queries, and only those that take no argument; a handler declared as a
command is refused by the service. If the state a scenario names can be read by no declared query and no
route, say so: it is a gap in the service's observability, not a pass.

For an agent, `local_agent_session` shows the session's messages, tool calls and the tokens they cost;
check that the tools the scenario expects were called, and that nothing wrote state except through the
entity commands the design names.

For durability, when a scenario says something must survive: stop the service, start it again, and read
the same ids. An entity rebuilds its state from the journal, so state that is gone after a restart was
never persisted.

## 6. Read the traces

`local_traces` lists recent requests; with a `trace_id`, one request's tree of spans. For each scenario,
check that the request went through the components the design says it should, and look at the time
nobody accounts for (**unattributed**), which is usually a journal write, a model call or a database
wait. A span with an unknown parent is work the runtime could not attribute; report it as unknown rather
than guessing which request it belonged to. The trace window is a fixed ring of recent spans, so read a
scenario's trace soon after running it.

## 7. Report

One row per scenario, and one per design finding:

| Scenario | Expected | Observed | Evidence | Verdict |
|---|---|---|---|---|

Evidence is the status and body that decided it, the query result, the trace id. The verdict is *met*,
*not met*, or *not checkable* with the reason. End with what was not inspected and why: a scenario with
no route, a service that needs a model key the machine does not have, a behaviour only a deployment can
show. Stop the service and the containers this inspection started, unless the user wants them left
running.

A deployed service is not inspected with these tools, which see only this machine. For one, use
`get_service` and `service_logs`, and the deployment's own routes.

## Reference files

Open the one a task needs; each is one topic and stands alone.

### Get started

- `references/get-started/coding-agents.md` — Give a coding agent ankka's documentation as a skill, and its hands through `ankka mcp` — the CLI's verbs, the local services on your machine, and this version's docs as MCP tools.

### Concepts

- `references/concepts/observability.md` — What ankka records about every request — spans, traces, unattributed time, token usage — where you can read it locally and in a cluster, and what it deliberately does not do.

### Build

- `references/build/testing.md` — Test ankka components at two levels in Scala, Python and TypeScript — unit test kits that run a component with nothing else, and integration test kits that run the whole service against a real database — with scripted models for agents.

### Run and deploy

- `references/deploy/run-locally.md` — Run an ankka service on your own machine against a local Postgres, configure its database and HTTP port, form a two-node cluster in two terminals, and run a Python service beside the sidecar.

### Observe and operate

- `references/operate/local-console.md` — Use `ankka local console` to see every ankka service running on your machine — its components, a form per HTTP route, the traces of recent requests, entity state and agent sessions.
