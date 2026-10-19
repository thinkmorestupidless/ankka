"""A service's MCP endpoint: its tools, served by the sidecar to any MCP client (protocol 1.20).

A subclass declares ``path`` and ``acl`` and its tools: methods decorated ``@tool("description")``,
whose type hints are the tool's input schema, and references made with ``tool.command``,
``tool.query`` and ``tool.route``, which offer a command, a query or a route the service already
has. The sidecar answers the MCP protocol, verifies the caller's client key or token, applies the
ACL and checks the arguments against the schema; this process is asked only to run the tool, and is
told the principal the credential established, never the credential.
"""

from __future__ import annotations

import dataclasses
import inspect
import json
import re
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, ClassVar, get_type_hints

from ankka._proto.ankka.protocol.v1 import discovery_pb2
from ankka.agent import _schema_for
from ankka.client_keys import HasClientKeys
from ankka.codec import Done, from_json_value, to_json_value, write_json
from ankka.context import RequestContext
from ankka.endpoint import Acl, Endpoint, _current
from ankka.event_sourced_entity import RegistrationError
from ankka.secrets import HasSecrets
from ankka.services import HasServices

if TYPE_CHECKING:
    from ankka.client import ComponentClient
    from ankka.service import Registry

MCP_SINCE = (1, 20)
"""The protocol version whose runtimes serve a process's MCP endpoints."""

_TOOL = "_ankka_mcp_tool"
_NAME = re.compile(r"[a-z0-9_-]{1,128}")
_PLACEHOLDER = re.compile(r"\{([A-Za-z_][A-Za-z0-9_]*)\}")


@dataclass(frozen=True)
class ToolAnswer:
    """What a tool answered: text for every client, and structured content when it was a record."""

    text: str
    structured_json: str | None = None


def answer_of(result: Any) -> ToolAnswer:
    """A handler's return value as the protocol carries it."""
    if result is None or isinstance(result, Done):
        return ToolAnswer("done")
    if isinstance(result, str):
        return ToolAnswer(result)
    if isinstance(result, ToolAnswer):
        return result
    plain = to_json_value(result)
    text = write_json(plain)
    return ToolAnswer(text, text if isinstance(plain, dict) else None)


# ── Tools: a decorated method, or a reference ───────────────────────────────


@dataclass(frozen=True)
class _Marked:
    description: str
    acl: Acl | None
    name: str | None


@dataclass(frozen=True)
class ToolReference:
    """A command, a query or a route the service has, offered as a tool; bound at registration."""

    kind: str  # "command" | "query" | "route"
    description: str
    acl: Acl | None
    component: Any = None  # a component class, or its id
    handler: str = ""
    id: str = ""
    input: str | None = None
    endpoint: Any = None  # an Endpoint class
    method: str = ""
    path: str = ""
    name: str | None = None


Run = Callable[[Any, dict[str, Any], RequestContext], Awaitable[Any]]


@dataclass(frozen=True)
class McpToolSpec:
    """One tool as discovery declares it and as the server runs it."""

    name: str
    description: str
    acl: Acl | None
    input_schema: dict[str, Any]
    run: Run  # (the endpoint instance, the checked arguments, the request) -> the result

    def to_pb(self) -> discovery_pb2.McpTool:
        pb = discovery_pb2.McpTool(
            name=self.name, description=self.description, input_schema_json=json.dumps(self.input_schema)
        )
        if self.acl is not None:
            pb.acl.CopyFrom(self.acl.to_mcp_pb())
        return pb


