"""Other services, called as this one.

The runtime beside this process makes the call: it finds the service, presents this service's
certificate and checks that what answered is the service asked for. This process never holds a
key. The client is offered to endpoints, workflow steps, consumers, timed actions and agents, and
not to an entity or a view: an entity's ``CommandContext`` and a ``View`` have no ``services``.

A call ends in an answer, whatever its status, or one of four errors: ``ServiceUnresolvable`` and
``ServiceIdentityMismatch``, where nothing was sent; ``ServiceUnanswered``, where no answer came;
and ``ServiceCallFailed``, which only the typed helpers raise, for an answer outside 2xx.
"""

from __future__ import annotations

from collections.abc import Callable, Sequence
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, TypeVar

import grpc

from ankka._proto.ankka.protocol.v1 import client_pb2, endpoint_pb2
from ankka.codec import default_codec_for
from ankka.context import Metadata
from ankka.effects.common import Error, ErrorCode

if TYPE_CHECKING:
    from ankka.client import ComponentClient

R = TypeVar("R")

SERVICES_SINCE = "1.7"
MAX_BODY_BYTES = 4_000_000

Headers = Sequence[tuple[str, str]]


@dataclass(frozen=True)
class ServiceResponse:
    """What the service answered: its status, content type, body and headers, as it sent them."""

    status: int
    content_type: str
    body: bytes
    headers: tuple[tuple[str, str], ...] = ()

    @property
    def text(self) -> str:
        return self.body.decode("utf-8")

    def header(self, name: str) -> str | None:
        for k, v in self.headers:
            if k.lower() == name.lower():
                return v
        return None


class ServiceError(Exception):
    """A call to another service that did not end in an answer a typed helper accepts."""

    def __init__(self, service: str, message: str) -> None:
        super().__init__(message)
        self.service = service


class ServiceUnresolvable(ServiceError):
    """No such service was found; nothing was sent. ``reason`` says what was tried."""

    def __init__(self, service: str, reason: str) -> None:
        super().__init__(service, f"cannot reach {service}: {reason}")
        self.reason = reason


class ServiceIdentityMismatch(ServiceError):
    """What answered for the name is not the service asked for; nothing was sent."""

    def __init__(self, service: str, detail: str) -> None:
        super().__init__(service, f"the service reached as {service} is not it: {detail}")
        self.detail = detail


class ServiceUnanswered(ServiceError):
    """No answer came: the connection was refused or broke, or the service's timeout passed. The
    service may have received the request; one that may change something was sent at most once."""

    def __init__(self, service: str, reason: str) -> None:
        super().__init__(service, f"{service} did not answer: {reason}")
        self.reason = reason


class ServiceCallFailed(ServiceError):
    """The service answered with a status outside 2xx, to a typed helper: its status and body as it
    sent them. ``request`` returns every answer instead."""

    def __init__(self, service: str, status: int, body: str) -> None:
        super().__init__(service, f"{service} answered {status}: {body}")
        self.status = status
        self.body = body


def _refused(message: str, code: ErrorCode) -> Exception:
    from ankka.client import CommandError

    return CommandError(Error(message, code))


def _too_old(failure: grpc.aio.AioRpcError) -> Exception:
    from ankka.service import PROTOCOL_VERSION

    return _refused(
        f"the runtime beside this process cannot call another service, which needs protocol "
        f"{SERVICES_SINCE} (this SDK speaks {PROTOCOL_VERSION}): {failure.details()}",
        ErrorCode.INTERNAL,
    )


