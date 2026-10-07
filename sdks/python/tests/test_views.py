"""A view's declared queries: sent in discovery as written, asked by name with values, and refused
to a sidecar too old to know them."""

from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass
from typing import Any

from ankka import DeclaredQuery, json_codec, table_of
from ankka._proto.ankka.protocol.v1 import client_pb2, payload_pb2
from ankka.client import Views
from ankka.context import Metadata
from ankka.effects.view import ViewEffect
from ankka.server import DiscoveryServicer
from ankka.service import Ankka
from ankka.view import View, query

from tests.counter import CounterEntity

CODEC = json_codec(dict, "row")

@dataclass(frozen=True)
class TreeRow:
    key: str


UNDER = f"SELECT payload FROM {table_of('tree-rows')} WHERE payload::jsonb->>'under' = :row"


class TreeRows(View[Any, Any]):
    component_id = "tree-rows"
    source = CounterEntity
    event_codec = CODEC
    row_codec = CODEC
    under = query("under", UNDER)

    def on_change(self, event: Any) -> ViewEffect:
        return self.effects.update_row(event)


class Plain(View[Any, Any]):
    component_id = "plain"
    source = CounterEntity
    event_codec = CODEC
    row_codec = CODEC

    def on_change(self, event: Any) -> ViewEffect:
        return self.effects.update_row(event)


def test_a_table_is_named_as_the_platform_names_it() -> None:
    assert table_of("tree-rows") == "ankka_view_tree_rows"
    assert table_of("a.b-c") == "ankka_view_a_b_c"


def test_discovery_carries_the_declared_queries_as_written() -> None:
    detail = TreeRows.to_component().view
    assert [(q.name, q.statement) for q in detail.declared_queries] == [("under", UNDER)]
    assert TreeRows.under == DeclaredQuery("under", UNDER)
    assert list(Plain.to_component().view.declared_queries) == []


class _Stub:
    def __init__(self) -> None:
        self.asked: list[client_pb2.QueryRequest] = []

    async def Query(self, request: client_pb2.QueryRequest) -> client_pb2.QueryReply:
        self.asked.append(request)
        rows = json.dumps([{"key": "b"}, {"key": "c"}]).encode()
        return client_pb2.QueryReply(rows=payload_pb2.Payload(content_type="application/json", manifest="rows", data=rows))


def test_ask_sends_the_name_the_values_and_the_limit() -> None:
    stub = _Stub()
    views = Views(stub, Metadata().set("ankka.caller", "x"))  # type: ignore[arg-type]
    rows = asyncio.run(views.ask("tree-rows", "under", TreeRow, {"row": "a"}, limit=5))
    assert rows == [TreeRow("b"), TreeRow("c")]
    request = stub.asked[0]
    assert (request.view_id, request.name, dict(request.values), request.limit) == ("tree-rows", "under", {"row": "a"}, 5)
    assert any(e.key == "ankka.caller" for e in request.metadata.entries)


def test_ask_without_a_limit_sends_none() -> None:
    stub = _Stub()
    asyncio.run(Views(stub).ask("tree-rows", "under", TreeRow, {"row": "a"}))  # type: ignore[arg-type]
    assert not stub.asked[0].HasField("limit")


def test_a_sidecar_too_old_for_declared_queries_is_refused_naming_the_view() -> None:
    servicer = DiscoveryServicer(Ankka.service().register(TreeRows)._registry)
    refusal = servicer.refusal("1.7")
    assert refusal is not None
    assert "TreeRows" in refusal and "1.7" in refusal and "1.13" in refusal
    assert servicer.refusal("1.13") is None


def test_a_view_declaring_no_query_is_served_by_a_1_7_sidecar() -> None:
    servicer = DiscoveryServicer(Ankka.service().register(Plain)._registry)
    assert servicer.refusal("1.7") is None


# ── Keyed views ─────────────────────────────────────────────────────────────

from ankka import EventSourcedEffect, EventSourcedEntity, KeyedView, KeyedViewEffect, RegistrationError, command  # noqa: E402
from ankka._proto.ankka.protocol.v1 import discovery_pb2, view_pb2  # noqa: E402
from ankka.keyed_view import on, on_deleted  # noqa: E402
from ankka.server import ViewServicer  # noqa: E402
from ankka.testkit import KeyedViewTestKit  # noqa: E402

import pytest  # noqa: E402


@dataclass(frozen=True)
class Note:
    text: str


class Customer(EventSourcedEntity[Note, Note]):
    component_id = "customer"
    state_codec = json_codec(Note, "customer")
    event_codec = json_codec(Note, "note")

    def empty_state(self) -> Note:
        return Note("")

    def apply_event(self, state: Note, event: Note) -> Note:
        return event

    @command("note")
    def note(self, text: str) -> EventSourcedEffect[Note, Note, str]:
        return self.effects.persist(Note(text)).then_reply(lambda _: "noted")


@dataclass(frozen=True)
class ShipmentRow:
    key: str
    customer: str | None = None
    notes: tuple[str, ...] = ()


class Shipments(KeyedView[ShipmentRow]):
    component_id = "shipments"
    row_codec = json_codec(ShipmentRow, "shipment-row")
    of_customer = query("of-customer", f"SELECT payload FROM {table_of('shipments')} WHERE payload::jsonb->>'customer' = :customer")

    @on(CounterEntity, CounterEntity.event_codec)
    async def on_shipment(self, event: Any) -> KeyedViewEffect:
        current = await self.rows.get(self.subject)
        notes = current.notes if current is not None else ()
        return self.effects.update_row(self.subject, ShipmentRow(self.subject, "c1", (*notes, "shipment")))

    @on(Customer, Customer.event_codec)
    async def on_customer(self, event: Note) -> KeyedViewEffect:
        theirs = await self.rows.ask("of-customer", customer=self.subject)
        return self.effects.update_rows({r.key: ShipmentRow(r.key, r.customer, (*r.notes, event.text)) for r in theirs})

    @on_deleted(Customer)
    def on_customer_deleted(self) -> KeyedViewEffect:
        return self.effects.delete_rows(["held-" + self.subject])


