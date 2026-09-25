"""HTTP endpoints, declared here and served by the sidecar.

A subclass declares ``prefix`` and ``acl`` and decorates methods with ``@get("/{cart_id}")``,
``@post(...)``, ``@put``, ``@delete``, ``@patch`` or ``@sse``. Path parameters bind by name from
the template; one further typed parameter is the body; the return value is encoded with its
type's default codec. The process never binds an HTTP port: the sidecar's router matches the
route, applies the ACL and forwards the request.
"""

from __future__ import annotations

import inspect
import re
import typing
from collections.abc import AsyncIterator, Awaitable, Callable
from contextvars import ContextVar
from dataclasses import dataclass
from enum import Enum
from typing import Any, ClassVar, get_type_hints

from ankka._proto.ankka.protocol.v1 import discovery_pb2
from ankka.codec import Codec, Done, default_codec_for
from ankka.context import RequestContext
from ankka.event_sourced_entity import RegistrationError


class Acl(Enum):
    ALLOW_ALL = discovery_pb2.Endpoint.ALLOW_ALL
    DENY_ALL = discovery_pb2.Endpoint.DENY_ALL
    AUTHENTICATED = discovery_pb2.Endpoint.AUTHENTICATED


class HttpProblem(Exception):
    """Raise from a handler to answer with this status and message."""

    def __init__(self, status: int, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.message = message


_MARK = "_ankka_route"
_PLACEHOLDER = re.compile(r"\{([A-Za-z_][A-Za-z0-9_]*)\}")


def _route(method: str, streaming: bool = False) -> Callable[..., Callable[[Callable[..., Any]], Callable[..., Any]]]:
    def with_template(template: str, *, acl: Acl | None = None) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
        """``acl`` replaces the endpoint's for this route alone; omitted, the endpoint's applies."""

        def decorate(fn: Callable[..., Any]) -> Callable[..., Any]:
            setattr(fn, _MARK, (method, template, streaming, acl))
            return fn

        return decorate

    return with_template


get = _route("GET")
post = _route("POST")
put = _route("PUT")
delete = _route("DELETE")
patch = _route("PATCH")
sse = _route("GET", streaming=True)


def _parse_path(value: str, tp: Any) -> Any:
    if tp is str or tp is Any:
        return value
    if tp is int:
        return int(value)
    if tp is float:
        return float(value)
    if tp is bool:
        return value == "true"
    raise HttpProblem(400, f"cannot bind a path parameter of type {tp}")


@dataclass(frozen=True)
class RouteSpec:
    id: str
    method: str
    template: str
    streaming: bool
    method_name: str
    path_params: tuple[tuple[str, Any], ...]  # (name, type) in template order
    body_param: str | None
    body_codec: Codec[Any] | None
    reply_codec: Codec[Any] | None  # None for streaming routes
    acl: Acl | None = None  # None: the endpoint's

    @property
    def has_body(self) -> bool:
        return self.body_param is not None

    def to_pb(self) -> discovery_pb2.Route:
        route = discovery_pb2.Route(
            id=self.id, method=self.method, template=self.template, has_body=self.has_body, streaming=self.streaming
        )
        # Left unset when the route declares nothing, so the sidecar reads "the endpoint's"
        # rather than ALLOW_ALL — the field is `optional` in the protocol for exactly this.
        if self.acl is not None:
            route.acl = self.acl.value
        return route


def collect_routes(cls: type) -> dict[str, RouteSpec]:
    found: dict[str, RouteSpec] = {}
    for attr, member in inspect.getmembers(cls, predicate=inspect.isfunction):
        mark = getattr(member, _MARK, None)
        if mark is None:
            continue
        method, template, streaming, route_acl = mark
        if not template.startswith("/"):
            raise RegistrationError(f"{cls.__name__}.{attr}: template '{template}' must start with '/'")
        names = _PLACEHOLDER.findall(template)
        hints = get_type_hints(member)
        params = [p for p in inspect.signature(member).parameters.values() if p.name != "self"]
        by_name = {p.name: p for p in params}
        for n in names:
            if n not in by_name:
                raise RegistrationError(f"{cls.__name__}.{attr}: template names '{{{n}}}' but the handler has no parameter '{n}'")
        extra = [p.name for p in params if p.name not in names]
        if len(extra) > 1:
            raise RegistrationError(f"{cls.__name__}.{attr}: at most one parameter may be the body, found {extra}")
        body_param = extra[0] if extra else None
        if body_param is not None and method == "GET":
            raise RegistrationError(f"{cls.__name__}.{attr}: a GET route cannot take a body parameter ('{body_param}')")
        ret = hints.get("return")
        found[attr] = RouteSpec(
            id=attr,
            method=method,
            template=template,
            streaming=streaming,
            method_name=attr,
            path_params=tuple((n, hints.get(n, str)) for n in names),
            body_param=body_param,
            body_codec=default_codec_for(hints[body_param]) if body_param else None,
            reply_codec=None if streaming else default_codec_for(ret if ret is not None else Done),
            acl=route_acl,
        )
    return found


_current: ContextVar[RequestContext | None] = ContextVar("ankka_request", default=None)


class Endpoint:
    """Subclass this. Class attributes: ``prefix`` and ``acl``. Routes are decorated methods."""

    prefix: ClassVar[str]
    acl: ClassVar[Acl]
    _routes: ClassVar[dict[str, RouteSpec]]

    def __init_subclass__(cls, **kwargs: Any) -> None:
        super().__init_subclass__(**kwargs)
        if not hasattr(cls, "prefix"):
            raise RegistrationError(f"{cls.__name__} must declare a prefix")
        if not cls.prefix.startswith("/"):
            raise RegistrationError(f"{cls.__name__}: prefix '{cls.prefix}' must start with '/'")
        # Required, not defaulted, and checked here so it fails when the class is defined rather
        # than on the first request: the Scala SDK makes `acl` abstract for the same reason, that
        # an unstated acl is a decision nobody made. A default of ALLOW_ALL would open an endpoint
        # to the internet because its author did not think about it.
        if not hasattr(cls, "acl"):
            raise RegistrationError(
                f"{cls.__name__} must declare an acl — say 'acl = Acl.ALLOW_ALL' for a public "
                "endpoint, or Acl.AUTHENTICATED or Acl.DENY_ALL"
            )
        cls._routes = collect_routes(cls)

    @property
    def request(self) -> RequestContext:
        ctx = _current.get()
        if ctx is None:
            raise RuntimeError("request is only available inside a route handler")
        return ctx

    @classmethod
    def endpoint_id(cls) -> str:
        return cls.__name__

    @classmethod
    def routes(cls) -> dict[str, RouteSpec]:
        return cls._routes

    @classmethod
    def to_endpoint(cls) -> discovery_pb2.Endpoint:
        return discovery_pb2.Endpoint(
            id=cls.endpoint_id(),
            prefix=cls.prefix,
            acl=cls.acl.value,
            routes=[r.to_pb() for r in sorted(cls._routes.values(), key=lambda r: r.id)],
        )

    async def _handle(self, spec: RouteSpec, path_args: list[str], body: bytes, ctx: RequestContext) -> tuple[int, str, bytes]:
        """Runs a plain route: (status, content type, body)."""
        token = _current.set(ctx)
        try:
            kwargs = self._bind(spec, path_args, body)
            result = getattr(self, spec.method_name)(**kwargs)
            if isinstance(result, Awaitable):
                result = await result
            codec = spec.reply_codec
            assert codec is not None
            if result is None or isinstance(result, Done):
                return 204, codec.content_type, b""  # as a Scala endpoint answers Done: no content
            return 200, codec.content_type, codec.encode(result)
        finally:
            _current.reset(token)

    async def _handle_stream(self, spec: RouteSpec, path_args: list[str], body: bytes, ctx: RequestContext) -> AsyncIterator[str]:
        token = _current.set(ctx)
        try:
            kwargs = self._bind(spec, path_args, body)
            result = getattr(self, spec.method_name)(**kwargs)
            if isinstance(result, Awaitable):
                result = await result
            async for frame in typing.cast(AsyncIterator[str], result):
                yield frame
        finally:
            _current.reset(token)

    def _bind(self, spec: RouteSpec, path_args: list[str], body: bytes) -> dict[str, Any]:
        kwargs: dict[str, Any] = {}
        for (name, tp), raw in zip(spec.path_params, path_args, strict=True):
            kwargs[name] = _parse_path(raw, tp)
        if spec.body_param is not None:
            assert spec.body_codec is not None
            try:
                kwargs[spec.body_param] = spec.body_codec.decode(body)
            except Exception as e:  # a body that does not decode is the caller's problem
                raise HttpProblem(400, f"cannot decode the request body: {e}") from e
        return kwargs
