"""A small entity and endpoint the SDK's own tests share."""

from __future__ import annotations

from collections.abc import AsyncIterator
from dataclasses import dataclass
from datetime import timedelta

from ankka import (
    Done,
    DONE,
    Endpoint,
    ErrorCode,
    EventSourcedEffect,
    EventSourcedEntity,
    HttpProblem,
    ReadOnlyEffect,
    command,
    get,
    json_codec,
    post,
    query,
    sse,
)


@dataclass(frozen=True)
class Counter:
    total: int
    notes: list[str]


@dataclass(frozen=True)
class Incremented:
    by: int


@dataclass(frozen=True)
class Noted:
    note: str


CounterEvent = Incremented | Noted


class CounterEntity(EventSourcedEntity[Counter, CounterEvent]):
    component_id = "counter"
    state_codec = json_codec(Counter, "counter")
    event_codec = json_codec(CounterEvent, "counter-event")
    snapshot_every = 3

    def empty_state(self) -> Counter:
        return Counter(0, [])

    def apply_event(self, state: Counter, event: CounterEvent) -> Counter:
        match event:
            case Incremented(by):
                return Counter(state.total + by, state.notes)
            case Noted(note):
                return Counter(state.total, [*state.notes, note])
        raise AssertionError(event)

    @command("increment")
    def increment(self, by: int) -> EventSourcedEffect[Counter, CounterEvent, int]:
        if by <= 0:
            return self.effects.error("must be positive", ErrorCode.BAD_REQUEST)
        return self.effects.persist(Incremented(by)).then_reply(lambda s: s.total)

    @command("note")
    async def note(self, note: str) -> EventSourcedEffect[Counter, CounterEvent, Done]:
        return self.effects.persist(Noted(note)).then_reply(lambda _: DONE)

    @command("note-quietly")
    def note_quietly(self, note: str) -> EventSourcedEffect[Counter, CounterEvent, Done]:
        return self.effects.persist(Noted(note)).then_no_reply()

    @query("total")
    def total(self) -> ReadOnlyEffect[Counter, CounterEvent, int]:
        return self.effects.reply(self.state.total)

    @query("state")
    def get_state(self) -> ReadOnlyEffect[Counter, CounterEvent, Counter]:
        return self.effects.reply(self.state)

    @command("reset")
    def reset(self) -> EventSourcedEffect[Counter, CounterEvent, Done]:
        return self.effects.delete_entity().then_reply(lambda _: DONE)

    @command("expire")
    def expire(self, millis: int) -> EventSourcedEffect[Counter, CounterEvent, Done]:
        return self.effects.persist(Noted("expiring")).expire_after(timedelta(milliseconds=millis)).then_reply(lambda _: DONE)

    @command("boom")
    def boom(self) -> EventSourcedEffect[Counter, CounterEvent, Done]:
        raise RuntimeError("boom")


class CounterEndpoint(Endpoint):
    prefix = "/counters"

    def __init__(self, greeting: str = "hi") -> None:
        self.greeting = greeting

    @get("/{counter_id}")
    def show(self, counter_id: str) -> Counter:
        return Counter(len(counter_id), [self.greeting])

    @get("/awkward")
    def awkward(self) -> str:
        return "literal"

    @post("/{counter_id}/increments")
    def add(self, counter_id: str, by: Incremented) -> int:
        page = self.request.query_param("page")
        if page == "boom":
            raise HttpProblem(418, "no")
        return by.by * 2

    @get("/{counter_id}/echo")
    def echo(self, counter_id: str) -> list[str]:
        return [f"{k}={v}" for k, v in self.request.query] + [self.request.header("X-Test") or ""]

    @sse("/{counter_id}/events")
    async def events(self, counter_id: str) -> AsyncIterator[str]:
        yield " leading space"
        yield "two\nlines"
