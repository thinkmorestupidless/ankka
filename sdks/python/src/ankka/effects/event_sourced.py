"""Effects for event sourced entities: persist events, then decide what to reply.

State can only change by persisting an event — there is deliberately no ``update_state`` here,
mirroring the Scala SDK. A ``ReadOnlyEffect`` provably persists nothing; ``@query`` handlers
must return one, and the sidecar refuses events from a read-only handler regardless.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import timedelta
from typing import Any, Generic, TypeVar

from ankka.context import Metadata
from ankka.effects.common import DeleteNow, Error, ErrorCode, ExpireAfter, Fail, NoReply, Outcome, Reply, Retention

S = TypeVar("S")
E = TypeVar("E")
R = TypeVar("R")


@dataclass(frozen=True)
class EventSourcedEffect(Generic[S, E, R]):
    events: tuple[E, ...]
    retention: Retention | None
    outcome: Outcome


@dataclass(frozen=True)
class ReadOnlyEffect(EventSourcedEffect[S, E, R]):
    """An effect that persists nothing. Its ``events`` are always empty."""

    events: tuple[E, ...] = ()
    retention: Retention | None = None
    outcome: Outcome = NoReply()


@dataclass(frozen=True)
class PersistBuilder(Generic[S, E]):
    """Events and retention accumulated before the reply is chosen."""

    events: tuple[E, ...]
    retention: Retention | None = None

    def delete_entity(self) -> PersistBuilder[S, E]:
        return PersistBuilder(self.events, DeleteNow())

    def expire_after(self, duration: timedelta) -> PersistBuilder[S, E]:
        return PersistBuilder(self.events, ExpireAfter(duration))

    def then_reply(self, compute: Callable[[S], R], metadata: Metadata | None = None) -> EventSourcedEffect[S, E, R]:
        """Reply with a value derived from the state *after* the events are applied."""
        return EventSourcedEffect(self.events, self.retention, Reply(compute, metadata or Metadata()))

    def then_reply_state(self) -> EventSourcedEffect[S, E, S]:
        return EventSourcedEffect(self.events, self.retention, Reply(lambda s: s))

    def then_no_reply(self) -> EventSourcedEffect[S, E, Any]:
        return EventSourcedEffect(self.events, self.retention, NoReply())


class EventSourcedEffects(Generic[S, E]):
    """The ``effects`` surface inside an event sourced entity's handlers. Stateless."""

    def persist(self, event: E, *more: E) -> PersistBuilder[S, E]:
        return PersistBuilder((event, *more))

    def persist_all(self, events: list[E] | tuple[E, ...]) -> PersistBuilder[S, E]:
        return PersistBuilder(tuple(events))

    def reply(self, value: R, metadata: Metadata | None = None) -> ReadOnlyEffect[S, E, R]:
        return ReadOnlyEffect(outcome=Reply(lambda _: value, metadata or Metadata()))

    def error(self, message: str, code: ErrorCode = ErrorCode.BAD_REQUEST) -> ReadOnlyEffect[S, E, Any]:
        return ReadOnlyEffect(outcome=Fail(Error(message, code)))

    def no_reply(self) -> ReadOnlyEffect[S, E, Any]:
        return ReadOnlyEffect(outcome=NoReply())

    def delete_entity(self) -> PersistBuilder[S, E]:
        """Delete the entity without recording a final event."""
        return PersistBuilder((), DeleteNow())
