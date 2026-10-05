"""Other services as a Python service calls them: what is sent to the runtime, how each reply is
read, who is given a client, and a runtime too old to make the call. The call itself is made by the
sidecar and is held by the conformance suite's ``service.*`` cases."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Any

import grpc
import pytest

from ankka import (
    CommandContext,
    ErrorCode,
    EventSourcedEntity,
    Metadata,
    RequestContext,
    ScriptedServices,
    ServiceCallFailed,
    ServiceError,
    ServiceIdentityMismatch,
    ServiceResponse,
    Services,
    ServiceUnanswered,
    ServiceUnresolvable,
)
from ankka._proto.ankka.protocol.v1 import client_pb2, endpoint_pb2, payload_pb2
from ankka.agent import Agent
from ankka.autonomous import AutonomousAgent
from ankka.client import CommandError
from ankka.consumer import Consumer
from ankka.endpoint import Endpoint, _current
from ankka.graph import GraphConsumer
from ankka.key_value_entity import KeyValueEntity
from ankka.server import _request_metadata
from ankka.services import MAX_BODY_BYTES
from ankka.testkit import WorkflowTestKit
from ankka.timed_action import TimedAction
from ankka.view import View
from ankka import DONE, Done, command, json_codec
from ankka.effects.workflow import WorkflowEffect, WorkflowStepEffect
from ankka.workflow import Workflow, step

TRACE = Metadata((("ankka-trace-id", "abc"), ("ankka-caller", "payouts#initiate")))


class _Stub:
    """The sidecar's ``Request``, answering with ``reply`` and keeping what it was sent."""

    def __init__(self, reply: client_pb2.ServiceReply | Exception) -> None:
        self.reply = reply
        self.sent: list[client_pb2.ServiceRequest] = []

    async def Request(self, request: client_pb2.ServiceRequest) -> client_pb2.ServiceReply:  # noqa: N802
        self.sent.append(request)
        if isinstance(self.reply, Exception):
            raise self.reply
        return self.reply


class _Client:
    """A component client as a handler holds it: a stub and the handler's metadata."""

    def __init__(self, stub: _Stub, metadata: Metadata = Metadata()) -> None:
        self._stub = stub
        self._metadata = metadata


def _answer(status: int = 200, body: bytes = b"ok", content_type: str = "text/plain") -> client_pb2.ServiceReply:
    return client_pb2.ServiceReply(
        response=endpoint_pb2.HttpResponse(
            status=status, content_type=content_type, body=body, headers=[endpoint_pb2.HttpRequest.Pair(name="X-Answer", value="yes")]
        )
    )


@dataclass
class Payout:
    amount: int


def _services(reply: client_pb2.ServiceReply | Exception = _answer(), metadata: Metadata = Metadata()) -> tuple[Services, _Stub]:
    stub = _Stub(reply)
    return Services(_Client(stub, metadata)), stub  # type: ignore[arg-type]


def test_each_way_of_calling_builds_the_request_the_runtime_is_sent() -> None:
    services, stub = _services(_answer(body=b'{"amount": 5}', content_type="application/json"))
    client = services("psp-gateway")
    asyncio.run(client.get("/payouts/1", Payout))
    asyncio.run(client.get_text("/text"))
    asyncio.run(client.post("/payouts", Payout(5), Payout, headers=[("X-Request-Id", "r1")]))
    asyncio.run(client.put("/payouts/1", Payout(6), Payout))
    asyncio.run(client.delete("/payouts/1"))
    asyncio.run(client.request("PATCH", "/raw", body=b"x", content_type="text/plain"))
    asyncio.run(services("invoices", project="billing").get_text("/i"))
    sent = stub.sent
    assert [(r.method, r.path) for r in sent] == [
        ("GET", "/payouts/1"), ("GET", "/text"), ("POST", "/payouts"), ("PUT", "/payouts/1"),
        ("DELETE", "/payouts/1"), ("PATCH", "/raw"), ("GET", "/i"),
    ]
    assert all(r.service == "psp-gateway" and not r.HasField("project") for r in sent[:6])
    assert (sent[6].service, sent[6].project) == ("invoices", "billing")
    assert sent[2].content_type == "application/json" and sent[2].body == b'{"amount":5}'
    assert [(h.name, h.value) for h in sent[2].headers] == [("X-Request-Id", "r1")]
    assert not sent[0].HasField("body")


