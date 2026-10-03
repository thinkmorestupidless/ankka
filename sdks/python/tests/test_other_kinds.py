from __future__ import annotations

from typing import Any

import grpc
import pytest

from ankka import ErrorCode, Metadata
from ankka._proto.ankka.protocol.v1 import consumer_pb2, payload_pb2
from ankka._proto.ankka.protocol.v1 import discovery_pb2
from ankka.effects.common import DeleteNow
from ankka.effects.consumer import ConsumerEffects, Done as ConsumerDone, Ignore as ConsumerIgnore, Message, Produce, ProduceAll
from ankka.effects.timed_action import Done as TimerDone, Failed
from ankka.effects.view import DeleteRow, UpdateRow
from ankka.effects.workflow import End, Pause, TransitionTo
from ankka.codec import json_codec
from ankka.effects.workflow import WorkflowStepEffect
from ankka.event_sourced_entity import RegistrationError
from ankka.server import ConsumerServicer
from ankka.service import Ankka, Registry
from ankka.testkit.unit import _NoClient
from ankka.workflow import Recovery, Workflow, WorkflowSettings, step
from ankka.testkit import ConsumerTestKit, KeyValueTestKit, Produced, TimedActionTestKit, ViewTestKit, WorkflowTestKit
from tests.counter import CounterEndpoint, CounterEntity, Incremented, Noted
from tests.kinds import Alert, BigIncrements, Fanout, Charge, Checkout, CheckoutWorkflow, CounterRow, CounterRows, Profile, ProfileEntity, Reminder


def test_key_value_update_reply_query_delete() -> None:
    kit = KeyValueTestKit.of(ProfileEntity, "u1")
    assert kit.call("set", "Ada").changed
    assert kit.call("visit").reply == 1
    assert kit.call("visit").reply == 2
    assert kit.call("get").reply == Profile("Ada", 2)
    refused = kit.call("set", "")
    assert refused.error is not None and refused.error.code == ErrorCode.BAD_REQUEST and not refused.changed
    assert kit.call("delete").retention == DeleteNow()
    assert ProfileEntity.to_component().kind == discovery_pb2.KEY_VALUE_ENTITY


def test_workflow_steps_transition_and_end() -> None:
    kit = WorkflowTestKit.of(CheckoutWorkflow, "w1")
    started = kit.call("start", Charge(42))
    assert started.transition is not None and started.transition.step == "reserve"
    assert kit.run_until_end() == End()
    assert kit.transitions == ["reserve", "charge"]
    assert kit.state.status == "charged" and kit.state.charged == 42
    assert kit.call("status").reply == "charged"
    assert kit.call("start", Charge(1)).error is not None


def test_workflow_failure_compensates_and_pause() -> None:
    kit = WorkflowTestKit.of(CheckoutWorkflow, "w2")
    kit.call("start", Charge(7, fail=True))
    assert kit.run_until_end() == End()
    assert kit.transitions == ["reserve", "charge", "compensate"]
    assert kit.state.status == "compensated"
    paused = kit.run_step("wait")
    assert isinstance(paused.next, Pause) and paused.next.after is not None
    assert isinstance(kit.run_step("reserve", Charge(1)).next, TransitionTo)
    component = CheckoutWorkflow.to_component()
    assert list(component.workflow.steps) == ["charge", "compensate", "reserve", "wait"]
    settings = component.workflow.settings
    assert settings.default_step_timeout_millis == 5000 and not settings.HasField("timeout_millis")
    (charge,) = settings.steps
    assert charge.step == "charge" and charge.timeout_millis == 2000
    assert charge.recovery.max_retries == 1 and charge.recovery.failover_to == "compensate"


def test_workflow_settings_must_name_declared_steps() -> None:
    with pytest.raises(RegistrationError, match="not a declared step"):

        class Broken(Workflow[Checkout]):
            component_id = "broken"
            state_codec = json_codec(Checkout, "checkout")
            settings = WorkflowSettings(default_recovery=Recovery(failover_to="nowhere"))

            @step("only")
            def only(self) -> WorkflowStepEffect[Checkout]:
                return self.step_effects.end()


