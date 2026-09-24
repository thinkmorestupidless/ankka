"""Small components of every other kind, for the SDK's own tests."""

from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import timedelta

from ankka import DONE, Done, ErrorCode, ReadOnlyEffect, command, json_codec, query
from ankka.consumer import Consumer
from ankka.effects.consumer import ConsumerEffect
from ankka.effects.key_value import KeyValueEffect, KeyValueReadOnlyEffect
from ankka.effects.timed_action import TimedActionEffect
from ankka.effects.view import ViewEffect
from ankka.effects.workflow import WorkflowEffect, WorkflowReadOnlyEffect, WorkflowStepEffect
from ankka.key_value_entity import KeyValueEntity
from ankka.timed_action import TimedAction, action
from ankka.view import View
from ankka.workflow import Recovery, StepSettings, WorkflowSettings, Workflow, step
from tests.counter import CounterEntity, CounterEvent, Incremented, Noted


@dataclass(frozen=True)
class Profile:
    name: str
    visits: int


class ProfileEntity(KeyValueEntity[Profile]):
    component_id = "profile"
    state_codec = json_codec(Profile, "profile")

    def empty_state(self) -> Profile:
        return Profile("", 0)

    @command("set")
    def set_name(self, name: str) -> KeyValueEffect[Profile, Done]:
        if not name:
            return self.effects.error("a name is required", ErrorCode.BAD_REQUEST)
        return self.effects.update_state(replace(self.state, name=name)).then_reply(lambda _: DONE)

    @command("visit")
    def visit(self) -> KeyValueEffect[Profile, int]:
        return self.effects.update_state(replace(self.state, visits=self.state.visits + 1)).then_reply(lambda p: p.visits)

    @query("get")
    def get(self) -> KeyValueReadOnlyEffect[Profile, Profile]:
        return self.effects.reply(self.state)

    @command("delete")
    def delete(self) -> KeyValueEffect[Profile, Done]:
        return self.effects.delete_entity().then_reply(lambda _: DONE)


@dataclass(frozen=True)
class Checkout:
    cart_id: str
    status: str
    charged: int


@dataclass(frozen=True)
class Charge:
    amount: int
    fail: bool = False


class CheckoutWorkflow(Workflow[Checkout]):
    component_id = "checkout"
    state_codec = json_codec(Checkout, "checkout")
    settings = WorkflowSettings(
        default_step_timeout=timedelta(seconds=5),
        steps={"charge": StepSettings(timeout=timedelta(seconds=2), recovery=Recovery(max_retries=1, failover_to="compensate"))},
    )

    def empty_state(self) -> Checkout:
        return Checkout(self.entity_id, "new", 0)

    @command("start")
    def start(self, charge: Charge) -> WorkflowEffect[Checkout, Done]:
        if self.state.status != "new":
            return self.effects.error("already started", ErrorCode.CONFLICT)
        return self.effects.update_state(replace(self.state, status="reserving")).then_transition_to("reserve", charge).then_reply(lambda _: DONE)

    @query("status")
    def status(self) -> WorkflowReadOnlyEffect[Checkout, str]:
        return self.effects.reply(self.state.status)

    @step("reserve")
    def reserve(self, charge: Charge) -> WorkflowStepEffect[Checkout]:
        return self.step_effects.update_state(replace(self.state, status="reserved")).then_transition_to("charge", charge)

    @step("charge")
    async def charge(self, charge: Charge) -> WorkflowStepEffect[Checkout]:
        if charge.fail:
            return self.step_effects.update_state(replace(self.state, status="failed")).then_transition_to("compensate")
        return self.step_effects.update_state(replace(self.state, status="charged", charged=charge.amount)).then_end()

    @step("compensate")
    def compensate(self) -> WorkflowStepEffect[Checkout]:
        return self.step_effects.update_state(replace(self.state, status="compensated")).then_end()

    @step("wait")
    def wait(self) -> WorkflowStepEffect[Checkout]:
        return self.step_effects.pause(after=timedelta(seconds=1))


@dataclass(frozen=True)
class CounterRow:
    total: int
    notes: int


class CounterRows(View[CounterEvent, CounterRow]):
    component_id = "counter-rows"
    source = CounterEntity
    event_codec = CounterEntity.event_codec
    row_codec = json_codec(CounterRow, "counter-row")

    def on_change(self, event: CounterEvent) -> ViewEffect:
        current = self.row or CounterRow(0, 0)
        match event:
            case Incremented(by):
                if by == 999:
                    return self.effects.delete_row()
                return self.effects.update_row(replace(current, total=current.total + by))
            case Noted(_):
                return self.effects.update_row(replace(current, notes=current.notes + 1))
        return self.effects.ignore()


@dataclass(frozen=True)
class Alert:
    text: str


class BigIncrements(Consumer[CounterEvent, Alert]):
    component_id = "big-increments"
    source = CounterEntity
    message_codec = CounterEntity.event_codec
    produces_to = "alerts"
    out_codec = json_codec(Alert, "alert")

    def on_message(self, message: CounterEvent) -> ConsumerEffect:
        match message:
            case Incremented(by) if by >= 10:
                return self.effects.produce(Alert(f"+{by}"))
            case Incremented(_):
                return self.effects.done()
        return self.effects.ignore()


class Reminder(TimedAction):
    component_id = "reminder"

    @action("remind")
    def remind(self, who: str) -> TimedActionEffect:
        if who == "nobody":
            return self.effects.fail("nobody to remind", ErrorCode.NOT_FOUND)
        return self.effects.done()