class ServiceClient:
    """Calls one service. ``target`` is the service as it was named: ``name`` or ``project/name``."""

    def __init__(self, send: Callable[..., Any], service: str, project: str | None) -> None:
        self._send = send
        self.service = service
        self.project = project
        self.target = f"{project}/{service}" if project else service

    async def request(
        self,
        method: str,
        path: str,
        *,
        body: bytes | None = None,
        content_type: str | None = None,
        headers: Headers | None = None,
    ) -> ServiceResponse:
        """One request, answered whatever its status. Raises only when no answer came."""
        if body is not None and len(body) > MAX_BODY_BYTES:
            raise _refused(
                f"a call's body is at most {MAX_BODY_BYTES} bytes through the sidecar; this one is {len(body)}",
                ErrorCode.BAD_REQUEST,
            )
        response: ServiceResponse = await self._send(self, method, path, body, content_type, tuple(headers or ()))
        return response

    async def get(self, path: str, returns: type[R], *, headers: Headers | None = None) -> R:
        """A JSON answer, decoded as ``returns``; a status outside 2xx is a ``ServiceCallFailed``."""
        return self._decoded(await self.request("GET", path, headers=headers), returns)

    async def get_text(self, path: str, *, headers: Headers | None = None) -> str:
        """A text answer: what an endpoint returning a string sends."""
        return self._succeeded(await self.request("GET", path, headers=headers)).text

    async def post(self, path: str, body: Any, returns: type[R], *, headers: Headers | None = None) -> R:
        return self._decoded(await self._with_body("POST", path, body, headers), returns)

    async def put(self, path: str, body: Any, returns: type[R], *, headers: Headers | None = None) -> R:
        return self._decoded(await self._with_body("PUT", path, body, headers), returns)

    async def delete(self, path: str, *, headers: Headers | None = None) -> None:
        """For a route answering nothing: succeeds on any 2xx."""
        self._succeeded(await self.request("DELETE", path, headers=headers))

    async def _with_body(self, method: str, path: str, body: Any, headers: Headers | None) -> ServiceResponse:
        codec = default_codec_for(type(body))
        return await self.request(method, path, body=codec.encode(body), content_type=codec.content_type, headers=headers)

    def _succeeded(self, response: ServiceResponse) -> ServiceResponse:
        if response.status // 100 != 2:
            raise ServiceCallFailed(self.target, response.status, response.text)
        return response

    def _decoded(self, response: ServiceResponse, returns: type[R]) -> R:
        decoded: R = default_codec_for(returns).decode(self._succeeded(response).body)
        return decoded


class Services:
    """How a component obtains clients for other services: ``self.services("orders")``, or
    ``self.services("invoices", project="billing")`` for a service in another project — whether it
    admits this one is its own ACL's decision.

    The call carries the metadata of the handler that is running, so the runtime makes it as that
    handler: counted from it, and in its trace.
    """

    def __init__(self, client: ComponentClient, metadata: Callable[[], Metadata] | None = None) -> None:
        self._client = client
        self._metadata = metadata

    def __call__(self, name: str, *, project: str | None = None) -> ServiceClient:
        return ServiceClient(self._send, name, project)

    def _current_metadata(self) -> Metadata:
        if self._metadata is not None:
            return self._metadata()
        return self._client._metadata

    async def _send(
        self,
        client: ServiceClient,
        method: str,
        path: str,
        body: bytes | None,
        content_type: str | None,
        headers: tuple[tuple[str, str], ...],
    ) -> ServiceResponse:
        request = client_pb2.ServiceRequest(
            service=client.service,
            method=method,
            path=path,
            headers=[endpoint_pb2.HttpRequest.Pair(name=k, value=v) for k, v in headers],
            metadata=self._current_metadata().to_pb(),
        )
        if client.project is not None:
            request.project = client.project
        if content_type is not None:
            request.content_type = content_type
        if body is not None:
            request.body = body
        try:
            reply = await self._client._stub.Request(request)
        except grpc.aio.AioRpcError as failure:
            if failure.code() == grpc.StatusCode.UNIMPLEMENTED:
                raise _too_old(failure) from failure
            raise
        return _answer(client.target, reply)


def _answer(target: str, reply: client_pb2.ServiceReply) -> ServiceResponse:
    which = reply.WhichOneof("result")
    if which == "response":
        r = reply.response
        return ServiceResponse(r.status, r.content_type, bytes(r.body), tuple((h.name, h.value) for h in r.headers))
    if which == "failure":
        reason = reply.failure.reason
        detail = reply.failure.detail
        if reason == client_pb2.ServiceFailure.UNRESOLVABLE:
            raise ServiceUnresolvable(target, detail)
        if reason == client_pb2.ServiceFailure.IDENTITY_MISMATCH:
            raise ServiceIdentityMismatch(target, detail)
        raise ServiceUnanswered(target, detail)
    if which == "error":
        from ankka.client import CommandError, _error

        raise CommandError(_error(reply.error))
    raise _refused(f"the runtime answered a call to {target} with nothing", ErrorCode.INTERNAL)


