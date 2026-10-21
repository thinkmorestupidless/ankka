"""An MCP endpoint (feature 054): its tools' schemas, its references, its refusals, and a tool run."""

from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass
from typing import Any

import grpc
import pytest

from ankka import Ankka, Done, Expiry, McpEndpoint, tool
from ankka._proto.ankka.protocol.v1 import client_pb2, endpoint_pb2, mcp_pb2
from ankka.client import CommandError
from ankka.client_keys import ClientKeys, IssuedClientKey
from ankka.endpoint import Acl, Endpoint, get
from ankka.mcp_endpoint import answer_of
from ankka.server import HttpServicer, McpServicer, mcp_refusal
from ankka.service import RegistrationError
from ankka.testkit.unit import _NoClient
from tests.counter import CounterEndpoint, CounterEntity


@dataclass(frozen=True)
class Note:
    text: str
    loud: bool = False


class CounterMcp(McpEndpoint):
    path = "/mcp"
    acl = Acl.any_of(Acl.CLIENT_KEYS, Acl.AUTHENTICATED)

    @tool("Says who asked")
    def whoami(self, greeting: str, times: int = 1) -> dict[str, Any]:
        p = self.request.principal
        return {
            "greeting": greeting * times,
            "subject": p.subject if p else None,
            "claims": dict(p.claims) if p else {},
        }

    @tool("Takes a note", acl=Acl.AUTHENTICATED)
    def note(self, note: Note) -> Done:
        return Done()

    total = tool.query(CounterEntity, "total", id="counterId", description="Reads a total")
    increment = tool.command(CounterEntity, "increment", id="counterId", input="by", description="Adds")


def _registry() -> Any:
    return Ankka.service().register(CounterEntity).register(CounterEndpoint).register(CounterMcp).validate()


def test_a_method_tools_schema_is_its_parameters() -> None:
    tools = _registry().tools_of("CounterMcp")
    assert tools["whoami"].input_schema == {
        "type": "object",
        "properties": {"greeting": {"type": "string"}, "times": {"type": "integer"}},
        "required": ["greeting"],
    }
    note = tools["note"].input_schema
    assert note["properties"]["text"] == {"type": "string"}
    assert "text" in note["required"] and "loud" not in note["required"]


def test_a_references_schema_is_its_id_and_input() -> None:
    tools = _registry().tools_of("CounterMcp")
    assert tools["total"].input_schema == {
        "type": "object",
        "properties": {"counterId": {"type": "string"}},
        "required": ["counterId"],
    }
    assert tools["increment"].input_schema["required"] == ["counterId", "by"]
    assert tools["increment"].input_schema["properties"]["by"] == {"type": "integer"}


def test_the_spec_carries_the_endpoint_and_its_acls() -> None:
    spec = _registry().spec()
    (mcp,) = spec.mcp_endpoints
    assert (mcp.id, mcp.path, mcp.brokered) == ("CounterMcp", "/mcp", False)
    assert mcp.acl.client_keys and mcp.acl.tokens and not mcp.acl.allow_all
    assert [t.name for t in mcp.tools] == ["increment", "note", "total", "whoami"]
    note = next(t for t in mcp.tools if t.name == "note")
    assert note.HasField("acl") and note.acl.tokens and not note.acl.client_keys
    total = next(t for t in mcp.tools if t.name == "total")
    assert not total.HasField("acl")
    assert json.loads(total.input_schema_json)["required"] == ["counterId"]


def test_a_reference_to_nothing_registered_is_refused() -> None:
    class Orphan(McpEndpoint):
        path = "/orphan"
        acl = Acl.CLIENT_KEYS
        total = tool.query(CounterEntity, "total", id="counterId", description="Reads a total")

    with pytest.raises(RegistrationError, match="does not register"):
        Ankka.service().register(Orphan).validate()


def test_a_reference_to_a_missing_handler_is_refused() -> None:
    class Missing(McpEndpoint):
        path = "/missing"
        acl = Acl.CLIENT_KEYS
        nothing = tool.command(CounterEntity, "no-such", id="counterId", description="Nothing")

    with pytest.raises(RegistrationError, match="does not have"):
        Ankka.service().register(CounterEntity).register(Missing).validate()


def test_a_command_named_as_a_query_is_refused() -> None:
    class Wrong(McpEndpoint):
        path = "/wrong"
        acl = Acl.CLIENT_KEYS
        reset = tool.query(CounterEntity, "reset", id="counterId", description="Resets")

    with pytest.raises(RegistrationError, match="it is a command"):
        Ankka.service().register(CounterEntity).register(Wrong).validate()


def test_an_endpoint_without_a_tool_is_refused() -> None:
    class Empty(McpEndpoint):
        path = "/empty"
        acl = Acl.CLIENT_KEYS

    with pytest.raises(RegistrationError, match="declares no tool"):
        Ankka.service().register(Empty).validate()


def test_an_endpoint_without_an_acl_is_refused() -> None:
    with pytest.raises(RegistrationError, match="must declare an acl"):

        class Open(McpEndpoint):
            path = "/open"


