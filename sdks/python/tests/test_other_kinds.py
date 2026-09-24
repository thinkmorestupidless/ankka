from __future__ import annotations

import pytest

from ankka import ErrorCode
from ankka._proto.ankka.protocol.v1 import discovery_pb2
from ankka.effects.common import DeleteNow
from ankka.effects.consumer import Done as ConsumerDone, Ignore as ConsumerIgnore, Produce
from ankka.effects.timed_action import Done as TimerDone, Failed
from ankka.effects.view import DeleteRow, UpdateRow
from ankka.effects.workflow import End, Pause, TransitionTo
from ankka.codec import json_codec
from ankka.effects.workflow import WorkflowStepEffect
from ankka.event_sourced_entity import RegistrationError
from ankka.service import Ankka
from ankka.workflow import Recovery, Workflow, WorkflowSettings, step
from ankka.testkit import ConsumerTestKit, KeyValueTestKit, TimedActionTestKit, ViewTestKit, WorkflowTestKit
from tests.counter import CounterEndpoint, CounterEntity, Incremented, Noted
from tests.kinds import Alert, BigIncrements, Charge, Checkout, CheckoutWorkflow, CounterRow, CounterRows, Profile, ProfileEntity, Reminder


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
