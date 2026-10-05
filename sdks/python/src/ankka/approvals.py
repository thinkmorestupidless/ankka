"""Approvals: a tool that waits for a person's decision before it runs.

A tool declared with ``approval`` is never run when the model calls it. The sidecar records an
approval request and answers the caller with it instead of an answer; the turn goes on when a person
decides, through ``decide``. Approved, the tool runs once and the model is told its result; refused,
the model is told who refused it and why, and the tool never runs. Who decided is required and
recorded; who *may* decide is the ACL of whatever route sends the decision.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import timedelta
from typing import Any, Generic, TypeVar

from ankka._proto.ankka.protocol.v1 import client_pb2, discovery_pb2

T = TypeVar("T")

APPROVALS_SINCE = "1.11"
"""The protocol version that carries approvals: a runtime before it cannot record a decision."""


@dataclass(frozen=True)
class Approval:
    """That a tool, or every tool of an MCP server, waits for a person. With ``within``, the
    platform refuses the request when that much time passes with no decision."""

    within: timedelta | None = None

    def __post_init__(self) -> None:
        if self.within is not None and self.within <= timedelta(0):
            raise ValueError(f"a time limit for approval must be positive, not {self.within}")


def approval_of(value: bool | Approval | None) -> Approval | None:
    """``True`` is an approval with no time limit; ``False`` and ``None`` are none."""
    if value is None or value is False:
        return None
    if value is True:
        return Approval()
    return value


def approval_to_pb(value: bool | Approval | None) -> discovery_pb2.Approval | None:
    approval = approval_of(value)
    if approval is None:
        return None
    pb = discovery_pb2.Approval()
    if approval.within is not None:
        pb.within_millis = int(approval.within.total_seconds() * 1000)
    return pb


@dataclass(frozen=True)
class ApprovalRequest:
    """What an agent records when the model calls a tool that requires approval: its id, which a
    decision names, the tool and the arguments the model proposed."""

    id: str
    tool: str
    arguments: Any
    requested_at: int
    expires_at: int | None = None

    @staticmethod
    def from_pb(pb: client_pb2.ApprovalRequest) -> ApprovalRequest:
        return ApprovalRequest(
            id=pb.id,
            tool=pb.tool,
            arguments=json.loads(pb.arguments_json) if pb.arguments_json else {},
            requested_at=pb.requested_at_millis,
            expires_at=pb.expires_at_millis if pb.HasField("expires_at_millis") else None,
        )

    @staticmethod
    def from_json(body: dict[str, Any]) -> ApprovalRequest:
        """A request as an autonomous agent's record holds it."""
        return ApprovalRequest(
            id=body["id"],
            tool=body["tool"],
            arguments=body.get("arguments", {}),
            requested_at=body.get("requestedAt", 0),
            expires_at=body.get("expiresAt"),
        )


@dataclass(frozen=True)
class Answered(Generic[T]):
    """The turn ended with the model's answer."""

    value: T


@dataclass(frozen=True)
class AwaitingApproval:
    """The turn waits: these requests await a person's decision."""

    requests: list[ApprovalRequest]


class ApprovalAwaited(Exception):
    """Raised by the calls that answer only with a value — ``invoke`` and ``stream`` — when the
    turn waits. Use ``ask``, ``stream_parts`` or ``decide`` to be answered with the requests."""

    def __init__(self, requests: list[ApprovalRequest]) -> None:
        names = ", ".join(f"'{r.tool}' ({r.id})" for r in requests)
        super().__init__(f"approval awaited for {names}")
        self.requests = requests


def awaiting_of(pb: client_pb2.ApprovalAwaited) -> AwaitingApproval:
    return AwaitingApproval([ApprovalRequest.from_pb(r) for r in pb.requests])


__all__ = [
    "APPROVALS_SINCE",
    "Answered",
    "Approval",
    "ApprovalAwaited",
    "ApprovalRequest",
    "AwaitingApproval",
]