def test_a_bad_tool_name_is_refused() -> None:
    with pytest.raises(RegistrationError, match="tool name"):

        class Shouty(McpEndpoint):
            path = "/shouty"
            acl = Acl.CLIENT_KEYS

            @tool("Shouts", name="Shout!")
            def shout(self) -> str:
                return "!"


def test_an_http_endpoint_refuses_client_keys() -> None:
    with pytest.raises(RegistrationError, match="for an McpEndpoint"):

        class Keyed(Endpoint):
            prefix = "/keyed"
            acl = Acl.CLIENT_KEYS

            @get("/")
            def show(self) -> str:
                return "no"


def test_an_mcp_acl_renders_each_member() -> None:
    pb = Acl.any_of(Acl.CLIENT_KEYS, Acl.AUTHENTICATED).to_mcp_pb()
    assert (pb.client_keys, pb.tokens, pb.allow_all, pb.deny_all) == (True, True, False, False)
    assert Acl.ALLOW_ALL.to_mcp_pb().allow_all
    assert Acl.DENY_ALL.to_mcp_pb().deny_all
    nested = Acl.any_of(Acl.any_of(Acl.CLIENT_KEYS, Acl.AUTHENTICATED), Acl.ALLOW_ALL)
    assert len(nested.members) == 3


def test_an_older_runtime_is_refused_naming_1_20() -> None:
    registry = _registry()
    refusal = mcp_refusal(registry, "1.19")
    assert refusal is not None and "1.20" in refusal and "/mcp" in refusal
    assert mcp_refusal(registry, "1.20") is None
    assert mcp_refusal(Ankka.service().register(CounterEntity).validate(), "1.19") is None


class _Unimplemented:
    def __getattr__(self, name: str) -> Any:
        async def refuse(_request: Any, **_: Any) -> Any:
            raise grpc.aio.AioRpcError(
                grpc.StatusCode.UNIMPLEMENTED, grpc.aio.Metadata(), grpc.aio.Metadata(), "Method not found"
            )

        return refuse


class _OldRuntime:
    _stub = _Unimplemented()


def test_client_keys_on_an_older_runtime_name_1_20() -> None:
    keys = ClientKeys(_OldRuntime())  # type: ignore[arg-type]
    with pytest.raises(CommandError) as refused:
        asyncio.run(keys.issue("alice", Expiry.never()))
    assert "1.20" in refused.value.error.message


def test_an_expiry_fills_its_case() -> None:
    request = client_pb2.IssueClientKeyRequest()
    Expiry.after(minutes=5).apply_to(request)
    assert request.WhichOneof("expiry") == "after_millis" and request.after_millis == 300_000
    request = client_pb2.IssueClientKeyRequest()
    Expiry.never().apply_to(request)
    assert request.WhichOneof("expiry") == "never"


def test_an_issued_keys_repr_hides_the_key() -> None:
    assert "secret-key" not in repr(IssuedClientKey("k1", "secret-key"))


def _servicer() -> McpServicer:
    registry = _registry()
    client = _NoClient()
    return McpServicer(registry, client, HttpServicer(registry, client))


def test_a_tool_runs_with_the_principal_and_no_credential() -> None:
    request = mcp_pb2.McpToolRequest(
        endpoint_id="CounterMcp",
        tool="whoami",
        arguments_json=json.dumps({"greeting": "hi", "times": 2}),
        principal=endpoint_pb2.Principal(subject="alice", claims={"tenant": "acme"}),
    )
    reply = asyncio.run(_servicer().InvokeTool(request, None))
    assert reply.HasField("ok"), reply
    answer = json.loads(reply.ok.structured_json)
    assert answer == {"greeting": "hihi", "subject": "alice", "claims": {"tenant": "acme"}}


def test_an_unknown_tool_is_a_failure() -> None:
    request = mcp_pb2.McpToolRequest(endpoint_id="CounterMcp", tool="nope")
    reply = asyncio.run(_servicer().InvokeTool(request, None))
    assert reply.HasField("failure")


def test_a_tool_that_raises_is_a_tool_error() -> None:
    class Raising(McpEndpoint):
        path = "/raising"
        acl = Acl.CLIENT_KEYS

        @tool("Fails")
        def fail(self) -> str:
            raise ValueError("no such thing")

    registry = Ankka.service().register(Raising).validate()
    servicer = McpServicer(registry, _NoClient(), HttpServicer(registry, _NoClient()))
    reply = asyncio.run(servicer.InvokeTool(mcp_pb2.McpToolRequest(endpoint_id="Raising", tool="fail"), None))
    assert reply.error == "no such thing"


def test_what_a_tool_returns_is_its_answer() -> None:
    assert answer_of(None).text == "done" and answer_of(Done()).text == "done"
    assert answer_of("plain").text == "plain" and answer_of("plain").structured_json is None
    structured = answer_of({"a": 1})
    assert json.loads(structured.text) == {"a": 1} and structured.structured_json is not None
    assert answer_of([1, 2]).structured_json is None
