"""A workflow's standing and how a copy stands, as this SDK asks the sidecar for them (protocol 1.24).

The decode of every standing the platform records, a refusal carried in the reply, and the scenarios
"a runtime from before the read refuses ..., naming the protocol version" of
``features/polyglot/standing.feature`` and ``features/replication/status.feature``: a runtime before
1.24 answers either rpc UNIMPLEMENTED, and the handler is told which protocol the read needs.
"""

from __future__ import annotations

import asyncio
from typing import Any

import grpc
import grpc.aio
import pytest

from ankka import Lifecycle, ReplicationStatus, Status
from ankka._proto.ankka.protocol.v1 import client_pb2, discovery_pb2, payload_pb2
from ankka.client import READS_SINCE, Calls, CommandError
from ankka.context import Metadata
from ankka.effects.common import ErrorCode


def _unimplemented() -> grpc.aio.AioRpcError:
    return grpc.aio.AioRpcError(grpc.StatusCode.UNIMPLEMENTED, grpc.aio.Metadata(), grpc.aio.Metadata(), "Method not found")


class _Old:
    """A runtime from before the reads: grpc-java answers UNIMPLEMENTED for both rpcs."""

    async def Lifecycle(self, _request: Any) -> Any:
        raise _unimplemented()

    async def Replication(self, _request: Any) -> Any:
        raise _unimplemented()


class _Answering:
    """A runtime that records what it was asked and answers as told."""

    def __init__(self, lifecycle: client_pb2.LifecycleReply | None = None, replication: client_pb2.ReplicationReply | None = None) -> None:
        self.sent: list[Any] = []
        self.lifecycle = lifecycle
        self.replication = replication

    async def Lifecycle(self, request: Any) -> Any:
        self.sent.append(request)
        return self.lifecycle

    async def Replication(self, request: Any) -> Any:
        self.sent.append(request)
        return self.replication


def _workflow(stub: Any) -> Calls:
    return Calls(stub, discovery_pb2.WORKFLOW, "transfer", "t1", Metadata())


def _entity(stub: Any) -> Calls:
    return Calls(stub, discovery_pb2.EVENT_SOURCED_ENTITY, "vehicle", "v1", Metadata())


def _standing(**fields: Any) -> client_pb2.LifecycleReply:
    return client_pb2.LifecycleReply(lifecycle=client_pb2.WorkflowLifecycle(**fields))


@pytest.mark.parametrize(
    "status, expected",
    [
        (client_pb2.NOT_STARTED, Status.NOT_STARTED),
        (client_pb2.RUNNING, Status.RUNNING),
        (client_pb2.PAUSED, Status.PAUSED),
        (client_pb2.COMPLETED, Status.COMPLETED),
        (client_pb2.FAILED, Status.FAILED),
    ],
)
def test_each_standing_the_platform_records_is_read(status: int, expected: Status) -> None:
    stub = _Answering(lifecycle=_standing(status=status))
    read = asyncio.run(_workflow(stub).lifecycle())
    assert read.status == expected
    (asked,) = stub.sent
    assert (asked.component_id, asked.workflow_id) == ("transfer", "t1")


def test_a_running_workflow_names_its_step() -> None:
    read = asyncio.run(_workflow(_Answering(lifecycle=_standing(status=client_pb2.RUNNING, pending_step="withdraw"))).lifecycle())
    assert read == Lifecycle(Status.RUNNING, "withdraw", {}, None)
    assert read.is_running and not read.is_terminal


def test_a_failed_workflow_carries_its_reason_and_its_retries() -> None:
    reply = _standing(status=client_pb2.FAILED, retries={"deposit": 2}, failure="the bank was shut")
    read = asyncio.run(_workflow(_Answering(lifecycle=reply)).lifecycle())
    assert read.is_failed and read.is_terminal
    assert read.retries == {"deposit": 2}
    assert read.failure == "the bank was shut"
    assert read.pending_step is None


def test_a_status_the_sdk_does_not_know_is_refused() -> None:
    with pytest.raises(CommandError) as refused:
        asyncio.run(_workflow(_Answering(lifecycle=_standing(status=0))).lifecycle())
    assert refused.value.error.code == ErrorCode.INTERNAL


def test_a_refusal_crosses_with_the_runtimes_code() -> None:
    refusal = payload_pb2.Error(message="no such handler", code=payload_pb2.NOT_FOUND)
    with pytest.raises(CommandError) as standing:
        asyncio.run(_workflow(_Answering(lifecycle=client_pb2.LifecycleReply(error=refusal))).lifecycle())
    assert standing.value.error.code == ErrorCode.NOT_FOUND
    with pytest.raises(CommandError) as copy:
        asyncio.run(_entity(_Answering(replication=client_pb2.ReplicationReply(error=refusal))).replication())
    assert copy.value.error.code == ErrorCode.NOT_FOUND


def test_a_copy_reads_its_home_its_set_and_its_position() -> None:
    reply = client_pb2.ReplicationReply(status=client_pb2.ReplicationStatus(home="leipzig", replication_set=["leipzig", "vienna"], position=3))
    stub = _Answering(replication=reply)
    read = asyncio.run(_entity(stub).replication())
    assert read == ReplicationStatus("leipzig", frozenset({"leipzig", "vienna"}), None, None, 3)
    assert not read.in_conflict
    (asked,) = stub.sent
    assert (asked.component_id, asked.entity_id) == ("vehicle", "v1")


def test_a_copy_reads_a_handover_waiting_and_a_conflict() -> None:
    reply = client_pb2.ReplicationReply(
        status=client_pb2.ReplicationStatus(home="leipzig", replication_set=["leipzig", "vienna"], pending_handover="vienna", conflict="vienna", position=5)
    )
    read = asyncio.run(_entity(_Answering(replication=reply)).replication())
    assert read.pending_handover == "vienna"
    assert read.in_conflict and read.conflict == "vienna"


def test_a_runtime_from_before_the_read_refuses_a_workflows_standing_naming_the_protocol_version() -> None:
    with pytest.raises(CommandError) as refused:
        asyncio.run(_workflow(_Old()).lifecycle())
    assert READS_SINCE == "1.24"
    assert "protocol 1.24" in refused.value.error.message
    assert refused.value.error.code == ErrorCode.UNAVAILABLE


def test_a_runtime_from_before_the_read_refuses_how_a_copy_stands_naming_the_protocol_version() -> None:
    with pytest.raises(CommandError) as refused:
        asyncio.run(_entity(_Old()).replication())
    assert "protocol 1.24" in refused.value.error.message
    assert refused.value.error.code == ErrorCode.UNAVAILABLE
