"""Graph deltas: the builder, the reader, a graph consumer and its test kit, against the fixtures
ankka shares with ankka-flow, whose merge sink reads what a graph consumer publishes."""

from __future__ import annotations

import json
import math
import pathlib
from typing import Any

import grpc
import pytest

from ankka import GraphConsumer, Metadata, StartFrom, graph
from ankka._proto.ankka.protocol.v1 import consumer_pb2, discovery_pb2, payload_pb2
from ankka.consumer import Consumer
from ankka.effects.consumer import ConsumerEffect, Done, Ignore, ProduceAll
from ankka.event_sourced_entity import RegistrationError
from ankka.graph import Element, Graph, GraphEffect, RefusedElement
from ankka.server import ConsumerServicer
from ankka.service import Ankka, Registry
from ankka.testkit import GraphConsumerTestKit
from ankka.testkit.unit import _NoClient
from tests.counter import CounterEntity, CounterEvent, Incremented, Noted

FIXTURES = pathlib.Path(__file__).resolve().parent.parent / "proto" / "fixtures" / "graph-deltas"
if not FIXTURES.is_dir():  # the canonical copy, in the repository
    FIXTURES = pathlib.Path(__file__).resolve().parents[3] / "protocol" / "fixtures" / "graph-deltas"


def _rows(name: str) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = json.loads((FIXTURES / name).read_text(encoding="utf-8"))
    return rows


KEYS, DELTAS, REFUSED = _rows("keys.json"), _rows("deltas.json"), _rows("refused.json")
G = Graph()


def build(delta: dict[str, Any]) -> Element:
    """A fixture's delta, built the way a handler builds it. Fields the row lacks are passed as an
    author would leave them out; a field it has is passed as it is, valid or not."""
    version = delta.get("version")
    if delta["kind"] == "node":
        return G.node(delta["id"], labels=delta.get("labels", ()), properties=delta.get("properties"), version=version)
    if delta["kind"] == "edge":
        return G.edge(delta["id"], type=delta.get("type", ""), from_id=delta.get("from", ""), to_id=delta.get("to", ""), properties=delta.get("properties"), version=version)
    if delta["element"] == "node":
        return G.tombstone_node(delta["id"], version=version)
    return G.tombstone_edge(delta["id"], type=delta.get("type", ""), from_id=delta.get("from", ""), to_id=delta.get("to", ""), version=version)


def kind_of(value: Any) -> str:
    if isinstance(value, list):
        return f"list:{kind_of(value[0])}"
    if isinstance(value, str):
        return "string"
    if isinstance(value, bool):
        return "boolean"
    return "integer" if isinstance(value, int) else "float"


def _id(prefix: str, rows: list[dict[str, Any]]) -> list[str]:
    return [f"{prefix}{i}:{r.get('key') or r.get('name')}" for i, r in enumerate(rows)]


# ── the shared fixtures: what the builder writes is what the sink reads ─────────────────────────


def test_the_fixtures_cover_each_kind_of_delta_and_of_property() -> None:
    kinds = {(r["delta"]["kind"], r["delta"].get("element")) for r in KEYS + DELTAS}
    assert {("node", None), ("edge", None), ("tombstone", "node"), ("tombstone", "edge")} <= kinds
    assert any(not r["key"].isascii() for r in KEYS)
    read_as = {kind for r in DELTAS for kind in r["reads"].values()}
    assert read_as == {k for s in ("string", "integer", "float", "boolean") for k in (s, f"list:{s}")}
    assert {r["why"] for r in REFUSED} == {"id", "endpoints", "identifier", "reserved", "property-value", "integer-range", "version", "duplicate", "no-sequence"}


@pytest.mark.parametrize("row", KEYS + DELTAS, ids=_id("", KEYS + DELTAS))
def test_an_element_built_from_a_fixture_row_has_its_key_and_reads_back_as_its_delta(row: dict[str, Any]) -> None:
    element = build(row["delta"])
    assert element.key == row["key"]
    written = graph.CODEC.encode(element)
    # Equality is the reader's: 2.0 and 2 are one value, and labels and properties that are absent
    # equal ones that are empty.
    assert graph.read(written, row["key"]) == graph.read(json.dumps(row["delta"]).encode("utf-8"), row["key"])
    # Labels and properties are always written, empty when there are none.
    as_written = json.loads(written)
    if row["delta"]["kind"] == "node":
        assert as_written["labels"] == row["delta"].get("labels", [])
    if row["delta"]["kind"] in ("node", "edge"):
        assert set(as_written["properties"]) == set(row["delta"].get("properties", {}))
    assert as_written["version"] == row["delta"]["version"]


