"""What a view or consumer that reads a topic declares about it: where it starts, and its version."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Any, ClassVar

from ankka._proto.ankka.protocol.v1 import discovery_pb2

# The first protocol in which a process can declare either; an older sidecar would ignore both.
START_POSITION_PROTOCOL = (1, 7)


@dataclass(frozen=True)
class StartFrom:
    """Where a topic source begins, the first time its consumer group reads a partition.

    Applied once and committed at once, so a restart, a rebalance or a new instance resumes where
    the group got to, never from here again. ``StartFrom.EARLIEST`` is the oldest message the broker
    still holds, ``StartFrom.LATEST`` only what is published from then on, and
    ``StartFrom.at(when)`` the first message published at or after ``when``."""

    EARLIEST: ClassVar[StartFrom]
    LATEST: ClassVar[StartFrom]

    named: str | None = None
    at_millis: int | None = None

    @staticmethod
    def at(when: datetime) -> StartFrom:
        """The first message published at or after ``when``, which must say its timezone: a time
        read in the machine's own zone would start somewhere else on a laptop than in a pod."""
        if when.tzinfo is None or when.utcoffset() is None:
            raise ValueError(f"StartFrom.at needs a datetime with a timezone, not {when!r}")
        return StartFrom(at_millis=int(when.timestamp() * 1000))

    def to_pb(self) -> discovery_pb2.StartFrom:
        if self.at_millis is not None:
            return discovery_pb2.StartFrom(at_millis=self.at_millis)
        named = discovery_pb2.StartFrom.EARLIEST if self.named == "earliest" else discovery_pb2.StartFrom.LATEST
        return discovery_pb2.StartFrom(named=named)

    def __str__(self) -> str:
        if self.at_millis is not None:
            return f"at {self.at_millis}"
        return self.named or ""


StartFrom.EARLIEST = StartFrom(named="earliest")
StartFrom.LATEST = StartFrom(named="latest")


def problems(cls: type, *, consumer: bool) -> list[str]:
    """What is wrong with what ``cls`` declares about a topic, all of it, naming ``cls``."""
    found: list[str] = []
    reads_topic = getattr(cls, "source", None) is None and getattr(cls, "topic", None) is not None
    start = getattr(cls, "start_from", None)
    version = getattr(cls, "version", None)
    name = cls.__name__
    if start is not None and not isinstance(start, StartFrom):
        found.append(f"{name}.start_from must be StartFrom.EARLIEST, StartFrom.LATEST or StartFrom.at(...)")
    if start is not None and not reads_topic:
        found.append(f"{name} declares a start position, which applies to a topic; it reads a component")
    if consumer and reads_topic and start is None:
        found.append(
            f"{name} reads topic '{cls.topic}' and declares no start position; "  # type: ignore[attr-defined]
            "declare start_from = StartFrom.EARLIEST, StartFrom.LATEST or StartFrom.at(...)"
        )
    if version is not None:
        if isinstance(version, bool) or not isinstance(version, int) or version < 1:
            found.append(f"{name} declares version {version!r}; a version is a whole number of 1 or more")
        elif consumer and not reads_topic:
            # A view that reads an entity is rebuilt by its version; a consumer has nothing to rebuild.
            found.append(f"{name} declares a version, which applies to a topic; it reads a component")
    return found


def declares_any(cls: type) -> bool:
    """Whether ``cls`` says something a sidecar older than 1.7 would ignore."""
    return getattr(cls, "start_from", None) is not None or getattr(cls, "version", None) is not None


def apply(source: discovery_pb2.Source, cls: type) -> discovery_pb2.Source:
    start: Any = getattr(cls, "start_from", None)
    if isinstance(start, StartFrom):
        source.start_from.CopyFrom(start.to_pb())
    return source


def older_than(protocol_version: str, wanted: tuple[int, int]) -> bool:
    """Whether ``protocol_version`` is earlier than ``wanted``; an unreadable one is not."""
    try:
        major, minor = (int(part) for part in protocol_version.split(".")[:2])
    except ValueError:
        return False
    return (major, minor) < wanted


def older_than_start_positions(protocol_version: str) -> bool:
    return older_than(protocol_version, START_POSITION_PROTOCOL)
