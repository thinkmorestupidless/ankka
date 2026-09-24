"""Effects for key value entities: replace the state, then decide what to reply."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import timedelta
from typing import Any, Generic, TypeVar

from ankka.context import Metadata
from ankka.effects.common import DeleteNow, Error, ErrorCode, ExpireAfter, Fail, NoReply, Outcome, Reply, Retention

S = TypeVar("S")
R = TypeVar("R")


@dataclass(frozen=True)
class KeyValueEffect(Generic[S, R]):
    new_state: S | None  # None: unchanged
    retention: Retention | None
    outcome: Outcome


@dataclass(frozen=True)
class KeyValueReadOnlyEffect(KeyValueEffect[S, R]):
    new_state: S | None = None
    retention: Retention | None = None
    outcome: Outcome = NoReply()


@dataclass(frozen=True)
class UpdateBuilder(Generic[S]):
    new_state: S | None
    retention: Retention | None = None

    def delete_entity(self) -> UpdateBuilder[S]:
        return UpdateBuilder(self.new_state, DeleteNow())

    def expire_after(self, duration: timedelta) -> UpdateBuilder[S]:
        return UpdateBuilder(self.new_state, ExpireAfter(duration))

    def then_reply(self, compute: Callable[[S], R], metadata: Metadata | None = None) -> KeyValueEffect[S, R]:
        return KeyValueEffect(self.new_state, self.retention, Reply(compute, metadata or Metadata()))

    def then_reply_state(self) -> KeyValueEffect[S, S]:
        return KeyValueEffect(self.new_state, self.retention, Reply(lambda s: s))

    def then_no_reply(self) -> KeyValueEffect[S, Any]:
        return KeyValueEffect(self.new_state, self.retention, NoReply())


class KeyValueEffects(Generic[S]):
    def update_state(self, state: S) -> UpdateBuilder[S]:
        return UpdateBuilder(state)

    def delete_entity(self) -> UpdateBuilder[S]:
        return UpdateBuilder(None, DeleteNow())

    def reply(self, value: R, metadata: Metadata | None = None) -> KeyValueReadOnlyEffect[S, R]:
        return KeyValueReadOnlyEffect(outcome=Reply(lambda _: value, metadata or Metadata()))

    def error(self, message: str, code: ErrorCode = ErrorCode.BAD_REQUEST) -> KeyValueReadOnlyEffect[S, Any]:
        return KeyValueReadOnlyEffect(outcome=Fail(Error(message, code)))

    def no_reply(self) -> KeyValueReadOnlyEffect[S, Any]:
        return KeyValueReadOnlyEffect(outcome=NoReply())
