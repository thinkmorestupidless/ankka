"""Autonomous agents, declared in Python: what discovery says, what registration refuses, and what
the process answers when the sidecar asks it to run a tool or check a rule."""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, cast

import grpc
import pytest

from ankka._proto.ankka.protocol.v1 import agent_pb2, discovery_pb2
from ankka.agent import Tool
from ankka.autonomous import Accepted, AutonomousAgent, Malformed, Notification, Rejected, TaskAcceptance, TaskRule, TaskType
from ankka.event_sourced_entity import RegistrationError
from ankka.server import AgentServicer
from ankka.service import Registry


@dataclass
class Answer:
    answer: str
    sources: list[str]


def cites_sources(a: Answer) -> Accepted | Rejected:
    return Rejected("sources must not be empty") if not a.sources else Accepted()


ANSWER = TaskType("answer", "Answer a question", result=Answer, rules=(TaskRule("cites-sources", cites_sources),))
SUMMARY = TaskType("summary", "Summarise something")


@dataclass
class Topic:
    topic: str


def lookup(agent: AutonomousAgent, t: Topic) -> str:
    return f"{t.topic} for task {agent.task_id}"


class Answerer(AutonomousAgent):
    component_id = "answerer"
    description = "Answers questions"
    instructions = "Be brief."
    tools = {"lookup": Tool("Looks a topic up", lookup, Topic)}
    accepts = [TaskAcceptance(ANSWER, max_iterations=5), TaskAcceptance(SUMMARY)]


def test_discovery_carries_the_whole_definition() -> None:
    c = Answerer.to_component()
    assert c.kind == discovery_pb2.AUTONOMOUS_AGENT
    d = c.autonomous_agent
    assert (d.description, d.instructions) == ("Answers questions", "Be brief.")
    assert [t.name for t in d.tools] == ["lookup"]
    assert [(a.task_type, a.max_iterations) for a in d.accepts] == [("answer", 5), ("summary", 10)]
    answer = next(t for t in d.task_types if t.name == "answer")
    assert list(answer.rules) == ["cites-sources"]
    assert json.loads(answer.result_schema_json)["required"] == ["answer", "sources"]
    summary = next(t for t in d.task_types if t.name == "summary")
    assert not summary.HasField("result_schema_json")


def test_registration_refuses_every_problem_at_once() -> None:
    with pytest.raises(RegistrationError) as e:

        class Bad(AutonomousAgent):
            component_id = "bad"
            description = ""
            tools = {"complete_task": Tool("x", lambda a: "x")}
            accepts = [TaskAcceptance(ANSWER, 0), TaskAcceptance(ANSWER)]

    message = str(e.value)
    for expected in ("description is required", "'answer' is accepted 2 times", "at least one iteration", "'complete_task' is reserved"):
        assert expected in message, message


def test_a_task_type_needs_a_name_and_a_description() -> None:
    with pytest.raises(RegistrationError):
        TaskType("", "x")
    with pytest.raises(RegistrationError):
        TaskType("x", "")


async def test_a_tool_knows_which_task_it_runs_for() -> None:
    agent = Answerer()
    assert await agent._invoke_tool("lookup", '{"topic":"red"}', "task:t-1") == "red for task t-1"


async def test_a_result_is_decoded_then_checked_by_the_rules() -> None:
    agent = Answerer()
    empty = json.dumps({"answer": "3", "sources": []})
    cited = json.dumps({"answer": "3", "sources": ["lookup"]})
    assert await agent._check_result("answer", empty, "t-1") == ("cites-sources", "sources must not be empty")
    assert await agent._check_result("answer", cited, "t-1") is None
    assert isinstance(await agent._check_result("answer", json.dumps({"answer": 3}), "t-1"), Malformed)
    with pytest.raises(LookupError):
        await agent._check_result("no-such-type", cited, "t-1")


class _Aborted(Exception):
    pass


class _Context:
    """The part of a gRPC servicer context the check uses: an abort that raises, as grpc's does."""

    def __init__(self) -> None:
        self.code: Any = None
        self.details = ""

    async def abort(self, code: Any, details: str) -> None:
        self.code, self.details = code, details
        raise _Aborted(details)


def _servicer() -> AgentServicer:
    registry = Registry()
    registry.autonomous[Answerer.component_id] = Answerer
    return AgentServicer(registry, cast(Any, None))


def _request(result: object) -> agent_pb2.TaskResultRequest:
    return agent_pb2.TaskResultRequest(component_id="answerer", task_id="t-1", task_type="answer", result_json=json.dumps(result))


async def test_the_process_answers_a_result_check_with_the_first_refusing_rule() -> None:
    servicer = _servicer()
    rejected = await servicer.CheckTaskResult(_request({"answer": "3", "sources": []}), _Context())
    assert rejected.WhichOneof("verdict") == "reject"
    assert (rejected.reject.rule, rejected.reject.reason) == ("cites-sources", "sources must not be empty")
    accepted = await servicer.CheckTaskResult(_request({"answer": "3", "sources": ["lookup"]}), _Context())
    assert accepted.WhichOneof("verdict") == "accept"
    malformed = await servicer.CheckTaskResult(_request({"answer": 3}), _Context())
    assert malformed.WhichOneof("verdict") == "malformed"


async def test_a_rule_that_raises_is_an_error_not_a_verdict() -> None:
    def boom(a: Answer) -> Accepted | Rejected:
        raise RuntimeError("the checker is down")

    class Fragile(AutonomousAgent):
        component_id = "fragile"
        description = "Checks unreliably"
        accepts = [TaskAcceptance(TaskType("answer", "Answer", result=Answer, rules=(TaskRule("unreliable", boom),)))]

    registry = Registry()
    registry.autonomous["fragile"] = Fragile
    context = _Context()
    request = agent_pb2.TaskResultRequest(component_id="fragile", task_id="t-1", task_type="answer", result_json='{"answer":"3","sources":[]}')
    with pytest.raises(_Aborted):
        await AgentServicer(registry, cast(Any, None)).CheckTaskResult(request, context)
    assert context.code == grpc.StatusCode.INTERNAL and "the checker is down" in context.details


def test_a_notification_reads_its_common_fields_and_keeps_the_rest() -> None:
    n = Notification.from_json(
        '{"type":"TaskResultRejected","componentId":"answerer","instanceId":"i-1","taskId":"t-1","reason":"no","iteration":2,"at":7}'
    )
    assert (n.type, n.component_id, n.instance_id, n.task_id, n.at) == ("TaskResultRejected", "answerer", "i-1", "t-1", 7)
    assert n.detail == {"reason": "no", "iteration": 2}


def test_the_wire_fixtures_decode() -> None:
    from pathlib import Path

    here = Path(__file__).resolve().parent.parent
    fixtures = here / "proto" / "fixtures" / "autonomous"
    if not fixtures.is_dir():
        fixtures = here.parents[1] / "protocol" / "fixtures" / "autonomous"
    rejected = json.loads((fixtures / "agent-notification-task-result-rejected.json").read_text())
    n = Notification.from_json(json.dumps(rejected["value"]))
    assert n.type == "TaskResultRejected" and n.detail["reason"] == "sources must not be empty"
    created = json.loads((fixtures / "task-created.json").read_text())["value"]
    assert created["type"] == "Created" and created["typeName"] == "answer"
