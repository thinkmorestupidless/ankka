from __future__ import annotations

import pytest

from ankka import ErrorCode, EventSourcedEffect, EventSourcedEntity, ReadOnlyEffect, RegistrationError, command, json_codec, query
from ankka.effects.common import DeleteNow, ExpireAfter
from ankka.testkit import EventSourcedTestKit
from tests.counter import Counter, CounterEntity, Incremented, Noted


def test_persist_and_reply_from_the_post_events_state() -> None:
    kit = EventSourcedTestKit.of(CounterEntity, "c1")
    result = kit.call("increment", 2)
    assert result.events == (Incremented(2),)
    assert result.reply == 2
    assert kit.state == Counter(2, [])
    assert kit.call("increment", 3).reply == 5


def test_refusal_persists_nothing() -> None:
    kit = EventSourcedTestKit.of(CounterEntity)
    result = kit.call("increment", 0)
    assert result.error is not None and result.error.code == ErrorCode.BAD_REQUEST
    assert not result.persisted
    assert kit.state.total == 0
    with pytest.raises(AssertionError):
        result.reply_or_raise()


def test_async_handler_and_no_reply() -> None:
    kit = EventSourcedTestKit.of(CounterEntity)
    assert kit.call("note", "a").events == (Noted("a"),)
    quiet = kit.call("note-quietly", "b")
    assert quiet.events == (Noted("b"),) and quiet.reply is None
    assert kit.state.notes == ["a", "b"]


def test_queries_and_retention() -> None:
    kit = EventSourcedTestKit.of(CounterEntity)
    kit.call("increment", 4)
    assert kit.call("total").reply == 4
    assert kit.call("state").reply == Counter(4, [])
    assert kit.call("reset").retention == DeleteNow()
    assert kit.call("expire", 500).retention == ExpireAfter.__call__(__import__("datetime").timedelta(milliseconds=500))


def test_a_thrown_handler_raises() -> None:
    kit = EventSourcedTestKit.of(CounterEntity)
    with pytest.raises(RuntimeError, match="boom"):
        kit.call("boom")


def test_discovery_entry_carries_wire_names_and_flags() -> None:
    component = CounterEntity.to_component()
    names = {h.name: h.read_only for h in component.handlers}
    assert names["increment"] is False
    assert names["total"] is True
    assert component.event_sourced.snapshot_every == 3
    assert component.id == "counter"


def test_a_query_must_be_annotated_read_only() -> None:
    with pytest.raises(RegistrationError, match="ReadOnlyEffect"):

        class Bad(EventSourcedEntity[int, int]):
            component_id = "bad"
            state_codec = json_codec(int, "int")
            event_codec = json_codec(int, "int")

            @query("peek")
            def peek(self) -> EventSourcedEffect[int, int, int]:
                return self.effects.reply(1)


def test_duplicate_wire_names_are_refused() -> None:
    with pytest.raises(RegistrationError, match="declared twice"):

        class Twice(EventSourcedEntity[int, int]):
            component_id = "twice"
            state_codec = json_codec(int, "int")
            event_codec = json_codec(int, "int")

            @query("x")
            def a(self) -> ReadOnlyEffect[int, int, int]:
                return self.effects.reply(1)

            @query("x")
            def b(self) -> ReadOnlyEffect[int, int, int]:
                return self.effects.reply(2)
