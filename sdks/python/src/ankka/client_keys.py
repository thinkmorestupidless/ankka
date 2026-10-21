"""The service's client keys: what an outside MCP client presents to call this service's tools.

The runtime beside this process keeps the keys; this process asks it to issue, list and revoke
them. A key is shown once, in what ``issue`` returns, and nowhere again: a listing never holds it,
nor anything it could be derived from. Offered to endpoints, MCP endpoints, timed actions and
agents as ``self.client_keys``.
"""

from __future__ import annotations

import secrets as _random
from collections.abc import Callable, Iterable, Mapping
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import TYPE_CHECKING, Any

import grpc

from ankka._proto.ankka.protocol.v1 import client_pb2
from ankka.effects.common import Error, ErrorCode

if TYPE_CHECKING:
    from ankka.client import ComponentClient
    from ankka.context import Metadata

CLIENT_KEYS_SINCE = "1.20"


@dataclass(frozen=True)
class Expiry:
    """When a client key stops being admitted. There is no default: whoever issues a key says.

    ``Expiry.after(days=365)``, ``Expiry.at(moment)`` or ``Expiry.never()``.
    """

    after_millis: int | None = None
    at_epoch_millis: int | None = None
    never_expires: bool = False

    @staticmethod
    def after(delta: timedelta | None = None, **parts: float) -> Expiry:
        """After ``delta``, or after the ``timedelta`` the keywords make (``days=365``)."""
        span = delta if delta is not None else timedelta(**parts)
        return Expiry(after_millis=int(span.total_seconds() * 1000))

    @staticmethod
    def at(moment: datetime) -> Expiry:
        """At ``moment``; a naive datetime is read as UTC."""
        aware = moment if moment.tzinfo is not None else moment.replace(tzinfo=timezone.utc)
        return Expiry(at_epoch_millis=int(aware.timestamp() * 1000))

    @staticmethod
    def never() -> Expiry:
        return Expiry(never_expires=True)

    def apply_to(self, request: client_pb2.IssueClientKeyRequest) -> None:
        if self.after_millis is not None:
            request.after_millis = self.after_millis
        elif self.at_epoch_millis is not None:
            request.at_epoch_millis = self.at_epoch_millis
        elif self.never_expires:
            request.never = True


@dataclass(frozen=True)
class IssuedClientKey:
    """A key just issued: ``id`` names it from now on, ``client_key`` is shown here and never again."""

    id: str
    client_key: str = field(repr=False)

    def __repr__(self) -> str:
        return f"IssuedClientKey(id={self.id!r}, client_key=<the key>)"


@dataclass(frozen=True)
class ClientKeySummary:
    """One client key as a listing shows it: never the key, never its digest."""

    id: str
    subject: str
    attributes: Mapping[str, str]
    label: str | None
    endpoint: str
    scope: frozenset[str]
    issued_at: datetime
    expires_at: datetime | None
    last_used: datetime | None
    revoked_at: datetime | None


def _refused(message: str, code: ErrorCode) -> Exception:
    from ankka.client import CommandError

    return CommandError(Error(message, code))


def _raise_on(error: Any) -> None:
    from ankka.client import CommandError, _error

    raise CommandError(_error(error))


def _too_old(failure: grpc.aio.AioRpcError) -> Exception:
    from ankka.service import PROTOCOL_VERSION

    return _refused(
        f"the runtime is too old for client keys ({CLIENT_KEYS_SINCE}); this SDK speaks {PROTOCOL_VERSION}: "
        f"{failure.details()}",
        ErrorCode.INTERNAL,
    )


def _moment(millis: int) -> datetime:
    return datetime.fromtimestamp(millis / 1000, tz=timezone.utc)


def _summary(k: client_pb2.ClientKeySummary) -> ClientKeySummary:
    return ClientKeySummary(
        id=k.id,
        subject=k.subject,
        attributes=dict(k.attributes),
        label=k.label if k.HasField("label") else None,
        endpoint=k.endpoint,
        scope=frozenset(k.scope),
        issued_at=_moment(k.issued_at_millis),
        expires_at=_moment(k.expires_at_millis) if k.HasField("expires_at_millis") else None,
        last_used=_moment(k.last_used_millis) if k.HasField("last_used_millis") else None,
        revoked_at=_moment(k.revoked_at_millis) if k.HasField("revoked_at_millis") else None,
    )


