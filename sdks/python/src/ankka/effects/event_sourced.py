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
class HandOver:
    """Hand a replicated entity's home to ``to``, once it holds everything before the handover."""

    to: str


@dataclass(frozen=True)
class SetChange:
    """Add ``add`` to a replicated entity's replication set and take ``remove`` out of it."""

    add: frozenset[str] = frozenset()
    remove: frozenset[str] = frozenset()


ReplicationChange = HandOver | SetChange
"""What a replicated entity's handler asks of its home beside the events it persists (1.19)."""


@dataclass(frozen=True)
class EventSourcedEffect(Generic[S, E, R]):
    events: tuple[E, ...]
    retention: Retention | None
    outcome: Outcome
    replication: ReplicationChange | None = None


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
    replication: ReplicationChange | None = None

    def delete_entity(self) -> PersistBuilder[S, E]:
        return PersistBuilder(self.events, DeleteNow(), self.replication)

    def expire_after(self, duration: timedelta) -> PersistBuilder[S, E]:
        return PersistBuilder(self.events, ExpireAfter(duration), self.replication)

    def hand_over_to(self, installation: str) -> PersistBuilder[S, E]:
        """Persist these events, then hand a replicated entity's home to ``installation``, which must
        be in its replication set. Recorded once the new home holds everything before it."""
        if self.replication is not None:
            raise ValueError(f"a command asks for one handover or one change to the replication set; it asked for {self.replication}")
        return PersistBuilder(self.events, self.retention, HandOver(installation))

    def replicate_to(self, installation: str) -> PersistBuilder[S, E]:
        """Persist these events, then add ``installation`` to a replicated entity's replication set."""
        return self._set_change(frozenset({installation}), frozenset())

    def stop_replicating_to(self, installation: str) -> PersistBuilder[S, E]:
        """Persist these events, then take ``installation`` out of a replicated entity's replication
        set. It keeps its copy, which no query reads again."""
        return self._set_change(frozenset(), frozenset({installation}))

    def _set_change(self, add: frozenset[str], remove: frozenset[str]) -> PersistBuilder[S, E]:
        current = self.replication
        if isinstance(current, HandOver):
            raise ValueError(f"a command asks for one handover or one change to the replication set; it asked for {current}")
        before = current or SetChange()
        change = SetChange(before.add | add, before.remove | remove)
        both = change.add & change.remove
        if both:
            raise ValueError(f"a command both adds and removes {', '.join(sorted(both))} in the replication set")
        return PersistBuilder(self.events, self.retention, change)

    def then_reply(self, compute: Callable[[S], R], metadata: Metadata | None = None) -> EventSourcedEffect[S, E, R]:
        """Reply with a value derived from the state *after* the events are applied."""
        return EventSourcedEffect(self.events, self.retention, Reply(compute, metadata or Metadata()), self.replication)

    def then_reply_state(self) -> EventSourcedEffect[S, E, S]:
        return EventSourcedEffect(self.events, self.retention, Reply(lambda s: s), self.replication)

    def then_no_reply(self) -> EventSourcedEffect[S, E, Any]:
        return EventSourcedEffect(self.events, self.retention, NoReply(), self.replication)


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

    def hand_over_to(self, installation: str) -> PersistBuilder[S, E]:
        """Hand a replicated entity's home to ``installation``, with no event of its own."""
        return PersistBuilder[S, E](()).hand_over_to(installation)

    def replicate_to(self, installation: str) -> PersistBuilder[S, E]:
        """Add ``installation`` to a replicated entity's replication set, with no event of its own."""
        return PersistBuilder[S, E](()).replicate_to(installation)

    def stop_replicating_to(self, installation: str) -> PersistBuilder[S, E]:
        """Take ``installation`` out of a replicated entity's replication set; it keeps its copy."""
        return PersistBuilder[S, E](()).stop_replicating_to(installation)
