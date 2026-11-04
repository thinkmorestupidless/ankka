"""The service's erasure handler (feature 042, protocol 1.23): what the platform cannot erase for it.

```python
service = Ankka.service().register(...)

@service.erasure_handler
async def erase(ctx: ErasureContext, subject: DataSubject) -> ErasureReport:
    removed = await ctx.storage.bucket("kyc").erase(subject)
    return ErasureReport(objects_erased=removed.count, finality=removed.finality)
```

One per service. The sidecar calls it on one instance for each erased data subject, and again on
each re-run within the window, so it must be idempotent. One that raises is recorded as failed for
that run and run again on the next; the platform's own duties do not wait for it.
"""

from __future__ import annotations

import inspect
import logging
import traceback
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import timedelta
from typing import Any

from ankka._proto.ankka.protocol.v1 import erasure_pb2, erasure_pb2_grpc, payload_pb2
from ankka.client import CommandError, ComponentClient
from ankka.codec import _format_duration
from ankka.context import Metadata
from ankka.personal import DataSubject, subject_problem

log = logging.getLogger("ankka")


@dataclass(frozen=True)
class ErasureReport:
    """What a handler erased: a count of objects, and when they are gone for good. ``finality``
    None is at once; a duration is how long a store keeps a deleted object recoverable."""

    objects_erased: int = 0
    finality: timedelta | None = None

    def finality_text(self) -> str:
        """``immediate``, or ``after:`` and an ISO-8601 duration, as ``ErasureReply`` carries it."""
        return "immediate" if self.finality is None else "after:" + _format_duration(self.finality)


@dataclass(frozen=True)
class ErasureContext:
    """What a handler is handed: the call's metadata, which erasure this is, and the service's
    clients and buckets."""

    erasure_seq: int
    rerun: bool
    metadata: Metadata
    client: ComponentClient
    storage: Any = None
    secrets: Any = None


ErasureHandler = Callable[[ErasureContext, DataSubject], "ErasureReport | None | Awaitable[ErasureReport | None]"]


class ErasureServicer(erasure_pb2_grpc.ErasureServicer):
    """Runs the service's erasure handler for one data subject and reports what it erased."""

    def __init__(self, handler: ErasureHandler | None, client: ComponentClient, storage: Any = None) -> None:
        self.handler = handler
        self.client = client
        self.storage = storage

    async def Invoke(self, request: erasure_pb2.ErasureCall, context: Any) -> erasure_pb2.ErasureReply:
        if self.handler is None:
            return _failed("this service declares no erasure handler", payload_pb2.NOT_FOUND)
        problem = subject_problem(request.subject)
        if problem is not None:
            return _failed(problem, payload_pb2.BAD_REQUEST)
        from ankka.secrets import Secrets

        metadata = Metadata.from_pb(request.metadata)
        scoped = self.client.with_metadata(metadata)
        ctx = ErasureContext(request.erasure_seq, request.rerun, metadata, scoped, self.storage, Secrets(scoped))
        try:
            result = self.handler(ctx, DataSubject(request.subject))
            if inspect.isawaitable(result):
                result = await result
        except CommandError as refused:
            return _failed(refused.error.message, refused.error.code.value)
        except Exception as e:
            log.warning("the erasure handler raised for %s: %s", request.subject, e)
            log.debug("%s", traceback.format_exc())
            return _failed(str(e) or type(e).__name__, payload_pb2.INTERNAL)
        report = result if isinstance(result, ErasureReport) else ErasureReport()
        return erasure_pb2.ErasureReply(objects_erased=report.objects_erased, finality=report.finality_text())


def _failed(message: str, code: Any) -> erasure_pb2.ErasureReply:
    return erasure_pb2.ErasureReply(error=payload_pb2.Error(message=message, code=code))