class _ToolFactory:
    """``@tool("Reads one cart")`` on a method; ``tool.command(...)``, ``tool.query(...)`` and
    ``tool.route(...)`` for a reference. The tool's name is the method's or the attribute's, unless
    ``name`` says otherwise."""

    def __call__(
        self, description: str, *, acl: Acl | None = None, name: str | None = None
    ) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
        def decorate(fn: Callable[..., Any]) -> Callable[..., Any]:
            setattr(fn, _TOOL, _Marked(description, acl, name))
            return fn

        return decorate

    @staticmethod
    def command(
        component: Any,
        handler: str,
        *,
        id: str,
        description: str,
        input: str | None = None,
        acl: Acl | None = None,
        name: str | None = None,
    ) -> ToolReference:
        """A command of an entity or a workflow: its id from the argument ``id``, its input's fields
        beside it, or a scalar input under the argument ``input``."""
        return ToolReference("command", description, acl, component, handler, id, input, name=name)

    @staticmethod
    def query(
        component: Any,
        handler: str,
        *,
        id: str,
        description: str,
        input: str | None = None,
        acl: Acl | None = None,
        name: str | None = None,
    ) -> ToolReference:
        """A query of an entity or a workflow: as ``command``, for a handler that only reads."""
        return ToolReference("query", description, acl, component, handler, id, input, name=name)

    @staticmethod
    def route(
        endpoint: Any, method: str, path: str, *, description: str, acl: Acl | None = None, name: str | None = None
    ) -> ToolReference:
        """A route of one of the service's endpoints, by its whole path (prefix and template). Its
        path names are the tool's arguments, and a ``body`` argument is the request's body."""
        return ToolReference("route", description, acl, endpoint=endpoint, method=method.upper(), path=path, name=name)


tool = _ToolFactory()


def _decode(tp: Any, value: Any) -> Any:
    return value if tp is Any else from_json_value(value, tp)


def _method_tool(cls: type, attr: str, fn: Callable[..., Any], mark: _Marked) -> McpToolSpec:
    hints = get_type_hints(fn)
    params = [p for p in inspect.signature(fn).parameters.values() if p.name != "self"]
    record = (
        params[0].name
        if len(params) == 1
        and dataclasses.is_dataclass(hints.get(params[0].name))
        and isinstance(hints.get(params[0].name), type)
        else None
    )
    if record is not None:
        record_type = hints[record]
        schema = _schema_for(record_type)

        async def run_record(instance: Any, args: dict[str, Any], ctx: RequestContext) -> Any:
            result = getattr(instance, attr)(from_json_value(args, record_type))
            return await result if inspect.isawaitable(result) else result

        return McpToolSpec(mark.name or attr, mark.description, mark.acl, schema, run_record)
    properties: dict[str, Any] = {}
    required: list[str] = []
    for p in params:
        if p.kind in (p.VAR_POSITIONAL, p.VAR_KEYWORD):
            raise RegistrationError(f"{cls.__name__}.{attr}: a tool's parameters are named, not *args or **kwargs")
        properties[p.name] = _schema_for(hints.get(p.name, Any))
        if p.default is inspect.Parameter.empty:
            required.append(p.name)
    schema = {"type": "object", "properties": properties, "required": required}
    types = {p.name: hints.get(p.name, Any) for p in params}

    async def run(instance: Any, args: dict[str, Any], ctx: RequestContext) -> Any:
        kwargs = {n: _decode(types[n], args[n]) for n in types if n in args}
        result = getattr(instance, attr)(**kwargs)
        return await result if inspect.isawaitable(result) else result

    return McpToolSpec(mark.name or attr, mark.description, mark.acl, schema, run)


