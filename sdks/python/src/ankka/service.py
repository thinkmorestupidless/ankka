"""``Ankka.service().register(...).listen()``: what a process runs.

Registration is explicit, as in the Scala SDK: a component reaches the sidecar only by being
handed over here, so an unregistered one fails at startup rather than at its first request.
Problems are collected and raised together before anything listens.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Any

from ankka import __version__
from ankka._proto.ankka.protocol.v1 import discovery_pb2
from ankka.consumer import Consumer
from ankka.endpoint import Endpoint
from ankka.event_sourced_entity import EventSourcedEntity, RegistrationError
from ankka.key_value_entity import KeyValueEntity
from ankka.timed_action import TimedAction
from ankka.view import View
from ankka.workflow import Workflow

PROTOCOL_VERSION = "1.0"
DEFAULT_PROCESS_PORT = 9010


@dataclass
class Registry:
    """Everything registered, by kind. Filled by ``ServiceBuilder``, read by the server."""

    entities: dict[str, type[EventSourcedEntity[Any, Any]]] = field(default_factory=dict)
    key_values: dict[str, type[KeyValueEntity[Any]]] = field(default_factory=dict)
    workflows: dict[str, type[Workflow[Any]]] = field(default_factory=dict)
    views: dict[str, type[View[Any, Any]]] = field(default_factory=dict)
    consumers: dict[str, type[Consumer[Any, Any]]] = field(default_factory=dict)
    timed_actions: dict[str, type[TimedAction]] = field(default_factory=dict)
    endpoints: dict[str, type[Endpoint]] = field(default_factory=dict)
    others: list[Any] = field(default_factory=list)

    def spec(self) -> discovery_pb2.Spec:
        components = [cls.to_component() for cls in self.entities.values()]
        for registry in (self.key_values, self.workflows, self.views, self.consumers, self.timed_actions):
            components.extend(cls.to_component() for cls in registry.values())
        for other in self.others:
            components.append(other.to_component())
        return discovery_pb2.Spec(
            protocol_version=PROTOCOL_VERSION,
            sdk=discovery_pb2.SdkInfo(name="ankka-python", version=__version__),
            components=components,
            endpoints=[cls.to_endpoint() for cls in self.endpoints.values()],
        )


class ServiceBuilder:
    def __init__(self) -> None:
        self._registry = Registry()
        self._problems: list[str] = []

    def _add(self, registry: dict[str, Any], kind: str, component: Any) -> None:
        cid = component.component_id
        if cid in registry:
            self._problems.append(f"{kind} '{cid}' is registered twice")
        registry[cid] = component

    def register(self, component: type[Any]) -> ServiceBuilder:
        """Registers a component class (an entity, an endpoint, …). Returns self for chaining."""
        if isinstance(component, type) and issubclass(component, EventSourcedEntity):
            self._add(self._registry.entities, "event sourced entity", component)
        elif isinstance(component, type) and issubclass(component, KeyValueEntity):
            self._add(self._registry.key_values, "key value entity", component)
        elif isinstance(component, type) and issubclass(component, Workflow):
            self._add(self._registry.workflows, "workflow", component)
        elif isinstance(component, type) and issubclass(component, View):
            self._add(self._registry.views, "view", component)
        elif isinstance(component, type) and issubclass(component, Consumer):
            self._add(self._registry.consumers, "consumer", component)
        elif isinstance(component, type) and issubclass(component, TimedAction):
            self._add(self._registry.timed_actions, "timed action", component)
        elif isinstance(component, type) and issubclass(component, Endpoint):
            eid = component.endpoint_id()
            if eid in self._registry.endpoints:
                self._problems.append(f"endpoint '{eid}' is registered twice")
            for other in self._registry.endpoints.values():
                if other.prefix == component.prefix:
                    self._problems.append(f"endpoints '{other.endpoint_id()}' and '{eid}' share the prefix '{component.prefix}'")
            self._registry.endpoints[eid] = component
        elif hasattr(component, "to_component") and hasattr(component, "component_id"):
            self._registry.others.append(component)
        else:
            self._problems.append(f"{component!r} is not an ankka component")
        return self

    def validate(self) -> Registry:
        if self._problems:
            raise RegistrationError("invalid ankka service:\n  - " + "\n  - ".join(self._problems))
        return self._registry

    def spec(self) -> discovery_pb2.Spec:
        """The discovery Spec, without listening. For tests."""
        return self.validate().spec()

    async def listen(self, port: int | None = None, host: str = "127.0.0.1") -> None:
        """Validates, binds the gRPC server the sidecar dials (loopback only), answers discovery,
        and runs until cancelled."""
        from ankka.server import Server

        registry = self.validate()
        resolved = port if port is not None else int(os.environ.get("ANKKA_PROCESS_PORT", DEFAULT_PROCESS_PORT))
        server = Server(registry)
        await server.start(host, resolved)
        await server.wait()

    def server(self) -> Any:
        """The server without starting it: what the integration testkit drives."""
        from ankka.server import Server

        return Server(self.validate())


class Ankka:
    @staticmethod
    def service() -> ServiceBuilder:
        return ServiceBuilder()