def test_a_keyed_view_is_discovered_with_its_sources_and_no_single_source() -> None:
    detail = Shipments.to_component().view
    assert not detail.HasField("source")
    assert [(s.component.kind, s.component.id) for s in detail.sources] == [
        (discovery_pb2.EVENT_SOURCED_ENTITY, "counter"),
        (discovery_pb2.EVENT_SOURCED_ENTITY, "customer"),
    ]
    assert detail.row_manifest == "shipment-row"
    assert [q.name for q in detail.declared_queries] == ["of-customer"]


def test_a_keyed_view_with_no_source_or_a_source_twice_is_refused() -> None:
    with pytest.raises(RegistrationError, match="declares no source"):
        type("Empty", (KeyedView,), {"component_id": "empty", "row_codec": Shipments.row_codec})

    def one(self: Any, event: Any) -> KeyedViewEffect:
        return KeyedViewEffect()

    with pytest.raises(RegistrationError, match="reads 'counter' twice"):
        type(
            "Twice",
            (KeyedView,),
            {
                "component_id": "twice",
                "row_codec": Shipments.row_codec,
                "a": on(CounterEntity, CounterEntity.event_codec)(one),
                "b": on(CounterEntity, CounterEntity.event_codec)(one),
            },
        )


class _RowsStub:
    """The sidecar's client: one row held for customer c1, and nothing under any key."""

    def __init__(self) -> None:
        self.asked: list[client_pb2.QueryRequest] = []

    async def Query(self, request: client_pb2.QueryRequest) -> client_pb2.QueryReply:
        self.asked.append(request)
        rows = [] if request.name == "get" else [{"key": "s1", "customer": "c1", "notes": ["shipment"]}]
        return client_pb2.QueryReply(
            rows=payload_pb2.Payload(content_type="application/json", manifest="rows", data=json.dumps(rows).encode())
        )


class _Client:
    def __init__(self, stub: _RowsStub) -> None:
        self.stub = stub

    def with_metadata(self, metadata: Metadata) -> Any:
        stub = self.stub

        class _Scoped:
            views = Views(stub, metadata)  # type: ignore[arg-type]

        return _Scoped()


def _request(source: str, event: Any | None, subject: str) -> view_pb2.ViewRequest:
    codec = CounterEntity.event_codec if source == "counter" else Customer.event_codec
    metadata = Metadata().set("ce-subject", subject).to_pb()
    if event is None:
        return view_pb2.ViewRequest(component_id="shipments", source_id=source, deleted=True, metadata=metadata)
    return view_pb2.ViewRequest(
        component_id="shipments",
        source_id=source,
        event=payload_pb2.Payload(content_type="application/json", manifest=codec.manifest, data=codec.encode(event)),
        metadata=metadata,
    )


def test_a_change_with_a_source_id_goes_to_that_source_and_answers_rows() -> None:
    stub = _RowsStub()
    servicer = ViewServicer(Ankka.service().register(Shipments)._registry, _Client(stub))  # type: ignore[arg-type]
    effect = asyncio.run(servicer.Handle(_request("customer", Note("renamed"), "c1"), None))
    assert effect.WhichOneof("effect") == "rows"
    [change] = effect.rows.changes
    assert change.key == "s1" and change.WhichOneof("change") == "upsert"
    assert json.loads(change.upsert.data)["notes"] == ["shipment", "renamed"]
    assert change.upsert.manifest == "shipment-row"
    asked = stub.asked[0]
    assert (asked.view_id, asked.name, dict(asked.values)) == ("shipments", "of-customer", {"customer": "c1"})


def test_a_source_entity_deleted_answers_its_deletions_or_nothing() -> None:
    servicer = ViewServicer(Ankka.service().register(Shipments)._registry, _Client(_RowsStub()))  # type: ignore[arg-type]
    deleted = asyncio.run(servicer.Handle(_request("customer", None, "c1"), None))
    assert [(c.key, c.WhichOneof("change")) for c in deleted.rows.changes] == [("held-c1", "delete")]
    nothing = asyncio.run(servicer.Handle(_request("counter", None, "s1"), None))
    assert nothing.WhichOneof("effect") == "rows" and list(nothing.rows.changes) == []


def test_a_sidecar_too_old_for_a_keyed_view_is_refused_naming_it() -> None:
    servicer = DiscoveryServicer(Ankka.service().register(Shipments)._registry)
    refusal = servicer.refusal("1.7")
    assert refusal is not None and "Shipments" in refusal and "1.13" in refusal
    assert servicer.refusal("1.13") is None


def test_the_keyed_kit_applies_each_source_and_answers_only_what_it_was_told() -> None:
    from tests.counter import Incremented

    kit = KeyedViewTestKit.of(Shipments)
    kit.change(CounterEntity, "s1", Incremented(1))
    assert kit.get("s1") == ShipmentRow("s1", "c1", ("shipment",))
    with pytest.raises(LookupError, match="of-customer"):
        kit.change(Customer, "c1", Note("renamed"))
    kit.answering("of-customer", lambda values: [r for r in kit.rows.values() if r.customer == values["customer"]])
    kit.change(Customer, "c1", Note("renamed"))
    assert kit.get("s1") == ShipmentRow("s1", "c1", ("shipment", "renamed"))
