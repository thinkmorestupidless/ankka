"""The documentation's autonomous agent samples (docs/build/autonomous-agents.md), run: an
operator whose tools wait for a person, and the decision a caller sends its instance."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from datetime import timedelta

from ankka import Approval
from ankka._proto.ankka.protocol.v1 import client_pb2, discovery_pb2, payload_pb2
from ankka.agent import Tool
from ankka.autonomous import AutonomousAgent, TaskAcceptance, TaskType
from ankka.client import ComponentClient
from ankka.testkit import AutonomousAgentTestKit

runs: list[str] = []


@dataclass(frozen=True)
class ServiceName:
    """The service to restart, or to read."""

    service: str


@dataclass(frozen=True)
class NodeName:
    """The node to drain."""

    node: str


def _restart_service(agent: AutonomousAgent, arguments: ServiceName) -> str:
    runs.append(f"restart_service({arguments.service})")
    return f"restarted {arguments.service}"


def _drain_node(agent: AutonomousAgent, arguments: NodeName) -> str:
    runs.append(f"drain_node({arguments.node})")
    return f"drained {arguments.node}"


def _read_metrics(agent: AutonomousAgent, arguments: ServiceName) -> str:
    runs.append(f"read_metrics({arguments.service})")
    return f"{arguments.service} is healthy"


OPERATE = TaskType("operate", "Keep a service healthy")


# docs:start autonomous-requires-approval
class Operator(AutonomousAgent):
    component_id = "operator"
    description = "Keeps services healthy, with an operator's approval for anything that changes them."
    tools = {
        "restart_service": Tool("Restarts a service. An operator approves every restart.", _restart_service, ServiceName, approval=True),
        "drain_node": Tool("Drains a node of its work.", _drain_node, NodeName, approval=Approval(within=timedelta(seconds=2))),
        "read_metrics": Tool("Reads a service's metrics.", _read_metrics, ServiceName),
    }
    accepts = [TaskAcceptance(OPERATE, max_iterations=5)]
# docs:end autonomous-requires-approval


# docs:start autonomous-decide
async def approve(client: ComponentClient, approval_id: str) -> None:
    await client.for_autonomous_agent(Operator, "ops-1").decide(approval_id, True, "dana")
# docs:end autonomous-decide


def test_the_tools_that_wait_say_so_in_discovery_and_the_one_that_reads_does_not() -> None:
    tools = {t.name: t for t in Operator.to_component().autonomous_agent.tools}
    assert tools["restart_service"].HasField("approval") and not tools["restart_service"].approval.HasField("within_millis")
    assert tools["drain_node"].approval.within_millis == 2000
    assert not tools["read_metrics"].HasField("approval")


def test_the_tools_run_as_the_sidecar_would_run_them_once_approved() -> None:
    runs.clear()
    kit = AutonomousAgentTestKit.of(Operator)
    assert asyncio.run(kit.run_tool("read_metrics", {"service": "carts"})) == "carts is healthy"
    assert asyncio.run(kit.run_tool("restart_service", {"service": "carts"})) == "restarted carts"
    assert runs == ["read_metrics(carts)", "restart_service(carts)"]


class _Sidecar:
    def __init__(self) -> None:
        self.decisions: list[client_pb2.DecideRequest] = []

    async def Decide(self, request: client_pb2.DecideRequest) -> client_pb2.InvokeReply:  # noqa: N802
        self.decisions.append(request)
        return client_pb2.InvokeReply(reply=payload_pb2.Outcome.Reply())


def test_a_decision_names_the_instance_the_request_and_who_decided() -> None:
    sidecar = _Sidecar()
    client = ComponentClient(address="127.0.0.1:0")
    client._stub_cache = sidecar  # type: ignore[assignment]
    asyncio.run(approve(client, "a-1"))
    (sent,) = sidecar.decisions
    assert (sent.kind, sent.component_id, sent.entity_id) == (discovery_pb2.AUTONOMOUS_AGENT, "operator", "ops-1")
    assert (sent.approval_id, sent.approved, sent.by) == ("a-1", True, "dana")
