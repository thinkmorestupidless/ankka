"""The documentation's photos endpoint (docs/build/object-storage.md): an endpoint over the service's
buckets, and the route that keeps a photo's bytes and gives the listing entity its name."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import timedelta

from ankka import DONE, Acl, Done, Endpoint, HttpProblem, delete, get, post
from ankka.client import ComponentClient
from ankka.storage import Access, Bucket


# docs:start endpoint
@dataclass(frozen=True)
class KeepObject:
    name: str
    text: str
    contentType: str


@dataclass(frozen=True)
class Described:
    name: str
    contentType: str
    length: int
    lastKept: str


@dataclass(frozen=True)
class ListedObject:
    name: str
    length: int
    lastKept: str


@dataclass(frozen=True)
class Listed:
    objects: list[ListedObject]


@dataclass(frozen=True)
class RecordPhoto:
    name: str
    text: str
    contentType: str


class PhotosEndpoint(Endpoint):
    """An endpoint over the service's buckets: keep, read, describe, list, delete, sign and address."""

    prefix = "/storage"
    acl = Acl.ALLOW_ALL

    def __init__(self, client: ComponentClient) -> None:
        self.client = client

    def _bucket(self) -> Bucket:
        """The bucket the request names, or the service's unnamed one."""
        name = self.request.query_param("bucket")
        return self.storage.default if name is None else self.storage.bucket(name)

    def _name(self) -> str:
        name = self.request.query_param("name")
        if name is None:
            raise HttpProblem(400, "the query parameter 'name' is required")
        return name

    @post("/objects")
    async def keep(self, request: KeepObject) -> Done:
        await self._bucket().put(request.name, request.text.encode("utf-8"), content_type=request.contentType)
        return DONE

    # An upload: the body as it was sent, under the content type it was sent as.
    @post("/upload")
    async def upload(self, body: bytes) -> Done:
        content_type = self.request.header("Content-Type") or "application/octet-stream"
        await self._bucket().put(self._name(), body, content_type=content_type)
        return DONE

    # A download: the object's bytes as they were kept.
    @get("/objects")
    async def read(self) -> bytes:
        name = self._name()
        found = await self._bucket().get(name)
        if found is None:
            raise HttpProblem(404, f"no object '{name}'")
        return await found.read()

    @get("/stat")
    async def stat(self) -> Described:
        name = self._name()
        info = await self._bucket().stat(name)
        if info is None:
            raise HttpProblem(404, f"no object '{name}'")
        return Described(info.name, info.content_type, info.length, info.last_kept.isoformat())

    @get("/list")
    async def list_objects(self) -> Listed:
        prefix = self.request.query_param("prefix") or ""
        entries = self._bucket().list(prefix)
        return Listed([ListedObject(e.name, e.length, e.last_kept.isoformat()) async for e in entries])

    @delete("/objects")
    async def remove(self) -> Done:
        await self._bucket().delete(self._name())
        return DONE

    @get("/signed")
    def signed(self) -> str:
        access: Access = "keep" if self.request.query_param("access") == "keep" else "read"
        seconds = self.request.query_param("seconds")
        if seconds is None:
            return self._bucket().signed_url(self._name(), access)
        return self._bucket().signed_url(self._name(), access, timedelta(seconds=int(seconds)))

    @get("/url")
    def url(self) -> str:
        return self._bucket().url(self._name())

    # The entity keeps the name; the bucket keeps the object.
    @post("/listings/{id}/photos")
    async def record_photo(self, id: str, request: RecordPhoto) -> Done:
        await self.storage.default.put(request.name, request.text.encode("utf-8"), content_type=request.contentType)
        listing = self.client.with_metadata(self.request.metadata).for_event_sourced_entity("listing", id)
        return await listing.call("record-photo").invoke(request.name)
# docs:end endpoint
