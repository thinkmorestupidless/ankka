"""Effects for consumers: produce onward — one message or several — acknowledge, or ignore."""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from typing import Any, Generic, TypeVar

from ankka.context import Metadata

Out = TypeVar("Out")


@dataclass(frozen=True)
class Produce(Generic[Out]):
    payload: Out
    metadata: Metadata = Metadata()


@dataclass(frozen=True)
class Message(Generic[Out]):
    """One message of several: what to publish, its headers, and optionally the record key it is
    published under. A message with no key is keyed by its subject — the source entity's id unless
    the metadata sets ``ce-subject`` — and naming a key does not change the subject."""

    payload: Out
    key: str | None = None
    metadata: Metadata = Metadata()

    def __post_init__(self) -> None:
        if self.key is not None and not isinstance(self.key, str):
            raise ValueError(f"a record key must be a string, not {self.key!r}")
        if self.key == "":
            raise ValueError("a record key must not be empty; leave it out to key by the subject")


@dataclass(frozen=True)
class ProduceAll(Generic[Out]):
    """Several messages for one change, published in the order given. The change is handled when
    the broker has accepted every one; if one is refused the change comes again and all are
    published again. None at all behaves as ``Done``."""

    messages: tuple[Message[Out], ...]


@dataclass(frozen=True)
class Done:
    pass


@dataclass(frozen=True)
class Ignore:
    pass


ConsumerEffect = Produce[Any] | ProduceAll[Any] | Done | Ignore


class ConsumerEffects(Generic[Out]):
    def produce(self, payload: Out, metadata: Metadata | None = None) -> Produce[Out]:
        return Produce(payload, metadata or Metadata())

    def message(self, payload: Out, *, key: str | None = None, metadata: Metadata | None = None) -> Message[Out]:
        """One message of several, for ``produce_all``."""
        return Message(payload, key, metadata or Metadata())

    def produce_all(self, messages: Iterable[Message[Out]]) -> ProduceAll[Out]:
        """Publish several messages for this change, in order. An empty list publishes nothing and
        the change is handled at once."""
        collected = tuple(messages)
        for message in collected:
            if not isinstance(message, Message):
                raise TypeError(f"produce_all takes messages built with effects.message(...), not {type(message).__name__}")
        return ProduceAll(collected)

    def done(self) -> Done:
        return Done()

    def ignore(self) -> Ignore:
        return Ignore()
