"""The documentation's agent samples (docs/build/agents.md), run: the weather agent's tools,
structured reply and guardrails against the unit kit's scripted model; the approval agent's wait
and the decision that ends it, through the client and through the kit; a tool that calls another
service; a handler that names a model."""

from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass
from typing import Any

from ankka import Answered, AwaitingApproval, ErrorCode, ScriptedServices
from ankka._proto.ankka.protocol.v1 import client_pb2, payload_pb2
from ankka.agent import Agent, Tool
from ankka.client import ComponentClient
from ankka.effects.agent import AgentEffect
from ankka.event_sourced_entity import command
from ankka.testkit import AgentTestKit, ScriptedModel
from tests.approval_agent import ApprovalAgent, ChatEndpoint, runs
from tests.weather_agent import Forecast, WeatherAgent, tool_calls


# ── The weather agent ────────────────────────────────────────────────────────


def test_the_model_calls_a_tool_and_the_result_goes_back_to_it() -> None:
    tool_calls.clear()
    # docs:start scripted-model
    model = ScriptedModel().expect_tool_call("get_weather", {"location": "Lisbon"}).expect_text("Lisbon: 18C, sunny.")
    # docs:end scripted-model
    answer = AgentTestKit.of(WeatherAgent, "w-1", model).call("ask", "Lisbon today?")
    assert answer.reply == "Lisbon: 18C, sunny." and answer.tool_results == ["Lisbon: 18C, sunny"]
    assert tool_calls == ["get_weather(Lisbon,-)"]


def test_a_failing_tool_is_an_answer_the_model_recovers_from() -> None:
    model = ScriptedModel().expect_tool_call("get_weather", {"location": "Nowhere"}).expect_text("I do not know Nowhere.")
    answer = AgentTestKit.of(WeatherAgent, "w-2", model).call("ask", "Nowhere?")
    assert answer.tool_results == ["error: unknown location"] and answer.reply == "I do not know Nowhere."


def test_a_structured_reply_is_decoded_into_the_handlers_type() -> None:
    model = ScriptedModel().expect_text(json.dumps({"location": "Lisbon", "summary": "sunny", "degreesCelsius": 18}))
    answer = AgentTestKit.of(WeatherAgent, "w-3", model).call("ask-structured", "Lisbon?")
    assert answer.plan.json_reply and answer.reply == Forecast("Lisbon", "sunny", 18)


def test_an_input_guardrail_rejects_before_any_model_is_called() -> None:
    model = ScriptedModel()  # nothing scripted: a model call would fail the test
    rejected = AgentTestKit.of(WeatherAgent, "w-4", model).call("guarded", "x" * 41)
    assert rejected.error is not None and rejected.error.code == ErrorCode.FORBIDDEN
    assert "max-input-length" in rejected.error.message
    assert AgentTestKit.of(WeatherAgent, "w-5", ScriptedModel().expect_text("fine")).call("guarded", "short").reply == "fine"


def test_an_output_guardrail_keeps_a_key_out_of_the_reply() -> None:
    class Guarded(WeatherAgent):
        component_id = "weather-agent-guarded"

        @command("ask")
        def ask(self, question: str) -> AgentEffect[str]:
            return self.effects.user_message(question).guardrails("no-keys").then_reply()

    rejected = AgentTestKit.of(Guarded, "w-6", ScriptedModel().expect_text("the key is sk-000")).call("ask", "key?")
    assert rejected.error is not None and "no-keys" in rejected.error.message


# ── The approval agent, through the client ───────────────────────────────────


# docs:start ask-and-decide
async def refund_with_approval(client: ComponentClient) -> str:
    session = client.for_agent("approval-agent", "s-42").call("ask")
    match await session.ask("refund order o-7"):
        case Answered(text):
            return str(text)
        case AwaitingApproval(requests):
            # In a real service the requests are shown to a person, and the decision arrives later,
            # from whatever route they make it through; that route's ACL is who may decide.
            match await session.decide(requests[0].id, approved=True, by="dana"):
                case Answered(text):
                    return str(text)
                case AwaitingApproval(more):
                    return f"still waiting on {len(more)}"
    raise AssertionError("an outcome is answered or awaiting")
# docs:end ask-and-decide


WAITING = client_pb2.ApprovalAwaited(
    requests=[client_pb2.ApprovalRequest(id="a-1", tool="issue_refund", arguments_json='{"order":"o-7","amount":10}', requested_at_millis=5)]
)


def _text_reply(text: str) -> client_pb2.InvokeReply:
    return client_pb2.InvokeReply(
        reply=payload_pb2.Outcome.Reply(payload=payload_pb2.Payload(content_type="text/plain", manifest="string", data=text.encode()))
    )


class _Sidecar:
    """What the sidecar answers: the turn waits at `ask`, and goes on at `decide`; a streamed turn
    sends its text and then the requests it waits on."""

    def __init__(self) -> None:
        self.decisions: list[client_pb2.DecideRequest] = []

    async def Invoke(self, request: client_pb2.InvokeRequest) -> client_pb2.InvokeReply:  # noqa: N802
        return client_pb2.InvokeReply(approval=WAITING)

    async def Decide(self, request: client_pb2.DecideRequest) -> client_pb2.InvokeReply:  # noqa: N802
        self.decisions.append(request)
        return _text_reply("refunded 10 on o-7")

    async def InvokeStream(self, request: client_pb2.InvokeRequest) -> Any:  # noqa: N802
        yield client_pb2.StreamToken(text="checking the order")
        yield client_pb2.StreamToken(approval=WAITING)


