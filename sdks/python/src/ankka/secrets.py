"""The service's secret store: named text values the platform keeps for this service and never
records.

The runtime beside this process holds the store and the key it encrypts with; this process never
sees the key. A value kept here is in the service's own database, encrypted, and in no journal,
snapshot or view. The store is offered to endpoints, workflow steps, consumers, timed actions and
agents, and not to an entity or a view: an entity's ``CommandContext`` and a ``View`` have no
``secrets`` at all.
"""

from __future__ import annotations

import re
from typing import TYPE_CHECKING, Any

import grpc

from ankka._proto.ankka.protocol.v1 import client_pb2
from ankka.effects.common import Error, ErrorCode

if TYPE_CHECKING:
    from ankka.client import ComponentClient

MAX_NAME_LENGTH = 253
MAX_VALUE_BYTES = 65536
NAME_RULE = f"a secret's name is 1 to {MAX_NAME_LENGTH} characters, each a letter, a digit, '.', '_', '-' or '/'"
_VALID_NAME = re.compile(rf"[A-Za-z0-9._/-]{{1,{MAX_NAME_LENGTH}}}")
SECRETS_SINCE = "1.6"


def name_problem(name: str) -> str | None:
    """What is wrong with a secret's name, if anything: the runtime's own rule."""
    if _VALID_NAME.fullmatch(name):
        return None
    shown = name if len(name) <= 40 else name[:40] + "…"
    return f'{NAME_RULE}; "{shown}" is not'


def value_problem(value: str) -> str | None:
    """What is wrong with a secret's value, if anything. Never quotes the value."""
    if value == "":
        return "a secret's value must not be empty"
    size = len(value.encode("utf-8"))
    if size > MAX_VALUE_BYTES:
        return f"a secret's value is at most {MAX_VALUE_BYTES} bytes as UTF-8; this one is {size}"
    return None


def _refused(message: str, code: ErrorCode) -> Exception:
    from ankka.client import CommandError

    return CommandError(Error(message, code))


def _raise_on(error: Any) -> None:
    from ankka.client import CommandError, _error

    raise CommandError(_error(error))


def _too_old(failure: grpc.aio.AioRpcError) -> Exception:
    from ankka.service import PROTOCOL_VERSION

    return _refused(
        f"the runtime beside this process does not offer the secret store, which needs protocol "
        f"{SECRETS_SINCE} (this SDK speaks {PROTOCOL_VERSION}): {failure.details()}",
        ErrorCode.INTERNAL,
    )


class Secrets:
    """Keeps, reads and removes this service's secrets through the runtime.

    Every refusal is a ``CommandError`` with the runtime's code: ``BAD_REQUEST`` for a name or a
    value that breaks its rule, ``INTERNAL`` when the service has no secret key or a value was kept
    with another one, ``UNAVAILABLE`` when the database cannot be reached.
    """

    def __init__(self, client: ComponentClient) -> None:
        self._client = client

    async def put(self, name: str, value: str) -> None:
        """Keeps ``value`` under ``name``, replacing what was there."""
        try:
            reply = await self._client._stub.PutSecret(client_pb2.PutSecretRequest(name=name, value=value))
        except grpc.aio.AioRpcError as failure:
            if failure.code() == grpc.StatusCode.UNIMPLEMENTED:
                raise _too_old(failure) from failure
            raise
        if reply.HasField("error"):
            _raise_on(reply.error)

    async def get(self, name: str) -> str | None:
        """The value kept under ``name``, or None when there is none. Never an empty string."""
        try:
            reply = await self._client._stub.GetSecret(client_pb2.GetSecretRequest(name=name))
        except grpc.aio.AioRpcError as failure:
            if failure.code() == grpc.StatusCode.UNIMPLEMENTED:
                raise _too_old(failure) from failure
            raise
        which = reply.WhichOneof("result")
        if which == "value":
            return str(reply.value)
        if which == "absent":
            return None
        if which == "error":
            _raise_on(reply.error)
        raise _refused("the runtime answered a secret's read with nothing", ErrorCode.INTERNAL)

    async def delete(self, name: str) -> None:
        """Removes what is kept under ``name``. Removing nothing is not an error."""
        try:
            reply = await self._client._stub.DeleteSecret(client_pb2.DeleteSecretRequest(name=name))
        except grpc.aio.AioRpcError as failure:
            if failure.code() == grpc.StatusCode.UNIMPLEMENTED:
                raise _too_old(failure) from failure
            raise
        if reply.HasField("error"):
            _raise_on(reply.error)


class InMemorySecrets(Secrets):
    """A secret store for unit tests: a dict, applying the runtime's rules with its words, so a test
    cannot pass on a name or a value a running service would refuse."""

    def __init__(self) -> None:
        self.values: dict[str, str] = {}

    async def put(self, name: str, value: str) -> None:
        problem = name_problem(name) or value_problem(value)
        if problem:
            raise _refused(problem, ErrorCode.BAD_REQUEST)
        self.values[name] = value

    async def get(self, name: str) -> str | None:
        problem = name_problem(name)
        if problem:
            raise _refused(problem, ErrorCode.BAD_REQUEST)
        return self.values.get(name)

    async def delete(self, name: str) -> None:
        problem = name_problem(name)
        if problem:
            raise _refused(problem, ErrorCode.BAD_REQUEST)
        self.values.pop(name, None)


class HasSecrets:
    """What gives a consumer, a timed action, an agent and an endpoint their ``secrets``.

    The store is the runtime's, reached through the component's client. A unit test assigns its own
    (``component.secrets = InMemorySecrets()``) before calling the handler.
    """

    _secrets_store: Secrets | None = None

    @property
    def secrets(self) -> Secrets:
        if self._secrets_store is not None:
            return self._secrets_store
        client = getattr(self, "client", None)
        if client is None:
            raise RuntimeError(
                f"{type(self).__name__} has no client, so no secret store: it is given one when the "
                "runtime builds it, and a unit test assigns one (InMemorySecrets)"
            )
        return Secrets(client)

    @secrets.setter
    def secrets(self, store: Secrets) -> None:
        self._secrets_store = store
