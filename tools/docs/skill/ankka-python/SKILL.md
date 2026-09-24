---
name: ankka-python
description: Write, run, test or deploy an ankka service in Python — components as classes with component_id, codecs and decorators (@command, @query, @step, @action, @get, @stream), the SDK's effects and testkits, the sidecar that hosts the process, the gRPC protocol and discovery, the shared JSON encoding with Scala, and the descriptor fields and environment split of a process-hosted service. Use when the task involves Python, the ankka Python SDK, the sidecar, uv or pytest in an ankka project, or a service in a language other than Scala.
pages:
  - concepts/polyglot.md
  - get-started/first-service-python.md
  - reference/python-sdk.md
  - reference/sidecar-protocol.md
  - build/serialization.md
  - build/testing.md
  - deploy/deploy-a-service.md
---

# ankka in Python

A Python service runs as its own process with the ankka runtime beside it as a sidecar. The sidecar owns
everything durable and distributed (sharding, the journal, projections, timers, the workflow engine, the
agent loop and the model's key, HTTP binding and ACLs, cluster membership); the process owns the
decisions: which events for this command, what a step does, an agent's plan and tools. The SDK speaks the
gRPC protocol so your code never does. Every component guide in the other ankka skills applies; this
skill holds what differs.

## Rules

1. **Every component is a class with a `component_id` and codecs.** `state_codec = json_codec(State,
   "manifest")`, `event_codec = json_codec(Event, "manifest")`, `row_codec`, `out_codec`. Handlers are
   `@command("wire-name")`, `@query("wire-name")` (must return a read-only effect; registration refuses
   otherwise, and the sidecar refuses events from one regardless), `@step("name")`, `@action("name")`,
   `@get("/{id}")` and friends, `@stream`. Register classes on `Ankka.service().register(Cls)`.
2. **Field names are the stored JSON.** Dataclass fields use the stored spelling (`productId`), not
   snake case, so one journal is readable from Scala and Python. Sum types are unions of frozen
   dataclasses with the case's simple name as `"type"`. A `str`, `int` or `bool` argument or reply
   crosses the wire as plain text under manifests `string`, `int`, `boolean`, so an endpoint returning
   `str` answers `text/plain`, a test calling `.json()` on it fails with "Expecting value", and a `str`
   body is posted raw.
3. **Effects mirror Scala's in snake case.** `self.effects.persist(e).then_reply(lambda _: DONE)`,
   `.then_reply_state()`, `.delete_entity()`, `.expire_after(timedelta)`, `self.effects.error(msg,
   ErrorCode.CONFLICT)`; `update_state`, `update_row`/`delete_row`/`ignore`, `produce`/`done`/`ignore`,
   `self.step_effects.update_state(s).then_transition_to("charge", input)`, `then_pause(after=...,
   on_timeout=StepRef("step"))`, `then_end()`, `then_fail()`. `self.state`, `self.row`, `self.entity_id`,
   `self.metadata.subject`.
4. **Calls are awaited and addressed by id and wire name.** `await client.for_event_sourced_entity(
   "shopping-cart", "c1").call("add-item").invoke(item, reply=Done)`; `for_key_value_entity`,
   `for_workflow`, `for_agent(...).call(name).stream(input)`, `views.get(view_id, key, Row)`,
   `views.all(view_id, Row)`, `timers.schedule(timer_id, delay, component_id, name, input)`,
   `timers.cancel`. A refusal raises `ankka.client.CommandError` with `error.code`. The client comes as a
   constructor argument in an endpoint, `self.context.client` in a step, `self.client` elsewhere.
5. **Pass the request's metadata on.** In an endpoint, `self.client.with_metadata(self.request.metadata)`
   makes the handler's calls children of the request's trace. `self.request` has `query_param`,
   `query_params`, `header`, `principal`; raise `HttpProblem(status, message)` for a status. The process
   never binds an HTTP port; `acl` defaults to `Acl.ALLOW_ALL`, so set it; `Acl.AUTHENTICATED` answers
   503 for now.
6. **Steps and agents run in the sidecar's engines.** A step may be `async`; the sidecar journals its
   transition. Workflow settings the engine enforces (timeouts, `Recovery(max_retries, failover_to)`)
   are declared on the class so discovery can carry them. An agent's `tools` and `guardrails` are class
   attributes named in the effect; `memory(False)` for one-shot; `with_model("anthropic" | "scripted")`;
   the model is configured on the sidecar through `ANTHROPIC_API_KEY`, `ANKKA_MODEL_NAME` or
   `ANKKA_MODEL_SCRIPT` in the descriptor's `env`, and the process never sees the key.
7. **Views in Python answer `get` and `all` only.** SQL conditions over rows are a Scala service's.
   `ViewTestKit`, `ConsumerTestKit`, `WorkflowTestKit`, `TimedActionTestKit`, `EventSourcedTestKit` and
   `KeyValueTestKit` run one component with no sidecar; the integration testkit runs the real sidecar
   (Docker; on Linux it needs `host.docker.internal` mapped, which the testkit sets).
8. **Discovery reports every problem at once.** The sidecar asks the process what it hosts, validates it
   all, and logs every problem in its own log and back to the process; read the whole list and fix in
   one restart. It waits 60 seconds for discovery. Ports: the process listens on 9010, the sidecar on
   9011, both loopback.
9. **Deploy as a process-hosted service.** The descriptor sets `hosting: "process"` and the protocol
   version the SDK speaks; the image holds only your process; the platform adds the sidecar at the
   platform's version. Environment is split by name: `ANTHROPIC_*`, `ANKKA_MODEL_*` and `ANKKA_DB_*` go to
   the sidecar, everything else to the process. A topic-sourced view or producing consumer needs
   `ANKKA_KAFKA_BOOTSTRAP_SERVERS` or the sidecar refuses to start, naming the component.
10. **The process holds no durable state.** It may crash or restart freely; the sidecar re-opens entities
    when it returns and callers retry a brief `Unavailable`. Do not cache state in the process across
    commands.

## Development loop

`uv sync`, `uv run pytest -q`, `uv run mypy`; `uv run conformance` runs the behaviour suite the SDK must
pass. Install with `pip` from the official `python` image in a Dockerfile rather than a `ghcr.io` base.
See `references/get-started/first-service-python.md` for the first service and
`references/reference/python-sdk.md` for the map of every class, attribute and decorator.

## Mistakes to check for

- Snake-case field names on a type that shares a journal with Scala.
- A `@query` that returns a persisting effect; a producing consumer with `produces_to` and no `out_codec`.
- A test parsing a `str` reply as JSON, or posting a `str` body as a JSON string.
- Calls from an endpoint without `with_metadata`, leaving orphan traces.
- Reading a session id inside a tool function instead of in the handler's plan.