class McpEndpoint(HasSecrets, HasServices, HasClientKeys):
    """Subclass this. Class attributes: ``path``, ``acl``, and optionally ``brokered`` (the platform
    signs people in for the endpoint), ``server_name`` and ``server_version``. Tools are decorated
    methods and ``tool.command/query/route`` attributes."""

    path: ClassVar[str]
    acl: ClassVar[Acl]
    brokered: ClassVar[bool] = False
    server_name: ClassVar[str] = ""
    server_version: ClassVar[str] = ""
    _methods: ClassVar[dict[str, McpToolSpec]]
    _references: ClassVar[dict[str, ToolReference]]

    def __init_subclass__(cls, **kwargs: Any) -> None:
        super().__init_subclass__(**kwargs)
        if not hasattr(cls, "path"):
            raise RegistrationError(f"{cls.__name__} must declare a path, such as path = \"/mcp\"")
        if not cls.path.startswith("/"):
            raise RegistrationError(f"{cls.__name__}: path '{cls.path}' must start with '/'")
        # Required, as an endpoint's: an MCP endpoint whose author said nothing must not be open.
        if not hasattr(cls, "acl") or cls.acl is None:
            raise RegistrationError(
                f"{cls.__name__} must declare an acl — such as Acl.any_of(Acl.CLIENT_KEYS, Acl.AUTHENTICATED)"
            )
        methods: dict[str, McpToolSpec] = {}
        for attr, member in inspect.getmembers(cls, predicate=inspect.isfunction):
            mark = getattr(member, _TOOL, None)
            if isinstance(mark, _Marked):
                spec = _method_tool(cls, attr, member, mark)
                methods[spec.name] = spec
        references: dict[str, ToolReference] = {}
        for attr, member in vars(cls).items():
            if isinstance(member, ToolReference):
                references[member.name or attr] = member
        for name in [*methods, *references]:
            if not _NAME.fullmatch(name):
                raise RegistrationError(
                    f"{cls.__name__}: tool name '{name}' must be 1 to 128 of a-z, 0-9, '_' and '-'"
                )
        clash = set(methods) & set(references)
        if clash:
            raise RegistrationError(f"{cls.__name__}: tool '{sorted(clash)[0]}' is declared twice")
        cls._methods = methods
        cls._references = references

    def __init__(self, client: ComponentClient | None = None) -> None:
        self.client = client
        # The service's endpoint instance a route reference runs, by id; the server sets it.
        self._endpoints: Callable[[str], Any] = _outside_the_server

    @property
    def request(self) -> RequestContext:
        ctx = _current.get()
        if ctx is None:
            raise RuntimeError("request is only available inside a tool")
        return ctx

    @classmethod
    def endpoint_id(cls) -> str:
        return cls.__name__

    @classmethod
    def resolve(cls, registry: Registry) -> tuple[dict[str, McpToolSpec], list[str]]:
        """Every tool, its references bound to what the service registered; and what is missing."""
        tools = dict(cls._methods)
        problems: list[str] = []
        for name, ref in cls._references.items():
            bound = _bind(cls, name, ref, registry)
            if isinstance(bound, str):
                problems.append(bound)
            else:
                tools[name] = bound
        if not tools:
            problems.append(f"MCP endpoint {cls.path} ({cls.__name__}) declares no tool")
        return tools, problems

    @classmethod
    def to_pb(cls, tools: dict[str, McpToolSpec]) -> discovery_pb2.McpEndpoint:
        return discovery_pb2.McpEndpoint(
            id=cls.endpoint_id(),
            path=cls.path,
            name=cls.server_name,
            version=cls.server_version,
            acl=cls.acl.to_mcp_pb(),
            tools=[tools[n].to_pb() for n in sorted(tools)],
            brokered=cls.brokered,
        )

    async def _run(self, spec: McpToolSpec, args: dict[str, Any], ctx: RequestContext) -> Any:
        token = _current.set(ctx)
        try:
            return await spec.run(self, args, ctx)
        finally:
            _current.reset(token)


def _outside_the_server(endpoint_id: str) -> Any:
    raise RuntimeError(f"no endpoint '{endpoint_id}' outside the server")


# ── Binding references ─────────────────────────────────────────────────────


def _component_of(ref: ToolReference, registry: Registry) -> tuple[Any, Any] | None:
    """The registered component a reference names, and the client call kind for it."""
    from ankka._proto.ankka.protocol.v1 import discovery_pb2 as pb

    wanted = ref.component if isinstance(ref.component, str) else getattr(ref.component, "component_id", None)
    for kind, held in (
        (pb.EVENT_SOURCED_ENTITY, registry.entities),
        (pb.KEY_VALUE_ENTITY, registry.key_values),
        (pb.WORKFLOW, registry.workflows),
    ):
        found = held.get(wanted)  # type: ignore[arg-type]
        if found is not None:
            return found, kind
    return None


