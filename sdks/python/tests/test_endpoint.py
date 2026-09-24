from __future__ import annotations

from ankka.testkit import EndpointTestKit
from tests.counter import CounterEndpoint, Counter, Incremented


def test_path_parameters_bind_and_replies_are_encoded() -> None:
    kit = EndpointTestKit.of(CounterEndpoint, "hello")
    r = kit.get("/counters/abc")
    assert r.status == 200
    assert r.json() == {"total": 3, "notes": ["hello"]}


def test_literal_beats_parameter() -> None:
    kit = EndpointTestKit.of(CounterEndpoint)
    assert kit.get("/counters/awkward").text() == "literal"


def test_body_query_and_headers() -> None:
    kit = EndpointTestKit.of(CounterEndpoint)
    assert kit.post("/counters/c1/increments", Incremented(21)).json() == 42
    assert kit.post("/counters/c1/increments", Incremented(1), query=[("page", "boom")]).status == 418
    echoed = kit.get("/counters/c1/echo", query=[("a", "1"), ("a", "2")], headers=[("X-Test", "y")]).json()
    assert echoed == ["a=1", "a=2", "y"]


def test_streaming_route_frames() -> None:
    kit = EndpointTestKit.of(CounterEndpoint)
    r = kit.get("/counters/c1/events")
    assert r.text() == " leading space\ntwo\nlines"


def test_unknown_route_is_404() -> None:
    assert EndpointTestKit.of(CounterEndpoint).get("/counters/c1/nothing/here").status == 404


def test_discovery_entry() -> None:
    ep = CounterEndpoint.to_endpoint()
    assert ep.prefix == "/counters"
    routes = {r.id: r for r in ep.routes}
    assert routes["add"].method == "POST" and routes["add"].has_body and routes["add"].template == "/{counter_id}/increments"
    assert routes["events"].streaming
    assert Counter  # the module's shapes are the ones the routes encode
