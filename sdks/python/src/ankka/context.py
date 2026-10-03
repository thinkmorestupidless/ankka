"""What a handler can see about the call it is handling."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any

from ankka._proto.ankka.protocol.v1 import payload_pb2

if TYPE_CHECKING:
    from ankka.client import ComponentClient


@dataclass(frozen=True)
class Metadata:
    """Key/value pairs that travel with a call. Opaque to a handler: the sidecar puts the trace
    and span ids in here, and copying it onto a nested call is what makes that call a child span."""

    entries: tuple[tuple[str, str], ...] = ()

    def get(self, key: str) -> str | None:
        for k, v in self.entries:
            if k.lower() == key.lower():
                return v
        return None

    def get_all(self, key: str) -> list[str]:
        return [v for k, v in self.entries if k.lower() == key.lower()]

    def set(self, key: str, value: str) -> Metadata:
        rest = tuple((k, v) for k, v in self.entries if k.lower() != key.lower())
        return Metadata(rest + ((key, value),))

    def add(self, key: str, value: str) -> Metadata:
        return Metadata(self.entries + ((key, value),))

    @property
    def subject(self) -> str | None:
        """``ce-subject``: on a view's or consumer's change, the id of the entity that changed."""
        return self.get("ce-subject")

    @property
    def sequence_number(self) -> int | None:
        """``ankka.sequence``: the change's sequence number (an entity's event or revision)."""
        value = self.get("ankka.sequence")
        return int(value) if value is not None else None

    @property
    def protocol(self) -> str | None:
        """``ankka.protocol``: on a consumer's change, the protocol version the runtime speaks. A
        runtime that does not say accepts one message per change and no record key."""
        return self.get("ankka.protocol")

    def to_pb(self) -> payload_pb2.Metadata:
        return payload_pb2.Metadata(entries=[payload_pb2.Metadata.Entry(key=k, value=v) for k, v in self.entries])

    @staticmethod
    def from_pb(pb: payload_pb2.Metadata | None) -> Metadata:
        if pb is None:
            return Metadata()
        return Metadata(tuple((e.key, e.value) for e in pb.entries))


EMPTY = Metadata()


@dataclass(frozen=True)
class CommandContext:
    """What an entity or workflow handler sees: which instance, which component, the call's
    metadata, the sequence number, and the client for calling other components."""

    entity_id: str
    component_id: str
    metadata: Metadata
    sequence_number: int
    client: ComponentClient


@dataclass(frozen=True)
class Gateway:
    """The request arrived through the installation's gateway: from the internet."""


@dataclass(frozen=True)
class ServiceCaller:
    """Another workload of this installation, named by the certificate the platform issued it."""

    project: str
    name: str


@dataclass(frozen=True)
class LocalCaller:
    """Outside a cluster, where there is no certificate to read: every caller is this."""


Caller = Gateway | ServiceCaller | LocalCaller
"""Which workload sent a request, as the platform established it; never read from the request."""


@dataclass(frozen=True)
class Principal:
    subject: str
    name: str | None = None
    email: str | None = None
    email_verified: bool = False
    roles: frozenset[str] = frozenset()


@dataclass(frozen=True)
class RequestContext:
    """What an endpoint handler sees beyond its typed arguments: query parameters (repeatable),
    headers, the principal when the ACL established one, the request's trace metadata, and the
    caller — which workload sent it."""

    query: tuple[tuple[str, str], ...] = ()
    headers: tuple[tuple[str, str], ...] = ()
    principal: Principal | None = None
    metadata: Metadata = field(default_factory=Metadata)
    caller: Caller = field(default_factory=LocalCaller)

    def query_param(self, name: str) -> str | None:
        for k, v in self.query:
            if k == name:
                return v
        return None

    def query_params(self, name: str) -> list[str]:
        return [v for k, v in self.query if k == name]

    def header(self, name: str) -> str | None:
        for k, v in self.headers:
            if k.lower() == name.lower():
                return v
        return None


@dataclass(frozen=True)
class SessionContext:
    """What an agent handler or tool sees."""

    session_id: str
    component_id: str
    metadata: Metadata
    client: ComponentClient


def _unused(*_: Any) -> None:  # keeps the TYPE_CHECKING import honest for linters
    return None
