"""The documentation's HTTP samples (docs/build/http-endpoints.md), run against the endpoint
test kit: query parameters, headers, a streaming route reading its query, the principal a verified
token names, and a route whose ACL differs from its endpoint's."""

from __future__ import annotations

from ankka import Principal
from ankka._proto.ankka.protocol.v1 import discovery_pb2
from ankka.testkit import EndpointTestKit
from tests.query_endpoint import AccountEndpoint, MixedAclEndpoint, QueryEndpoint, SearchResult


def test_query_parameters_are_read_from_the_request() -> None:
    kit = EndpointTestKit.of(QueryEndpoint)
    full = kit.get("/search/", query=[("q", "pens"), ("limit", "5"), ("tag", "a"), ("tag", "b"), ("verbose", "")])
    assert full.json() == {"term": "pens", "limit": 5, "tags": ["a", "b"], "verbose": True}
    assert kit.get("/search/", query=[("q", "ink")]).json() == {"term": "ink", "limit": 20, "tags": [], "verbose": False}
    missing = kit.get("/search/")
    assert missing.status == 400 and "q" in missing.text()
    assert SearchResult("pens", 5, ["a", "b"], True).term == "pens"


def test_a_header_is_read_by_name_whatever_its_case() -> None:
    kit = EndpointTestKit.of(QueryEndpoint)
    assert kit.get("/search/trace").text() == "none"
    assert kit.get("/search/trace", headers=[("x-trace-id", "t-1")]).text() == "t-1"


def test_a_streaming_route_reads_its_query_before_streaming() -> None:
    kit = EndpointTestKit.of(QueryEndpoint)
    assert kit.get("/search/stream", query=[("q", "pen"), ("count", "3")]).text().split("\n") == ["pen-1", "pen-2", "pen-3"]


def test_the_handler_reads_the_principal_the_token_named() -> None:
    ada = Principal("ada", roles=frozenset({"admin", "support"}), claims={"tier": "gold"}, issuer="corp")
    kit = EndpointTestKit.of(AccountEndpoint)
    assert kit.get("/account/me", principal=ada).text() == "ada from corp roles=admin,support tier=gold"
    assert AccountEndpoint.to_endpoint().acl == discovery_pb2.Endpoint.AUTHENTICATED


def test_a_routes_own_acl_replaces_the_endpoints_for_that_route() -> None:
    routes = {r.id: r for r in MixedAclEndpoint.to_endpoint().routes}
    assert MixedAclEndpoint.to_endpoint().acl == discovery_pb2.Endpoint.ALLOW_ALL
    assert not routes["cart"].HasField("acl") and routes["purge"].acl == discovery_pb2.Endpoint.AUTHENTICATED
    kit = EndpointTestKit.of(MixedAclEndpoint)
    assert kit.get("/mixed/c1").text() == "cart:c1"
    assert kit.delete("/mixed/c1", principal=Principal("sam")).text() == "purged:c1 by sam"