def test_view_rows() -> None:
    kit = ViewTestKit.of(CounterRows)
    assert isinstance(kit.on_change("c1", Incremented(2)), UpdateRow)
    kit.on_change("c1", Incremented(3))
    kit.on_change("c1", Noted("x"))
    assert kit.get("c1") == CounterRow(5, 1)
    assert isinstance(kit.on_change("c1", Incremented(999)), DeleteRow)
    assert kit.get("c1") is None
    component = CounterRows.to_component()
    assert component.view.source.component.id == "counter"
    assert component.view.row_manifest == "counter-row"
    assert list(component.view.queries) == ["get", "all"]


def test_consumer_produces_and_acknowledges() -> None:
    kit = ConsumerTestKit.of(BigIncrements)
    assert isinstance(kit.on_message(Incremented(12)), Produce)
    assert isinstance(kit.on_message(Incremented(1)), ConsumerDone)
    assert isinstance(kit.on_message(Noted("x")), ConsumerIgnore)
    assert kit.produced == [Alert("+12")]
    assert BigIncrements.to_component().consumer.produces_to == "alerts"


# ── Several messages for one change ─────────────────────────────────────────────────────────────


def test_a_consumer_produces_several_messages_in_order_each_with_its_key_and_metadata() -> None:
    kit = ConsumerTestKit.of(Fanout)
    effect = kit.on_message(Incremented(12), "c1")
    assert isinstance(effect, ProduceAll)
    assert kit.messages == [
        Produced(Alert("12:1"), None, Metadata()),
        Produced(Alert("12:2"), "second:c1", Metadata()),
        Produced(Alert("12:3"), None, Metadata().set("x-n", "3")),
    ]
    # `produced` keeps its meaning: each message's payload, one entry per message.
    assert kit.produced == [Alert("12:1"), Alert("12:2"), Alert("12:3")]
    assert Fanout.to_component().consumer.produces_to == "fanned"


def test_an_empty_list_of_messages_is_an_effect_that_produces_nothing() -> None:
    kit = ConsumerTestKit.of(Fanout)
    assert kit.on_message(Incremented(0)) == ProduceAll(())
    assert kit.produced == [] and kit.messages == []


def test_a_single_produce_is_recorded_as_one_message_with_no_key() -> None:
    kit = ConsumerTestKit.of(BigIncrements)
    kit.on_message(Incremented(12))
    assert kit.messages == [Produced(Alert("+12"), None, Metadata())]


def test_an_empty_key_is_refused_where_it_is_named() -> None:
    effects: ConsumerEffects[Alert] = ConsumerEffects()
    with pytest.raises(ValueError, match="must not be empty"):
        effects.message(Alert("x"), key="")
    with pytest.raises(ValueError, match="must not be empty"):
        Message(Alert("x"), "")
    with pytest.raises(TypeError, match="effects.message"):
        effects.produce_all([Alert("x")])  # type: ignore[list-item]


def test_the_test_kit_hands_a_consumer_the_sequence_number_of_its_change() -> None:
    kit = ConsumerTestKit.of(Fanout)
    kit.on_delete("c1", sequence=7)
    assert kit.messages == [Produced(Alert("gone at 7"), "gone:c1", Metadata())]
    kit.on_delete("c2")
    assert kit.produced[-1] == Alert("gone at None")


class _Refused(Exception):
    pass


class _Context:
    """The part of a gRPC servicer context a consumer's answer uses: an abort that raises."""

    def __init__(self) -> None:
        self.code: Any = None
        self.details = ""

    async def abort(self, code: Any, details: str) -> None:
        self.code, self.details = code, details
        raise _Refused(details)


def _request(component: type[Any], event: Any, protocol: str | None, subject: str = "c1", deleted: bool = False) -> consumer_pb2.ConsumerRequest:
    """As the runtime sends a change; `protocol` None is a runtime from before it said what it speaks."""
    entries = [payload_pb2.Metadata.Entry(key="ce-subject", value=subject), payload_pb2.Metadata.Entry(key="ankka.sequence", value="4")]
    if protocol is not None:
        entries.append(payload_pb2.Metadata.Entry(key="ankka.protocol", value=protocol))
    message = None if deleted else payload_pb2.Payload(content_type="application/json", manifest="counter-event", data=component.message_codec.encode(event))
    return consumer_pb2.ConsumerRequest(component_id=component.component_id, message=message, metadata=payload_pb2.Metadata(entries=entries), deleted=deleted)