@pytest.mark.parametrize("row", DELTAS, ids=_id("", DELTAS))
def test_a_property_reads_back_as_the_kind_the_sink_reads_it_as(row: dict[str, Any]) -> None:
    element = graph.read(graph.CODEC.encode(build(row["delta"])))
    assert {name: kind_of(value) for name, value in element.properties.items()} == row["reads"]


@pytest.mark.parametrize("row", REFUSED, ids=_id("", REFUSED))
def test_what_the_fixture_says_is_refused_is_refused_for_the_reason_it_names(row: dict[str, Any]) -> None:
    with pytest.raises(RefusedElement) as refused:
        elements = [build(e) for e in row.get("elements", [row.get("element")])]
        graph.resolve(elements, row.get("sequence", 1))
    assert refused.value.why == row["why"], str(refused.value)
    assert isinstance(refused.value, ValueError)


# ── what JSON cannot say ────────────────────────────────────────────────────────────────────────


@pytest.mark.parametrize("value", [math.nan, math.inf, -math.inf, [1.5, math.inf]])
def test_a_number_that_is_not_finite_is_refused(value: Any) -> None:
    with pytest.raises(RefusedElement, match="not a finite number"):
        G.node("n", properties={"p": value})


def test_a_boolean_is_not_an_integer() -> None:
    with pytest.raises(RefusedElement) as refused:
        G.node("n", version=True)
    assert refused.value.why == "version"
    # As a property it is its own kind, and so does not sit in a list with integers.
    assert graph.read(graph.CODEC.encode(G.node("n", properties={"p": True}, version=1))).properties == {"p": True}
    with pytest.raises(RefusedElement, match="more than one kind"):
        G.node("n", properties={"p": [1, True]})


def test_a_property_name_that_is_not_a_string_is_refused() -> None:
    with pytest.raises(RefusedElement) as refused:
        G.node("n", properties={1: "x"})  # type: ignore[dict-item]
    assert refused.value.why == "property-name"


def test_labels_given_as_one_string_are_refused_rather_than_read_letter_by_letter() -> None:
    with pytest.raises(RefusedElement, match="list of identifiers"):
        G.node("n", labels="Cart")
    assert G.node("n", labels=("Cart", "Open")).labels == ("Cart", "Open")


def test_a_tuple_is_a_list_and_a_whole_float_sits_with_integers() -> None:
    element = G.node("n", properties={"sizes": (1, 2.0, 3), "weights": [0.5, 1.5]}, version=1)
    assert graph.read(graph.CODEC.encode(element)).properties == {"sizes": [1, 2, 3], "weights": [0.5, 1.5]}


# ── versions: stated, or the change's ───────────────────────────────────────────────────────────


def test_an_element_takes_the_changes_sequence_number_unless_it_states_a_version() -> None:
    resolved = graph.resolve([G.node("a"), G.node("b", version=40), G.tombstone_edge("e", type="T", from_id="a", to_id="b")], 7)
    assert [(e.key, e.version) for e in resolved] == [("node:a", 7), ("node:b", 40), ("edge:e", 7)]


def test_a_change_with_no_sequence_number_needs_versions_stated() -> None:
    for sequence in (None, 0, -1):
        with pytest.raises(RefusedElement, match="no sequence number.*state a version") as refused:
            graph.resolve([G.node("a")], sequence)
        assert refused.value.why == "no-sequence"
        assert [e.version for e in graph.resolve([G.node("a", version=3)], sequence)] == [3]


def test_the_same_element_twice_is_refused_and_a_node_and_an_edge_sharing_an_id_are_not() -> None:
    with pytest.raises(RefusedElement, match="twice") as refused:
        graph.resolve([G.node("x"), G.tombstone_node("x")], 1)
    assert refused.value.why == "duplicate"
    both = graph.resolve([G.node("x"), G.edge("x", type="T", from_id="a", to_id="b")], 1)
    assert [e.key for e in both] == ["node:x", "edge:x"]