def test_a_call_carries_the_metadata_of_the_handler_making_it() -> None:
    stub = _Stub(_answer())
    client = _Client(stub, TRACE)
    for kind in (Consumer, TimedAction, Agent, AutonomousAgent, GraphConsumer):
        component = kind(client)  # type: ignore[arg-type,call-arg]
        asyncio.run(component.services("psp-gateway").get_text("/x"))
    assert all(Metadata.from_pb(r.metadata) == TRACE for r in stub.sent)
    assert len(stub.sent) == 5


def test_an_endpoints_call_carries_the_metadata_of_the_request_it_is_handling() -> None:
    stub = _Stub(_answer())
    services = Services(_Client(stub), _request_metadata)  # type: ignore[arg-type]
    token = _current.set(RequestContext(metadata=TRACE))
    try:
        asyncio.run(services("psp-gateway").get_text("/x"))
    finally:
        _current.reset(token)
    asyncio.run(services("psp-gateway").get_text("/outside"))
    assert Metadata.from_pb(stub.sent[0].metadata) == TRACE
    assert Metadata.from_pb(stub.sent[1].metadata) == Metadata()


def test_an_answer_outside_2xx_is_returned_by_request_and_raised_by_a_typed_helper() -> None:
    services, _ = _services(_answer(status=404, body=b"gone"))
    response = asyncio.run(services("psp-gateway").request("GET", "/x"))
    assert (response.status, response.text, response.header("x-answer")) == (404, "gone", "yes")
    with pytest.raises(ServiceCallFailed) as failed:
        asyncio.run(services("psp-gateway").get_text("/x"))
    assert (failed.value.status, failed.value.body) == (404, "gone")


@pytest.mark.parametrize(
    ("reason", "error"),
    [
        (client_pb2.ServiceFailure.UNRESOLVABLE, ServiceUnresolvable),
        (client_pb2.ServiceFailure.IDENTITY_MISMATCH, ServiceIdentityMismatch),
        (client_pb2.ServiceFailure.UNANSWERED, ServiceUnanswered),
    ],
)
def test_each_way_a_call_gets_no_answer_is_its_own_error(reason: int, error: type[ServiceError]) -> None:
    services, _ = _services(client_pb2.ServiceReply(failure=client_pb2.ServiceFailure(reason=reason, detail="what was tried")))  # type: ignore[arg-type]
    with pytest.raises(error) as raised:
        asyncio.run(services("psp-gateway").request("GET", "/x"))
    assert isinstance(raised.value, ServiceError)
    assert "what was tried" in str(raised.value)


def test_a_refusal_by_the_runtime_is_a_command_error_and_an_empty_reply_a_fault() -> None:
    services, _ = _services(client_pb2.ServiceReply(error=payload_pb2.Error(message="in a step", code=payload_pb2.BAD_REQUEST)))
    with pytest.raises(CommandError) as refused:
        asyncio.run(services("psp-gateway").request("GET", "/x"))
    assert refused.value.error.code == ErrorCode.BAD_REQUEST
    empty, _ = _services(client_pb2.ServiceReply())
    with pytest.raises(CommandError) as fault:
        asyncio.run(empty("psp-gateway").request("GET", "/x"))
    assert fault.value.error.code == ErrorCode.INTERNAL


def test_a_runtime_that_cannot_call_another_service_is_reported_as_too_old() -> None:
    old = grpc.aio.AioRpcError(grpc.StatusCode.UNIMPLEMENTED, grpc.aio.Metadata(), grpc.aio.Metadata(), "Method not found")
    services, _ = _services(old)
    with pytest.raises(CommandError) as refused:
        asyncio.run(services("psp-gateway").get_text("/x"))
    assert "1.8" in refused.value.error.message
    assert refused.value.error.code == ErrorCode.INTERNAL


