from __future__ import annotations

import json
from dataclasses import dataclass

from ankka import ErrorCode
from ankka._proto.ankka.protocol.v1 import discovery_pb2
from ankka.agent import Agent, Guardrail, Tool, stream
from ankka.effects.agent import AgentEffect
from ankka.event_sourced_entity import RegistrationError, command
from ankka.testkit import AgentTestKit, ScriptedModel


@dataclass(frozen=True)
class Lookup:
    city: str
    days: int = 1


def _forecast(agent: Agent, arguments: Lookup) -> str:
    if arguments.city == "Atlantis":
        raise ValueError("no such city")
    return f"{arguments.city}: sunny for {arguments.days} day(s)"


class WeatherAgent(Agent):
    component_id = "weather"
    role = "weather"
    max_tool_call_steps = 2
    tools = {"forecast": Tool("A short forecast for a city.", _forecast, Lookup)}
    guardrails = {"no-secrets": Guardrail(lambda stage, text: "a key leaked" if stage == "output" and "sk-" in text else None)}

    @command("ask")
    def ask(self, question: str) -> AgentEffect[str]:
        if not question.strip():
            return self.effects.error("ask something")
        return self.effects.system_message("You are a weather specialist.").user_message(question).tools("forecast").guardrails("no-secrets").then_reply()

    @stream("chat")
    def chat(self, question: str) -> AgentEffect[str]:
        return self.effects.user_message(question).memory(False).then_reply()


def test_discovery_carries_tools_with_schemas_and_guardrails() -> None:
    component = WeatherAgent.to_component()
    assert component.kind == discovery_pb2.AGENT and component.agent.role == "weather"
    assert component.agent.max_tool_call_steps == 2
    (tool,) = component.agent.tools
    assert tool.name == "forecast" and tool.description
    assert json.loads(tool.input_schema_json) == {
        "type": "object",
        "properties": {"city": {"type": "string"}, "days": {"type": "integer"}},
        "required": ["city"],
        "additionalProperties": False,
    }
    assert list(component.agent.guardrails) == ["no-secrets"]
    assert {(h.name, h.streaming) for h in component.handlers} == {("ask", False), ("chat", True)}


def test_plan_tool_call_and_reply() -> None:
    model = ScriptedModel().expect_tool_call("forecast", {"city": "Lisbon", "days": 3}).expect_text("Sunny in Lisbon.")
    kit = AgentTestKit.of(WeatherAgent, "s1", model)
    answer = kit.call("ask", "Lisbon this week?")
    assert answer.reply == "Sunny in Lisbon."
    assert answer.plan.system == "You are a weather specialist." and answer.plan.tool_names == ("forecast",)
    assert [c.name for c in answer.tool_calls] == ["forecast"]
    assert answer.tool_results == ["Lisbon: sunny for 3 day(s)"]
    assert kit.history == [("Lisbon this week?", "Sunny in Lisbon.")]


def test_tool_error_is_fed_back_and_the_loop_continues() -> None:
    model = ScriptedModel().expect_tool_call("forecast", {"city": "Atlantis"}).expect_text("I cannot find Atlantis.")
    answer = AgentTestKit.of(WeatherAgent, "s2", model).call("ask", "Atlantis?")
    assert answer.reply == "I cannot find Atlantis." and answer.tool_results == ["error: no such city"]


def test_guardrail_blocks_and_refusal_calls_no_model() -> None:
    model = ScriptedModel().expect_text("the key is sk-1")
    blocked = AgentTestKit.of(WeatherAgent, "s3", model).call("ask", "key?")
    assert blocked.error is not None and blocked.error.code == ErrorCode.FORBIDDEN and "no-secrets" in blocked.error.message
    refused = AgentTestKit.of(WeatherAgent, "s4").call("ask", "   ")
    assert refused.error is not None and refused.error.message == "ask something"


def test_script_runs_out_loudly_and_steps_are_bounded() -> None:
    import pytest

    with pytest.raises(AssertionError, match="ran out of script"):
        AgentTestKit.of(WeatherAgent, "s5").call("ask", "hi")
    model = ScriptedModel()
    for _ in range(3):
        model.expect_tool_call("forecast", {"city": "Oslo"})
    bounded = AgentTestKit.of(WeatherAgent, "s6", model).call("ask", "loop")
    assert bounded.error is not None and "2 tool-call steps" in bounded.error.message


def test_a_plan_may_only_name_declared_tools() -> None:
    class Sloppy(Agent):
        component_id = "sloppy"

        @command("ask")
        def ask(self) -> AgentEffect[str]:
            return self.effects.user_message("x").tools("nope").then_reply()

    import pytest

    with pytest.raises(RegistrationError, match="undeclared nope"):
        AgentTestKit.of(Sloppy).call("ask")