def test_an_element_with_no_version_cannot_be_written() -> None:
    with pytest.raises(RefusedElement, match="no version yet"):
        graph.CODEC.encode(G.node("a"))


# ── the bytes ───────────────────────────────────────────────────────────────────────────────────


def test_a_delta_is_written_as_the_contract_writes_it() -> None:
    node = G.node("cart:c1", labels=["Cart"], properties={"cartId": "c1", "checkedOut": False}, version=3)
    assert graph.CODEC.encode(node) == b'{"kind":"node","id":"cart:c1","version":3,"labels":["Cart"],"properties":{"cartId":"c1","checkedOut":false}}'
    edge = G.edge("checked-out:c1", type="CHECKED_OUT", from_id="cart:c1", to_id="checkout:c1", version=4)
    assert (
        graph.CODEC.encode(edge)
        == b'{"kind":"edge","id":"checked-out:c1","version":4,"type":"CHECKED_OUT","from":"cart:c1","to":"checkout:c1","properties":{}}'
    )
    assert graph.CODEC.encode(G.tombstone_node("cart:c1", version=5)) == b'{"kind":"tombstone","element":"node","id":"cart:c1","version":5}'
    assert (
        graph.CODEC.encode(G.tombstone_edge("checked-out:c1", type="CHECKED_OUT", from_id="cart:c1", to_id="checkout:c1", version=6))
        == b'{"kind":"tombstone","element":"edge","id":"checked-out:c1","version":6,"type":"CHECKED_OUT","from":"cart:c1","to":"checkout:c1"}'
    )
    assert graph.CODEC.manifest == graph.SCHEMA_NAME == "ankka.graph-delta.v1"
    assert graph.CODEC.content_type == "application/json"
    assert (graph.node_key("x"), graph.edge_key("x")) == ("node:x", "edge:x")


# ── the reader ──────────────────────────────────────────────────────────────────────────────────


def test_the_reader_checks_the_key_when_it_is_given_one() -> None:
    value = graph.CODEC.encode(G.node("cart:c1", version=1))
    assert graph.read(value).key == "node:cart:c1"
    assert graph.read(value, "node:cart:c1").id == "cart:c1"
    with pytest.raises(ValueError, match="key 'cart:c1' is not this delta's element key 'node:cart:c1'"):
        graph.read(value, "cart:c1")
    with pytest.raises(ValueError, match="is not this delta's element key"):
        graph.read(value, "edge:cart:c1")


@pytest.mark.parametrize(
    ("value", "problem"),
    [
        (b"[]", "not a JSON object"),
        (b"not json", "not a JSON object"),
        (b'{"id":"a","version":1}', "kind missing"),
        (b'{"kind":"vertex","id":"a","version":1}', "unknown kind 'vertex'"),
        (b'{"kind":"node","id":"","version":1}', "id missing or empty"),
        (b'{"kind":"node","id":"a"}', "version is not a non-negative integer"),
        (b'{"kind":"node","id":"a","version":-1}', "version is not a non-negative integer"),
        (b'{"kind":"node","id":"a","version":1.5}', "version is not a non-negative integer"),
        (b'{"kind":"node","id":"a","version":true}', "version is not a non-negative integer"),
        (b'{"kind":"node","id":"a","version":1,"labels":["a b"]}', "labels must be an array of identifiers"),
        (b'{"kind":"edge","id":"a","version":1,"type":"T","from":"x"}', "edge needs type, from and to"),
        (b'{"kind":"tombstone","id":"a","version":1}', "tombstone needs element 'node' or 'edge'"),
        (b'{"kind":"tombstone","element":"edge","id":"a","version":1}', "tombstone of an edge needs type, from and to"),
        (b'{"kind":"node","id":"a","version":1,"properties":[]}', "properties must be an object"),
        (b'{"kind":"node","id":"a","version":1,"properties":{"p":null}}', "property 'p' is not a scalar or array of scalars"),
        (b'{"kind":"node","id":"a","version":1,"properties":{"p":[1,"a"]}}', "property 'p' is not a scalar or array of scalars"),
        (b'{"kind":"node","id":"a","version":1,"properties":{"_version":1}}', "property '_version' is reserved"),
    ],
)
def test_the_reader_refuses_what_the_contract_refuses(value: bytes, problem: str) -> None:
    with pytest.raises(ValueError) as refused:
        graph.read(value)
    assert str(refused.value) == problem


