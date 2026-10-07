"""Recurring timers as a Python service sees them: what ``schedule_recurring`` sends, what it
refuses before sending, a runtime too old to have them, and the due time a handler is told. The
timer itself runs in the sidecar and is held by the conformance suite's ``timer.recurring.*`` cases."""

from __future__ import annotations

import asyncio
from datetime import datetime, timedelta, timezone
from typing import Any

import grpc
import pytest

from ankka import ErrorCode
from ankka._proto.ankka.protocol.v1 import client_pb2, payload_pb2
from ankka.client import CommandError, Timers
from ankka.service import PROTOCOL_VERSION
from ankka.effects.timed_action import Done, Failed, TimedActionEffect
from ankka.testkit.unit import TimedActionTestKit
from ankka.timed_action import TimedAction, action


class _Recording:
    """A fake ``Client`` service: records what it was sent and answers a scripted reply."""

    def __init__(self, reply: client_pb2.ScheduleRecurringReply | None = None) -> None:
        self.sent: list[Any] = []
        self.reply = reply or client_pb2.ScheduleRecurringReply()

    async def ScheduleRecurring(self, request: client_pb2.ScheduleRecurringRequest) -> client_pb2.ScheduleRecurringReply:
        self.sent.append(request)
        return self.reply

    async def Schedule(self, request: client_pb2.ScheduleRequest) -> None:
        self.sent.append(request)
        return None


class _Unimplemented:
    """A runtime that predates recurring timers: grpc-java answers UNIMPLEMENTED."""

    async def ScheduleRecurring(self, _request: Any) -> Any:
        raise grpc.aio.AioRpcError(grpc.StatusCode.UNIMPLEMENTED, grpc.aio.Metadata(), grpc.aio.Metadata(), "Method not found")


def test_a_recurring_timer_sends_its_id_delay_period_target_and_payload() -> None:
    stub = _Recording()
    timers = Timers(stub)  # type: ignore[arg-type]
    asyncio.run(timers.schedule_recurring("recur-a", timedelta(seconds=2), timedelta(minutes=5), "reminder", "tick", "a"))
    asyncio.run(timers.schedule("once-a", timedelta(seconds=2), "reminder", "tick", "a"))
    recurring, once = stub.sent
    assert isinstance(recurring, client_pb2.ScheduleRecurringRequest)
    assert recurring.timer_id == "recur-a"
    assert recurring.delay_millis == 2000
    assert recurring.period_millis == 300_000
    assert recurring.component_id == "reminder"
    assert recurring.name == "tick"
    # Encoded exactly as `schedule` encodes its input.
    assert recurring.payload == once.payload
    assert recurring.payload.data == b"a"


def test_a_recurring_timer_with_no_input_sends_the_unit_payload() -> None:
    stub = _Recording()
    timers = Timers(stub)  # type: ignore[arg-type]
    asyncio.run(timers.schedule_recurring("r", timedelta(0), timedelta(milliseconds=1), "reminder", "tick"))
    asyncio.run(timers.schedule("o", timedelta(0), "reminder", "tick"))
    assert stub.sent[0].payload == stub.sent[1].payload
    assert stub.sent[0].period_millis == 1


def test_the_longest_period_is_sent_exactly() -> None:
    stub = _Recording()
    asyncio.run(Timers(stub).schedule_recurring("r", timedelta(0), timedelta(days=36500), "reminder", "tick"))  # type: ignore[arg-type]
    assert stub.sent[0].period_millis == 36500 * 86_400_000


@pytest.mark.parametrize(
    "period",
    [timedelta(0), timedelta(milliseconds=-1), timedelta(microseconds=999), timedelta(days=36500, milliseconds=1)],
    ids=["zero", "negative", "under-a-millisecond", "over-a-century"],
)
def test_a_period_out_of_bounds_is_refused_naming_the_timer_and_nothing_is_sent(period: timedelta) -> None:
    stub = _Recording()
    with pytest.raises(CommandError) as refused:
        asyncio.run(Timers(stub).schedule_recurring("recur-x", timedelta(0), period, "reminder", "tick", "x"))  # type: ignore[arg-type]
    assert refused.value.error.code == ErrorCode.BAD_REQUEST
    message = refused.value.error.message
    assert "timer 'recur-x'" in message
    assert "from 1 millisecond to 36500 days" in message
    assert stub.sent == []


def test_an_error_in_the_reply_is_raised_with_its_code_and_message() -> None:
    stub = _Recording(client_pb2.ScheduleRecurringReply(error=payload_pb2.Error(message="timers are not running", code=payload_pb2.UNAVAILABLE)))
    with pytest.raises(CommandError) as refused:
        asyncio.run(Timers(stub).schedule_recurring("r", timedelta(0), timedelta(seconds=1), "reminder", "tick"))  # type: ignore[arg-type]
    assert refused.value.error.code == ErrorCode.UNAVAILABLE
    assert refused.value.error.message == "timers are not running"


def test_a_runtime_without_recurring_timers_is_reported_as_too_old_naming_both_versions() -> None:
    with pytest.raises(CommandError) as refused:
        asyncio.run(Timers(_Unimplemented()).schedule_recurring("r", timedelta(0), timedelta(seconds=1), "reminder", "tick"))  # type: ignore[arg-type]
    message = refused.value.error.message
    assert "recurring timers" in message
    assert "1.12" in message
    assert f"this SDK speaks {PROTOCOL_VERSION}" in message


class Ticker(TimedAction):
    component_id = "ticker"

    @action("tick")
    def tick(self) -> TimedActionEffect:
        due = self.due_time
        if due is None:
            return self.effects.fail("no due time", ErrorCode.NOT_FOUND)
        if due != datetime(2026, 10, 4, 12, 0, 0, 250_000, tzinfo=timezone.utc):
            return self.effects.fail(f"due {due.isoformat()}", ErrorCode.BAD_REQUEST)
        if self.metadata.get("ankka.attempts") != "2" or self.metadata.get("ankka.timer") != "t-1":
            return self.effects.fail("attempts or timer missing", ErrorCode.BAD_REQUEST)
        return self.effects.done()


def test_a_handler_is_told_its_due_time_in_utc() -> None:
    due = int(datetime(2026, 10, 4, 12, 0, 0, 250_000, tzinfo=timezone.utc).timestamp() * 1000)
    effect = TimedActionTestKit.of(Ticker).call("tick", metadata={"ankka.due": str(due), "ankka.attempts": "2", "ankka.timer": "t-1"})
    assert effect == Done()


def test_the_due_time_is_none_when_the_runtime_does_not_say() -> None:
    effect = TimedActionTestKit.of(Ticker).call("tick")
    assert isinstance(effect, Failed) and effect.error.message == "no due time"


def test_the_due_time_is_timezone_aware() -> None:
    from ankka.context import Metadata

    action_ = Ticker()
    action_._metadata = Metadata().set("ankka.due", "1000")
    due = action_.due_time
    assert due is not None
    assert due.tzinfo is not None and due.utcoffset() == timedelta(0)
    assert due == datetime(1970, 1, 1, 0, 0, 1, tzinfo=timezone.utc)
