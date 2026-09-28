"""Caller-naming ACLs and the caller on the request, in the Python SDK.

The sidecar applies the ACL and establishes the caller; the SDK's jobs are to declare the ACL in
discovery exactly and to hand the handler the caller the sidecar sent.
"""

from __future__ import annotations

import pytest

from ankka import Acl, Callers, Endpoint, Gateway, LocalCaller, RequestContext, ServiceCaller, get
from ankka._proto.ankka.protocol.v1 import discovery_pb2, endpoint_pb2, payload_pb2
from ankka.server import _caller
from ankka.testkit import EndpointTestKit


class Carts(Endpoint):
    prefix = "/carts"
    acl = Acl.allow_callers(Callers.internet, Callers.service("orders"))

    @get("/whoami")
    def whoami(self) -> str:
        c = self.request.caller
        if isinstance(c, ServiceCaller):
            return f"service:{c.project}/{c.name}"
        return "gateway" if isinstance(c, Gateway) else "local"

    @get("/self", acl=Acl.allow_callers(Callers.self_, Callers.service("orders", project="billing"), Callers.any_in_project))
    def only_self(self) -> str:
        return "self"


def test_an_endpoints_callers_are_declared_in_discovery() -> None:
    ep = Carts.to_endpoint()
    assert ep.acl == discovery_pb2.Endpoint.CALLERS
    kinds = [m.WhichOneof("kind") for m in ep.allow_callers]
    assert kinds == ["internet", "service"]
    assert ep.allow_callers[1].service.name == "orders"
    assert not ep.allow_callers[1].service.HasField("project"), "no project means this service's own"


def test_a_routes_own_callers_are_declared_on_the_route() -> None:
    routes = {r.id: r for r in Carts.to_endpoint().routes}
    route = routes["only_self"]
    assert route.acl == discovery_pb2.Endpoint.CALLERS
    assert [m.WhichOneof("kind") for m in route.allow_callers] == ["self", "service", "any_in_project"]
    assert route.allow_callers[1].service.project == "billing"
    assert not routes["whoami"].HasField("acl")


def test_allow_callers_names_at_least_one() -> None:
    with pytest.raises(ValueError, match="at least one"):
        Acl.allow_callers()


def test_the_plain_acls_are_unchanged() -> None:
    assert Acl.ALLOW_ALL.value == discovery_pb2.Endpoint.ALLOW_ALL
    assert Acl.DENY_ALL == Acl(discovery_pb2.Endpoint.DENY_ALL)
    assert Acl.AUTHENTICATED.callers == ()


def test_the_handler_reads_the_caller_the_test_states() -> None:
    kit = EndpointTestKit.of(Carts)
    assert kit.get("/carts/whoami").text() == "local"
    assert kit.get("/carts/whoami", caller=Gateway()).text() == "gateway"
    assert kit.get("/carts/whoami", caller=ServiceCaller("checkout", "orders")).text() == "service:checkout/orders"


def test_the_servers_mapping_from_the_protocol() -> None:
    empty = payload_pb2.Empty()
    gateway = endpoint_pb2.HttpRequest(caller=endpoint_pb2.Caller(gateway=empty))
    service = endpoint_pb2.HttpRequest(caller=endpoint_pb2.Caller(service=endpoint_pb2.ServiceCaller(project="p", name="s")))
    assert _caller(gateway) == Gateway()
    assert _caller(service) == ServiceCaller("p", "s")
    # A sidecar that predates protocol 1.1 sends no caller at all.
    assert _caller(endpoint_pb2.HttpRequest()) == LocalCaller()
    assert RequestContext().caller == LocalCaller()


def test_an_endpoint_with_no_constructor_is_built_without_a_client() -> None:
    """Every Python endpoint without its own __init__ used to be handed a client and fail its first request."""
    from ankka.server import _takes_client

    assert not _takes_client(Carts)

    class WithClient(Endpoint):
        prefix = "/with"
        acl = Acl.ALLOW_ALL

        def __init__(self, client: object) -> None:
            self.client = client

    assert _takes_client(WithClient)