def test_a_body_over_the_limit_is_refused_before_anything_is_sent() -> None:
    services, stub = _services()
    with pytest.raises(CommandError) as refused:
        asyncio.run(services("psp-gateway").request("POST", "/x", body=b"x" * (MAX_BODY_BYTES + 1)))
    assert str(MAX_BODY_BYTES) in refused.value.error.message
    assert stub.sent == []


def test_an_entity_or_a_view_is_given_no_client_for_other_services() -> None:
    for cls in (EventSourcedEntity, KeyValueEntity, View, CommandContext):
        assert not hasattr(cls, "services"), f"{cls.__name__} must have no services"


def test_every_other_kind_is_given_one() -> None:
    for cls in (Consumer, GraphConsumer, TimedAction, Agent, AutonomousAgent, Endpoint, Workflow):
        assert hasattr(cls, "services"), f"{cls.__name__} must have services"


@dataclass
class PayoutState:
    answer: str


# docs:start step-calls-service
class PayoutWorkflow(Workflow[PayoutState]):
    """Asks the PSP gateway service to start a payout, from a step, and goes on with the answer."""

    component_id = "payout"
    state_codec = json_codec(PayoutState, "payout-state")

    def empty_state(self) -> PayoutState:
        return PayoutState("")

    @command("start")
    def start(self, amount: int) -> WorkflowEffect[PayoutState, Done]:
        return self.effects.update_state(self.state).then_transition_to("initiate-payout", amount).then_reply(lambda _: DONE)

    @step("initiate-payout")
    async def initiate_payout(self, amount: int) -> WorkflowStepEffect[PayoutState]:
        answer = await self.services("psp-gateway").get_text(f"/payouts?amount={amount}")
        return self.step_effects.update_state(PayoutState(answer)).then_end()


def test_a_workflows_step_calls_another_service_and_goes_on_with_the_answer() -> None:
    kit = WorkflowTestKit.of(PayoutWorkflow, "pay-1")
    kit.workflow.services = ScriptedServices().answer("psp-gateway", lambda _: ScriptedServices.text("started"))
    kit.call("start", 25)
    kit.run_until_end()
    assert kit.state.answer == "started"
# docs:end step-calls-service


def test_a_workflow_calls_another_service_in_a_step_and_not_in_a_command() -> None:
    workflow = PayoutWorkflow()
    workflow.services = ScriptedServices()
    with pytest.raises(CommandError) as refused:
        workflow.services
    assert refused.value.error.code == ErrorCode.BAD_REQUEST
    assert "in a step" in refused.value.error.message


def test_the_unit_test_double_answers_as_scripted_and_records_what_it_was_asked() -> None:
    scripted = (
        ScriptedServices()
        .answer("psp-gateway", lambda r: ScriptedServices.json(r.text))
        .answer("invoices", lambda _: ScriptedServices.text("i"), project="billing")
        .unresolvable("a")
        .unanswered("b")
        .mismatch("c")
    )
    assert asyncio.run(scripted("psp-gateway").post("/p", Payout(3), Payout)) == Payout(3)
    assert asyncio.run(scripted("invoices", project="billing").get_text("/")) == "i"
    for name, error in (("a", ServiceUnresolvable), ("b", ServiceUnanswered), ("c", ServiceIdentityMismatch)):
        with pytest.raises(error):
            asyncio.run(scripted(name).get_text("/"))
    with pytest.raises(AssertionError) as unscripted:
        asyncio.run(scripted("ledger").get_text("/"))
    assert 'answer("ledger"' in str(unscripted.value)
    assert [(r.service, r.path) for r in scripted.requests][:2] == [("psp-gateway", "/p"), ("invoices", "/")]
    assert isinstance(ScriptedServices.text("x"), ServiceResponse)