class ClientKeys:
    """Issues, lists and revokes this service's client keys through the runtime.

    Every refusal is a ``CommandError``: ``BAD_REQUEST`` for a request that breaks a rule (a blank
    subject, a tool the endpoint does not have, too many attributes), ``NOT_FOUND`` for a revoke of
    an unknown id, ``UNAVAILABLE`` when the service's MCP endpoints admit no key.
    """

    def __init__(self, client: ComponentClient, metadata: Callable[[], Metadata] | None = None) -> None:
        self._client = client
        self._metadata = metadata

    def _metadata_pb(self) -> Any:
        if self._metadata is not None:
            return self._metadata().to_pb()
        held = getattr(self._client, "_metadata", None)
        return held.to_pb() if held is not None else None

    async def issue(
        self,
        subject: str,
        expiry: Expiry,
        *,
        endpoint: str | None = None,
        scope: Iterable[str] = (),
        attributes: Mapping[str, str] | None = None,
        label: str | None = None,
    ) -> IssuedClientKey:
        """A key for ``subject``, for the MCP endpoint at ``endpoint`` (implied when the service has
        one), able to call the tools in ``scope`` (every tool when empty). Its ``attributes`` are
        what a tool reads as the principal's claims."""
        request = client_pb2.IssueClientKeyRequest(
            subject=subject,
            scope=sorted(set(scope)),
            attributes=dict(attributes or {}),
            metadata=self._metadata_pb(),
        )
        expiry.apply_to(request)
        if endpoint is not None:
            request.endpoint = endpoint
        if label is not None:
            request.label = label
        try:
            reply = await self._client._stub.IssueClientKey(request)
        except grpc.aio.AioRpcError as failure:
            if failure.code() == grpc.StatusCode.UNIMPLEMENTED:
                raise _too_old(failure) from failure
            raise
        which = reply.WhichOneof("reply")
        if which == "issued":
            return IssuedClientKey(reply.issued.id, reply.issued.client_key)
        if which == "error":
            _raise_on(reply.error)
        raise _refused("the runtime answered an issue of a client key with nothing", ErrorCode.INTERNAL)

    async def list(self) -> list[ClientKeySummary]:
        """Every client key of the service, revoked ones included, never the key itself."""
        try:
            reply = await self._client._stub.ListClientKeys(client_pb2.ListClientKeysRequest(metadata=self._metadata_pb()))
        except grpc.aio.AioRpcError as failure:
            if failure.code() == grpc.StatusCode.UNIMPLEMENTED:
                raise _too_old(failure) from failure
            raise
        if reply.HasField("error"):
            _raise_on(reply.error)
        return [_summary(k) for k in reply.keys]

    async def revoke(self, id: str) -> None:
        """Revokes a key: refused on its next request on every instance. Revoking twice is not an
        error; an unknown id is ``NOT_FOUND``."""
        try:
            reply = await self._client._stub.RevokeClientKey(
                client_pb2.RevokeClientKeyRequest(id=id, metadata=self._metadata_pb())
            )
        except grpc.aio.AioRpcError as failure:
            if failure.code() == grpc.StatusCode.UNIMPLEMENTED:
                raise _too_old(failure) from failure
            raise
        if reply.HasField("error"):
            _raise_on(reply.error)


@dataclass
class _HeldKey:
    summary: ClientKeySummary
    client_key: str


class InMemoryClientKeys(ClientKeys):
    """Client keys for unit tests: a dict. ``admits(key)`` answers the summary a key would be
    admitted as, so a test can play the platform's part."""

    def __init__(self) -> None:
        self.held: dict[str, _HeldKey] = {}

    async def issue(
        self,
        subject: str,
        expiry: Expiry,
        *,
        endpoint: str | None = None,
        scope: Iterable[str] = (),
        attributes: Mapping[str, str] | None = None,
        label: str | None = None,
    ) -> IssuedClientKey:
        if not subject.strip():
            raise _refused("a client key needs a subject", ErrorCode.BAD_REQUEST)
        if expiry == Expiry():
            raise _refused("a client key needs an expiry: after, at or never", ErrorCode.BAD_REQUEST)
        now = datetime.now(timezone.utc)
        expires: datetime | None = None
        if expiry.after_millis is not None:
            expires = now + timedelta(milliseconds=expiry.after_millis)
        elif expiry.at_epoch_millis is not None:
            expires = _moment(expiry.at_epoch_millis)
        key_id = _random.token_hex(8)
        key = "ak_" + _random.token_urlsafe(32)
        self.held[key_id] = _HeldKey(
            ClientKeySummary(
                key_id, subject, dict(attributes or {}), label, endpoint or "", frozenset(scope), now, expires, None, None
            ),
            key,
        )
        return IssuedClientKey(key_id, key)

    async def list(self) -> list[ClientKeySummary]:
        return [h.summary for h in self.held.values()]

    async def revoke(self, id: str) -> None:
        held = self.held.get(id)
        if held is None:
            raise _refused(f"no client key '{id}'", ErrorCode.NOT_FOUND)
        if held.summary.revoked_at is None:
            from dataclasses import replace

            held.summary = replace(held.summary, revoked_at=datetime.now(timezone.utc))

    def admits(self, client_key: str) -> ClientKeySummary | None:
        for held in self.held.values():
            if held.client_key == client_key and held.summary.revoked_at is None:
                return held.summary
        return None


class HasClientKeys:
    """What gives an endpoint, an MCP endpoint, a timed action and an agent their ``client_keys``.

    The keys are the runtime's, reached through the component's client. A unit test assigns its own
    (``component.client_keys = InMemoryClientKeys()``).
    """

    _client_keys: ClientKeys | None = None

    @property
    def client_keys(self) -> ClientKeys:
        if self._client_keys is not None:
            return self._client_keys
        client = getattr(self, "client", None)
        if client is None:
            raise RuntimeError(
                f"{type(self).__name__} has no client, so no client keys: it is given them when the "
                "runtime builds it, and a unit test assigns them (InMemoryClientKeys)"
            )
        return ClientKeys(client)

    @client_keys.setter
    def client_keys(self, keys: ClientKeys) -> None:
        self._client_keys = keys
