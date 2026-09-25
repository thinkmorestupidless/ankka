from __future__ import annotations

import pytest

from ankka import Acl, Endpoint, RegistrationError, get
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


def test_a_route_acl_is_carried_in_discovery_and_absence_means_the_endpoints() -> None:
    """The sidecar applies the acl; the SDK's job is to say which one, or to say nothing.

    Absence has to stay distinguishable from ALLOW_ALL, or a route that declares nothing would
    silently open an endpoint that denies.
    """

    class Mixed(Endpoint):
        prefix = "/mixed"
        acl = Acl.DENY_ALL

        @get("/open", acl=Acl.ALLOW_ALL)
        def open_route(self) -> str:
            return "ok"

        @get("/inherited")
        def inherited(self) -> str:
            return "ok"

    routes = {r.id: r for r in Mixed.to_endpoint().routes}
    assert routes["open_route"].HasField("acl")
    assert routes["open_route"].acl == Acl.ALLOW_ALL.value
    assert not routes["inherited"].HasField("acl")


def test_an_endpoint_must_declare_an_acl() -> None:
    """An unstated acl is a decision nobody made, so it fails when the class is defined.

    The Scala SDK gets this from `acl` being abstract; Python has to check for it. A default of
    ALLOW_ALL would open an endpoint to the internet because its author did not think about it.
    """
    with pytest.raises(RegistrationError, match="must declare an acl"):

        class NoAcl(Endpoint):
            prefix = "/no-acl"

            @get("/")
            def anything(self) -> str:
                return "reachable"