def test_the_reader_accepts_what_the_contract_allows_and_the_builder_does_not_write() -> None:
    # A version of zero, and a whole number written with an exponent.
    assert graph.read(b'{"kind":"node","id":"a","version":0}').version == 0
    assert graph.read(b'{"kind":"node","id":"a","version":1e3}').version == 1000
    # Fields the contract does not name are ignored.
    assert graph.read(b'{"kind":"node","id":"a","version":1,"extra":{"x":1}}') == Element("node", "node", "a", 1)


# ── a graph consumer ────────────────────────────────────────────────────────────────────────────


class CounterGraph(GraphConsumer[CounterEvent]):
    """A counter as a node, a note as a second node and the edge to it, and a tombstone when the
    counter is deleted."""

    component_id = "counter-graph"
    source = CounterEntity
    produces_to = "counter-graph"
    message_codec = CounterEntity.event_codec

    def on_message(self, event: CounterEvent) -> GraphEffect:
        cid = self.metadata.subject or ""
        match event:
            case Incremented(0):
                return self.effects.publish([])
            case Incremented(by) if by < 0:
                return self.effects.ignore()
            case Incremented(by):
                return self.effects.publish([self.graph.node(f"counter:{cid}", labels=["Counter"], properties={"by": by})])
            case Noted(note):
                return self.effects.publish(
                    [
                        self.graph.node(f"counter:{cid}", labels=["Counter"]),
                        self.graph.node(f"note:{cid}", labels=["Note"], properties={"text": note}, version=1000),
                        self.graph.edge(f"noted:{cid}", type="NOTED", from_id=f"counter:{cid}", to_id=f"note:{cid}"),
                    ]
                )
        return self.effects.done()

    def on_delete(self) -> GraphEffect:
        return self.effects.publish([self.graph.tombstone_node(f"counter:{self.metadata.subject}")])


class TopicGraph(GraphConsumer[CounterEvent]):
    """Over a topic, whose messages have no sequence number: a version has to be stated."""

    component_id = "topic-graph"
    topic = "counts"
    start_from = StartFrom.EARLIEST
    produces_to = "counter-graph"
    message_codec = CounterEntity.event_codec

    def on_message(self, event: CounterEvent) -> GraphEffect:
        match event:
            case Incremented(by):
                return self.effects.publish([self.graph.node("total", properties={"by": by}, version=by)])
        return self.effects.publish([self.graph.node("total")])


def test_a_graph_consumer_is_discovered_as_a_consumer_that_produces_to_its_topic() -> None:
    component = CounterGraph.to_component()
    assert component.kind == discovery_pb2.CONSUMER
    assert component.consumer.produces_to == "counter-graph"
    assert component.consumer.source.component.id == "counter"
    assert TopicGraph.to_component().consumer.source.topic == "counts"
    spec = Ankka.service().register(CounterEntity).register(CounterGraph).spec()
    assert [c.id for c in spec.components if c.kind == discovery_pb2.CONSUMER] == ["counter-graph"]


def test_a_graph_consumer_and_a_consumer_share_one_id_space() -> None:
    class Clash(Consumer[CounterEvent, None]):
        component_id = "counter-graph"
        source = CounterEntity
        message_codec = CounterEntity.event_codec

    with pytest.raises(RegistrationError, match="consumer 'counter-graph' is registered twice"):
        Ankka.service().register(CounterGraph).register(Clash).validate()


def test_a_graph_consumer_can_publish_nothing_but_elements_and_set_no_key() -> None:
    consumer = CounterGraph()
    assert not hasattr(consumer.effects, "produce") and not hasattr(consumer.effects, "produce_all") and not hasattr(consumer.effects, "message")
    with pytest.raises(TypeError, match="built with self.graph"):
        consumer.effects.publish([{"kind": "node"}])  # type: ignore[list-item]
    with pytest.raises(RegistrationError, match="must declare produces_to"):

        class Nowhere(GraphConsumer[CounterEvent]):
            component_id = "nowhere"
            source = CounterEntity
            message_codec = CounterEntity.event_codec

    with pytest.raises(RegistrationError, match="cannot declare an out_codec"):

        class OwnCodec(GraphConsumer[CounterEvent]):
            component_id = "own-codec"
            source = CounterEntity
            produces_to = "t"
            message_codec = CounterEntity.event_codec
            out_codec = CounterEntity.event_codec


