"""The secret store as a Python service sees it: who is given one, the rules, and a runtime too old
to have one. The store itself runs in the sidecar and is held by the conformance suite's
``secret.*`` cases."""

from __future__ import annotations

import asyncio
import json
from pathlib import Path
from typing import Any

import grpc
import pytest

from ankka import CommandContext, EventSourcedEntity, ErrorCode, InMemorySecrets, Secrets
from ankka.agent import Agent
from ankka.client import CommandError
from ankka.consumer import Consumer
from ankka.endpoint import Endpoint
from ankka.key_value_entity import KeyValueEntity
from ankka.secrets import name_problem, value_problem
from ankka.timed_action import TimedAction
from ankka.view import View
from tests.kinds import CheckoutWorkflow

RULES = Path(__file__).resolve().parent.parent / "proto" / "fixtures" / "secrets" / "rules.json"


def _rows(kind: str) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = json.loads(RULES.read_text("utf-8"))[kind]
    assert rows
    return rows


@pytest.mark.parametrize("row", _rows("names"), ids=lambda r: r["why"])
def test_every_name_in_the_fixture_gets_its_verdict(row: dict[str, Any]) -> None:
    assert (name_problem(row["unit"] * row["repeat"]) is None) == row["accepted"]


@pytest.mark.parametrize("row", _rows("values"), ids=lambda r: r["why"])
def test_every_value_in_the_fixture_gets_its_verdict(row: dict[str, Any]) -> None:
    assert (value_problem(row["unit"] * row["repeat"]) is None) == row["accepted"]


def test_the_unit_test_double_refuses_what_the_runtime_refuses() -> None:
    store = InMemorySecrets()
    asyncio.run(store.put("provider/acme", "sk-1"))
    assert asyncio.run(store.get("provider/acme")) == "sk-1"
    assert asyncio.run(store.get("never")) is None
    with pytest.raises(CommandError) as refused:
        asyncio.run(store.put("provider acme", "sk-1"))
    assert refused.value.error.code == ErrorCode.BAD_REQUEST
    assert "'.', '_', '-' or '/'" in refused.value.error.message
    with pytest.raises(CommandError):
        asyncio.run(store.put("empty", ""))


def test_an_entity_or_a_view_is_given_no_secret_store() -> None:
    for cls in (EventSourcedEntity, KeyValueEntity, View, CommandContext):
        assert not hasattr(cls, "secrets"), f"{cls.__name__} must have no secrets"


def test_a_consumer_a_timed_action_an_agent_and_an_endpoint_are_given_one() -> None:
    for cls in (Consumer, TimedAction, Agent, Endpoint):
        assert hasattr(cls, "secrets"), f"{cls.__name__} must have secrets"


def test_a_workflow_reads_a_service_secret_in_a_step_and_not_in_a_command() -> None:
    workflow = CheckoutWorkflow()
    workflow.secrets = InMemorySecrets()
    with pytest.raises(CommandError) as refused:
        workflow.secrets
    assert refused.value.error.code == ErrorCode.BAD_REQUEST
    assert "in a step" in refused.value.error.message
    workflow._in_step = True
    assert isinstance(workflow.secrets, InMemorySecrets)


class _Unimplemented:
    """A stub of a runtime that predates the secret store: grpc-java answers UNIMPLEMENTED."""

    def __getattr__(self, name: str) -> Any:
        async def refuse(_request: Any) -> Any:
            raise grpc.aio.AioRpcError(grpc.StatusCode.UNIMPLEMENTED, grpc.aio.Metadata(), grpc.aio.Metadata(), "Method not found")

        return refuse


class _OldRuntime:
    _stub = _Unimplemented()


def test_a_runtime_without_the_store_is_reported_as_too_old_not_as_absent() -> None:
    store = Secrets(_OldRuntime())  # type: ignore[arg-type]
    for call in (lambda: store.get("acme"), lambda: store.put("acme", "sk-1"), lambda: store.delete("acme")):
        with pytest.raises(CommandError) as refused:
            asyncio.run(call())
        assert "1.4" in refused.value.error.message