def _client(sidecar: _Sidecar) -> ComponentClient:
    client = ComponentClient(address="127.0.0.1:0")
    client._stub_cache = sidecar  # type: ignore[assignment]
    return client


def test_a_turn_that_waits_goes_on_with_the_decision() -> None:
    sidecar = _Sidecar()
    assert asyncio.run(refund_with_approval(_client(sidecar))) == "refunded 10 on o-7"
    (decision,) = sidecar.decisions
    assert (decision.component_id, decision.entity_id, decision.name) == ("approval-agent", "s-42", "ask")
    assert (decision.approval_id, decision.approved, decision.by) == ("a-1", True, "dana")


def test_a_streamed_turn_ends_with_an_approval_event() -> None:
    endpoint = ChatEndpoint(_client(_Sidecar()))

    async def frames() -> list[Any]:
        return [frame async for frame in endpoint.support("s-42")]

    text, event = asyncio.run(frames())
    assert text == "checking the order"
    assert event.name == "approval" and json.loads(event.data())[0]["id"] == "a-1"


# ── The approval agent, in the unit kit ──────────────────────────────────────


def test_an_approved_tool_runs_once_and_a_refused_one_never() -> None:
    runs.clear()
    model = ScriptedModel().expect_tool_call("issue_refund", {"order": "o-7", "amount": 10}).expect_text("refund made")
    kit = AgentTestKit.of(ApprovalAgent, "s-1", model)
    waiting = kit.call("ask", "refund order o-7")
    assert waiting.reply is None and [r.tool for r in waiting.awaiting] == ["issue_refund"] and runs == []
    assert kit.decide(waiting.awaiting[0].id, approved=True, by="dana").reply == "refund made"
    assert runs == ["issue_refund(o-7,10)"]

    model = ScriptedModel().expect_tool_call("close_account", {"customer": "c-1"}).expect_text("left open")
    kit = AgentTestKit.of(ApprovalAgent, "s-2", model)
    waiting = kit.call("ask", "close c-1")
    assert kit.decide(waiting.awaiting[0].id, approved=False, by="sam", note="not yet").reply == "left open"
    assert runs == ["issue_refund(o-7,10)"]


def test_the_time_limit_renders_into_discovery() -> None:
    tools = {t.name: t for t in ApprovalAgent.to_component().agent.tools}
    assert tools["issue_refund"].HasField("approval") and not tools["issue_refund"].approval.HasField("within_millis")
    assert tools["close_account"].approval.within_millis == 2000


# ── A tool that calls another service ────────────────────────────────────────


# docs:start service-call-tool
@dataclass(frozen=True)
class BalanceRequest:
    """The customer whose balance to read."""

    customer: str


async def _read_balance(agent: Agent, arguments: BalanceRequest) -> str:
    # The call is made as this service: the wallet service's ACL admits it by name.
    return await agent.services("wallet").get_text(f"/wallet/balances/{arguments.customer}")


class WalletAgent(Agent):
    """A support agent whose tool reads a balance from the wallet service, as its own service."""

    component_id = "wallet-agent"
    tools = {"read_balance": Tool("Reads a customer's wallet balance.", _read_balance, BalanceRequest)}

    @command("ask")
    def ask(self, question: str) -> AgentEffect[str]:
        return self.effects.system_message("You answer questions about wallets.").user_message(question).tools("read_balance").then_reply()
# docs:end service-call-tool


def test_a_tool_calls_the_wallet_service_as_this_service() -> None:
    class Scripted(WalletAgent):
        component_id = "wallet-agent-scripted"
        services = ScriptedServices().answer("wallet", lambda r: ScriptedServices.text(f"42 on {r.path}"))

    model = ScriptedModel().expect_tool_call("read_balance", {"customer": "c-9"}).expect_text("c-9 holds 42.")
    answer = AgentTestKit.of(Scripted, "s-3", model).call("ask", "balance of c-9?")
    assert answer.tool_results == ["42 on /wallet/balances/c-9"] and answer.reply == "c-9 holds 42."


def test_a_service_that_cannot_be_reached_is_the_tools_error() -> None:
    class Unreachable(WalletAgent):
        component_id = "wallet-agent-unreachable"
        services = ScriptedServices().unresolvable("wallet")

    model = ScriptedModel().expect_tool_call("read_balance", {"customer": "c-9"}).expect_text("I cannot reach the wallet.")
    answer = AgentTestKit.of(Unreachable, "s-4", model).call("ask", "balance?")
    assert answer.tool_results[0].startswith("error:") and "wallet" in answer.tool_results[0]


# ── Naming a model ───────────────────────────────────────────────────────────


# docs:start naming-a-provider
class Triage(Agent):
    component_id = "triage"

    @command("route")
    def route(self, question: str) -> AgentEffect[str]:
        return self.effects.model("openai").system_message("Say which team should answer.").user_message(question).then_reply()
# docs:end naming-a-provider


def test_a_handler_names_the_model_the_sidecar_configured() -> None:
    answer = AgentTestKit.of(Triage, "t-1", ScriptedModel().expect_text("billing")).call("route", "my invoice is wrong")
    assert answer.plan.model == "openai" and answer.plan.to_pb().model == "openai"
    assert answer.reply == "billing"
