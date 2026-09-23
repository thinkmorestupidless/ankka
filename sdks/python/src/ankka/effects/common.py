"""What every kind of effect shares: outcomes, refusals, retention."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import timedelta
from enum import Enum
from typing import Any, Generic, TypeVar

from ankka._proto.ankka.protocol.v1 import payload_pb2
from ankka.context import Metadata

S = TypeVar("S")
R = TypeVar("R")


class ErrorCode(Enum):
    """Mirrors ``core.ErrorCode``; the wire form is the protobuf enum of the same names."""

    BAD_REQUEST = payload_pb2.BAD_REQUEST
    UNAUTHORIZED = payload_pb2.UNAUTHORIZED
    FORBIDDEN = payload_pb2.FORBIDDEN
    NOT_FOUND = payload_pb2.NOT_FOUND
    CONFLICT = payload_pb2.CONFLICT
    TIMEOUT = payload_pb2.TIMEOUT
    UNAVAILABLE = payload_pb2.UNAVAILABLE
    INTERNAL = payload_pb2.INTERNAL

    @staticmethod
    def from_pb(value: int) -> ErrorCode:
        for code in ErrorCode:
            if code.value == value:
                return code
        return ErrorCode.INTERNAL


@dataclass(frozen=True)
class Error:
    message: str
    code: ErrorCode = ErrorCode.BAD_REQUEST

    def to_pb(self) -> payload_pb2.Error:
        return payload_pb2.Error(message=self.message, code=self.code.value)


@dataclass(frozen=True)
class DeleteNow:
    pass


@dataclass(frozen=True)
class ExpireAfter:
    duration: timedelta


Retention = DeleteNow | ExpireAfter


def retention_to_pb(retention: Retention | None) -> payload_pb2.Retention | None:
    if retention is None:
        return None
    if isinstance(retention, DeleteNow):
        return payload_pb2.Retention(delete_now=payload_pb2.Retention.DeleteNow())
    return payload_pb2.Retention(
        expire_after=payload_pb2.Retention.ExpireAfter(millis=int(retention.duration.total_seconds() * 1000))
    )


# ── Outcomes: the three cases of core.effect.Outcome ─────────────────────────


@dataclass(frozen=True)
class Reply(Generic[S, R]):
    compute: Callable[[S], R]
    metadata: Metadata = Metadata()


@dataclass(frozen=True)
class NoReply:
    pass


@dataclass(frozen=True)
class Fail:
    error: Error


Outcome = Reply[Any, Any] | NoReply | Fail
