"""Effects for consumers: produce onward, acknowledge, or ignore."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Generic, TypeVar

from ankka.context import Metadata

Out = TypeVar("Out")


@dataclass(frozen=True)
class Produce(Generic[Out]):
    payload: Out
    metadata: Metadata = Metadata()


@dataclass(frozen=True)
class Done:
    pass


@dataclass(frozen=True)
class Ignore:
    pass


ConsumerEffect = Produce[Any] | Done | Ignore


class ConsumerEffects(Generic[Out]):
    def produce(self, payload: Out, metadata: Metadata | None = None) -> Produce[Out]:
        return Produce(payload, metadata or Metadata())

    def done(self) -> Done:
        return Done()

    def ignore(self) -> Ignore:
        return Ignore()