def test_the_test_kit_returns_the_elements_a_change_published_as_a_reader_would_read_them() -> None:
    kit = GraphConsumerTestKit.of(CounterGraph)
    assert kit.on_message(Incremented(5), "c1", sequence=3) == [Element("node", "node", "counter:c1", 3, ("Counter",), properties={"by": 5})]
    noted = kit.on_message(Noted("hello"), "c1", sequence=4)
    assert [(e.kind, e.key, e.version) for e in noted] == [("node", "node:counter:c1", 4), ("node", "node:note:c1", 1000), ("edge", "edge:noted:c1", 4)]
    assert (noted[2].type, noted[2].from_id, noted[2].to_id) == ("NOTED", "counter:c1", "note:c1")
    assert kit.on_delete("c1", sequence=5) == [Element("tombstone", "node", "counter:c1", 5)]
    # Nothing to publish, three ways.
    assert kit.on_message(Incremented(0), "c1") == [] and kit.on_message(Incremented(-1), "c1") == []


def test_the_same_change_handled_twice_publishes_the_same_elements() -> None:
    kit = GraphConsumerTestKit.of(CounterGraph)
    assert kit.on_message(Noted("hello"), "c1", sequence=4) == kit.on_message(Noted("hello"), "c1", sequence=4)
    assert kit.on_delete("c1", sequence=9) == kit.on_delete("c1", sequence=9)


def test_a_graph_consumer_over_a_topic_states_its_versions() -> None:
    kit = GraphConsumerTestKit.of(TopicGraph)
    # A topic's message has sequence number 0.
    assert kit.on_message(Incremented(12), "s", sequence=0) == [Element("node", "node", "total", 12, properties={"by": 12})]
    with pytest.raises(RefusedElement, match="the node 'total' states no version and this change has no sequence number"):
        kit.on_message(Noted("x"), "s", sequence=0)


# ── as the runtime is answered ──────────────────────────────────────────────────────────────────


class _Refused(Exception):
    pass


class _Context:
    def __init__(self) -> None:
        self.code: Any = None
        self.details = ""

    async def abort(self, code: Any, details: str) -> None:
        self.code, self.details = code, details
        raise _Refused(details)


def _request(component: type[Any], event: Any, protocol: str | None, sequence: str | None = "4", deleted: bool = False) -> consumer_pb2.ConsumerRequest:
    entries = [payload_pb2.Metadata.Entry(key="ce-subject", value="c1")]
    if sequence is not None:
        entries.append(payload_pb2.Metadata.Entry(key="ankka.sequence", value=sequence))
    if protocol is not None:
        entries.append(payload_pb2.Metadata.Entry(key="ankka.protocol", value=protocol))
    message = None if deleted else payload_pb2.Payload(content_type="application/json", manifest="counter-event", data=component.message_codec.encode(event))
    return consumer_pb2.ConsumerRequest(component_id=component.component_id, message=message, metadata=payload_pb2.Metadata(entries=entries), deleted=deleted)


def _servicer(*components: type[Any]) -> ConsumerServicer:
    registry = Registry()
    for component in components:
        registry.consumers[component.component_id] = component
    return ConsumerServicer(registry, _NoClient())


async def test_each_element_is_one_message_under_its_element_key() -> None:
    effect = await _servicer(CounterGraph).Handle(_request(CounterGraph, Noted("hello"), "1.3"), _Context())
    assert effect.WhichOneof("effect") == "produce_all"
    messages = effect.produce_all.messages
    assert [m.key for m in messages] == ["node:counter:c1", "node:note:c1", "edge:noted:c1"]
    assert {(m.payload.manifest, m.payload.content_type) for m in messages} == {("ankka.graph-delta.v1", "application/json")}
    assert [[(e.key, e.value) for e in m.metadata.entries] for m in messages] == [[("ce-type", "ankka.graph-delta.v1")]] * 3
    assert messages[0].payload.data == b'{"kind":"node","id":"counter:c1","version":4,"labels":["Counter"],"properties":{}}'
    assert [graph.read(m.payload.data, m.key).version for m in messages] == [4, 1000, 4]