@dataclass(frozen=True)
class ScriptedRequest:
    """One request a component made to a scripted service."""

    service: str
    project: str | None
    method: str
    path: str
    headers: tuple[tuple[str, str], ...]
    content_type: str | None
    body: bytes

    @property
    def text(self) -> str:
        return self.body.decode("utf-8")


class ScriptedServices(Services):
    """Other services, for a unit test: each answers as the test scripted it, and every request is
    recorded. A call to a service nothing is scripted for fails the test, naming the service: a
    test whose call quietly got a default answer is no longer testing what it says."""

    def __init__(self) -> None:
        self._scripts: dict[tuple[str | None, str], Callable[[ScriptedRequest], ServiceResponse] | Exception] = {}
        self.requests: list[ScriptedRequest] = []

    def answer(
        self, name: str, handler: Callable[[ScriptedRequest], ServiceResponse], *, project: str | None = None
    ) -> ScriptedServices:
        self._scripts[(project, name)] = handler
        return self

    def unresolvable(self, name: str, *, project: str | None = None) -> ScriptedServices:
        self._scripts[(project, name)] = ServiceUnresolvable(_target(project, name), "scripted as unresolvable")
        return self

    def unanswered(self, name: str, *, project: str | None = None) -> ScriptedServices:
        self._scripts[(project, name)] = ServiceUnanswered(_target(project, name), "scripted as unanswered")
        return self

    @staticmethod
    def text(body: str, status: int = 200) -> ServiceResponse:
        """An answer of ``body`` as ``text/plain``, for a script."""
        return ServiceResponse(status, "text/plain", body.encode("utf-8"))

    @staticmethod
    def json(body: str, status: int = 200) -> ServiceResponse:
        """An answer of ``body`` as ``application/json``, for a script."""
        return ServiceResponse(status, "application/json", body.encode("utf-8"))

    def mismatch(self, name: str, *, project: str | None = None) -> ScriptedServices:
        self._scripts[(project, name)] = ServiceIdentityMismatch(_target(project, name), "scripted as another service")
        return self

    async def _send(
        self,
        client: ServiceClient,
        method: str,
        path: str,
        body: bytes | None,
        content_type: str | None,
        headers: tuple[tuple[str, str], ...],
    ) -> ServiceResponse:
        made = ScriptedRequest(client.service, client.project, method, path, headers, content_type, body or b"")
        self.requests.append(made)
        script = self._scripts.get((client.project, client.service))
        if script is None:
            hint = f', project="{client.project}"' if client.project else ""
            raise AssertionError(
                f"the test called the service '{client.target}', and nothing is scripted for it: "
                f'script it with answer("{client.service}", lambda request: ServiceResponse(...){hint})'
            )
        if isinstance(script, Exception):
            raise script
        return script(made)


def _target(project: str | None, name: str) -> str:
    return f"{project}/{name}" if project else name


class HasServices:
    """What gives a consumer, a timed action, an agent and an endpoint their ``services``.

    The calls go through the runtime, reached through the component's client. A unit test assigns
    its own (``component.services = ScriptedServices()``) before calling the handler.
    """

    _services: Services | None = None

    @property
    def services(self) -> Services:
        if self._services is not None:
            return self._services
        client = getattr(self, "client", None)
        if client is None:
            raise RuntimeError(
                f"{type(self).__name__} has no client, so no way to call another service: it is given "
                "one when the runtime builds it, and a unit test assigns one (ScriptedServices)"
            )
        return Services(client)

    @services.setter
    def services(self, services: Services) -> None:
        self._services = services


__all__ = [
    "HasServices",
    "MAX_BODY_BYTES",
    "ScriptedRequest",
    "ScriptedServices",
    "ServiceCallFailed",
    "ServiceClient",
    "ServiceError",
    "ServiceIdentityMismatch",
    "ServiceResponse",
    "ServiceUnanswered",
    "ServiceUnresolvable",
    "Services",
]
