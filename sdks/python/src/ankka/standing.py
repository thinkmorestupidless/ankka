"""Where a workflow stood once the effect that recorded a state was applied (protocol 1.15).

A view or a consumer whose source is a workflow is handed, with each state the workflow records,
its standing: running, paused, completed or failed, the step it is on or waits after, the retries
of each step and, when it failed, why. ``Unknown`` is the standing of a state recorded before the
platform stamped standings. A change from an entity or a topic has none.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from types import MappingProxyType

from ankka._proto.ankka.protocol.v1 import payload_pb2

WORKFLOW_SOURCE_PROTOCOL = (1, 15)
"""The protocol version whose runtimes read a workflow as a source."""


@dataclass(frozen=True)
class Standing:
    status: str
    """``NotStarted``, ``Running``, ``Paused``, ``Completed``, ``Failed`` or ``Unknown``."""
    step: str | None = None
    retries: Mapping[str, int] = field(default_factory=lambda: MappingProxyType({}))
    failure: str | None = None

    @property
    def is_running(self) -> bool:
        return self.status == "Running"

    @property
    def is_paused(self) -> bool:
        return self.status == "Paused"

    @property
    def is_completed(self) -> bool:
        return self.status == "Completed"

    @property
    def is_failed(self) -> bool:
        return self.status == "Failed"

    @property
    def is_terminal(self) -> bool:
        return self.is_completed or self.is_failed

    @property
    def is_unknown(self) -> bool:
        """The state was recorded before the platform stamped standings."""
        return self.status == "Unknown"

    @staticmethod
    def from_pb(standing: payload_pb2.WorkflowStanding) -> Standing:
        return Standing(
            status=standing.status,
            step=standing.step if standing.HasField("step") and standing.step else None,
            retries=MappingProxyType(dict(standing.retries)),
            failure=standing.failure if standing.HasField("failure") and standing.failure else None,
        )

    def to_pb(self) -> payload_pb2.WorkflowStanding:
        pb = payload_pb2.WorkflowStanding(status=self.status, retries=dict(self.retries))
        if self.step is not None:
            pb.step = self.step
        if self.failure is not None:
            pb.failure = self.failure
        return pb
