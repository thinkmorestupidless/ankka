"""The erasure handler (protocol 1.15): what a service does of its own when a data subject of its
project is erased.

The platform does everything it can see before the handler runs: the subject's key is destroyed, its
view rows are redacted, its agent sessions forgotten. The handler is for what the platform cannot
see, chiefly the subject's objects in the service's bucket, which :meth:`ObjectErasure.erase` removes
under the prefix ``subjects/<subject>/``. It runs on every application and again on each later one,
so a late write is removed on the next pass; it must be safe to run twice.
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from ankka.client import ComponentClient


def object_prefix(subject: str) -> str:
    """The prefix a service keeps a data subject's objects under."""
    return f"subjects/{subject}/"


@dataclass(frozen=True)
class ErasedObjects:
    """How many objects an erasure deleted, and when their deletion is final on the object store."""

    count: int
    final_at: datetime


class ObjectErasureError(Exception):
    """The objects could not be erased: no bucket, or the object store refused."""


class ObjectErasure:
    """Every object under the subject's prefix, every version where the store keeps versions."""

    def __init__(self, client: ComponentClient, subject: str) -> None:
        self._client = client
        self._subject = subject

    async def erase(self) -> ErasedObjects:
        from ankka._proto.ankka.protocol.v1 import client_pb2

        reply = await self._client._stub.EraseObjects(client_pb2.EraseObjectsRequest(subject=self._subject))
        if reply.WhichOneof("result") == "error":
            raise ObjectErasureError(reply.error.message)
        return ErasedObjects(reply.erased.count, datetime.fromtimestamp(reply.erased.final_at_millis / 1000, UTC))


@dataclass(frozen=True)
class ErasureContext:
    subject: str
    erasure_id: str
    # Whether the handler has run for this erasure before.
    reapply: bool
    objects: ObjectErasure
    client: ComponentClient


@dataclass(frozen=True)
class Done:
    detail: str = ""
    objects: ErasedObjects | None = None


@dataclass(frozen=True)
class Failed:
    """Not done: the handler is run again on the next application."""

    reason: str


ErasureOutcome = Done | Failed
ErasureHandler = Callable[[ErasureContext], Awaitable[ErasureOutcome]]
