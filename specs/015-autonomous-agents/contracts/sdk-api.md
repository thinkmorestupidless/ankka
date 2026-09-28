# Contract: the Python and TypeScript SDKs

## Python (`sdks/python`, full)

```python
from ankka.autonomous import AutonomousAgent, TaskType, TaskAcceptance, TaskRule, Rejected, Accepted

@dataclass
class Answer:
    answer: str
    confidence: int
    sources: list[str]

def cites_sources(a: Answer) -> Rejected | Accepted:
    return Rejected("sources must not be empty") if not a.sources else Accepted()

ANSWER = TaskType("answer", "Answer a question about the catalogue, citing what you looked up",
                  result=Answer, rules=[TaskRule("cites-sources", cites_sources)])

class CatalogueAnswerer(AutonomousAgent):
    component_id = "catalogue-answerer"
    description = "Answers questions about the catalogue"
    instructions = "Be brief. Look things up rather than guessing."
    tools = {"count_items": Tool("How many items the catalogue holds", count_items)}
    guardrails = {"short-input": Guardrail(max_input(4000))}
    accepts = [TaskAcceptance(ANSWER, max_iterations=5)]
    settings = AutonomousSettings(approaching_budget_at=0.8)
```

`to_component()` renders `AutonomousAgentDetail`; the result schema is `_schema_for(Answer)`; a
`TaskType` with `result=None` is text. `__init_subclass__` validates as the Scala companion does.
A result is checked in the process through `Agent.CheckTaskResult`: decoded with
`default_codec_for(result)` (`Malformed` when it does not), then every rule in order.

Client (`ankka.client.ComponentClient`):

```python
client.tasks.create(ANSWER, "How many red items are there?", id=None, attachments=[...], depends_on=[...]) -> str
client.for_task(task_id).get(ANSWER) -> TaskSnapshot[Answer]      # .status, .result, .reason, .iterations, .usage, .assignee, timestamps
await client.for_task(task_id).wait(ANSWER, timeout=600.0)        # async; polls
client.for_task(task_id).cancel(reason="…")
client.for_autonomous_agent(CatalogueAnswerer).run_single_task(ANSWER, "…") -> str   # task id
calls = client.for_autonomous_agent(CatalogueAnswerer, instance_id="reviewer-1")
calls.assign(task_id, ...); calls.suspend(); calls.resume(); calls.terminate(); calls.state() -> AgentState
async for n in calls.notifications(): ...                        # Notification dataclasses, decoded from JSON
```

Testkit:

```python
from ankka.testkit import AutonomousAgentTestKit         # unit: no sidecar
kit = AutonomousAgentTestKit.of(CatalogueAnswerer)
kit.run_tool("count_items", {"colour": "red"}) -> str
await kit.check_result(ANSWER, Answer(...)) -> None | ("rule", "reason") | Malformed   # the kit's methods are async
kit.check_guardrail("short-input", "input", "…") -> str | None

from ankka.testkit.integration import AnkkaTestKit       # whole service, sidecar in Docker
kit = AnkkaTestKit.start(service, env={"ANKKA_MODEL_SCRIPT": json.dumps([...])})
kit.await_task(task_id, ANSWER, timeout=30.0) -> TaskSnapshot[Answer]
kit.notifications("catalogue-answerer", instance_id) -> AsyncIterator[Notification]
kit.restart()                                            # existing: the task resumes
```

`ANKKA_MODEL_SCRIPT` turns, unchanged and new:

```json
[{"tool": "count_items", "arguments": {"colour": "red"}},
 {"when_tool_result": "3 items", "tool": "complete_task", "arguments": {"answer": "3", "confidence": 90, "sources": ["count_items"]}},
 {"when": "cancel", "tool": "fail_task", "arguments": {"reason": "asked to stop"}}]
```

`when` may now pair with a `tool`/`tools` turn as well as `text`; `when_tool_result` matches the
latest tool result's content. The loop is not re-implemented in Python: the unit kit runs the
definition's pieces, the integration kit runs the real loop in the sidecar.

## TypeScript (`sdks/typescript`, declaration and client only)

```ts
export const ANSWER = taskType("answer", "Answer …", { result: s.record({ answer: s.string, confidence: s.int, sources: s.list(s.string) }),
                                                        rules: [taskRule("cites-sources", a => a.sources.length ? accepted() : rejected("…"))] });

export class CatalogueAnswerer extends AutonomousAgent {
  static componentId = "catalogue-answerer";
  static description = "Answers questions about the catalogue";
  static instructions = "…";
  static tools = [tool("count_items", "…", s.record({ colour: s.string }), async ({ colour }) => …)];
  static guardrails = [guardrail("short-input", (stage, text) => …)];
  static accepts = [taskAcceptance(ANSWER, { maxIterations: 5 })];
}
registerAutonomousAgent(service, CatalogueAnswerer);

client.tasks.create(ANSWER, "…", { id?, attachments?, dependsOn? }): Promise<string>
client.forTask(id).get(ANSWER) / .wait(ANSWER, { timeoutMs }) / .cancel(reason?)
client.forAutonomousAgent(CatalogueAnswerer).runSingleTask(ANSWER, "…"): Promise<string>
client.forAutonomousAgent(CatalogueAnswerer, "reviewer-1").assign(...) / suspend() / resume() / terminate() / state() / notifications(): AsyncIterable<Notification>
```

`server/agent.ts` answers `CheckTaskResult`. No testkit support, no guide, no sample beyond the
conformance reference's declaration; `docs/reference/typescript-sdk.md` and `limitations.md` say
so.
