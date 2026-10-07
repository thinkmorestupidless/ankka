"""MCP servers an agent offers the model, and the guardrails that check what they answer.

The sidecar connects to each server when it starts, reads its tools and offers them to the model
as ``mcp__<server>__<tool>``. This process is never asked to run one, and never sees a credential:
a header's value is read from a variable on the sidecar, and only ``ANKKA_MCP_`` variables reach
it. What a server's tool answers is put through the agent's result guardrails, which this process
answers, before the model is told it.
"""

from __future__ import annotations

import re
from collections.abc import Awaitable, Callable, Mapping
from dataclasses import dataclass, field
from typing import Any

from ankka._proto.ankka.protocol.v1 import discovery_pb2
from ankka.approvals import Approval, approval_of, approval_to_pb

VARIABLE_PREFIX = "ANKKA_MCP_"
TOOL_PREFIX = "mcp__"
_NAME = re.compile(r"[a-z0-9-]+")


@dataclass(frozen=True)
class McpServer:
    """An MCP server an agent lists under its key. Its address is ``url``, or the ankka
    ``service`` (in ``project``, by default this one) at ``path``, or neither: then the sidecar
    reads ``ANKKA_MCP_<NAME>_URL``, which also replaces a ``url`` given here. ``headers`` maps a
    header's name to the variable its value is read from. With ``approval``, every tool of the
    server waits for a person."""

    url: str | None = None
    service: str | None = None
    project: str | None = None
    path: str = "/mcp"
    headers: Mapping[str, str] = field(default_factory=dict)
    approval: bool | Approval = False

    def problems(self, name: str) -> list[str]:
        found: list[str] = []
        if not _NAME.fullmatch(name):
            found.append(f"an MCP server's name is lower-case letters, digits and hyphens ([a-z0-9-]), not '{name}'")
        if self.url is not None and self.service is not None:
            found.append(f"MCP server '{name}' has both a URL and a service; give one")
        if self.url is not None and not self.url.strip():
            found.append(f"MCP server '{name}' needs a URL")
        for header, variable in self.headers.items():
            if not header.strip():
                found.append(f"MCP server '{name}' has a header with no name")
            elif not variable.startswith(VARIABLE_PREFIX):
                found.append(
                    f"MCP server '{name}': the header '{header}' takes its value from '{variable}', which must "
                    f"start {VARIABLE_PREFIX} — only those variables reach the platform's program, which is what "
                    "connects to the server"
                )
        try:
            approval_of(self.approval)
        except ValueError as e:
            found.append(f"MCP server '{name}': {e}")
        return found

    def to_pb(self, name: str) -> discovery_pb2.McpServer:
        pb = discovery_pb2.McpServer(
            name=name,
            headers=[discovery_pb2.McpServer.Header(name=h, variable=v) for h, v in self.headers.items()],
        )
        if self.url is not None:
            pb.url = self.url
        elif self.service is not None:
            service = discovery_pb2.McpServer.Service(name=self.service, path=self.path)
            if self.project is not None:
                service.project = self.project
            pb.service.CopyFrom(service)
        approval = approval_to_pb(self.approval)
        if approval is not None:
            pb.approval.CopyFrom(approval)
        return pb


@dataclass(frozen=True)
class ResultGuardrail:
    """Checks what an MCP server's tool answered before the model is told it: ``check(tool, text)``
    answers a reason to withhold it, or ``None``. ``tool`` is the name the model called. A result it
    withholds is never told to the model, which is told of an error instead."""

    check: Callable[[str, str], str | None | Awaitable[str | None]]


def tool_name(server: str, tool: str) -> str:
    """The name a server's tool is offered to the model under."""
    return f"{TOOL_PREFIX}{server}__{tool}"


def problems(
    tools: Mapping[str, Any],
    servers: Mapping[str, McpServer],
    result_guardrails: Mapping[str, ResultGuardrail],
) -> list[str]:
    """What is wrong with an agent's tools and servers, in the sidecar's words."""
    found: list[str] = []
    for name, server in servers.items():
        found.extend(server.problems(name))
    for name, tool in tools.items():
        if name.startswith(TOOL_PREFIX):
            found.append(f"tool '{name}' takes the prefix '{TOOL_PREFIX}', which names an MCP server's tools")
        try:
            approval_of(getattr(tool, "approval", None))
        except ValueError as e:
            found.append(f"tool '{name}': {e}")
    for name in result_guardrails:
        if not name.strip():
            found.append("a result guardrail needs a name")
    return sorted(found)


__all__ = ["McpServer", "ResultGuardrail", "tool_name"]