async def _answer(component: type[Any], event: Any, protocol: str | None, deleted: bool = False) -> consumer_pb2.ConsumerEffect:
    registry = Registry()
    registry.consumers[component.component_id] = component
    return await ConsumerServicer(registry, _NoClient()).Handle(_request(component, event, protocol, deleted=deleted), _Context())


async def test_several_messages_are_answered_as_produce_all_to_a_runtime_that_accepts_them() -> None:
    effect = await _answer(Fanout, Incremented(12), "1.3")
    assert effect.WhichOneof("effect") == "produce_all"
    messages = effect.produce_all.messages
    assert [m.payload.data for m in messages] == [b'{"text":"12:1"}', b'{"text":"12:2"}', b'{"text":"12:3"}']
    assert [m.payload.manifest for m in messages] == ["alert"] * 3
    # A key is on the wire only where one was named.
    assert [m.key if m.HasField("key") else None for m in messages] == [None, "second:c1", None]
    assert [[(e.key, e.value) for e in m.metadata.entries] for m in messages] == [[], [], [("x-n", "3")]]


async def test_a_deletion_may_answer_with_messages_too() -> None:
    effect = await _answer(Fanout, None, "1.3", deleted=True)
    assert [(m.key, m.payload.data) for m in effect.produce_all.messages] == [("gone:c1", b'{"text":"gone at 4"}')]


@pytest.mark.parametrize(
    ("protocol", "spoken"),
    [(None, "1.2 or earlier"), ("1.2", "1.2"), ("1.0", "1.0"), ("not-a-version", "1.2 or earlier")],
)
async def test_several_messages_are_refused_to_a_runtime_that_would_drop_them(protocol: str | None, spoken: str) -> None:
    context = _Context()
    registry = Registry()
    registry.consumers[Fanout.component_id] = Fanout
    with pytest.raises(_Refused):
        await ConsumerServicer(registry, _NoClient()).Handle(_request(Fanout, Incremented(12), protocol), context)
    assert context.details == f"this runtime speaks protocol {spoken}; several messages or a record key need 1.3"
    assert context.code == grpc.StatusCode.FAILED_PRECONDITION
    # One message under a key needs the same: an earlier runtime would key it by its subject.
    with pytest.raises(_Refused):
        await ConsumerServicer(registry, _NoClient()).Handle(_request(Fanout, None, protocol, deleted=True), _Context())


@pytest.mark.parametrize("protocol", ["1.3", "1.10", "2.0"])
async def test_a_later_runtime_is_answered(protocol: str) -> None:
    assert (await _answer(Fanout, Incremented(12), protocol)).WhichOneof("effect") == "produce_all"


async def test_what_any_runtime_understands_is_answered_to_any_runtime() -> None:
    # A single produce is the reply it always was.
    single = await _answer(BigIncrements, Incremented(12), None)
    assert single.WhichOneof("effect") == "produce"
    assert single.produce.payload.data == b'{"text":"+12"}'
    # One message with no key is the same thing, however it was returned.
    only = await _answer(Fanout, Incremented(1), None)
    assert only.WhichOneof("effect") == "produce"
    assert only.produce.payload.data == b'{"text":"only"}'
    # No messages at all is `done`.
    assert (await _answer(Fanout, Incremented(0), None)).WhichOneof("effect") == "done"
    assert (await _answer(Fanout, Noted("x"), None)).WhichOneof("effect") == "ignore"


def test_a_consumers_metadata_says_what_the_runtime_speaks() -> None:
    assert Metadata().protocol is None
    assert Metadata().set("ankka.protocol", "1.3").protocol == "1.3"


def test_timed_action() -> None:
    kit = TimedActionTestKit.of(Reminder)
    assert kit.call("remind", "ada") == TimerDone()
    failed = kit.call("remind", "nobody")
    assert isinstance(failed, Failed) and failed.error.code == ErrorCode.NOT_FOUND
    assert Reminder.to_component().kind == discovery_pb2.TIMED_ACTION


def test_every_kind_registers_and_is_discovered() -> None:
    spec = (
        Ankka.service()
        .register(CounterEntity)
        .register(ProfileEntity)
        .register(CheckoutWorkflow)
        .register(CounterRows)
        .register(BigIncrements)
        .register(Reminder)
        .register(CounterEndpoint)
        .spec()
    )
    assert sorted(c.id for c in spec.components) == ["big-increments", "checkout", "counter", "counter-rows", "profile", "reminder"]
