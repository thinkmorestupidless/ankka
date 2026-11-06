"""Asking for an erasure request from code (feature 042, protocol 1.23): ``self.erasures.ask(subject)``.

The request is asked of the control plane as this service, through the sidecar's
``Client.AskErasure``. The service needs the ``erasure`` grant of the project it asks in, its own
included, and is named as who asked. Offered to endpoints and consumers as ``self.erasures``, and to
the erasure handler as ``ctx.erasures``.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from typing import Any

import grpc

from ankka._proto.ankka.protocol.v1 import client_pb2
from ankka.effects.common import Error, ErrorCode

ERASURES_SINCE = "1.23"
"""The protocol whose sidecars ask the control plane for an erasure request (``Client.AskErasure``)."""


@dataclass(frozen=True)
class ErasureAsked:
    """An erasure request as asked: its id in its project and its state (held, applying, applied)."""

    id: str
    state: str


class Erasures:
    """Asks for erasure requests as this service, through the sidecar (protocol 1.23).

    Every refusal is a ``CommandError`` carrying the control plane's code: ``FORBIDDEN`` without
    the ``erasure`` grant of the project, ``CONFLICT`` while erasure is off there, ``BAD_REQUEST``
    for a subject outside the rule.
    """

    def __init__(self, client: Any) -> None:
        self._client = client

    async def ask(
        self,
        subject: str,
        *,
        project: str | None = None,
        not_before: date | None = None,
        reason: str | None = None,
        correlation: str | None = None,
    ) -> ErasureAsked:
        """Asks for the erasure of ``subject`` in ``project``, this service's own when None; held
        until ``not_before`` when given. Answers the request's id and where it stands."""
        held = getattr(self._client, "_metadata", None)
        request = client_pb2.AskErasureRequest(
            project=project or "", subject=subject, metadata=held.to_pb() if held is not None else None
        )
        if not_before is not None:
            request.not_before = not_before.isoformat()
        if reason is not None:
            request.reason = reason
        if correlation is not None:
            request.correlation = correlation
        from ankka.client import CommandError

        try:
            reply = await self._client._stub.AskErasure(request)
        except grpc.aio.AioRpcError as failure:
            if failure.code() == grpc.StatusCode.UNIMPLEMENTED:
                from ankka.service import PROTOCOL_VERSION

                raise CommandError(
                    Error(
                        f"the runtime beside this process cannot ask for an erasure, which needs protocol "
                        f"{ERASURES_SINCE} (this SDK speaks {PROTOCOL_VERSION}): {failure.details()}",
                        ErrorCode.INTERNAL,
                    )
                ) from failure
            raise
        which = reply.WhichOneof("reply")
        if which == "ok":
            return ErasureAsked(reply.ok.id, reply.ok.state)
        if which == "error":
            from ankka.client import _error

            raise CommandError(_error(reply.error))
        raise CommandError(Error("the runtime answered an erasure request with nothing", ErrorCode.INTERNAL))


class HasErasures:
    """What gives an endpoint and a consumer their ``erasures``: asked through the component's
    client. A unit test assigns its own (``component.erasures = Erasures(stub_client)``)."""

    _erasures: Erasures | None = None

    @property
    def erasures(self) -> Erasures:
        if self._erasures is not None:
            return self._erasures
        client = getattr(self, "client", None)
        if client is None:
            raise RuntimeError(
                f"{type(self).__name__} has no client, so cannot ask for an erasure: it is given one when the "
                "runtime builds it, and a unit test assigns its erasures"
            )
        return Erasures(client)

    @erasures.setter
    def erasures(self, erasures: Erasures) -> None:
        self._erasures = erasures


__all__ = ["ERASURES_SINCE", "ErasureAsked", "Erasures", "HasErasures"]