def _bind(owner: type, name: str, ref: ToolReference, registry: Registry) -> McpToolSpec | str:
    label = f"MCP endpoint {getattr(owner, 'path', '?')}: tool '{name}'"
    if ref.kind == "route":
        return _bind_route(label, name, ref, registry)
    found = _component_of(ref, registry)
    if found is None:
        wanted = ref.component if isinstance(ref.component, str) else getattr(ref.component, "__name__", ref.component)
        return f"{label} refers to {wanted}, which the service does not register"
    component, kind = found
    handlers = component.handlers()
    handler = handlers.get(ref.handler)
    if handler is None:
        return f"{label} refers to {component.component_id}'s handler '{ref.handler}', which it does not have"
    if ref.kind == "query" and not handler.read_only:
        return f"{label} refers to {component.component_id}'s '{ref.handler}' as a query, and it is a command"
    properties: dict[str, Any] = {ref.id: {"type": "string"}}
    required = [ref.id]
    input_type = handler.input_type
    record = input_type is not None and dataclasses.is_dataclass(input_type) and isinstance(input_type, type)
    if input_type is not None:
        if ref.input is not None:
            properties[ref.input] = _schema_for(input_type)
            required.append(ref.input)
        elif record:
            shape = _schema_for(input_type)
            for field_name, field_schema in shape.get("properties", {}).items():
                if field_name == ref.id:
                    return f"{label}: its id '{ref.id}' is also a field of {input_type.__name__}"
                properties[field_name] = field_schema
            required.extend(shape.get("required", []))
        else:
            return f"{label}: {component.component_id}'s '{ref.handler}' takes a scalar input; name its argument with input="
    schema = {"type": "object", "properties": properties, "required": required}
    component_id = component.component_id

    async def run(instance: Any, args: dict[str, Any], ctx: RequestContext) -> Any:
        from ankka._proto.ankka.protocol.v1 import discovery_pb2 as pb

        client = instance.client.with_metadata(ctx.metadata)
        entity_id = str(args[ref.id])
        if kind == pb.EVENT_SOURCED_ENTITY:
            calls = client.for_event_sourced_entity(component_id, entity_id)
        elif kind == pb.KEY_VALUE_ENTITY:
            calls = client.for_key_value_entity(component_id, entity_id)
        else:
            calls = client.for_workflow(component_id, entity_id)
        invocation = calls.call(ref.handler)
        if input_type is None:
            value = None
        elif ref.input is not None:
            value = from_json_value(args[ref.input], input_type)
        else:
            value = from_json_value({k: v for k, v in args.items() if k != ref.id}, input_type)
        return await invocation.invoke(
            value,
            codec=handler.input_codec if input_type is not None else None,
            reply_codec=handler.reply_codec,
        )

    return McpToolSpec(name, ref.description, ref.acl, schema, run)


def _bind_route(label: str, name: str, ref: ToolReference, registry: Registry) -> McpToolSpec | str:
    endpoint = ref.endpoint
    if isinstance(endpoint, type) and issubclass(endpoint, Endpoint):
        held = registry.endpoints.get(endpoint.endpoint_id())
    else:
        held = next((e for e in registry.endpoints.values() if e.prefix == endpoint), None)
    if held is None:
        return f"{label} refers to a route of {getattr(endpoint, '__name__', endpoint)}, which the service does not register"
    route = next(
        (
            r
            for r in held.routes().values()
            if r.method == ref.method
            and (held.prefix.rstrip("/") + r.template) == ref.path
            and not r.streaming
            and not r.socket
        ),
        None,
    )
    if route is None:
        return f"{label} refers to the route {ref.method} {ref.path}, which {held.__name__} does not have"
    names = _PLACEHOLDER.findall(route.template)
    properties: dict[str, Any] = {n: _schema_for(tp) for n, tp in route.path_params}
    required = list(names)
    if route.body_param is not None:
        properties["body"] = {}
        required.append("body")
    schema = {"type": "object", "properties": properties, "required": required}
    endpoint_id = held.endpoint_id()

    async def run(instance: Any, args: dict[str, Any], ctx: RequestContext) -> Any:
        target = instance._endpoints(endpoint_id)
        body = b""
        if route.body_param is not None:
            raw = args.get("body")
            body = raw.encode("utf-8") if isinstance(raw, str) else json.dumps(raw).encode("utf-8")
        status, content_type, reply = await target._handle(route, [str(args[n]) for n in names], body, ctx)
        text = reply.decode("utf-8")
        if status >= 400:
            raise RouteRefused(text or f"the route answered {status}")
        if content_type.startswith("application/json") and text:
            try:
                parsed = json.loads(text)
            except ValueError:
                return text
            return ToolAnswer(text, text if isinstance(parsed, dict) else None)
        return text

    return McpToolSpec(name, ref.description, ref.acl, schema, run)


class RouteRefused(Exception):
    """A referenced route answered 400 or more: a tool error with the route's message."""