async def test_a_single_element_is_still_published_under_its_own_key() -> None:
    effect = await _servicer(CounterGraph).Handle(_request(CounterGraph, None, "1.3", deleted=True), _Context())
    assert [(m.key, m.payload.data) for m in effect.produce_all.messages] == [
        ("node:counter:c1", b'{"kind":"tombstone","element":"node","id":"counter:c1","version":4}')
    ]


async def test_nothing_to_publish_is_done_or_ignore() -> None:
    servicer = _servicer(CounterGraph)
    assert (await servicer.Handle(_request(CounterGraph, Incremented(0), "1.3"), _Context())).WhichOneof("effect") == "done"
    assert (await servicer.Handle(_request(CounterGraph, Incremented(-1), "1.3"), _Context())).WhichOneof("effect") == "ignore"


async def test_a_graph_consumer_fails_its_change_on_a_runtime_that_would_drop_its_deltas() -> None:
    context = _Context()
    with pytest.raises(_Refused):
        await _servicer(CounterGraph).Handle(_request(CounterGraph, Incremented(5), None), context)
    assert context.details == "this runtime speaks protocol 1.2 or earlier; several messages or a record key need 1.3"
    assert context.code == grpc.StatusCode.FAILED_PRECONDITION


async def test_a_change_that_arrives_with_no_sequence_number_fails_rather_than_publishing_at_zero() -> None:
    for sequence in (None, "0"):
        with pytest.raises(RefusedElement, match="no sequence number"):
            await _servicer(CounterGraph).Handle(_request(CounterGraph, Incremented(5), "1.3", sequence=sequence), _Context())
    # With versions stated, a topic's message publishes.
    effect = await _servicer(TopicGraph).Handle(_request(TopicGraph, Incremented(12), "1.3", sequence="0"), _Context())
    assert [graph.read(m.payload.data, m.key).version for m in effect.produce_all.messages] == [12]


async def test_a_handler_that_returns_something_else_is_a_mistake_that_fails() -> None:
    class Wrong(GraphConsumer[CounterEvent]):
        component_id = "wrong"
        source = CounterEntity
        produces_to = "t"
        message_codec = CounterEntity.event_codec

        def on_message(self, event: CounterEvent) -> GraphEffect:
            return [self.graph.node("a")]  # type: ignore[return-value]

    with pytest.raises(TypeError, match="not a graph effect"):
        await _servicer(Wrong).Handle(_request(Wrong, Incremented(1), "1.3"), _Context())


async def test_the_handlers_result_is_the_effect_a_consumer_would_have_returned() -> None:
    effect: ConsumerEffect = await CounterGraph()._handle(CounterEntity.event_codec.encode(Incremented(5)), Metadata().set("ce-subject", "c1").set("ankka.sequence", "2"))
    assert isinstance(effect, ProduceAll)
    assert [(m.key, m.payload.version, m.metadata.get("ce-type")) for m in effect.messages] == [("node:counter:c1", 2, "ankka.graph-delta.v1")]
    assert isinstance(await CounterGraph()._handle(CounterEntity.event_codec.encode(Incremented(0)), Metadata()), ProduceAll)
    assert isinstance(await CounterGraph()._handle(CounterEntity.event_codec.encode(Incremented(-1)), Metadata()), Ignore)
    assert not isinstance(Done(), Ignore)


# ── reading a delta topic ───────────────────────────────────────────────────────────────────────


def test_a_consumer_of_a_delta_topic_decodes_with_the_same_codec() -> None:
    class Reader(Consumer[Element, None]):
        component_id = "delta-reader"
        topic = "counter-graph"
        start_from = StartFrom.EARLIEST
        message_codec = graph.CODEC

        def on_message(self, message: Element) -> ConsumerEffect:
            Reader.seen.append(message)
            return self.effects.done()

        seen: list[Element] = []

    from ankka.testkit import ConsumerTestKit

    ConsumerTestKit.of(Reader).on_message(G.node("counter:c1", labels=["Counter"], version=2))
    assert Reader.seen == [Element("node", "node", "counter:c1", 2, ("Counter",))]
